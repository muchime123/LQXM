/**
 * ERP S0 沙箱验证 harness
 * ============================================================================
 * 把「ERP 建设前业务蓝图」T01–T18 与「单据契约」B01–B20 从合成案例表
 * 变成可执行验证资产。
 *
 * 隔离保证：只连 erp_s0_verify schema，绝不触碰 product_archive。
 * 依赖：mysql2（scripts/node_modules 已有），零新增包。
 *
 * 用法：
 *   node scripts/erp-verify-s0.cjs            # 全量
 *   node scripts/erp-verify-s0.cjs T01 B09    # 指定用例
 *   node scripts/erp-verify-s0.cjs --json     # 机器可读输出
 *
 * 设计原则（对应设计输入文档）：
 *   - 数量/金额全 DECIMAL，比较用容差 1e-9        → C5 浮点残差
 *   - 主键用稳定 ID，名称只作展示                  → C1 名称不是身份
 *   - 事务原子：单据状态+事件+余额+执行量一起提交   → 契约 §8
 *   - 判定失败照实报告，不静默跳过                 → 验证资产纪律
 * ============================================================================
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');
const accounting = require('./erp-s0-accounting.cjs');
const stock = require('./erp-s0-stock.cjs');

const SCHEMA = process.env.ERP_S0_SCHEMA || 'erp_s0_verify';
if (!['erp_s0_verify', 'erp_s0_hardening'].includes(SCHEMA)) throw new Error('Unsafe verification schema');
const EPS = 1e-9;                    // 数值比较容差（对应 nEq 的 1e-9）
const C = 'CNY';

// ---------------------------------------------------------------------------
// 基础设施：连接、断言、序号
// ---------------------------------------------------------------------------
const verification = require('./erp-verification-config.cjs');
function loadDbConfig() { return verification.config(); }

let conn;
let seq = 1000;
const nextId = () => ++seq;

/** 数值相等（容差 1e-9）—— 禁止直接用 === 比较 DECIMAL 回来的字符串 */
const nEq = (a, b) => Math.abs(Number(a) - Number(b)) < EPS;
const n0 = (a) => Math.abs(Number(a)) < EPS;

class AssertionError extends Error {
  constructor(msg) { super(msg); this.name = 'AssertionError'; }
}

/** 用例内的断言收集器：一条用例可能有多条断言，全过才 PASS */
class Checker {
  constructor(caseId) { this.caseId = caseId; this.failures = []; this.notes = []; }
  eq(actual, expected, label) {
    if (!nEq(actual, expected)) this.failures.push(`${label}: 期望 ${expected}，实际 ${actual}`);
    return this;
  }
  /** 严格相等（用于状态字符串、计数、结果引用等非数值场景） */
  is(actual, expected, label) {
    if (actual !== expected) this.failures.push(`${label}: 期望 ${expected}，实际 ${actual}`);
    return this;
  }
  /** 字符串相等（显式语义，避免误用数值容差比较） */
  str(actual, expected, label) {
    if (String(actual) !== String(expected)) {
      this.failures.push(`${label}: 期望 ${expected}，实际 ${actual}`);
    }
    return this;
  }
  truthy(cond, label) {
    if (!cond) this.failures.push(`${label}: 期望成立，实际不成立`);
    return this;
  }
  notNull(v, label) {
    if (v === null || v === undefined) this.failures.push(`${label}: 期望非空，实际为空`);
    return this;
  }
  /** 期望某个操作被拒绝（错误码匹配） */
  async rejects(fn, expectedCode, label) {
    try {
      await fn();
    } catch (e) {
      if (!accounting.rejectionMatches(e, expectedCode)) {
        this.failures.push(`${label}: 期望拒绝码 ${expectedCode}，实际 ${e.code}（${e.message}）`);
      }
      return this;
    }
    this.failures.push(`${label}: 期望被拒绝，实际执行成功`);
    return this;
  }
  note(msg) { this.notes.push(msg); return this; }
}

/** 业务错误：带稳定错误码（契约 §8） */
class BizError extends Error {
  constructor(code, msg) { super(msg); this.code = code; }
}

// ---------------------------------------------------------------------------
// 数据装载：每个用例前清场
// ---------------------------------------------------------------------------
const TABLES = [
  'case_result', 'idem_request', 'settlement_allocation', 'fund_doc', 'ar_ap_item',
  'stock_event', 'stock_balance', 'doc_line', 'doc',
  'sku_conversion', 'sku', 'party_company_role', 'party', 'warehouse', 'company',
];

