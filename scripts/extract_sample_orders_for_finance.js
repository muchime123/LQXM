/**
 * 从 MySQL 数据库提取 20 张代表性销售出库单，用于和财务对账。
 * Node.js 版本：需要 mysql2  (npm i mysql2)
 *
 * HTML 输出：卡片式布局（每张订单一张卡片，左右两栏：系统值 / 财务填写）
 *            不依赖横向滚动，任意分辨率下都能看全。
 *
 * 用法：node scripts/extract_sample_orders_for_finance.js
 */

const fs = require("fs");
const path = require("path");

const DB_HOST = "localhost";
const DB_PORT = 3306;
const DB_USER = "root";
const DB_PWD = "123456";
const DB_NAME = "product_archive";

const OUTPUT_DIR = "c:/Users/20676/source/LQXM";
const TODAY = new Date().toISOString().slice(0, 10).replace(/-/g, "");
const NOW_STR = new Date().toLocaleString("zh-CN", { hour12: false });

// ========== 场景定义 ==========
const SCENARIOS = [
  ["S01", "正常-全额回款(已收=应收)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 " +
    "AND ABS(COALESCE(o.received_amount,0)-COALESCE(o.sales_amount,0))<0.02", 2, "o.ship_date DESC"],
  ["S02", "正常-部分回款(有已收有未收)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 " +
    "AND COALESCE(o.received_amount,0)>0 AND COALESCE(o.outstanding_amount,0)>0", 2, "o.ship_date DESC"],
  ["S03", "正常-尚未回款(已收=0)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 " +
    "AND COALESCE(o.received_amount,0)<0.02 AND COALESCE(o.outstanding_amount,0)>0.02", 2, "o.ship_date DESC"],
  ["S04", "高毛利(毛利率>30%)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 " +
    "AND (o.sales_amount-o.purchase_amount)/o.sales_amount>0.30", 2, "o.ship_date DESC"],
  ["S05", "低毛利(毛利率<10%且>0)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0 " +
    "AND (o.sales_amount-o.purchase_amount)/o.sales_amount BETWEEN 0.0001 AND 0.10", 1, "o.ship_date DESC"],
  ["S06", "零/负毛利(毛利<=0)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>=COALESCE(o.sales_amount,0)",
    2, "o.ship_date DESC"],
  ["S07", "有确认订单费用(已归集)",
    "o.outbound_status='CONFIRMED' AND EXISTS(" +
    "SELECT 1 FROM expense_allocations a " +
    "WHERE a.target_type='SALES_OUTBOUND' AND a.target_id=o.id AND a.allocation_status='CONFIRMED')",
    2, "o.ship_date DESC"],
  ["S08", "有利润调整记录",
    "o.outbound_status='CONFIRMED' AND EXISTS(" +
    "SELECT 1 FROM sales_outbound_profit_adjustments a WHERE a.sales_outbound_id=o.id AND a.status='ACTIVE')",
    1, "o.ship_date DESC"],
  ["S09", "采购成本异常(为空/零/负)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)<=0",
    1, "o.ship_date DESC"],
  ["S10", "员工未映射(employee_id NULL)",
    "o.outbound_status='CONFIRMED' AND o.employee_id IS NULL", 1, "o.ship_date DESC"],
  ["S11", "客户未匹配(customer_id NULL)",
    "o.outbound_status='CONFIRMED' AND o.customer_id IS NULL", 1, "o.ship_date DESC"],
  ["S12", "回款金额校验异常(已收+未收≠应收)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 " +
    "AND ABS(COALESCE(o.received_amount,0)+COALESCE(o.outstanding_amount,0)-COALESCE(o.sales_amount,0))>0.02",
    1, "o.ship_date DESC"],
  ["S13", "包含退货/冲销行(明细有负数量)",
    "o.outbound_status='CONFIRMED' AND EXISTS(" +
    "SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0)",
    1, "o.ship_date DESC"],
  ["S14", "历史订单(2026年前发货)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.ship_date,o.business_date)<'2026-01-01'",
    1, "o.ship_date DESC"],
  ["S15", "大额订单(销售额TOP级别)",
    "o.outbound_status='CONFIRMED' AND COALESCE(o.sales_amount,0)>0 AND COALESCE(o.purchase_amount,0)>0",
    1, "o.sales_amount DESC"],
];

const BASE_SELECT = `
SELECT o.id, o.outbound_no, o.ship_date, o.business_date,
       o.customer_id, o.customer_name, o.employee_id, o.salesperson_name, o.department_name,
       o.sales_amount, o.purchase_amount, o.source_gross_profit,
       o.received_amount, o.outstanding_amount, o.payment_status, o.outbound_status,
       p.accounting_profit AS sys_accounting_profit,
       p.product_gross_profit AS sys_product_gross_profit,
       p.confirmed_expense_amount AS sys_confirmed_expense,
       p.adjustment_amount AS sys_adjustment,
       p.commissionable_profit AS sys_commissionable_profit,
       p.cost_abnormal AS sys_cost_abnormal,
       c.theoretical_commission AS sys_theoretical_commission,
       c.receipt_ratio_pct AS sys_receipt_ratio_pct,
       c.status AS sys_commission_status
FROM sales_outbounds o
LEFT JOIN sales_outbound_profits p ON p.sales_outbound_id = o.id
LEFT JOIN sales_outbound_commissions c ON c.sales_outbound_id = o.id
`;

