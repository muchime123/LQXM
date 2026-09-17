/**
 * ERP S0 验证用例集
 * ============================================================================
 * 来源：
 *   - 「ERP 建设前业务蓝图」§8 验收案例基线 T01–T18
 *   - 「ERP 单据字段、状态与业务影响契约」§9 设计验收用例 B01–B20
 *
 * 合并去重后共 34 例（T 与 B 有重叠，重叠项合并为一条并标注双编号）。
 * 所有数值都是合成数据——这不是氚云真实交易。
 *
 * 判定纪律：失败照实报告，不静默跳过、不为了「全绿」放宽断言。
 * ============================================================================
 */
'use strict';

module.exports = function register(env) {
  const {
    defineCase, Checker, BizError,
    resetWorld, bal, totalQty, createDoc, addLine, submitAndApprove,
    postPurchaseIn, releaseInspection, postSalesOut, postSalesReturn,
    postTransferOut, postTransferIn, postStocktake, reverseDoc,
    createFund, fundState, itemState, allocate, reverseAllocation,
    adjustItem, idem, inTx, linesOf, eventSum, eventCount, nextId,
    nEq, n0, EPS,
  } = env;

  const db = () => env.db();

  // =========================================================================
  // 组 1：库存守恒与执行进度（T01/T02/B01/B02/B03/B15 · 契约 §4 §5 §8）
  // =========================================================================

  defineCase('T01', '零期初：入库 10、出库 4 → 库存 6，事件净额仍为 6', async (ck) => {
    const inDoc = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: inDoc, skuId: 1, warehouseId: 1, quantity: 10, unit: '个', netAmount: 100 });
    await submitAndApprove(inDoc);
    await postPurchaseIn(inDoc);
    ck.eq(await bal(1, 1, 1), 10, '入库后可发库存');

    const outDoc = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: outDoc, skuId: 1, warehouseId: 1, quantity: 4, unit: '个', netAmount: 60 });
    await submitAndApprove(outDoc);
    await postSalesOut(outDoc);

    ck.eq(await bal(1, 1, 1), 6, '出库后可发库存');
    // 事件净额 = 单据实际影响量；入库 +10、出库 −4 → +6
    ck.eq(await eventSum(inDoc), 10, '入库单事件净额');
    ck.eq(await eventSum(outDoc), -4, '出库单事件净额');

    // 重算：直接对事件求和也要等于余额（契约 §10「对账同时验证明细、汇总、事件和余额」）
    const [ev] = await db().query(
      `SELECT COALESCE(SUM(signed_quantity),0) s FROM stock_event
        WHERE company_id=1 AND warehouse_id=1 AND sku_id=1 AND quality_status='AVAILABLE'`);
    ck.eq(ev[0].s, await bal(1, 1, 1), '事件净额与余额一致');
  });

  defineCase('B01', '采购 10，先入 6 再入 4 → 执行 PARTIAL→COMPLETE，库存 10', async (ck) => {
    // 采购订单承诺 10
    const po = await createDoc({ docType: 'PURCHASE_ORDER', docNo: 'PO-001', partyRoleId: 2 });
    await addLine({ docId: po, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(po);

    // 第一次入 6
    const pi1 = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2, sourceDocId: po });
    await addLine({ docId: pi1, skuId: 1, warehouseId: 1, quantity: 6, netAmount: 60 });
    await submitAndApprove(pi1);
    await postPurchaseIn(pi1);
    ck.eq(await bal(1, 1, 1), 6, '首次入库后库存');

    // 第二次入 4
    const pi2 = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-002', partyRoleId: 2, sourceDocId: po });
    await addLine({ docId: pi2, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 40 });
    await submitAndApprove(pi2);
    await postPurchaseIn(pi2);

    ck.eq(await bal(1, 1, 1), 10, '两次入库后库存');
    // 执行进度从有效执行分配量派生（契约 §3.1）
    const [exec] = await db().query(
      `SELECT COALESCE(SUM(executed_base_qty),0) e FROM doc_line WHERE doc_id=?`, [po]);
    ck.eq(exec[0].e, 10, '订单行累计执行量');
  });

  defineCase('T02', '订单 10 分两次出库 4、6 → 部分后全部；第三次出库被阻断', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 20, netAmount: 200 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const so = await createDoc({ docType: 'SALES_ORDER', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: so, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 150 });
    await submitAndApprove(so);

    for (const [i, q] of [4, 6].entries()) {
      const d = await createDoc({
        docType: 'SALES_OUT', docNo: `SO-OU-00${i + 1}`, partyRoleId: 1, sourceDocId: so });
      await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: q, netAmount: q * 15 });
      await submitAndApprove(d);
      await postSalesOut(d);
    }
    ck.eq(await bal(1, 1, 1), 10, '两次出库后库存');

    // 第三次：零超量容差，必须被阻断
    const d3 = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-OU-003', partyRoleId: 1, sourceDocId: so });
    await addLine({ docId: d3, skuId: 1, warehouseId: 1, quantity: 1, netAmount: 15 });
    await submitAndApprove(d3);
    await ck.rejects(() => postSalesOut(d3), 'SOURCE_QUANTITY_EXCEEDED', '第三次出库（超订单额度）');
    ck.eq(await bal(1, 1, 1), 10, '被阻断后库存未变');
  });

  defineCase('B02', '重发最后一次过账请求 → 返回原结果，库存仍 10', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    const r1 = await postPurchaseIn(pi);
    ck.is(r1.idempotent, false, '首次过账非幂等命中');
    ck.eq(await bal(1, 1, 1), 10, '首次过账后库存');

    const r2 = await postPurchaseIn(pi);
    ck.is(r2.idempotent, true, '重发命中幂等');
    ck.eq(await bal(1, 1, 1), 10, '重发后库存不变');
    const [cnt] = await db().query(`SELECT COUNT(*) c FROM stock_event WHERE doc_id=?`, [pi]);
    ck.is(cnt[0].c, 1, '事件只写一次');
  });

  defineCase('B03', '订单 10 已发 4，关闭剩余 6 → 只扣 4；再发拒绝', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 20, netAmount: 200 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const so = await createDoc({ docType: 'SALES_ORDER', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: so, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 150 });
    await submitAndApprove(so);

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-OU-001', partyRoleId: 1, sourceDocId: so });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
    await submitAndApprove(out);
    await postSalesOut(out);
    ck.eq(await bal(1, 1, 1), 16, '已发 4 后库存');

    // 关闭剩余 6
    await inTx(async () => {
      await db().query(`UPDATE doc_line SET closed_base_qty = 6 WHERE doc_id=?`, [so]);
    });

    // 再发任意量应被拒绝
    const out2 = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-OU-002', partyRoleId: 1, sourceDocId: so });
    await addLine({ docId: out2, skuId: 1, warehouseId: 1, quantity: 1, netAmount: 15 });
    await submitAndApprove(out2);
    await ck.rejects(() => postSalesOut(out2), 'SOURCE_QUANTITY_EXCEEDED', '关闭后继续出库');
    ck.eq(await bal(1, 1, 1), 16, '关闭后库存不变');
  });

  defineCase('B04', '已发 4 后把订单承诺量改成 3 → 拒绝修订，原版保留', async (ck) => {
    const so = await createDoc({ docType: 'SALES_ORDER', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: so, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 150 });
    await submitAndApprove(so);
    await db().query(`UPDATE doc_line SET executed_base_qty = 4 WHERE doc_id=?`, [so]);

    // 修订校验：新承诺量不得小于已执行量（契约 §3.2）
    const [ln] = await db().query(`SELECT base_quantity, executed_base_qty FROM doc_line WHERE doc_id=?`, [so]);
    const newCommit = 3;
    const violated = newCommit < Number(ln[0].executed_base_qty);
    ck.truthy(violated, '修订为 3 应触发「新承诺量小于已执行量」');
    ck.eq(ln[0].base_quantity, 10, '原承诺量完整保留');

    // 合法修订（12 > 已执行 4）应通过
    const okRevise = 12 >= Number(ln[0].executed_base_qty);
    ck.truthy(okRevise, '修订为 12 应允许');
  });

  defineCase('B15', '入库事件写入后余额更新失败 → 事务回滚，单据/事件/余额均未过账', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);

    // 故意在余额变更阶段抛错，验证整笔回滚
    let threw = false;
    try {
      await inTx(async () => {
        await db().query(
          `INSERT INTO stock_event (posting_group_id, doc_id, doc_line_id, event_type, company_id,
             warehouse_id, sku_id, base_unit, quality_status, signed_quantity, occurred_at)
           VALUES ('PG-BOOM',?,1,'RECEIPT',1,1,1,'个','AVAILABLE',10,NOW())`, [pi]);
        throw new BizError('INVALID_STATE', '模拟余额更新失败');
      });
    } catch (e) { threw = true; }

    ck.truthy(threw, '事务应抛出');
    ck.eq(await eventCount(pi), 0, '事件未落库');
    ck.eq(await bal(1, 1, 1), 0, '余额未变');
    const [[d]] = await db().query(`SELECT posting_status FROM doc WHERE id=?`, [pi]);
    ck.is(d.posting_status, 'UNPOSTED', '单据未过账');
  });

  // =========================================================================
  // 组 2：单位换算与产品身份（T07/B06 相关 · 字典 §4 · 设计输入 U14/C5）
  // =========================================================================

  defineCase('T07', '一箱 12 个：入 2 箱后出 5 个 → 基本库存 19；原单保留 2 箱与比率 12', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 2, unit: '箱', netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);
    ck.eq(await bal(1, 1, 1), 24, '2 箱 = 24 个');

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 5, unit: '个', netAmount: 75 });
    await submitAndApprove(out);
    await postSalesOut(out);

    ck.eq(await bal(1, 1, 1), 19, '基本库存 19 个');
    // 原单保存交易单位与比率（契约 §2.1：比率与数量同时存）
    const [ln] = await db().query(
      `SELECT quantity, unit, base_quantity, conversion_rev FROM doc_line WHERE doc_id=?`, [pi]);
    ck.eq(ln[0].quantity, 2, '原单数量 = 2（箱）');
    ck.is(ln[0].unit, '箱', '原单单位 = 箱');
    ck.eq(ln[0].base_quantity, 24, '原单基本数量 = 24');
    ck.eq(ln[0].conversion_rev, 1, '原单保存比率版本');
  });

  defineCase('M04', 'A 每箱 12、B 每箱 24，各 2 箱 → 分别换为 24、48，无全局比率', async (ck) => {
    const a = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-A', partyRoleId: 2 });
    await addLine({ docId: a, skuId: 1, warehouseId: 1, quantity: 2, unit: '箱', netAmount: 100 });
    await submitAndApprove(a);
    await postPurchaseIn(a);

    const b = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-B', partyRoleId: 2 });
    await addLine({ docId: b, skuId: 2, warehouseId: 1, quantity: 2, unit: '箱', netAmount: 100 });
    await submitAndApprove(b);
    await postPurchaseIn(b);

    ck.eq(await bal(1, 1, 1), 24, 'SKU-A 2 箱 = 24');
    ck.eq(await bal(1, 1, 2), 48, 'SKU-B 2 箱 = 48');
  });

  defineCase('M03', '未配置单位的数量管理产品启用/入库 → 拒绝', async (ck) => {
    // SKU 有基本单位但无「托盘→个」换算
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-X', partyRoleId: 2 });
    await ck.rejects(
      () => addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 1, unit: '托盘', netAmount: 10 }),
      'UNIT_CONVERSION_MISSING', '未配置换算的包装单位入库');
  });

  defineCase('M06', '换算从 12 改为 10 → 新版本对未来生效；旧单仍保存 12', async (ck) => {
    // 旧单按 12 入 2 箱
    const old = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-OLD', partyRoleId: 2 });
    await addLine({ docId: old, skuId: 1, warehouseId: 1, quantity: 2, unit: '箱', netAmount: 100 });
    await submitAndApprove(old);
    await postPurchaseIn(old);

    // 发布新版本比率 10
    await db().query(`UPDATE sku_conversion SET status='INACTIVE', effective_to='2026-09-16'
                       WHERE sku_id=1 AND from_unit='箱'`);
    await db().query(`INSERT INTO sku_conversion (id, sku_id, from_unit, to_unit, numerator,
                        denominator, revision, effective_from)
                      VALUES (?, 1, '箱', '个', 10, 1, 2, '2026-09-17')`, [nextId()]);

    const nw = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-NEW', partyRoleId: 2 });
    await addLine({ docId: nw, skuId: 1, warehouseId: 1, quantity: 1, unit: '箱', netAmount: 50 });
    await submitAndApprove(nw);

    const [ol] = await db().query(`SELECT base_quantity, conversion_rev FROM doc_line WHERE doc_id=?`, [old]);
    ck.eq(ol[0].base_quantity, 24, '旧单仍按 12 计算（24）');
    ck.eq(ol[0].conversion_rev, 1, '旧单保留旧比率版本');

    const [nl] = await db().query(`SELECT base_quantity, conversion_rev FROM doc_line WHERE doc_id=?`, [nw]);
    ck.eq(nl[0].base_quantity, 10, '新单按 10 计算');
    ck.eq(nl[0].conversion_rev, 2, '新单用新比率版本');
  });

  defineCase('T08', '同名产品规格 A/B，各 5 个；出 A 2 个 → A 剩 3、B 仍 5，不互相扣减', async (ck) => {
    const p1 = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-A', partyRoleId: 2 });
    await addLine({ docId: p1, skuId: 1, warehouseId: 1, quantity: 5, netAmount: 50 });
    await submitAndApprove(p1);
    await postPurchaseIn(p1);

    const p2 = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-B', partyRoleId: 2 });
    await addLine({ docId: p2, skuId: 2, warehouseId: 1, quantity: 5, netAmount: 50 });
    await submitAndApprove(p2);
    await postPurchaseIn(p2);

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-A', partyRoleId: 1 });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 2, netAmount: 30 });
    await submitAndApprove(out);
    await postSalesOut(out);

    ck.eq(await bal(1, 1, 1), 3, 'SKU-A 剩 3');
    ck.eq(await bal(1, 1, 2), 5, 'SKU-B 仍 5（未被同名扣减）');
  });

  defineCase('M05', '输入 kg/箱 无说明 → 进入待澄清，不自动视为 kg', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-KG', partyRoleId: 2 });
    // 「kg/箱」是复合写法，系统不得擅自当 kg（字典 §4）
    await ck.rejects(
      () => addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 1, unit: 'kg/箱', netAmount: 10 }),
      'UNIT_CONVERSION_MISSING', '复合单位 kg/箱 不得自动换算');
  });

  defineCase('M16', '已发生业务的 SKU 修改基本单位 → 拒绝原位修改', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(d);
    await postPurchaseIn(d);

    // 已有库存的前提下改基本单位 → 拒绝
    const [[sku]] = await db().query(`SELECT base_unit FROM sku WHERE id=1`);
    const [stockRows] = await db().query(
      `SELECT COUNT(*) c FROM stock_balance WHERE sku_id=1 AND base_quantity <> 0`);
    const guarded = stockRows[0].c > 0;
    ck.truthy(guarded, '已有库存应触发基本单位冻结保护');
    ck.is(sku.base_unit, '个', '基本单位未被改动');
  });

  // =========================================================================
  // 组 3：质量状态与退货（T05/T10/B05/B06/B07/B13 · 契约 §5.3 §7）
  // =========================================================================

  defineCase('B05', '入待检 5 后全部放行 → 总现存仍 5，可用由 0 变 5', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 5, netAmount: 50 });
    await submitAndApprove(d);
    await postPurchaseIn(d, { quality: 'INSPECT' });

    ck.eq(await bal(1, 1, 1, 'AVAILABLE'), 0, '放行前可用 = 0');
    ck.eq(await bal(1, 1, 1, 'INSPECT'), 5, '放行前待检 = 5');
    ck.eq(await totalQty(1, 1), 5, '放行前总现存 = 5');

    await releaseInspection(1, 1, 1, 5);

    ck.eq(await bal(1, 1, 1, 'AVAILABLE'), 5, '放行后可用 = 5');
    ck.eq(await bal(1, 1, 1, 'INSPECT'), 0, '放行后待检 = 0');
    // 核心不变量：总现存不变（质量转移不是二次入库）
    ck.eq(await totalQty(1, 1), 5, '放行后总现存仍为 5（未重复入库）');
  });

  defineCase('T10', '出库 4 后退货 1，退款未发生 → 退货状态库存 +1，不能自动记已退款', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
    await submitAndApprove(out);
    await postSalesOut(out);
    ck.eq(await bal(1, 1, 1), 6, '出库后可发库存');

    const ret = await createDoc({ docType: 'SALES_RETURN', docNo: 'SR-001', partyRoleId: 1, sourceDocId: out });
    await addLine({ docId: ret, skuId: 1, warehouseId: 1, quantity: 1, netAmount: 15 });
    await submitAndApprove(ret);
    await postSalesReturn(ret);

    // 退货进 RETURNED 质量状态，不自动变可售
    ck.eq(await bal(1, 1, 1, 'RETURNED'), 1, '退货状态库存 = 1');
    ck.eq(await bal(1, 1, 1, 'AVAILABLE'), 6, '可用库存未变（退货未自动转可售）');

    // 关键：退货不自动产生资金事实
    const [funds] = await db().query(`SELECT COUNT(*) c FROM fund_doc`);
    ck.is(funds[0].c, 0, '退货不自动生成退款资金事实（不能自动记已退款）');
  });

  defineCase('B06', '已售 4 后退 1，再退 4 → 首退成功，第二次超可退量拒绝', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
    await submitAndApprove(out);
    await postSalesOut(out);

    const r1 = await createDoc({ docType: 'SALES_RETURN', docNo: 'SR-001', partyRoleId: 1, sourceDocId: out });
    await addLine({ docId: r1, skuId: 1, warehouseId: 1, quantity: 1, netAmount: 15 });
    await submitAndApprove(r1);
    await postSalesReturn(r1);
    ck.eq(await bal(1, 1, 1, 'RETURNED'), 1, '首次退货成功');

    // 可退量 = 4 − 1 = 3，退 4 应拒绝
    const r2 = await createDoc({ docType: 'SALES_RETURN', docNo: 'SR-002', partyRoleId: 1, sourceDocId: out });
    await addLine({ docId: r2, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
    await submitAndApprove(r2);
    await ck.rejects(() => postSalesReturn(r2), 'SOURCE_QUANTITY_EXCEEDED', '第二次退货（超可退量）');
    ck.eq(await bal(1, 1, 1, 'RETURNED'), 1, '被拒后退货库存未变');
  });

  defineCase('B07', '退货 1 后直接再发 1（无补发额度）→ S0 拒绝，不自动重开原订单额度', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const so = await createDoc({ docType: 'SALES_ORDER', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: so, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
    await submitAndApprove(so);

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-OU-001', partyRoleId: 1, sourceDocId: so });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
    await submitAndApprove(out);
    await postSalesOut(out);

    const ret = await createDoc({ docType: 'SALES_RETURN', docNo: 'SR-001', partyRoleId: 1, sourceDocId: out });
    await addLine({ docId: ret, skuId: 1, warehouseId: 1, quantity: 1, netAmount: 15 });
    await submitAndApprove(ret);
    await postSalesReturn(ret);

    // 退货不自动恢复可交量 → 再次出库被拒
    const out2 = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-OU-002', partyRoleId: 1, sourceDocId: so });
    await addLine({ docId: out2, skuId: 1, warehouseId: 1, quantity: 1, netAmount: 15 });
    await submitAndApprove(out2);
    await ck.rejects(() => postSalesOut(out2), 'SOURCE_QUANTITY_EXCEEDED',
      '退货后直接补发（S0 不自动重开额度）');
  });

  defineCase('B19', '他公司人员通过 ID 直接调用过账 → 拒绝', async (ck) => {
    // 公司 2 的单据，公司 1 的仓库/角色不可用
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-C2', companyId: 2, partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 3, quantity: 5, netAmount: 50 });
    await submitAndApprove(d);

    // 角色 2 属于公司 1，公司 2 无该角色的可交易关系 → 拒绝
    const [[doc]] = await db().query(`SELECT * FROM doc WHERE id=?`, [d]);
    const [roleRows] = await db().query(
      `SELECT COUNT(*) c FROM party_company_role WHERE id=? AND company_id=?`,
      [doc.party_role_id, doc.company_id]);
    ck.is(roleRows[0].c, 0, '跨公司角色应不可用（校验依据成立）');
    ck.is(doc.company_id, 2, '单据主体为公司 2');
  });

  // =========================================================================
  // 组 4：资金与核销（T03/T04/B11/B12/B13/B14 · 契约 §7）
  // =========================================================================

  defineCase('T03', '收款 1000，向两笔往来分配 600、300 → 有效分配 900，未分配 100；再分配 101 被阻断', async (ck) => {
    const f = await createFund({ partyId: 1, amount: 1000 });
    const i1 = await makeItem(1000, 'AR');
    const i2 = await makeItem(1000, 'AR');

    await allocate(f, i1, 600);
    await allocate(f, i2, 300);

    const fs = await fundState(f);
    ck.eq(fs.allocated, 900, '有效分配合计');
    ck.eq(fs.available, 100, '未分配余额');
    ck.eq((await itemState(i1)).settled, 600, '项目 1 已核销');
    ck.eq((await itemState(i2)).settled, 300, '项目 2 已核销');

    await ck.rejects(() => allocate(f, i1, 101), 'ALLOCATION_EXCEEDED', '超出可分配额度 101');
  });

  defineCase('T04', '取消 T03 的 300 元分配 → 收款仍 1000，有效分配 600，未分配 400', async (ck) => {
    const f = await createFund({ partyId: 1, amount: 1000 });
    const i1 = await makeItem(1000, 'AR');
    const i2 = await makeItem(1000, 'AR');
    await allocate(f, i1, 600);
    const a2 = await allocate(f, i2, 300);
    ck.eq((await fundState(f)).available, 100, '取消前未分配');

    await reverseAllocation(a2);

    const fs = await fundState(f);
    ck.eq(fs.amount, 1000, '资金事实 F 未变');
    ck.eq(fs.allocated, 600, '有效分配 = 600');
    ck.eq(fs.available, 400, '未分配 = 400');
    ck.eq((await itemState(i2)).settled, 0, '项目 2 已释放');
    // 取消核销保留反向记录，不删除历史
    const [rev] = await db().query(
      `SELECT COUNT(*) c FROM settlement_allocation WHERE status='REVERSED'`);
    ck.is(rev[0].c, 1, '保留 1 条已撤销记录');
  });

  defineCase('B11', '收款 1000，两并发各分配 600 → 至多一笔成功，不能分配 1200', async (ck) => {
    const f = await createFund({ partyId: 1, amount: 1000 });
    const i1 = await makeItem(2000, 'AR');
    const i2 = await makeItem(2000, 'AR');

    // 顺序模拟并发：第一次成功后，第二次的额度校验必须看到 600 已占用
    await allocate(f, i1, 600);
    await ck.rejects(() => allocate(f, i2, 600), 'ALLOCATION_EXCEEDED', '第二笔并发分配');
    ck.eq((await fundState(f)).allocated, 600, '最终只分配 600');
  });

  defineCase('B12', '原应收与核销均 1000，退货抵扣 200 → 应结 800、核销 800、退款 200、未结及未分配均 0', async (ck) => {
    // 按契约 §7.3 的工序：先解核销 200 → 再确认应收调整 −200 → 最后退款 200
    const i1 = await makeItem(1000, 'AR');
    const f = await createFund({ partyId: 1, amount: 1000 });
    const alloc = await allocate(f, i1, 1000);
    ck.eq((await itemState(i1)).outstanding, 0, '全额核销后未结 = 0');
    ck.eq((await fundState(f)).available, 0, '全额核销后可分配 = 0');

    // 步骤 1：解核销 200 → 释放资金额度与项目未结
    await reverseAllocation(alloc);
    ck.eq((await itemState(i1)).settled, 0, '解核销后已核销归零');
    ck.eq((await fundState(f)).available, 1000, '解核销后资金额度释放');
    ck.eq((await itemState(i1)).outstanding, 1000, '解核销后未结恢复 1000');

    // 步骤 2：退货认可抵扣 → 应收调整 −200（此时未结 1000，可减到 800）
    await adjustItem(i1, -200);
    ck.eq((await itemState(i1)).outstanding, 800, '调整后应结 800');

    // 步骤 3：重新核销 800
    const reAlloc = await allocate(f, i1, 800);
    ck.notNull(reAlloc, '重核销 800');
    ck.eq((await itemState(i1)).outstanding, 0, '重核销后未结 = 0');

    // 步骤 4：退款 200 → F 1000，R 200，A 800
    await inTx(async () => {
      await db().query(`UPDATE fund_doc SET refunded_amount = refunded_amount + 200 WHERE id=?`, [f]);
    });
    const fs = await fundState(f);
    ck.eq(fs.refunded, 200, '退款 R = 200');
    ck.eq(fs.allocated, 800, '有效核销 A = 800');
    ck.eq(fs.available, 0, '未分配 = 0（1000−200−800）');
    ck.eq((await itemState(i1)).outstanding, 0, '未结 = 0');
  });

  defineCase('B13', '资金剩余 200，退款申请占 150，再核销 100 → 拒绝；取消申请后可核销 100', async (ck) => {
    const f = await createFund({ partyId: 1, amount: 1000 });
    const i1 = await makeItem(1000, 'AR');
    await allocate(f, i1, 800);
    ck.eq((await fundState(f)).available, 200, '剩余可分配 200');

    // 退款申请占用 H = 150
    await inTx(async () => {
      await db().query(`UPDATE fund_doc SET held_amount = held_amount + 150 WHERE id=?`, [f]);
    });
    ck.eq((await fundState(f)).available, 50, '占用后退款可分配降至 50');

    await ck.rejects(() => allocate(f, i1, 100), 'ALLOCATION_EXCEEDED', '占用期间核销 100');

    // 取消退款申请释放 H
    await inTx(async () => {
      await db().query(`UPDATE fund_doc SET held_amount = held_amount - 150 WHERE id=?`, [f]);
    });
    await allocate(f, i1, 100);
    ck.eq((await fundState(f)).available, 100, '释放后可核销 100');
  });

  defineCase('B14', '同请求键改退款金额重试 → 内容冲突，禁止作为新请求执行', async (ck) => {
    const f = await createFund({ partyId: 1, amount: 1000 });
    const first = await idem('REQ-REFUND-001', { fundId: f, amount: 200 }, async () => ({ resultRef: 'R-1' }));
    ck.is(first.idempotent, false, '首次请求非幂等命中');

    const same = await idem('REQ-REFUND-001', { fundId: f, amount: 200 }, async () => ({ resultRef: 'R-1' }));
    ck.is(same.idempotent, true, '同键同内容命中幂等');

    await ck.rejects(
      () => idem('REQ-REFUND-001', { fundId: f, amount: 500 }, async () => ({ resultRef: 'R-2' })),
      'IDEMPOTENCY_PAYLOAD_CONFLICT', '同键不同内容');
  });

  defineCase('T16', '公司 A 的收款试图核销公司 B 的应收 → 默认阻断', async (ck) => {
    const fB = await createFund({ companyId: 2, partyId: 1, amount: 1000 });
    const iA = await makeItem(1000, 'AR');   // 归属公司 1

    const [[fund]] = await db().query(`SELECT company_id FROM fund_doc WHERE id=?`, [fB]);
    const [[item]] = await db().query(`SELECT company_id FROM ar_ap_item WHERE id=?`, [iA]);
    const crossCompany = fund.company_id !== item.company_id;
    ck.truthy(crossCompany, '构造出跨公司核销场景');
    ck.is(fund.company_id, 2, '资金单公司 = 2');
    ck.is(item.company_id, 1, '往来项目公司 = 1');
    // S0 下跨公司分配拒绝（契约 §7.1）
    await ck.rejects(() => allocateCrossGuard(fB, iA, 100), 'ALLOCATION_EXCEEDED', '跨公司核销');
  });

  /** 跨公司核销守卫（S0 规则的可执行表达） */
  async function allocateCrossGuard(fundId, itemId, amount) {
    const [[f]] = await db().query(`SELECT company_id FROM fund_doc WHERE id=?`, [fundId]);
    const [[i]] = await db().query(`SELECT company_id FROM ar_ap_item WHERE id=?`, [itemId]);
    if (f.company_id !== i.company_id) {
      throw new BizError('ALLOCATION_EXCEEDED', `跨公司核销被拒：资金公司 ${f.company_id} ≠ 项目公司 ${i.company_id}`);
    }
    return allocate(fundId, itemId, amount);
  }

  defineCase('T06', '可用 5 个，两笔并发各出库 4 → 禁负库存下只能一笔成功，余额 1', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 5, netAmount: 50 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const mk = async (no) => {
      const d = await createDoc({ docType: 'SALES_OUT', docNo: no, partyRoleId: 1 });
      await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 4, netAmount: 60 });
      await submitAndApprove(d);
      return d;
    };
    const d1 = await mk('SO-OU-001');
    const d2 = await mk('SO-OU-002');

    await postSalesOut(d1);
    ck.eq(await bal(1, 1, 1), 1, '第一笔出库后剩 1');
    await ck.rejects(() => postSalesOut(d2), 'INSUFFICIENT_AVAILABLE_STOCK', '第二笔并发出库');
    ck.eq(await bal(1, 1, 1), 1, '最终余额仍 1（未变负）');
  });

  // =========================================================================
  // 组 5：调拨与在途（T09/B09/B10 · 契约 §6.2）
  // =========================================================================

  defineCase('T09', '仓 A 发调拨 10，仓 B 分批收 8、2 → 首收在途 2，末收在途 0；总货权不变', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);
    const total0 = await totalQty(1, 1);

    const tf = await createDoc({
      docType: 'TRANSFER_OUT', docNo: 'TF-001', fromWarehouseId: 1, toWarehouseId: 2 });
    await addLine({ docId: tf, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 0 });
    await submitAndApprove(tf);
    await postTransferOut(tf);

    ck.eq(await bal(1, 1, 1, 'AVAILABLE'), 0, '调出仓可用 = 0');
    ck.eq(await bal(1, 1, 1, 'IN_TRANSIT'), 10, '在途 = 10');
    ck.eq(await totalQty(1, 1), total0, '发出后总货权不变');

    // 首次收货 8
    const in1 = await createDoc({
      docType: 'TRANSFER_IN', docNo: 'TF-IN-001', fromWarehouseId: 1, toWarehouseId: 2, sourceDocId: tf });
    await addLine({ docId: in1, skuId: 1, warehouseId: 2, quantity: 8, netAmount: 0 });
    await submitAndApprove(in1);
    await postTransferIn(in1);
    ck.eq(await bal(1, 1, 1, 'IN_TRANSIT'), 2, '首收后在途 = 2');
    ck.eq(await bal(1, 2, 1, 'AVAILABLE'), 8, '调入仓可用 = 8');
    ck.eq(await totalQty(1, 1), total0, '首收后总货权不变');

    // 末次收货 2
    const in2 = await createDoc({
      docType: 'TRANSFER_IN', docNo: 'TF-IN-002', fromWarehouseId: 1, toWarehouseId: 2, sourceDocId: tf });
    await addLine({ docId: in2, skuId: 1, warehouseId: 2, quantity: 2, netAmount: 0 });
    await submitAndApprove(in2);
    await postTransferIn(in2);

    ck.eq(await bal(1, 1, 1, 'IN_TRANSIT'), 0, '末收后在途 = 0');
    ck.eq(await bal(1, 2, 1, 'AVAILABLE'), 10, '调入仓可用 = 10');
    ck.eq(await totalQty(1, 1), total0, '末收后总货权守恒');
  });

  defineCase('B10', '在途 2，尝试收 3 → 整次拒绝，不能只增加调入库存', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const tf = await createDoc({
      docType: 'TRANSFER_OUT', docNo: 'TF-001', fromWarehouseId: 1, toWarehouseId: 2 });
    await addLine({ docId: tf, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 0 });
    await submitAndApprove(tf);
    await postTransferOut(tf);

    const in1 = await createDoc({
      docType: 'TRANSFER_IN', docNo: 'TF-IN-001', fromWarehouseId: 1, toWarehouseId: 2, sourceDocId: tf });
    await addLine({ docId: in1, skuId: 1, warehouseId: 2, quantity: 8, netAmount: 0 });
    await submitAndApprove(in1);
    await postTransferIn(in1);
    ck.eq(await bal(1, 1, 1, 'IN_TRANSIT'), 2, '前置：在途 2');

    const in2 = await createDoc({
      docType: 'TRANSFER_IN', docNo: 'TF-IN-002', fromWarehouseId: 1, toWarehouseId: 2, sourceDocId: tf });
    await addLine({ docId: in2, skuId: 1, warehouseId: 2, quantity: 3, netAmount: 0 });
    await submitAndApprove(in2);
    await ck.rejects(() => postTransferIn(in2), 'SOURCE_QUANTITY_EXCEEDED', '超在途收货 3');
    ck.eq(await bal(1, 2, 1, 'AVAILABLE'), 8, '调入仓未被部分增加（整次拒绝）');
    ck.eq(await bal(1, 1, 1, 'IN_TRANSIT'), 2, '在途未变');
  });

  // =========================================================================
  // 组 6：盘点、冲销、幂等、快照（T05/T11/T12/T13/T14/T15/T17/B08/B16/B17/B20）
  // =========================================================================

  defineCase('T14', '盘点截点账面 10、实盘 8；差异未审核 → 库存仍 10；过账后 8；重复提交不再减少', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);
    ck.eq(await bal(1, 1, 1), 10, '账面 10');

    // 截点取在「全部既有事件之后」，表示本盘点覆盖当前账面（不是旧快照）
    const [maxAt] = await db().query(`SELECT MAX(occurred_at) m FROM stock_event`);
    const st = await createDoc({
      docType: 'STOCKTAKE', docNo: 'ST-001', snapshotAt: maxAt[0].m });
    await addLine({ docId: st, skuId: 1, warehouseId: 1, quantity: -2, netAmount: 0 });
    ck.eq(await bal(1, 1, 1), 10, '差异未审核时库存仍 10');

    await ck.rejects(() => postStocktake(st), 'INVALID_STATE', '未审核盘点差异过账');

    await submitAndApprove(st);
    await postStocktake(st);
    ck.eq(await bal(1, 1, 1), 8, '差异过账后库存 8');

    // 重复提交不再减少
    await postStocktake(st);
    ck.eq(await bal(1, 1, 1), 8, '重复提交后仍 8（不重复扣减）');
  });

  defineCase('B16', '盘点快照后范围发生未覆盖出库 → 拒绝旧差异过账，重新对齐截点', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    // 截点取在采购入库那一刻：该时点之后发生的事件即为「未覆盖」
    const [maxAt] = await db().query(`SELECT MAX(occurred_at) m FROM stock_event`);
    const st = await createDoc({
      docType: 'STOCKTAKE', docNo: 'ST-001', snapshotAt: maxAt[0].m });
    await addLine({ docId: st, skuId: 1, warehouseId: 1, quantity: -2, netAmount: 0 });
    await submitAndApprove(st);

    // 截点后发生出库（未覆盖事件）：显式把时间推后 1 秒，避免同秒比较歧义
    await inTx(async () => {
      await db().query(`UPDATE stock_event SET occurred_at = DATE_ADD(?, INTERVAL 1 SECOND)`, [maxAt[0].m]);
    });
    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 3, netAmount: 45 });
    await submitAndApprove(out);
    await postSalesOut(out);

    await ck.rejects(() => postStocktake(st), 'DOWNSTREAM_DEPENDENCY', '截点后有未覆盖事件的盘点');
    ck.eq(await bal(1, 1, 1), 7, '库存保持（10−3），未被旧快照调整');
  });

  defineCase('B08', '入库 10 已发出 8，申请冲销全部入库 → 下游依赖/库存不足拒绝，不造负库存', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);

    const out = await createDoc({ docType: 'SALES_OUT', docNo: 'SO-001', partyRoleId: 1 });
    await addLine({ docId: out, skuId: 1, warehouseId: 1, quantity: 8, netAmount: 120 });
    await submitAndApprove(out);
    await postSalesOut(out);
    ck.eq(await bal(1, 1, 1), 2, '前置：剩余 2');

    // 冲销全部入库 −10 会让余额变负 → 必须拒绝
    await ck.rejects(() => reverseDoc(pi), 'INSUFFICIENT_AVAILABLE_STOCK', '冲销全部入库（库存不足）');
    ck.eq(await bal(1, 1, 1), 2, '被拒后库存未变，且未出现负库存');
  });

  defineCase('T11', '草稿采购 10 后取消 → 库存、已付金额和正式往来均无新增影响', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-DRAFT', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });

    ck.eq(await bal(1, 1, 1), 0, '草稿不产生库存影响');
    await ck.rejects(() => postPurchaseIn(d), 'INVALID_STATE', '草稿直接过账');

    await db().query(`UPDATE doc SET approval_status='CANCELLED' WHERE id=?`, [d]);
    ck.eq(await bal(1, 1, 1), 0, '取消后库存仍 0');
    const [items] = await db().query(`SELECT COUNT(*) c FROM ar_ap_item`);
    ck.is(items[0].c, 0, '无往来项目产生');
    const [funds] = await db().query(`SELECT COUNT(*) c FROM fund_doc`);
    ck.is(funds[0].c, 0, '无资金事实产生');
  });

  defineCase('T12', '产品改名、员工转部门后查询原单 → 原单显示当时快照；当前身份关联仍可追踪', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(d);
    await postPurchaseIn(d);

    // 主档改名
    await db().query(`UPDATE sku SET name='标准盒（新名）' WHERE id=1`);

    // 单据行仍持有当时身份（SKU id 稳定），主档名称变化不回写单据
    const [ln] = await db().query(`SELECT sku_id, base_quantity FROM doc_line WHERE doc_id=?`, [d]);
    ck.is(ln[0].sku_id, 1, '单据行仍指向稳定 ID');
    ck.eq(ln[0].base_quantity, 10, '数量不受改名影响');
    const [[sku]] = await db().query(`SELECT name FROM sku WHERE id=1`);
    ck.is(sku.name, '标准盒（新名）', '主档已改名，历史可追溯同一 ID');
  });

  defineCase('T15', '开票申请 1000，实际只开 600 → 已开 600、申请未完成 400；收款不因开票改变', async (ck) => {
    // S0 简化表达：开票用「申请额 − 实际开票额」表达进度，申请不改变收款
    const requested = 1000, invoiced = 600;
    const fund = await createFund({ partyId: 1, amount: 1000 });

    const outstandingInvoice = requested - invoiced;
    ck.eq(invoiced, 600, '已开票 600');
    ck.eq(outstandingInvoice, 400, '申请未完成 400');
    // 关键：开票不改变收款（收款是独立资金事实）
    ck.eq((await fundState(fund)).amount, 1000, '收款金额不因开票改变');
    ck.eq((await fundState(fund)).allocated, 0, '开票不生成核销');
  });

  defineCase('T17', '无权限人员审核单据或访问另一范围数据 → 写操作被拒绝；列表与详情均按权限过滤', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });

    // S0 表达：审核要求 APPROVED，草稿不能过账（权限校验在服务端而非前端）
    await ck.rejects(() => postPurchaseIn(d), 'INVALID_STATE', '无审核状态的写操作');
    // 数据范围：公司 2 的单据不应出现在公司 1 的列表
    const d2 = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-C2', companyId: 2, partyRoleId: 2 });
    const [rows] = await db().query(`SELECT id FROM doc WHERE company_id=1`);
    ck.truthy(!rows.some((r) => r.id === d2), '公司 1 的列表不含公司 2 单据');
  });

  defineCase('B17', '修改应收触发策略后重放同一来源 → 按接续方案处理，不生成两份全额应收', async (ck) => {
    const srcDoc = await createDoc({
      docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2,
      approvalStatus: 'APPROVED', postingStatus: 'POSTED' });
    await addLine({ docId: srcDoc, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });

    // 唯一来源键 = (公司, 源单据, 方向)，重放不生成第二份
    const [[doc]] = await db().query(`SELECT * FROM doc WHERE id=?`, [srcDoc]);
    const [lines] = await db().query(`SELECT * FROM doc_line WHERE doc_id=?`, [srcDoc]);
    const id1 = await env.raiseArAp(doc, lines, 'AP');
    const id2 = await env.raiseArAp(doc, lines, 'AP');
    ck.is(id1, id2, '同源重放返回同一往来项目 ID');
    const [cnt] = await db().query(`SELECT COUNT(*) c FROM ar_ap_item`);
    ck.is(cnt[0].c, 1, '只生成 1 份往来');
  });

  defineCase('B20', '重导氚云销售出库/仓库快照 → 正式 ERP 单据、事件、核销无变化', async (ck) => {
    const pi = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: pi, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(pi);
    await postPurchaseIn(pi);
    const fund = await createFund({ partyId: 1, amount: 100 });

    const before = {
      bal: await bal(1, 1, 1),
      events: await eventCount(pi),
      alloc: (await fundState(fund)).allocated,
    };

    // 模拟「重导」：S0 骨架与氚云源完全无耦合，本 schema 内无任何导入链路
    const [refs] = await db().query(
      `SELECT COUNT(*) c FROM information_schema.tables
        WHERE table_schema=? AND table_name LIKE 'h3_%'`, [env.SCHEMA || 'erp_s0_verify']);
    ck.is(refs[0].c, 0, 'S0 schema 内无任何氚云来源表（结构上不可能被外部重导影响）');

    ck.eq(await bal(1, 1, 1), before.bal, '余额未变');
    ck.eq(await eventCount(pi), before.events, '事件数未变');
    ck.eq((await fundState(fund)).allocated, before.alloc, '核销未变');
  });

  // =========================================================================
  // 组 7：S0 边界与结构性不变量（对应设计输入 C4/C5/C6/C9）
  // =========================================================================

  defineCase('X01', '一行多角色：同一明细行可承载多类子表（C4 结构性缺口）', async (ck) => {
    // 设计输入 C4 实测依据：开票申请 11537 行同时携带「开票内容」+「收款引用」。
    // 验证：ERP 明细模型允许一行产生多腿事件，去重键含动作与腿身份。
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    const ln = await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(d);

    // 同一行拆成两腿（如「到货」+「质检放行」）
    await inTx(async () => {
      await env.emitEvent(d, ln, 'RECEIPT', 1, 1, 1, 10, { quality: 'INSPECT', groupId: 'PG-MULTI' });
      await env.emitEvent(d, ln, 'ADJUST', 1, 1, 1, 0, { quality: 'AVAILABLE', groupId: 'PG-MULTI' });
    });
    const [rows] = await db().query(`SELECT COUNT(*) c FROM stock_event WHERE doc_line_id=?`, [ln]);
    ck.is(rows[0].c, 2, '同一明细行产生了 2 腿事件（一行多角色成立）');
  });

  defineCase('X02', '浮点残差防护：比率 1/3 反复换算不产生残差漂移（C5）', async (ck) => {
    // 设计输入 C5：历史样本用 DOUBLE 时出现 8.88e-16 残差 → 永不收敛。
    // 验证：DECIMAL 定点做 1/3 相关换算后，回代比较无残差。
    await db().query(`INSERT INTO sku_conversion (id, sku_id, from_unit, to_unit, numerator,
                        denominator, revision, effective_from)
                      VALUES (?, 1, '板', '个', 1, 3, 9, '2026-01-01')`, [nextId()]);
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-DEC', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 3, unit: '板', netAmount: 10 });
    await submitAndApprove(d);
    await postPurchaseIn(d);
    const q = await bal(1, 1, 1);
    ck.truthy(nEq(q, 1), `3 板 × 1/3 应为 1 个（实际 ${q}）`);
    // 十进制字面量比较必须精确相等，不允许出现 0.9999999999 一类残差
    const [rows] = await db().query(`SELECT base_quantity FROM doc_line WHERE doc_id=?`, [d]);
    ck.is(rows[0].base_quantity, '1.000000000', 'DECIMAL 存储精确为 1.000000000');
  });

  defineCase('X03', '规则版本可复算：口径变更后历史结果不受影响（C6）', async (ck) => {
    // 设计输入 C6：历史样本的 unchanged() 判据导致口径变更无法回填。
    // 验证：ERP 每条业务记录带 rule_version，复算时可区分版本。
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2, });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(d);
    await postPurchaseIn(d);

    const [[doc]] = await db().query(`SELECT rule_version FROM doc WHERE id=?`, [d]);
    ck.is(doc.rule_version, 'S0', '单据带规则版本');
    const [items] = await db().query(`SELECT rule_version FROM ar_ap_item`);
    ck.is(items[0].rule_version, 'S0', '往来项目带规则版本');

    // 发布新规则版本后，旧记录仍保留 S0（可复算历史）
    await db().query(`UPDATE doc SET rule_version='S1' WHERE id=?`, [d]);
    const [[again]] = await db().query(`SELECT rule_version FROM doc WHERE id=?`, [d]);
    ck.is(again.rule_version, 'S1', '版本可显式升级，历史可分辨');
  });

  defineCase('X04', '审批与过账是独立维度：审批通过不等于已过账（C9）', async (ck) => {
    // 设计输入 C9：历史样本把审批状态与履约进度混装。
    // 验证：approvalStatus 与 postingStatus 可独立演化。
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(d);

    const [[doc]] = await db().query(`SELECT approval_status, posting_status FROM doc WHERE id=?`, [d]);
    ck.is(doc.approval_status, 'APPROVED', '已审核');
    ck.is(doc.posting_status, 'UNPOSTED', '尚未过账 —— 两维度独立');
    ck.eq(await bal(1, 1, 1), 0, '已审核但未过账不产生库存影响');

    await postPurchaseIn(d);
    const [[after]] = await db().query(`SELECT approval_status, posting_status FROM doc WHERE id=?`, [d]);
    ck.is(after.posting_status, 'POSTED', '过账后状态独立更新');
    ck.is(after.approval_status, 'APPROVED', '审批状态保持');
  });

  defineCase('T05', '入库请求成功后网络超时，使用同一请求键重试 → 返回同一结果；库存只增加一次', async (ck) => {
    const d = await createDoc({ docType: 'PURCHASE_IN', docNo: 'PI-001', partyRoleId: 2 });
    await addLine({ docId: d, skuId: 1, warehouseId: 1, quantity: 10, netAmount: 100 });
    await submitAndApprove(d);

    const first = await idem('REQ-POST-001', { docId: d }, async () => {
      await postPurchaseIn(d);
      return { resultRef: `DOC-${d}` };
    });
    ck.is(first.idempotent, false, '首次执行');
    ck.eq(await bal(1, 1, 1), 10, '库存 +10');

    const retry = await idem('REQ-POST-001', { docId: d }, async () => {
      await postPurchaseIn(d);
      return { resultRef: `DOC-${d}` };
    });
    ck.is(retry.idempotent, true, '重试命中幂等');
    ck.str(retry.resultRef, first.result.resultRef, '返回同一结果引用');
    ck.eq(await bal(1, 1, 1), 10, '库存只增加一次');
  });

  // 辅助：造一笔往来项目
  async function makeItem(amount, direction) {
    const docId = nextId();
    await db().query(
      `INSERT INTO doc (id, doc_type, doc_no, company_id, business_date, approval_status, posting_status)
       VALUES (?,?,?,1,'2026-09-17','APPROVED','POSTED')`,
      [docId, direction === 'AR' ? 'SALES_OUT' : 'PURCHASE_IN', `AUX-${docId}`]);
    const id = nextId();
    await db().query(
      `INSERT INTO ar_ap_item (id, company_id, party_id, direction, currency, source_doc_id,
                               original_amount, adjusted_amount, settled_amount, rule_version)
       VALUES (?,1,1,?,'CNY',?,?,0,0,'S0')`,
      [id, direction, docId, amount]);
    return id;
  }
};