async function resetWorld() {
  for (const t of TABLES) {
    await conn.query(`DELETE FROM ${t}`);
    await conn.query(`ALTER TABLE ${t} AUTO_INCREMENT = 1`);
  }
  seq = 1000;
  await conn.query(
    `INSERT INTO company (id, code, name) VALUES (1,'C01','利强'),(2,'C02','利强二厂')`
  );
  await conn.query(
    `INSERT INTO warehouse (id, company_id, code, name) VALUES
      (1,1,'WH-A','仓A'),(2,1,'WH-B','仓B'),(3,2,'WH-C','仓C')`
  );
  await conn.query(
    `INSERT INTO party (id, code, display_name, kind) VALUES
      (1,'P-CUST','客户甲','ORGANIZATION'),
      (2,'P-SUPP','供应商乙','ORGANIZATION')`
  );
  await conn.query(
    `INSERT INTO party_company_role (id, party_id, company_id, role) VALUES
      (1,1,1,'CUSTOMER'),(2,2,1,'SUPPLIER')`
  );
  await conn.query(
    `INSERT INTO sku (id, code, name, base_unit) VALUES
      (1,'SKU-A','标准盒','个'),
      (2,'SKU-B','标准袋','个'),
      (3,'SKU-SVC','咨询服务','次')`
  );
  await conn.query(`UPDATE sku SET inventory_managed=0 WHERE id=3`);
  // 包装换算按 SKU 定义（字典 §4）：A 一箱 12，B 一箱 24 —— 不存在全局比率
  await conn.query(
    `INSERT INTO sku_conversion (id, sku_id, from_unit, to_unit, numerator, denominator, revision, effective_from) VALUES
      (1,1,'箱','个',12,1,1,'2026-01-01'),
      (2,2,'箱','个',24,1,1,'2026-01-01')`
  );
}

// ---------------------------------------------------------------------------
// 领域原语：S0 假设下的实现
// ---------------------------------------------------------------------------

/** 读余额（无行则视为 0） */
async function bal(companyId, warehouseId, skuId, quality = 'AVAILABLE') {
  const [rows] = await conn.query(
    `SELECT base_quantity FROM stock_balance
      WHERE company_id=? AND warehouse_id=? AND sku_id=? AND base_unit=(
        SELECT base_unit FROM sku WHERE id=?) AND quality_status=?`,
    [companyId, warehouseId, skuId, skuId, quality]
  );
  return rows.length ? Number(rows[0].base_quantity) : 0;
}

/** 总货权数量（现场 + 在途）——契约 §6.2 守恒检查用 */
async function totalQty(companyId, skuId) {
  const [rows] = await conn.query(
    `SELECT COALESCE(SUM(base_quantity),0) q FROM stock_balance
      WHERE company_id=? AND sku_id=? AND base_unit=(SELECT base_unit FROM sku WHERE id=?)`,
    [companyId, skuId, skuId]
  );
  return Number(rows[0].q);
}

/** 余额变更（UPSERT） */
async function applyBalance(companyId, warehouseId, skuId, delta, quality = 'AVAILABLE') {
  const [[sku]] = await conn.query(`SELECT base_unit, inventory_managed FROM sku WHERE id=?`, [skuId]);
  if (!sku.inventory_managed) throw new BizError('INVALID_STATE', `SKU ${skuId} 非库存管理产品，不得记账数量`);
  await conn.query(
    `INSERT INTO stock_balance (company_id, warehouse_id, sku_id, base_unit, quality_status, base_quantity)
     VALUES (?,?,?,?,?,?)
     ON DUPLICATE KEY UPDATE base_quantity = base_quantity + VALUES(base_quantity)`,
    [companyId, warehouseId, skuId, sku.base_unit, quality, delta]
  );
  // 禁负库存（S0）：任一余额维度为负即整笔拒绝（契约 §1 / B06 / B08）
  const [neg] = await conn.query(
    `SELECT warehouse_id, quality_status, base_quantity FROM stock_balance
      WHERE company_id=? AND sku_id=? AND base_quantity < 0 LIMIT 1`,
    [companyId, skuId]
  );
  if (neg.length) {
    throw new BizError('INSUFFICIENT_AVAILABLE_STOCK',
      `负库存：仓 ${neg[0].warehouse_id}/${neg[0].quality_status} = ${neg[0].base_quantity}`);
  }
}

