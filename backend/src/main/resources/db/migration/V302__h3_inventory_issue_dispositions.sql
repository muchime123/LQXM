-- ============================================================================
-- V302：库存快照「待处理异常」处置结论表
--
-- 背景：产品库存页的异常口径条只做展示（缺产品 / 缺价），没有任何处置落点——
--       用户看完 331 行不知道能做什么，处理完也没有地方沉淀「这行我看过了」，
--       而产品信息台账是增量 upsert（重导不会清掉历史行），下次重导后同样的行
--       会再次冒出来。本表把「人对这一行的判断」持久化，使异常数能真正收敛。
--
-- 业务键 = (warehouse_name, product_code, unit)，与 h3_warehouse_stock 的
--   UNIQUE KEY uk_h3ws_wh_code_unit(warehouse, product_code, unit) 完全一致：
--   · 必须含 unit —— V295 后同仓同编号可存在多单位行，不含单位会互相覆盖；
--   · warehouse_name 用氚云原始仓库名（h3_warehouse_stock.warehouse），不依赖
--     h3_warehouse_name_mappings 的存在与内容；warehouse_id 只是冗余便于页面 join；
--   · unit 规范为 ''（源表 unit 可为 NULL，而唯一索引允许多个 NULL，会把同一行
--     重复登记成多条）。
--
-- 语义边界：本表只记录处置结论，**不修改任何台账数据**（尤其不覆写
--   h3_product_catalog.purchase_price）——氚云仍是采购价的唯一来源，补价一律
--   回氚云改数后重导产品信息台账，避免本地改动被下一次重导静默覆盖。
--
-- 状态取值：
--   PENDING_H3      氚云待补  —— 已确认这是真缺，待回氚云补产品/补采购价后重导
--   DATA_RESIDUE    历史残留  —— 氚云产品信息表已无此产品（打样/定做/设备类用完即移除），
--                                仓库明细保留历史行，无需处理
--   NO_PRICE_NEEDED 无需计价  —— 设备/配件/模具/贴体机类不按库存金额计价，接受价格为空
--
-- 异常类型(issue_type)：MISSING_PRODUCT 缺产品信息 / MISSING_PRICE 缺采购价。
--   两者互斥（前者是产品信息台账无此编号，后者是编号能对上但价为空），故不进业务键；
--   仅作留痕，便于日后若某行成因发生变化仍能看出当初判定时的形态。
-- ============================================================================

CREATE TABLE IF NOT EXISTS h3_inventory_issue_dispositions (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  warehouse_name VARCHAR(100) NOT NULL COMMENT '氚云原始仓库名（h3_warehouse_stock.warehouse），业务键之一',
  warehouse_id   BIGINT       NULL     COMMENT '映射后的仓库 id（映射不上时为 NULL），仅用于页面 join',
  product_code   VARCHAR(200) NOT NULL COMMENT '产品编号（TRIM 后），业务键之一',
  unit           VARCHAR(100) NOT NULL DEFAULT '' COMMENT '单位（源表 NULL 规范为空串），业务键之一',
  issue_type     VARCHAR(20)  NOT NULL COMMENT '判处置时的异常类型：MISSING_PRODUCT 缺产品 / MISSING_PRICE 缺价',
  status         VARCHAR(30)  NOT NULL COMMENT '处置结论：PENDING_H3 氚云待补 / DATA_RESIDUE 历史残留 / NO_PRICE_NEEDED 无需计价',
  remark         VARCHAR(500) NULL     COMMENT '备注',
  disposed_by    VARCHAR(50)  NULL     COMMENT '处置人',
  disposed_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '处置时间',
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次更新',
  PRIMARY KEY (id),
  UNIQUE KEY uk_h3iid_biz (warehouse_name, product_code, unit),
  KEY idx_h3iid_status (status),
  KEY idx_h3iid_code (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存快照异常处置结论（不改台账，仅记录人的判断）';