const nf = new Intl.NumberFormat("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const money = v => (v === null || v === undefined || isNaN(v)) ? "-" : nf.format(Number(v));
const pct = v => (v === null || v === undefined || isNaN(v)) ? "-" : `${Number(v).toFixed(2)}%`;
const esc = s => String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const fmtDate = d => {
  if (!d) return "-";
  if (d instanceof Date) return d.toISOString().slice(0, 10);
  return String(d).slice(0, 10);
};
const m = (v, d = "-") => (v === null || v === undefined || v === "") ? d : v;

/** 尝试写文件，被占用时自动换后缀 v2/v3 */
function writeFileSafe(fullPath, content, enc = "utf-8") {
  try { fs.writeFileSync(fullPath, content, enc); return fullPath; }
  catch (e) {
    if (e && (e.code === "EBUSY" || e.code === "EPERM")) {
      const dir = path.dirname(fullPath);
      const ext = path.extname(fullPath);
      const base = path.basename(fullPath, ext);
      for (let i = 2; i <= 9; i++) {
        const alt = path.join(dir, `${base}_v${i}${ext}`);
        try { fs.writeFileSync(alt, content, enc); return alt; }
        catch (_) { /* continue */ }
      }
      throw new Error(`多次尝试仍无法写入 ${fullPath}（请确认文件没有在 Excel/WPS 中打开后重试）`);
    }
    throw e;
  }
}

/** 生成指标对（系统值 vs 财务手填） */
function metricPair(label, sysValue, sysCls = "", placeHolder = "财务填写 ________") {
  return `
<div class="mp-row">
  <div class="mp-label">${label}</div>
  <div class="mp-sys">
    <div class="mp-sys-tag">系统</div>
    <div class="mp-sys-val ${sysCls}">${sysValue}</div>
  </div>
  <div class="mp-fin">
    <div class="mp-fin-tag">财务✓</div>
    <div class="mp-fin-input">${placeHolder}</div>
  </div>
</div>`;
}

async function main() {
  let mysql;
  try {
    mysql = require("mysql2/promise");
  } catch (e) {
    console.log("需要 mysql2。正在安装……");
    const { execSync } = require("child_process");
    execSync('npm i mysql2 --no-save', { stdio: "inherit", cwd: __dirname });
    mysql = require("mysql2/promise");
  }

  const conn = await mysql.createConnection({
    host: DB_HOST, port: DB_PORT, user: DB_USER, password: DB_PWD,
    database: DB_NAME, charset: "utf8mb4", dateStrings: true
  });
  console.log(`✓ 已连接 ${DB_HOST}:${DB_PORT}/${DB_NAME}`);

  const selectedIds = new Set();
  const rows = [];

  for (const [scode, sname, where, limit, orderby] of SCENARIOS) {
    const exc = [0, ...selectedIds].join(",");
    const sql = `${BASE_SELECT} WHERE (${where}) AND o.id NOT IN (${exc}) ORDER BY ${orderby} LIMIT ${limit}`;
    const [result] = await conn.execute(sql);
    for (const r of result) {
      selectedIds.add(r.id);
      r._scene_code = scode;
      r._scene_name = sname;
      rows.push(r);
    }
    console.log(`  ${scode} ${sname}: 抽到 ${result.length} 张（limit=${limit}）`);
  }

  if (!rows.length) {
    console.error("✗ 一张出库单都没抽到，请确认 sales_outbounds 表是否有数据。");
    process.exit(1);
  }

  // 补齐到 20 张
  const TARGET = 20;
  let need = TARGET - rows.length;
  if (need > 0) {
    console.log(`\n· 补抽阶段：还差 ${need} 张到 20 张目标……`);
    const exc = [0, ...selectedIds].join(",");
    const [topup1] = await conn.execute(`
      ${BASE_SELECT}
      WHERE o.outbound_status='CONFIRMED' AND o.id NOT IN (${exc})
        AND o.sales_amount>0 AND o.purchase_amount>0
      ORDER BY RAND() LIMIT ${Math.min(need, 3)}`);
    for (const r of topup1) {
      selectedIds.add(r.id);
      r._scene_code = "S16";
      r._scene_name = "补充-代表性正常订单(填充)";
      rows.push(r);
    }
    console.log(`  S16 补充-代表性正常订单(填充): 抽到 ${topup1.length} 张`);

    need = TARGET - rows.length;
    if (need > 0) {
      const exc2 = [0, ...selectedIds].join(",");
      const [topup2] = await conn.execute(`
        ${BASE_SELECT}
        WHERE o.outbound_status='CONFIRMED' AND o.id NOT IN (${exc2})
        ORDER BY o.ship_date DESC, o.id DESC LIMIT ${need}`);
      for (const r of topup2) {
        selectedIds.add(r.id);
        r._scene_code = "S17";
        r._scene_name = "补充-随机正常订单(填充)";
        rows.push(r);
      }
      console.log(`  S17 补充-随机正常订单(填充): 抽到 ${topup2.length} 张`);
    }
  }
  console.log(`\n★ 合计抽取 ${rows.length} 张代表性出库单，覆盖 ${new Set(rows.map(r => r._scene_code)).size} 个场景`);

  // 拉详情
  const detailCache = {};
  for (const r of rows) {
    const oid = r.id;
    const [expenses] = await conn.execute(`
      SELECT a.allocated_amount, a.match_method, a.allocation_status,
             c.claim_no, c.title, c.expense_category, c.claim_date,
             c.submitter_name_snapshot
      FROM expense_allocations a
      JOIN expense_claims c ON c.id = a.expense_claim_id
      WHERE a.target_type='SALES_OUTBOUND' AND a.target_id=?
      ORDER BY a.id`, [oid]);
    const [items] = await conn.execute(`
      SELECT sequence_no, product_name, product_code, specification, unit, quantity,
             sales_unit_price, sales_total, purchase_unit_price, purchase_total, source_profit
      FROM sales_outbound_items WHERE sales_outbound_id=?
      ORDER BY COALESCE(sequence_no, 999999), id`, [oid]);
    const [adjustments] = await conn.execute(`
      SELECT adjustment_type, direction, amount, affects_commission, reason, status, created_by
      FROM sales_outbound_profit_adjustments WHERE sales_outbound_id=? ORDER BY id DESC`, [oid]);
    detailCache[oid] = { expenses, items, adjustments };
  }
  await conn.end();

  // 汇总
  const totalSales = rows.reduce((s, r) => s + Number(r.sales_amount || 0), 0);
  const totalCost = rows.reduce((s, r) => s + Number(r.purchase_amount || 0), 0);
  const totalReceived = rows.reduce((s, r) => s + Number(r.received_amount || 0), 0);
  const totalSysProfit = rows.reduce((s, r) => s + Number(r.sys_accounting_profit || 0), 0);
  const totalSysCommission = rows.reduce((s, r) => s + Number(r.sys_theoretical_commission || 0), 0);

  // ================= 写出 CSV =================
  const csvPath = path.join(OUTPUT_DIR, `财务对账_代表性出库单_${TODAY}.csv`);
  const CSV_COLS = [
    ["场景编号", "_scene_code"],["场景说明", "_scene_name"],
    ["出库单号", "outbound_no"],["发货日期", "ship_date"],
    ["业务员", "salesperson_name"],["部门", "department_name"],
    ["客户名称", "customer_name"],["出库状态", "outbound_status"],
    ["【系统】销售额", "sales_amount"],["【系统】采购成本", "purchase_amount"],
    ["【系统】产品毛利", "sys_product_gross_profit"],
    ["【系统】订单费用", "sys_confirmed_expense"],["【系统】利润调整", "sys_adjustment"],
    ["【系统】订单利润", "sys_accounting_profit"],["【系统】可计提利润", "sys_commissionable_profit"],
    ["【系统】已收金额", "received_amount"],["【系统】未收金额", "outstanding_amount"],
    ["【系统】回款率%", "sys_receipt_ratio_pct"],
    ["【系统】理论提成", "sys_theoretical_commission"],
    ["【系统】提成状态", "sys_commission_status"],
    ["【系统】成本异常", "sys_cost_abnormal"],
    ["【财务填写】确认销售额", ""],["【财务填写】确认采购成本", ""],
    ["【财务填写】确认毛利", ""],["【财务填写】确认订单费用", ""],
    ["【财务填写】确认利润调整", ""],["【财务填写】确认订单利润", ""],
    ["【财务填写】提成基数", ""],["【财务填写】提成比例%", ""],
    ["【财务填写】确认提成额", ""],["【财务填写】确认已回款", ""],
    ["【财务填写】差异/备注", ""],
  ];
  let csv = "\uFEFF";
  csv += CSV_COLS.map(c => `"${c[0].replace(/"/g, '""')}"`).join(",") + "\n";
  for (const r of rows) {
    csv += CSV_COLS.map(c => {
      const v = c[1] ? (r[c[1]] ?? "") : "";
      return `"${String(v).replace(/"/g, '""')}"`;
    }).join(",") + "\n";
  }
  const realCsv = writeFileSafe(csvPath, csv, "utf-8");
  console.log(`✓ CSV  已生成：${realCsv}`);

  // ================= 写出 HTML（卡片式布局） =================
  const htmlPath = path.join(OUTPUT_DIR, `财务对账_代表性出库单_${TODAY}.html`);
  const sceneCount = new Set(rows.map(r => r._scene_code)).size;

  const groups = new Map();
  for (const r of rows) {
    const key = r._scene_code + "|" + r._scene_name;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(r);
  }

  let body = "";
  body += `<h1>📋 财务对账 · 代表性销售出库单样本（卡片式）</h1>
<div class="sub">生成时间：${NOW_STR}　|　样本来源：${DB_NAME}.sales_outbounds　|　样本数：${rows.length} 张 / 覆盖 ${sceneCount} 个业务场景</div>

<div class="summary-box">
  <div><div class="k">样本 销售总额</div><div class="v">¥${money(totalSales)}</div></div>
  <div><div class="k">样本 采购成本总额</div><div class="v warn">¥${money(totalCost)}</div></div>
  <div><div class="k">样本 产品毛利总额</div><div class="v pos">¥${money(totalSales - totalCost)}</div></div>
  <div><div class="k">样本 系统订单利润</div><div class="v ${totalSysProfit >= 0 ? 'pos' : 'neg'}">¥${money(totalSysProfit)}</div></div>
  <div><div class="k">样本 已回款总额</div><div class="v">¥${money(totalReceived)}</div></div>
  <div><div class="k">样本 理论提成合计</div><div class="v warn">¥${money(totalSysCommission)}</div></div>
</div>

<div class="legend">
  <b>📌 使用说明（卡片式）：</b>
  <ul>
    <li>每张订单 = 一块独立卡片。卡片分三部分：<b>顶部基本信息</b> / <b>左侧系统计算值</b> / <b>右侧财务填写区（紫色斜纹 = 唯一权威口径）</b>。</li>
    <li>系统值 = 系统硬编码公式算出的<b>未经确认</b>的值；财务填写列是需要手写确认的权威数字。</li>
    <li>每张卡片底部有 ▶ <b>产品明细 / 费用归集 / 利润调整</b> 展开抽屉 + <b>大备注框</b>。</li>
    <li>卡片右上角颜色标签 = 场景编号（S01-S17），对应打印清单首页的场景对照表。</li>
    <li>建议先对完头部 6 个大数字，再逐张卡片对小数字，<b>最后把每张卡片的差异原因填进备注框</b>，签字确认。</li>
  </ul>
</div>
`;

  // 场景对照表
  body += `<h2>📇 场景对照表（共 ${groups.size} 类）</h2><div class="scene-ref-grid">`;
  const SCENE_CAT = {
    S01: "正常回款", S02: "正常回款", S03: "正常回款",
    S04: "毛利梯度", S05: "毛利梯度", S06: "毛利梯度",
    S07: "费用与调整", S08: "费用与调整",
    S09: "异常", S10: "异常", S11: "异常", S12: "异常", S13: "异常",
    S14: "时间与规模", S15: "时间与规模",
    S16: "补充填充", S17: "补充填充",
  };
  for (const [key, grp] of groups) {
    const [scode, sname] = key.split("|");
    const cat = SCENE_CAT[scode] || "其他";
    body += `<div class="scene-ref-card tagbg-${scode}">
      <div class="src-code">${scode}</div>
      <div class="src-cat">${cat}</div>
      <div class="src-name">${esc(sname)}</div>
      <div class="src-count">${grp.length} 张</div>
    </div>`;
  }
  body += `</div>`;

  // 逐场景渲染卡片组
  for (const [key, grp] of groups) {
    const [scode, sname] = key.split("|");
    body += `<h2>${scode} · ${esc(sname)}（${grp.length} 张）</h2>`;
    body += `<div class="cards-grid">`;

    for (let i = 0; i < grp.length; i++) {
      const r = grp[i];
      const oid = r.id;
      const det = detailCache[oid];
      const sales = Number(r.sales_amount || 0);
      const cost = Number(r.purchase_amount || 0);
      const gross = sales - cost;
      const sysProfit = Number(r.sys_accounting_profit || 0);
      const grossRate = sales > 0 ? (gross / sales * 100) : 0;
      const costAbnormal = r.sys_cost_abnormal;
      const employeeMissing = !r.employee_id;
      const customerMissing = !r.customer_id;
      const received = Number(r.received_amount || 0);
      const outstanding = Number(r.outstanding_amount || 0);
      const ratio = r.sys_receipt_ratio_pct;
      const theoryComm = Number(r.sys_theoretical_commission || 0);
      const commStatus = r.sys_commission_status || "-";
      const commissionable = Number(r.sys_commissionable_profit || 0);
      const expense = Number(r.sys_confirmed_expense || 0);
      const adjust = Number(r.sys_adjustment || 0);

      const grossCls = gross < 0 ? "neg" : "";
      const profitCls = sysProfit < 0 ? "neg" : sysProfit > 0 ? "pos" : "";
      const costWarn = costAbnormal
        ? `<div class="warn-chip warn-chip-red">⚠ 成本异常</div>` : "";
      const empWarn = employeeMissing
        ? `<div class="warn-chip warn-chip-amber">⚠ 员工未映射</div>` : "";
      const cusWarn = customerMissing
        ? `<div class="warn-chip warn-chip-amber">⚠ 客户未匹配</div>` : "";
      const commTag = commStatus === "TRIAL"
        ? `<span class="status-chip sc-amber">试算</span>`
        : commStatus === "AVAILABLE"
          ? `<span class="status-chip sc-green">可发</span>`
          : commStatus === "SETTLED"
            ? `<span class="status-chip sc-blue">已结算</span>`
            : commStatus === "NOT_AVAILABLE"
              ? `<span class="status-chip sc-gray">不可用</span>`
              : commStatus === "WAITING_RULE"
                ? `<span class="status-chip sc-purple">缺规则</span>`
                : `<span class="status-chip sc-gray">${esc(commStatus)}</span>`;

      // —— 卡片顶部：基本信息 —— //
      const cardHead = `
<div class="card-head">
  <div class="card-head-left">
    <div class="card-no">#${i + 1} · <b>${esc(r.outbound_no)}</b></div>
    <div class="card-meta">
      <span class="tag tag-${scode}">${scode}</span>
      <span class="meta-pill">📅 发货 ${fmtDate(r.ship_date)}${r.business_date ? ' / 业务 ' + fmtDate(r.business_date) : ''}</span>
      <span class="meta-pill">
        👤 ${employeeMissing ? '<span style="color:#dc2626">⚠未映射</span>' : esc(m(r.salesperson_name, "—"))}
        ${r.department_name ? `<small style="color:#6b7280">（${esc(r.department_name)}）</small>` : ''}
      </span>
      <span class="meta-pill">
        🏢 ${customerMissing ? '<span style="color:#dc2626">⚠未匹配</span>' : esc(m(r.customer_name, "—"))}
      </span>
    </div>
    <div class="card-warns">${costWarn}${empWarn}${cusWarn}${commTag}</div>
  </div>
</div>`;

      // —— 卡片主体：8 行指标对（每行 = 标签 + 系统值 + 财务填写框）—— //
      const metricsHtml = [
        metricPair("① 销售额（元）", money(sales)),
        metricPair("② 采购成本（元）", money(cost) + (costAbnormal ? ' <small style="color:#dc2626">异常</small>' : "")),
        metricPair(`③ 产品毛利（①-②） <small style="color:#6b7280">毛利率 ${pct(grossRate)}</small>`,
          money(gross), grossCls),
        metricPair("④ 确认订单费用（已归集已确认）", money(expense), expense > 0 ? "warn" : ""),
        metricPair("⑤ 利润调整净额（增利+ / 减利−）",
          money(adjust), adjust > 0 ? "pos" : adjust < 0 ? "neg" : ""),
        metricPair(`⑥ 订单利润（③-④+⑤）`, money(sysProfit), profitCls),
        metricPair(`⑦ 可计提利润（参与提成部分，最低为0）`, money(commissionable),
          commissionable <= 0 ? "neg" : ""),
        metricPair(`回款情况：已收 / 未收　<small style="color:#6b7280">回款率 ${m(ratio, "-")}${ratio != null && ratio !== "" ? "%" : ""}</small>`,
          `${money(received)} <span style="color:#6b7280">/</span> ${money(outstanding)}`),
        metricPair(`系统理论提成（锁：试算 TRIAl，available=0）`,
          money(theoryComm), "warn"),
      ].join("");

      // —— 抽屉详情（产品明细/费用/调整）—— //
      let itemsHtml = `<h4>📦 产品明细</h4>`;
      if (det.items.length) {
        itemsHtml += `<div class="scroll-wrap"><table class="det-tbl"><thead><tr>
          <th>行号</th><th>产品名称 / 规格 / 编码</th><th class="num">数量</th>
          <th class="num">销售单价</th><th class="num">销售小计</th>
          <th class="num">采购单价</th><th class="num">采购小计</th>
          <th class="num">源毛利</th></tr></thead><tbody>`;
        for (const it of det.items) {
          const q = Number(it.quantity || 0);
          const qCls = q < 0 ? ' class="num neg"' : ' class="num"';
          itemsHtml += `<tr>
            <td>${m(it.sequence_no, "-")}</td>
            <td><b>${esc(m(it.product_name, "-"))}</b><br/>
              <small style="color:#6b7280">规格 ${esc(m(it.specification, "—"))}　编码 ${esc(m(it.product_code, "—"))}${it.unit ? '　单位 ' + esc(it.unit) : ''}</small>
            </td>
            <td${qCls}>${m(it.quantity, "-")}</td>
            <td class="num">${money(it.sales_unit_price)}</td>
            <td class="num">${money(it.sales_total)}</td>
            <td class="num">${money(it.purchase_unit_price)}</td>
            <td class="num">${money(it.purchase_total)}</td>
            <td class="num">${money(it.source_profit)}</td>
          </tr>`;
        }
        itemsHtml += `</tbody></table></div>`;
      } else {
        itemsHtml += `<p style="color:#9ca3af;margin:0">— 无产品明细数据 —</p>`;
      }

      let expHtml = `<h4>💳 费用归集明细（本订单直接关联的报销，仅 CONFIRMED 才扣利润）</h4>`;
      if (det.expenses.length) {
        expHtml += `<div class="scroll-wrap"><table class="det-tbl"><thead><tr>
          <th>报销单号</th><th>费用类别</th><th>标题</th><th>日期</th><th>提交人</th>
          <th class="num">金额</th><th>匹配方式</th><th>状态</th></tr></thead><tbody>`;
        for (const e of det.expenses) {
          const st = e.allocation_status || "";
          const stTag = st === "CONFIRMED" ? `<span style="color:#059669">已确认✓</span>`
            : st === "PENDING" ? `<span style="color:#d97706">待确认</span>`
            : st === "DRAFT" ? `<span style="color:#6b7280">草稿</span>` : esc(st);
          expHtml += `<tr>
            <td>${m(e.claim_no, "-")}</td>
            <td>${esc(m(e.expense_category, "-"))}</td>
            <td>${esc(m(e.title, "-"))}</td>
            <td>${fmtDate(e.claim_date)}</td>
            <td>${esc(m(e.submitter_name_snapshot, "-"))}</td>
            <td class="num">${money(e.allocated_amount)}</td>
            <td>${esc(m(e.match_method, "-"))}</td>
            <td>${stTag}</td>
          </tr>`;
        }
        expHtml += `</tbody></table></div>`;
      } else {
        expHtml += `<p style="color:#9ca3af;margin:0">— 系统里本订单尚未归集任何订单费用（财务请确认：是没发生，还是发生了但没挂？）—</p>`;
      }

      let adjHtml = `<h4>✏️ 利润调整记录（PLUS 增利 / MINUS 减利；是否参与提成影响第⑦项基数）</h4>`;
      if (det.adjustments.length) {
        adjHtml += `<div class="scroll-wrap"><table class="det-tbl"><thead><tr>
          <th>调整类型</th><th>方向</th><th class="num">金额</th><th>参与提成</th>
          <th>原因</th><th>状态</th><th>操作人</th></tr></thead><tbody>`;
        for (const a of det.adjustments) {
          const d = a.direction || "";
          const dTag = d === "PLUS" ? `<span style="color:#059669">+ PLUS 增利</span>`
            : d === "MINUS" ? `<span style="color:#dc2626">− MINUS 减利</span>` : esc(d);
          const act = a.affects_commission === 1 || a.affects_commission === true ? "是"
            : `<span style="color:#6b7280">否</span>`;
          const sts = a.status || "";
          const sTag = sts === "REVERSED"
            ? `<span style="color:#9ca3af;text-decoration:line-through">已撤销</span>` : esc(sts);
          adjHtml += `<tr>
            <td>${esc(m(a.adjustment_type, "-"))}</td>
            <td>${dTag}</td>
            <td class="num">${money(a.amount)}</td>
            <td>${act}</td>
            <td>${esc(m(a.reason, "-"))}</td>
            <td>${sTag}</td>
            <td>${esc(m(a.created_by, "-"))}</td>
          </tr>`;
        }
        adjHtml += `</tbody></table></div>`;
      } else {
        adjHtml += `<p style="color:#9ca3af;margin:0">— 无利润调整记录 —</p>`;
      }

      const drawer = `<details class="card-drawer">
  <summary>▶ 展开详情：产品明细（${det.items.length} 行）/ 费用归集（${det.expenses.length} 笔）/ 利润调整（${det.adjustments.length} 笔）</summary>
  <div class="card-drawer-inner">
    ${itemsHtml}
    ${expHtml}
    ${adjHtml}
  </div>
</details>`;

      // 差异 & 备注（大框，紫色斜纹底）
      const remarkBlock = `<div class="remark-block">
  <div class="remark-title">✒️ 差异 / 备注（财务填写）：</div>
  <div class="remark-box">请在此标注系统值与财务账的差异原因，例如：
    <ul>
      <li>销售金额是否含税？是否应扣除运费 2,300 元？</li>
      <li>采购成本是否应加上关税 8,400 元？</li>
      <li>回款是否含 3 月到账的 50,000 元？</li>
      <li>提成是否应按"销售额 × 5%"而不是"利润 × 10%"？</li>
    </ul>
  </div>
</div>`;

      body += `<article class="order-card">
  ${cardHead}
  <div class="card-metrics">
    ${metricsHtml}
  </div>
  ${drawer}
  ${remarkBlock}
</article>`;
    }
    body += `</div>`; // cards-grid end
  }

  // 签字区
  body += `<div class="sign-area">
    <div class="col"><div class="line"></div>财务负责人签字<br/><small>日期：______年____月____日</small></div>
    <div class="col"><div class="line"></div>业务负责人签字<br/><small>日期：______年____月____日</small></div>
    <div class="col"><div class="line"></div>系统对接人签字<br/><small>日期：______年____月____日</small></div>
  </div>`;

  // ========== 新 CSS（卡片式） ==========
  const css = `
* { box-sizing: border-box; }
html, body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  margin: 0; padding: 22px 24px; color: #1f2937; background: #f3f4f6;
  font-size: 13px; line-height: 1.55;
}
h1 { margin: 0 0 4px; font-size: 22px; }
.sub { color: #6b7280; margin-bottom: 18px; font-size: 12.5px; }
h2 {
  margin: 30px 0 12px; padding-left: 10px; border-left: 4px solid #2563eb;
  font-size: 16px; display: flex; align-items: center; gap: 8px;
}

/* 顶部汇总卡片 */
.summary-box {
  display: grid; grid-template-columns: repeat(6, minmax(0,1fr)); gap: 10px;
  background: #fff; border: 1px solid #e5e7eb; border-radius: 10px;
  padding: 14px 18px; margin-bottom: 16px; box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}
.summary-box .k { color: #6b7280; font-size: 11.5px; }
.summary-box .v { font-size: 18px; font-weight: 600; margin-top: 4px; font-variant-numeric: tabular-nums; }
.summary-box .v.pos { color: #059669; }
.summary-box .v.neg { color: #dc2626; }
.summary-box .v.warn { color: #d97706; }

/* 图例 */
.legend {
  background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px;
  padding: 12px 18px; margin-bottom: 20px; color: #92400e; font-size: 12.5px;
}
.legend b { color: #78350f; }
.legend ul { margin: 6px 0 0 18px; padding: 0; }
.legend li { margin: 2px 0; }

/* 场景对照网格 */
.scene-ref-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px; margin-bottom: 8px;
}
.scene-ref-card {
  background: #fff; border-radius: 8px; padding: 10px 12px; border: 1px solid #e5e7eb;
  display: grid; grid-template-columns: 44px 1fr; grid-template-rows: auto auto; gap: 2px 10px;
}
.tagbg-S01,.tagbg-S02,.tagbg-S03 { border-left: 4px solid #16a34a; }
.tagbg-S04,.tagbg-S05,.tagbg-S06 { border-left: 4px solid #2563eb; }
.tagbg-S07,.tagbg-S08 { border-left: 4px solid #4f46e5; }
.tagbg-S09,.tagbg-S10,.tagbg-S11,.tagbg-S12,.tagbg-S13 { border-left: 4px solid #dc2626; }
.tagbg-S14,.tagbg-S15 { border-left: 4px solid #d97706; }
.tagbg-S16,.tagbg-S17 { border-left: 4px solid #6b7280; }
.src-code {
  grid-row: span 2; font-weight: 700; font-size: 16px; color: #334155;
  display: flex; align-items: center; justify-content: center;
  background: #f1f5f9; border-radius: 6px;
}
.src-cat { font-size: 10.5px; color: #64748b; letter-spacing: 0.04em; }
.src-name { font-size: 12.5px; color: #0f172a; font-weight: 500; }
.src-count {
  grid-column: 2; font-size: 11px; color: #6b7280; margin-top: 2px;
}

/* 订单卡片网格 */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(640px, 1fr));
  gap: 16px;
}

/* 单张订单卡片 */
.order-card {
  background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05); overflow: hidden;
  display: flex; flex-direction: column;
}

.card-head {
  background: linear-gradient(135deg, #f8fafc 0%, #ffffff 100%);
  border-bottom: 1px solid #e5e7eb; padding: 12px 16px;
}
.card-no { font-size: 15px; color: #111827; margin-bottom: 4px; }
.card-no b { letter-spacing: 0.02em; }
.card-meta {
  display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center;
  margin-top: 2px; font-size: 12.5px; color: #374151;
}
.meta-pill {
  background: #f1f5f9; border-radius: 999px; padding: 3px 10px;
}
.card-warns { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.warn-chip {
  display: inline-block; padding: 2px 9px; border-radius: 999px;
  font-size: 11px; font-weight: 500;
}
.warn-chip-red { background: #fee2e2; color: #991b1b; border: 1px solid #fecaca; }
.warn-chip-amber { background: #fef3c7; color: #92400e; border: 1px solid #fde68a; }
.status-chip {
  display: inline-block; padding: 2px 9px; border-radius: 999px;
  font-size: 11px; font-weight: 600; border: 1px solid transparent;
}
.sc-green { background: #d1fae5; color: #065f46; border-color: #6ee7b7; }
.sc-amber { background: #fef3c7; color: #92400e; border-color: #fcd34d; }
.sc-blue  { background: #dbeafe; color: #1e40af; border-color: #93c5fd; }
.sc-gray  { background: #f3f4f6; color: #374151; border-color: #d1d5db; }
.sc-purple{ background: #ede9fe; color: #5b21b6; border-color: #c4b5fd; }

/* 标签 */
.tag {
  display: inline-block; padding: 2px 8px; border-radius: 10px;
  font-size: 11px; font-weight: 600; white-space: nowrap;
}
.tag-S01,.tag-S02,.tag-S03 { background: #dcfce7; color: #166534; }
.tag-S04,.tag-S05,.tag-S06 { background: #dbeafe; color: #1e3a8a; }
.tag-S07,.tag-S08 { background: #e0e7ff; color: #3730a3; }
.tag-S09,.tag-S10,.tag-S11,.tag-S12,.tag-S13 { background: #fee2e2; color: #991b1b; }
.tag-S14,.tag-S15 { background: #fef3c7; color: #92400e; }
.tag-S16,.tag-S17 { background: #e5e7eb; color: #374151; }

/* 指标对区域（每行 = 左侧系统值 / 右侧财务填写） */
.card-metrics {
  display: flex; flex-direction: column;
}
.mp-row {
  display: grid;
  grid-template-columns: 200px 1fr 1.1fr;
  border-bottom: 1px dashed #e5e7eb;
  align-items: stretch; min-height: 44px;
}
.mp-row:last-of-type { border-bottom: none; }
.mp-label {
  padding: 9px 12px; font-weight: 500; font-size: 12.5px; color: #374151;
  border-right: 1px solid #f1f5f9; display: flex; align-items: center;
  background: #fafafa;
}
.mp-sys {
  padding: 7px 12px; display: grid;
  grid-template-columns: 36px 1fr; gap: 0 8px; align-items: center;
  border-right: 1px solid #f3e8ff;
}
.mp-sys-tag {
  background: #111827; color: #fff; font-size: 10px;
  padding: 2px 6px; border-radius: 4px; text-align: center;
  letter-spacing: 0.05em;
}
.mp-sys-val {
  font-weight: 600; font-size: 13.5px; font-variant-numeric: tabular-nums;
  text-align: right; color: #111827;
}
.mp-sys-val.neg { color: #dc2626; }
.mp-sys-val.pos { color: #059669; }
.mp-sys-val.warn { color: #d97706; }
.mp-fin {
  background: repeating-linear-gradient(45deg, #f5f3ff, #f5f3ff 6px, #ede9fe 6px, #ede9fe 12px);
  padding: 7px 12px; display: grid;
  grid-template-columns: 48px 1fr; gap: 0 8px; align-items: center;
}
.mp-fin-tag {
  background: #7c3aed; color: #fff; font-size: 10px; font-weight: 700;
  padding: 2px 6px; border-radius: 4px; text-align: center;
}
.mp-fin-input {
  border: 1.5px dashed #a78bfa; background: #fff; border-radius: 6px;
  min-height: 28px; padding: 3px 8px; color: #4c1d95;
  font-style: italic; display: flex; align-items: center; justify-content: flex-end;
  font-variant-numeric: tabular-nums;
}

/* 卡片抽屉（产品/费用/调整） */
.card-drawer {
  border-top: 1px solid #e5e7eb;
}
.card-drawer > summary {
  cursor: pointer; padding: 9px 16px; background: #f8fafc;
  font-weight: 600; color: #1e3a8a; list-style: none; font-size: 12.5px;
  border-top: 1px dashed #cbd5e1;
}
.card-drawer > summary::-webkit-details-marker { display: none; }
.card-drawer > summary::before {
  content: "▶ "; color: #94a3b8; font-size: 10px; margin-right: 4px;
}
.card-drawer[open] > summary::before { content: "▼ "; color: #2563eb; }
.card-drawer-inner {
  padding: 10px 16px 14px; background: #fafafa;
  border-top: 1px solid #f1f5f9;
}
.card-drawer-inner h4 {
  margin: 10px 0 6px; font-size: 12px; color: #334155;
  padding-left: 6px; border-left: 3px solid #0ea5e9;
}
.scroll-wrap { overflow-x: auto; }
.det-tbl {
  width: 100%; border-collapse: collapse; background: #fff;
  border: 1px solid #e2e8f0; font-size: 11.5px;
}
.det-tbl th {
  background: #475569; color: #fff; font-weight: 500;
  padding: 5px 9px; text-align: left; border: 1px solid #475569;
  white-space: nowrap;
}
.det-tbl td { padding: 4px 8px; border: 1px solid #e2e8f0; vertical-align: top; }
.det-tbl tr:nth-child(even) td { background: #f8fafc; }
.det-tbl td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.det-tbl td.neg { color: #dc2626; }

/* 备注块 */
.remark-block {
  border-top: 2px solid #c4b5fd; padding: 10px 16px 14px;
  background: repeating-linear-gradient(45deg, #f5f3ff, #f5f3ff 6px, #ede9fe 6px, #ede9fe 12px);
}
.remark-title {
  font-weight: 700; font-size: 12.5px; color: #5b21b6; margin-bottom: 6px;
}
.remark-box {
  min-height: 72px; background: #fff; border: 1.5px dashed #a78bfa;
  border-radius: 8px; padding: 8px 12px; color: #4c1d95;
  font-style: italic;
}
.remark-box ul { margin: 4px 0 0 18px; padding: 0; }
.remark-box li { margin: 1px 0; font-size: 11.5px; opacity: 0.7; }

/* 签字区 */
.sign-area {
  margin-top: 40px; display: grid; grid-template-columns: repeat(3, 1fr);
  gap: 24px; padding-top: 20px; border-top: 1px dashed #9ca3af;
  background: #fff; border-radius: 10px 10px 0 0; padding: 20px 24px 10px;
}
.sign-area .col { text-align: center; }
.sign-area .line {
  border-bottom: 1px solid #1f2937; width: 80%; margin: 0 auto 6px; height: 40px;
}

/* 打印样式 */
@media print {
  html, body { padding: 8px; background: #fff; font-size: 11px; }
  .summary-box { break-inside: avoid; grid-template-columns: repeat(3, 1fr); }
  .scene-ref-grid { break-inside: avoid; }
  .order-card { break-inside: avoid; box-shadow: none; margin-bottom: 10px; border: 1px solid #9ca3af; }
  .card-drawer, .card-drawer>summary { display: block; }
  .card-drawer[open] .card-drawer-inner { break-inside: avoid; }
  .cards-grid { grid-template-columns: 1fr; }
  .sign-area { break-inside: avoid; }
}
`;

  const html = `<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="UTF-8" />
<title>财务对账 · 代表性销售出库单样本 (${TODAY}) · 卡片式</title>
<style>${css}</style>
</head><body>${body}</body></html>`;

  const realHtml = writeFileSafe(htmlPath, html, "utf-8");
  console.log(`✓ HTML 已生成（卡片式布局）：${realHtml}`);

  console.log("\n" + "=".repeat(60));
  console.log("✅ 完成！");
  console.log("  · HTML：浏览器打开即可查看（所有内容无横向滚动），按 Ctrl+P 可打印");
  console.log("  · CSV：Excel 打开直接录入");
  console.log("=".repeat(60));
}

main().catch(err => { console.error("✗ 执行失败：", err); process.exit(1); });