/** 换算：交易单位 → 基本单位。无换算则阻断（契约 §2.1） */
async function toBaseQty(skuId, unit, qty) {
  const [[sku]] = await conn.query(`SELECT base_unit FROM sku WHERE id=?`, [skuId]);
  if (unit === sku.base_unit) return { base: Number(qty), rev: null };
  const [rows] = await conn.query(
    `SELECT numerator, denominator, revision FROM sku_conversion
      WHERE sku_id=? AND from_unit=? AND to_unit=? AND status='ACTIVE' LIMIT 1`,
    [skuId, unit, sku.base_unit]
  );
  if (!rows.length) throw new BizError('UNIT_CONVERSION_MISSING', `SKU ${skuId} 无 ${unit}→${sku.base_unit} 换算`);
  const r = rows[0];
  return { base: Number(qty) * Number(r.numerator) / Number(r.denominator), rev: r.revision };
}

/** 建单据（草稿态） */
async function createDoc(o) {
  const id = o.id ?? nextId();
  await conn.query(
    `INSERT INTO doc (id, doc_type, doc_no, company_id, party_role_id, business_date,
                      approval_status, posting_status, source_doc_id, from_warehouse_id,
                      to_warehouse_id, snapshot_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
    [id, o.docType, o.docNo ?? `D${id}`, o.companyId ?? 1, o.partyRoleId ?? null,
     o.businessDate ?? '2026-09-17', o.approvalStatus ?? 'DRAFT', o.postingStatus ?? 'UNPOSTED',
     o.sourceDocId ?? null, o.fromWarehouseId ?? null, o.toWarehouseId ?? null, o.snapshotAt ?? null]
  );
  return id;
}

async function addLine(o) {
  const id = o.id ?? nextId();
  const { base, rev } = await toBaseQty(o.skuId, o.unit ?? '个', o.quantity);
  await conn.query(
    `INSERT INTO doc_line (id, doc_id, line_no, sku_id, warehouse_id, quantity, unit,
                           base_quantity, conversion_rev, unit_price, net_amount)
     VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
    [id, o.docId, o.lineNo ?? 1, o.skuId, o.warehouseId ?? null, o.quantity, o.unit ?? '个',
     base, rev, o.unitPrice ?? 0, o.netAmount ?? 0]
  );
  return id;
}

async function submitAndApprove(docId) {
  await conn.query(`UPDATE doc SET approval_status='SUBMITTED' WHERE id=? AND approval_status='DRAFT'`, [docId]);
  const [r] = await conn.query(
    `UPDATE doc SET approval_status='APPROVED' WHERE id=? AND approval_status='SUBMITTED'`, [docId]);
  if (!r.affectedRows) throw new BizError('INVALID_STATE', `单据 ${docId} 无法审核`);
  return docId;
}

/** 写一条库存事件（同事务内多腿共享 posting_group_id） */
async function emitEvent(docId, docLineId, type, companyId, warehouseId, skuId, signedQty,
                         { quality = 'AVAILABLE', groupId, reverses = null } = {}) {
  await conn.query(
    `INSERT INTO stock_event (posting_group_id, doc_id, doc_line_id, event_type, company_id,
                              warehouse_id, sku_id, base_unit, quality_status, signed_quantity,
                              reverses_event_id, occurred_at)
     VALUES (?,?,?,?,?,?,?,(SELECT base_unit FROM sku WHERE id=?),?,?,?,NOW())`,
    [groupId, docId, docLineId, type, companyId, warehouseId, skuId, skuId, quality, signedQty, reverses]
  );
}

// ---------------------------------------------------------------------------
// 业务事务原语
// ---------------------------------------------------------------------------

/** 采购入库过账（契约 §5.1）：单据状态 + 事件 + 余额 一次提交 */
async function postPurchaseIn(docId, { quality = 'AVAILABLE' } = {}) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.approval_status !== 'APPROVED') throw new BizError('INVALID_STATE', '未审核不得过账');
    if (doc.posting_status === 'POSTED') return { idempotent: true, docId };
    const groupId = `PG-${docId}-${Date.now()}`;
    const lines = await linesOf(docId);
    for (const l of lines) {
      const q = Number(l.base_quantity);
      await emitEvent(docId, l.id, 'RECEIPT', doc.company_id, l.warehouse_id, l.sku_id, q, { quality, groupId });
      await applyBalance(doc.company_id, l.warehouse_id, l.sku_id, q, quality);
      await conn.query(`UPDATE doc_line SET executed_base_qty = executed_base_qty + ? WHERE id=?`, [q, l.id]);
      // 回写来源采购订单行的执行量（与出库侧 checkOrderLineQuota 对称）
      await consumeSourceQuota(l);
    }
    await conn.query(`UPDATE doc SET posting_status='POSTED' WHERE id=?`, [docId]);
    await raiseArAp(doc, lines, 'AP');       // 应付按 D06，S0 用「入库即确认」
    return { idempotent: false, docId, groupId };
  });
}

/**
 * 质量转移（契约 §5.1 / B05）：待检减、可用增，**不重复入库**
 * 总现存不变是这条用例的核心不变量。
 */
async function releaseInspection(companyId, warehouseId, skuId, qty) {
  return inTx(async () => {
    await applyBalance(companyId, warehouseId, skuId, -Number(qty), 'INSPECT');
    await applyBalance(companyId, warehouseId, skuId, Number(qty), 'AVAILABLE');
  });
}

/** 销售出库过账（契约 §5.2）：扣现存 + 履约增加一次提交 */
async function postSalesOut(docId) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.approval_status !== 'APPROVED') throw new BizError('INVALID_STATE', '未审核不得过账');
    if (doc.posting_status === 'POSTED') return { idempotent: true, docId };
    const groupId = `PG-${docId}-${Date.now()}`;
    const lines = await linesOf(docId);
    // 先校验全部行有足够可发量（零超量容差、禁负库存）
    for (const l of lines) {
      const avail = await bal(doc.company_id, l.warehouse_id, l.sku_id, 'AVAILABLE');
      if (avail + EPS < Number(l.base_quantity)) {
        throw new BizError('INSUFFICIENT_AVAILABLE_STOCK',
          `SKU ${l.sku_id} 可发 ${avail} < 需求 ${l.base_quantity}`);
      }
      await checkOrderLineQuota(l);
    }
    for (const l of lines) {
      const q = Number(l.base_quantity);
      await emitEvent(docId, l.id, 'ISSUE', doc.company_id, l.warehouse_id, l.sku_id, -q, { groupId });
      const [[sku]] = await conn.query('SELECT base_unit FROM sku WHERE id=?', [l.sku_id]);
      await stock.consumeAvailable(conn, doc.company_id, l.warehouse_id, l.sku_id, sku.base_unit, l.base_quantity);
      await conn.query(`UPDATE doc_line SET executed_base_qty = executed_base_qty + ? WHERE id=?`, [q, l.id]);
    }
    await conn.query(`UPDATE doc SET posting_status='POSTED' WHERE id=?`, [docId]);
    await raiseArAp(doc, lines, 'AR');
    return { idempotent: false, docId, groupId };
  });
}

/**
 * 来源订单额度校验 + 消耗（契约 §4）：累计执行 + 本次 ≤ 承诺量 − 已关闭量，零超量容差。
 * 出入库两个方向共用——入库回写采购订单行，出库回写销售订单行。
 */
async function checkOrderLineQuota(childLine) {
  const [rows] = await conn.query(
    `SELECT s.id, s.base_quantity, s.executed_base_qty, s.closed_base_qty
       FROM doc o
       JOIN doc_line s ON s.doc_id = o.source_doc_id
      WHERE o.id = ? AND s.sku_id = ? AND o.source_doc_id IS NOT NULL`,
    [childLine.doc_id, childLine.sku_id]
  );
  if (!rows.length) return;   // 无来源订单（直接出入库）不受订单额度约束
  const s = rows[0];
  const remain = Number(s.base_quantity) - Number(s.closed_base_qty);
  if (Number(s.executed_base_qty) + Number(childLine.base_quantity) > remain + EPS) {
    throw new BizError('SOURCE_QUANTITY_EXCEEDED',
      `来源订单行剩余 ${remain - Number(s.executed_base_qty)}，本次 ${childLine.base_quantity}`);
  }
  await conn.query(`UPDATE doc_line SET executed_base_qty = executed_base_qty + ? WHERE id=?`,
    [Number(childLine.base_quantity), s.id]);
}
const consumeSourceQuota = checkOrderLineQuota;

/** 销售退货入库（契约 §5.3）：原出库行关联，退到指定质量状态 */
async function postSalesReturn(docId) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.approval_status !== 'APPROVED') throw new BizError('INVALID_STATE', '未审核不得过账');
    if (doc.posting_status === 'POSTED') return { idempotent: true, docId };
    const groupId = `PG-${docId}-${Date.now()}`;
    const lines = await linesOf(docId);
    for (const l of lines) {
      const q = Number(l.base_quantity);
      // 可退量 = 原出库行已执行量 − 已过账退货量（契约 §4）
      const [src] = await conn.query(
        `SELECT i.id, i.base_quantity, i.executed_base_qty FROM doc_line i
          WHERE i.doc_id=? AND i.sku_id=? LIMIT 1`,
        [doc.source_doc_id, l.sku_id]
      );
      if (src.length) {
        const [returned] = await conn.query(
          `SELECT COALESCE(SUM(rl.base_quantity),0) q FROM doc r
            JOIN doc_line rl ON rl.doc_id=r.id
           WHERE r.doc_type='SALES_RETURN' AND r.posting_status='POSTED'
             AND r.source_doc_id=? AND rl.sku_id=?`,
          [doc.source_doc_id, l.sku_id]
        );
        const returnable = Number(src[0].base_quantity) - Number(returned[0].q);
        if (q > returnable + EPS) {
          throw new BizError('SOURCE_QUANTITY_EXCEEDED', `可退量 ${returnable} < 本次退货 ${q}`);
        }
      }
      // 退货进 RETURNED 质量状态：不能自动变成可售（契约 §5.3 / T10）
      await emitEvent(docId, l.id, 'RETURN_IN', doc.company_id, l.warehouse_id, l.sku_id, q,
        { quality: 'RETURNED', groupId });
      await applyBalance(doc.company_id, l.warehouse_id, l.sku_id, q, 'RETURNED');
      await conn.query(`UPDATE doc_line SET executed_base_qty = executed_base_qty + ? WHERE id=?`, [q, l.id]);
    }
    await conn.query(`UPDATE doc SET posting_status='POSTED' WHERE id=?`, [docId]);
    return { idempotent: false, docId, groupId };
  });
}

/** 调拨发出/收货（契约 §6.2）：两腿同事务，在途守恒 */
async function postTransferOut(docId) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.approval_status !== 'APPROVED') throw new BizError('INVALID_STATE', '未审核不得过账');
    if (doc.posting_status === 'POSTED') return { idempotent: true, docId };
    const groupId = `PG-${docId}-${Date.now()}`;
    const lines = await linesOf(docId);
    for (const l of lines) {
      const q = Number(l.base_quantity);
      const avail = await bal(doc.company_id, doc.from_warehouse_id, l.sku_id, 'AVAILABLE');
      if (avail + EPS < q) throw new BizError('INSUFFICIENT_AVAILABLE_STOCK', `调出仓可发 ${avail} < ${q}`);
      // 调出：现场 −Q，在途 +Q（同事务）
      await emitEvent(docId, l.id, 'TRANSFER_OUT', doc.company_id, doc.from_warehouse_id, l.sku_id, -q, { groupId });
      await applyBalance(doc.company_id, doc.from_warehouse_id, l.sku_id, -q, 'AVAILABLE');
      await applyBalance(doc.company_id, doc.from_warehouse_id, l.sku_id, q, 'IN_TRANSIT');
    }
    await conn.query(`UPDATE doc SET posting_status='POSTED' WHERE id=?`, [docId]);
    return { idempotent: false, docId, groupId };
  });
}

async function postTransferIn(docId, { receivedQty = null } = {}) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.approval_status !== 'APPROVED') throw new BizError('INVALID_STATE', '未审核不得过账');
    if (doc.posting_status === 'POSTED') return { idempotent: true, docId };
    const groupId = `PG-${docId}-${Date.now()}`;
    const lines = await linesOf(docId);
    for (const l of lines) {
      const r = receivedQty === null ? Number(l.base_quantity) : Number(receivedQty);
      // 累计接收不得超过实际在途额度（契约 §6.2 / B10）
      const inTransit = await bal(doc.company_id, doc.from_warehouse_id, l.sku_id, 'IN_TRANSIT');
      if (r > inTransit + EPS) {
        throw new BizError('SOURCE_QUANTITY_EXCEEDED', `在途 ${inTransit} < 本次收货 ${r}`);
      }
      await emitEvent(docId, l.id, 'TRANSFER_IN', doc.company_id, doc.to_warehouse_id, l.sku_id, r, { groupId });
      await applyBalance(doc.company_id, doc.from_warehouse_id, l.sku_id, -r, 'IN_TRANSIT');
      await applyBalance(doc.company_id, doc.to_warehouse_id, l.sku_id, r, 'AVAILABLE');
    }
    await conn.query(`UPDATE doc SET posting_status='POSTED' WHERE id=?`, [docId]);
    return { idempotent: false, docId, groupId };
  });
}

/** 盘点差异过账（契约 §6.3）：只过账一次；差异未审核不改库存 */
async function postStocktake(docId) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.approval_status !== 'APPROVED') throw new BizError('INVALID_STATE', '差异未审核不得过账');
    if (doc.posting_status === 'POSTED') return { idempotent: true, docId };  // B16/T14：重复提交不再减少
    const groupId = `PG-${docId}-${Date.now()}`;
    const lines = await linesOf(docId);
    for (const l of lines) {
      // 盘点期间若发生未覆盖事件，拒绝按旧快照直接调整（契约 §6.3 / B16）
      if (doc.snapshot_at) {
        const [uncov] = await conn.query(
          `SELECT COUNT(*) c FROM stock_event
            WHERE company_id=? AND warehouse_id=? AND sku_id=? AND occurred_at > ?`,
          [doc.company_id, l.warehouse_id, l.sku_id, doc.snapshot_at]
        );
        if (uncov[0].c > 0) {
          throw new BizError('DOWNSTREAM_DEPENDENCY',
            `SKU ${l.sku_id} 在盘点截点后有 ${uncov[0].c} 条未覆盖事件，拒绝按旧快照调整`);
        }
      }
      // line.quantity 存差异量（可正可负）；base_quantity 由 toBaseQty 对绝对值算出。
      // 直接用 quantity 的符号 × base_quantity 的绝对值，避免二次取反。
      const diff = Number(l.quantity);
      const signed = (diff < 0 ? -1 : 1) * Math.abs(Number(l.base_quantity));
      await emitEvent(docId, l.id, 'ADJUST', doc.company_id, l.warehouse_id, l.sku_id, signed, { groupId });
      await applyBalance(doc.company_id, l.warehouse_id, l.sku_id, signed);
      await conn.query(`UPDATE doc_line SET executed_base_qty = executed_base_qty + ? WHERE id=?`, [signed, l.id]);
    }
    await conn.query(`UPDATE doc SET posting_status='POSTED' WHERE id=?`, [docId]);
    return { idempotent: false, docId, groupId };
  });
}

/**
 * 冲销（契约 §3.2 / B08）：新建反向事件，独立过账。
 * 已交付/已消耗的库存不能通过直接冲销原入库凭空变负。
 */
async function reverseDoc(docId, { partialQty = null } = {}) {
  return inTx(async () => {
    const doc = await lockDoc(docId);
    if (doc.posting_status !== 'POSTED') throw new BizError('INVALID_STATE', '仅已过账单据可冲销');
    const lines = await linesOf(docId);
    const groupId = `PG-REV-${docId}-${Date.now()}`;
    for (const l of lines) {
      const q = partialQty === null ? Number(l.base_quantity) : Number(partialQty);
      const signed = doc.doc_type === 'PURCHASE_IN' ? -q : q;
      // 反向下游依赖检查：冲销后不得让任一余额为负
      await emitEvent(docId, l.id, 'REVERSAL', doc.company_id, l.warehouse_id, l.sku_id, signed, { groupId });
      await applyBalance(doc.company_id, l.warehouse_id, l.sku_id, signed);
    }
    return { docId, groupId };
  });
}

/** 应收应付产生（契约 §7.4）：唯一来源键 = 源单据，防重复生成两份全额 */
async function raiseArAp(doc, lines, direction) {
  const total = lines.reduce((s, l) => s + Number(l.net_amount), 0);
  if (n0(total)) return null;                     // 无金额（如纯数量调拨）不产生往来
  const [ex] = await conn.query(
    `SELECT id FROM ar_ap_item WHERE company_id=? AND source_doc_id=? AND direction=?`,
    [doc.company_id, doc.id, direction]
  );
  if (ex.length) return ex[0].id;                 // 幂等：同源不重复生成
  const id = nextId();
  await conn.query(
    `INSERT INTO ar_ap_item (id, company_id, party_id, direction, currency, source_doc_id,
                             original_amount, adjusted_amount, settled_amount, rule_version)
     VALUES (?,?,?,?,?,?,?,0,0,'S0')`,
    [id, doc.company_id, doc.party_role_id ? await partyOf(doc.party_role_id) : 1, direction, C, doc.id, total]
  );
  return id;
}

async function partyOf(roleId) {
  const [[r]] = await conn.query(`SELECT party_id FROM party_company_role WHERE id=?`, [roleId]);
  return r ? r.party_id : null;
}

/** 资金单登记（契约 §7.1）：F 确认金额，可先形成未分配余额 */
async function createFund({ companyId = 1, partyId, direction = 'IN', amount, occurredAt = '2026-09-17 10:00:00' }) {
  const id = nextId();
  await conn.query(
    `INSERT INTO fund_doc (id, company_id, party_id, direction, currency, amount, occurred_at)
     VALUES (?,?,?,?,?,?,?)`,
    [id, companyId, partyId, direction, C, amount, occurredAt]
  );
  return id;
}

async function fundState(fundId) {
  const [[f]] = await conn.query(`SELECT * FROM fund_doc WHERE id=?`, [fundId]);
  return {
    amount: Number(f.amount), refunded: Number(f.refunded_amount),
    allocated: Number(f.allocated_amount), held: Number(f.held_amount),
    // 可分配额度 = F − R − A − H，不得为负（契约 §7.2）
    available: Number(f.amount) - Number(f.refunded_amount) - Number(f.allocated_amount) - Number(f.held_amount),
  };
}

async function itemState(itemId) {
  const [[i]] = await conn.query(`SELECT * FROM ar_ap_item WHERE id=?`, [itemId]);
  return {
    original: Number(i.original_amount), adjusted: Number(i.adjusted_amount),
    settled: Number(i.settled_amount),
    // 未结金额 = O + Δ − S，S0 不允许未结负数（契约 §7.2）
    outstanding: Number(i.original_amount) + Number(i.adjusted_amount) - Number(i.settled_amount),
  };
}

/** 核销（契约 §7.2）：同时校验资金剩余额度与项目未结额，原子提交 */
async function allocate(fundId, itemId, amount) {
  return accounting.allocate(conn, fundId, itemId, amount, nextId());
}

/** 取消核销：保留反向记录，释放两侧额度，不改变资金 F（契约 §7.2 / T04） */
async function reverseAllocation(allocId) {
  return inTx(async () => {
    const [[a]] = await conn.query(`SELECT * FROM settlement_allocation WHERE id=?`, [allocId]);
    if (!a || a.status !== 'ACTIVE') throw new BizError('INVALID_STATE', '该核销不存在或已取消');
    await conn.query(`UPDATE settlement_allocation SET status='REVERSED' WHERE id=?`, [allocId]);
    await conn.query(`UPDATE fund_doc SET allocated_amount = allocated_amount - ? WHERE id=?`, [a.amount, a.fund_id]);
    await conn.query(`UPDATE ar_ap_item SET settled_amount = settled_amount - ? WHERE id=?`, [a.amount, a.item_id]);
    return { reversalOf: allocId };
  });
}

/** 往来调整（契约 §7.2）：改变有效应结额，保留原额 */
async function adjustItem(itemId, signedAmount) {
  return accounting.adjustItem(conn, itemId, signedAmount);
}

/** 幂等请求（契约 §8）：同键同内容返回原结果；同键不同内容冲突 */
async function idem(requestKey, payload, fn) {
  const hash = require('node:crypto').createHash('sha256')
    .update(JSON.stringify(payload)).digest('hex').slice(0, 32);
  const [rows] = await conn.query(`SELECT * FROM idem_request WHERE request_key=?`, [requestKey]);
  if (rows.length) {
    if (rows[0].payload_hash !== hash) {
      throw new BizError('IDEMPOTENCY_PAYLOAD_CONFLICT', '同请求键不同内容，拒绝作为新请求执行');
    }
    return { idempotent: true, resultRef: rows[0].result_ref };
  }
  const result = await fn();
  await conn.query(
    `INSERT INTO idem_request (request_key, payload_hash, result_ref) VALUES (?,?,?)`,
    [requestKey, hash, String(result.resultRef ?? result.docId ?? 'ok')]
  );
  return { idempotent: false, result };
}

// ---------------------------------------------------------------------------
// 事务包装与取数
// ---------------------------------------------------------------------------
async function inTx(fn) {
  await conn.beginTransaction();
  try {
    const r = await fn();
    await conn.commit();
    return r;
  } catch (e) {
    await conn.rollback();
    throw e;
  }
}

async function lockDoc(docId) {
  const [rows] = await conn.query(`SELECT * FROM doc WHERE id=? FOR UPDATE`, [docId]);
  if (!rows.length) throw new BizError('INVALID_STATE', `单据 ${docId} 不存在`);
  return rows[0];
}

async function linesOf(docId) {
  const [rows] = await conn.query(`SELECT * FROM doc_line WHERE doc_id=? ORDER BY line_no`, [docId]);
  return rows;
}

async function eventSum(docId) {
  const [rows] = await conn.query(
    `SELECT COALESCE(SUM(signed_quantity),0) s FROM stock_event WHERE doc_id=?`, [docId]);
  return Number(rows[0].s);
}

async function eventCount(docId) {
  const [[r]] = await conn.query(`SELECT COUNT(*) c FROM stock_event WHERE doc_id=?`, [docId]);
  return r.c;
}

// ---------------------------------------------------------------------------
// 用例注册表
// ---------------------------------------------------------------------------
const CASES = {};

function defineCase(id, title, fn) {
  CASES[id] = { id, title, fn };
}

require('./erp-s0-cases.cjs')({
  defineCase, Checker, BizError,
  db: () => conn,
  // 领域原语
  resetWorld, bal, totalQty, applyBalance, toBaseQty,
  createDoc, addLine, submitAndApprove, emitEvent,
  postPurchaseIn, releaseInspection, postSalesOut, postSalesReturn,
  postTransferOut, postTransferIn, postStocktake, reverseDoc,
  raiseArAp, createFund, fundState, itemState, allocate, reverseAllocation,
  adjustItem, idem, inTx, lockDoc, linesOf, eventSum, eventCount, nextId,
  partyOf, nEq, n0, EPS,
});

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  const args = process.argv.slice(2);
  const asJson = args.includes('--json');
  const want = args.filter((a) => !a.startsWith('--')).map((s) => s.toUpperCase());

  const cfg = loadDbConfig();
  conn = await mysql.createConnection({ ...cfg, database: SCHEMA });
  await verification.acquire(conn, SCHEMA);
  const results = [];

  const ids = Object.keys(CASES).sort();
  if (want.some(id => !CASES[id])) { await conn.end(); throw new Error('Unknown case ID'); }
  for (const id of ids) {
    if (want.length && !want.includes(id)) continue;
    const c = CASES[id];
    const ck = new Checker(id);
    let status = 'PASS', detail = '';
    try {
      await resetWorld();
      await c.fn(ck);
      if (ck.failures.length) { status = 'FAIL'; detail = ck.failures.join(' | '); }
    } catch (e) {
      status = 'ERROR';
      detail = e instanceof AssertionError ? e.message : `${e.code || e.name}: ${e.message}`;
    }
    const limitations = {
      T06: 'Sequential simulation only; no concurrent stock service verified',
      B11: 'Sequential simulation; see H01/H02 for independent-connection accounting tests',
      T17: 'State and explicit SQL filter only; no identity/API authorization verified',
      B19: 'Scope simulation only; no identity/API authorization verified',
      B20: 'Schema check only; no actual import executed',
      T15: 'Simplified invoice model only; no actual invoice integration',
      X02: 'Legacy Number arithmetic; not an exact-decimal proof'
    };
    results.push({ id, title: c.title, status, detail,
      evidenceLevel: limitations[id] ? 'LIMITED_MODEL' : 'S0_MODEL',
      limitation: limitations[id] || 'S0 model only, not production ERP acceptance' });
    if (!asJson) {
      const icon = status === 'PASS' ? '\u2713' : (status === 'FAIL' ? '\u2717' : '!');
      console.log(`${icon} ${id.padEnd(5)} ${status.padEnd(6)} ${c.title}${detail ? '\n        -> ' + detail : ''}`);
    }
  }

  await conn.query(`DELETE FROM case_result`);
  for (const r of results) {
    await conn.query(`INSERT INTO case_result (case_id, status, detail) VALUES (?,?,?)`,
      [r.id, r.status, r.detail.slice(0, 1000)]);
  }

  const pass = results.filter((r) => r.status === 'PASS').length;
  const fail = results.filter((r) => r.status === 'FAIL').length;
  const err = results.filter((r) => r.status === 'ERROR').length;

  if (asJson) {
    console.log(JSON.stringify({ total: results.length, pass, fail, error: err, results }, null, 2));
  } else {
    console.log(`\n${'='.repeat(72)}`);
    console.log(`合计 ${results.length} 例：PASS ${pass} / FAIL ${fail} / ERROR ${err}`);
    console.log(`${'='.repeat(72)}`);
  }
  await conn.end();
  process.exitCode = fail + err > 0 ? 1 : 0;
}

main().catch(async (e) => {
  if (conn) await conn.end();
  console.error('harness 启动失败:', e.code || e.name, e.message);
  process.exitCode = 2;
});
