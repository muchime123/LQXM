package com.liqiang.archive.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 氚云导入数据源「统一种类契约」（阶段 3：管线合并的一部分）。
 *
 * <p>单一事实源：批次表 {@code h3_import_batches.data_type}、前端路由 key、
 * 表头识别契约、台账归属、能力位全部收口到此处，A 域（四类业务事实）与
 * B 域（产品信息/仓库明细/经销商台账）各自的上传管线共用本描述符，避免两份
 * dbType/文案漂移。
 *
 * <p>种类如下（2026-09-17 扩到九类）：
 * <ul>
 *   <li>A 域业务事实：{@link #PRODUCTION} / {@link #OUTBOUND} /
 *       {@link #RECEIPT} / {@link #SETTLEMENT}（写入 sales_outbounds /
 *       production_orders / receipts，见 H3FactImportService）；{@link #SUPPLIER} /
 *       {@link #PURCHASE_IN}（写入 h3_supplier_catalog / purchase_receipts，
 *       供应商为主数据但复用 A 域暂存管线，见各常量注释）；</li>
 *   <li>B 域台账：{@link #CATALOG} / {@link #STOCK} / {@link #DEALER}（写入
 *       h3_product_catalog / h3_warehouse_stock / h3_dealer_catalog，见
 *       ProductInventoryLedgerImportService）。</li>
 * </ul>
 *
 * <p>能力位说明：本描述符只承载「元数据 + 能力声明」；diff/write/orchestrate 等
 * 差异逻辑当前仍由两域 Service 在 confirm 阶段各自实现（阶段 3 保证行为等价，
 * confirm 语义不动）。后续按确认契约拆分 Handler 时，各 Handler 依据本描述符
 * 的 domain / capability 落地，避免再次出现按域分叉的重复骨架。
 */
public enum ImportKindDescriptor {

    // ==================== A 域：业务事实（氚云销售/生产/财务表单） ====================

    /** 生产工单：业务键头「生产工单流水号」，写 production_orders（含零部件制程）。 */
    PRODUCTION("production", "PRODUCTION", "生产工单流水号", "生产工单", KindDomain.BUSINESS_FACT,
            true, false),

    /** 销售出库：业务键头「销售出库编号」，写 sales_outbounds（含明细/付款快照），confirm 后触发利润编排。 */
    OUTBOUND("outbound", "OUTBOUND", "销售出库编号", "销售出库", KindDomain.BUSINESS_FACT,
            true, true),

    /** 收款记录：业务键头「流水号」，写 receipts（receipt_type=SINGLE_OUTBOUND）。 */
    RECEIPT("receipt", "RECEIPT", "流水号", "收款记录", KindDomain.BUSINESS_FACT,
            true, false),

    /** 多订单结清：业务键头「流水号」，写 receipts（receipt_type=MULTI_OUTBOUND）。 */
    SETTLEMENT("settlement", "SETTLEMENT", "流水号", "多订单结清", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 供应商信息：业务键头「供应商名称」（仅作表头识别），写 h3_supplier_catalog。
     *
     * <p>主数据但走 A 域管线（暂存/预览/confirm 与业务事实共用，幂等键=数据ID UUID），
     * 不归入 {@link KindDomain#LEDGER}——LEDGER 域当前绑定 B 域固定列位管线与统一台账读端点，
     * 两者都不适用供应商（无编号、单表、按 UUID 幂等）。confirm 后无编排（orchestrates=false）。
     */
    SUPPLIER("supplier", "SUPPLIER", "供应商名称", "供应商信息", KindDomain.BUSINESS_FACT,
            false, false),

    /**
     * 采购入库：业务键头「采购入库编号」，写 purchase_receipts + purchase_receipt_items。
     *
     * <p>LQ 决策（2026-09-17）：只落事实层 + 采购价权威口径，不动库存余额/流水
     * （余额仍由台账快照合并驱动，L3 冻结），故 orchestrates=false 不触发回款/利润编排。
     */
    PURCHASE_IN("purchase", "PURCHASE_IN", "采购入库编号", "采购入库", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 其他入库：业务键头「流水号」，写 h3_other_stock_movements(direction='IN') + 明细。
     *
     * <p>⚠️ 表头陷阱：本表明细子表前缀在氚云导出件里被写成「其他出库明细.」（表单复制串名），
     * 与「其他出库」的前缀完全相同 → 类型识别只能靠主表字段「选择入库仓库」，
     * 不能靠明细前缀（见 H3FactImportService#matchesTemplate）。
     *
     * <p>内容以补录/调整库存为主（备注里常见「调整库存」），即盘点业务的替代承接。
     * LQ 决策（2026-09-17）：与采购入库同口径，只落事实层，不动库存余额/流水（L3 冻结）。
     */
    OTHER_IN("otherIn", "OTHER_IN", "流水号", "其他入库", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 其他出库：业务键头「其他出库编号」，写 h3_other_stock_movements(direction='OUT') + 明细。
     *
     * <p>内容以设备/模具/片膜（订做类）为主，主表带「合计数量」「合计金额」与业务员，
     * 明细带「采购总价」——与「其他入库」的差异列按方向分别取值。
     */
    OTHER_OUT("otherOut", "OTHER_OUT", "其他出库编号", "其他出库", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 库存调拨：业务键头「流水号」，写 h3_inventory_transfers + h3_inventory_transfer_items。
     *
     * <p>与其他入/出库的差异：一单**两个仓库**（调出/调入）+ 两个仓管员，故不合并方向位。
     *
     * <p>⚠️ 表头陷阱：主表有两个同名「仓管员」列（第 4 列=调出、第 6 列=调入），通用表头解析
     * 用 putIfAbsent（首命中优先）会吞掉调入仓管员 → 解析层对 TRANSFER 把第二列重命名为
     * 「仓管员(调入)」（见 H3FactImportService#locateHeader）。明细前缀「调拨明细.」正常无串名。
     *
     * <p>状态口径：已生效 216 / 草稿 1 / 已取消 1（实测）。按 §3 统一底线，草稿与已取消
     * **标记 data_status 保留**、不进正式业务口径；调出仓=调入仓 → 告警。
     *
     * <p>LQ 决策（2026-09-17）：只落事实层，不动库存余额/流水（L3 冻结），orchestrates=false。
     */
    TRANSFER("transfer", "TRANSFER", "流水号", "库存调拨", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 开票资料（2026-09-17 P3）：单表无子表、无业务单号（只有数据ID UUID）。
     * keyHeader=数据ID：44 组同名户名是合法事实（同名不同税号），绝不能按户名分组。
     * 未关联 customers 不是错误，只在台账列表标记（LQ 决策：不进 REVIEW）。
     */
    INVOICE_PROFILE("invoiceProfile", "INVOICE_PROFILE", "数据ID(不可修改)", "开票资料", KindDomain.BUSINESS_FACT,
            false, false),

    /**
     * 开票申请（2026-09-17 P3）：主表 + 开票内容 + 收款引用三写；两子表行可同行携带（实测 11537 行）。
     * ⚠️ keyHeader=数据ID 而非流水号：实测文件尾部 18 单整行缺流水号，按流水号分组会串进上一单。
     * 收款情况只建引用（receipt_id 恒 NULL），不写 receipts——收款权威=销售出库（§2.7 拍板）。
     */
    INVOICE_REQUEST("invoiceRequest", "INVOICE_REQUEST", "数据ID(不可修改)", "开票申请", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 生产报工（2026-09-17 第 15 类，G3 取证）：单表无子表、无业务单号（只有数据ID UUID）。
     * 粒度 = 生产工单 × 零部件制程（工序完工报工，非材料领料——生产任务单实测「是否使用零部件库存」
     * 全否、库存调用全 0，氚云从未启用领料；零部件流水号全表仅 10 值 = 10 个固定工序模板）。
     * 关联键 = 生产工单编号（SCGD…）→ production_orders（实测 4 997/5 691 = 87.8% 工单有报工）。
     * ⚠️ 同名列 ×2：「报工人」F0000005（主）/F0000038（辅助）→ 解析层把第二列改名「报工人(辅助)」；
     *   「生产零部件」F0000039/F0000002 实测同值，只落 F0000039、另一列仅作告警对比。
     * ⚠️ 数据状态全「已生效」：报工即时生效无审批流；氚云无撤销原语（缺口 C7）→ ERP 侧需报工冲销。
     */
    PRODUCTION_REPORT("productionReport", "PRODUCTION_REPORT", "数据ID(不可修改)", "生产报工", KindDomain.BUSINESS_FACT,
            false, false),

    /**
     * 进项发票（2026-09-18 第 16 类，G2 实开口径权威源·进项侧）：税务平台全量导出单表，2 382 行。
     * 非氚云导出件（无数据ID列）→ 幂等键 = 数电发票号码（实测唯一）。keyHeader 即数电发票号码。
     * LQ 拍板（§9.3）：实开口径以发票明细为权威，氚云开票申请降为申请侧意向。
     * 同文件「销项发票」sheet 表头不同（matchesTemplate 以收款金额区分）→ 各自 stage 一次。
     */
    INVOICE_INPUT("invoiceInput", "INVOICE_INPUT", "数电发票号码", "进项发票", KindDomain.BUSINESS_FACT,
            false, false),

    /**
     * 销项发票（2026-09-18 第 16 类，G2 实开口径权威源·销项侧）：4 174 主行 + 512 收款子行。
     * ⚠️ sheet 实为主子结构：无票号行=多次收款登记（挂靠前一行发票），childCount 按缺数电发票号码判定。
     * ⚠️ 同名列 ×2：「备注」第 17 列=人工收款备注 / 第 24 列=平台红冲备注（0 行同时非空）→
     *   解析层把第二列改名「备注(平台)」（putHeader，仓管员/报工人同款陷阱）。
     * 收款字段只作校验源（收款权威=销售出库，§9.3），不写 receipts、不触发编排。
     */
    INVOICE_SALES("invoiceSales", "INVOICE_SALES", "数电发票号码", "销项发票", KindDomain.BUSINESS_FACT,
            true, false),

    /**
     * 供应商应付（2026-09-18 第 17 类，G1 应付侧权威补缺）：《2026年各供应商明细.xlsx》55+ 张
     * 供应商 sheet（每张 12 个月度行：应付货款/结算货款/折让/付款日期/方式/金额/剩余货款）。
     * ⭐ 首个 multiSheet kind：管线聚合所有匹配模板的 sheet，sheet 名注入「供应商」列、
     *   组键改为 sheet名#月份；汇总/封口机/异构 sheet 由 matchesTemplate（8 列模板）排除。
     * 幂等键 = sheet名#月份（人工月度台账无业务单号）。⚠️ 尾部 RK/费用明细行不入库：
     *   RK 已由 purchase_receipts 覆盖（1064/1064 命中），费用行是人工脏结构（见口径手册 §9）。
     */
    SUPPLIER_PAYABLE("supplierPayable", "SUPPLIER_PAYABLE", "月份", "供应商应付", KindDomain.BUSINESS_FACT,
            false, false, true),

    /**
     * 流水日记账（2026-09-18 第 18 类，G5 资金校验源）：《2026年流水日记账.xlsx》35 张账户
     * sheet（12 列基线 + 3 张外币 sheet 多汇率列）。multiSheet kind：sheet 名 = 账户名。
     * 无任何业务单号/数据ID → 幂等键 = 账户#日期#收入#支出#结存#n（n=同键序号，
     * 实测同键最多 3 行）。期初行（摘要=上期结存）组键 = 账户#期初 → 写账户维度表期初余额；
     * 其余无日期无收支的空白填充行不入组（否则恒判 CREATE 永不收敛）。
     * 日期三分支：datetime 显示串（须剥 POI 带出的引号，如 2026"年"1"月"1"日"）/ Excel 序列号。
     * 供应商/客户/摘要/月份/报销人/是否开票均为人工自由文本，只存 _raw 原文（校验源纪律）。
     */
    CASHFLOW_JOURNAL("cashflowJournal", "CASHFLOW_JOURNAL", "日期", "流水日记账", KindDomain.BUSINESS_FACT,
            false, false, true),

    /**
     * 收银通收单流水（2026-09-18 第 19 类，G5 资金校验源·微信收单侧）：
     * 同文件「利强收银通」sheet 单表 6088 行（交易完成时间/状态/金额/扣费/openid/摘要/业务员）。
     * 幂等键 = 时间#金额#openid（实测零重复）。401 行缺摘要/业务员（openid 未在人工字典 Sheet2
     * 中）→ 照常入库 + 告警计数。摘要为「折扣率+客户名+备注」人工文本，不解读不清洗。
     */
    CASHIER_LOG("cashierLog", "CASHIER_LOG", "交易完成时间", "收银通流水", KindDomain.BUSINESS_FACT,
            false, false),

    // ==================== B 域：历史台账（库存类产品/仓库档案） ====================

    /** 产品信息台账：按产品编码逐行比对增量写入 h3_product_catalog，无表头识别（列位固定）。 */
    CATALOG("catalog", "PRODUCT_INFO", null, "产品信息", KindDomain.LEDGER,
            false, false),

    /** 仓库明细台账：按「仓库+产品编码」逐行比对增量写入 h3_warehouse_stock，无表头识别（列位固定）。 */
    STOCK("stock", "WAREHOUSE_STOCK", null, "仓库明细", KindDomain.LEDGER,
            false, false),

    /**
     * 经销商信息台账：按「经销商编号」逐行比对增量写入 h3_dealer_catalog，无表头识别（列位固定）。
     *
     * <p>补的是此前的空白：氚云「经销商信息」表单没有任何落点，销售出库上只有自由文本
     * {@code sales_outbounds.dealer_name}（97 种写法、无编号）。导入本台账后，经销商拥有
     * 权威主档与业务键（JXS 编号），可按实体而非字符串归属订单。
     */
    DEALER("dealer", "DEALER", null, "经销商信息", KindDomain.LEDGER,
            false, false);

    /** 台账归属域：A=业务事实（审批后参与业务编排），B=只读历史台账。 */
    public enum KindDomain {
        /** 销售/生产/财务类业务事实：确认写业务表，可能与利润/佣金等编排联动。 */
        BUSINESS_FACT,
        /** 库存档案类历史台账：只读展示，不与业务编排联动。 */
        LEDGER
    }

    private final String route;
    private final String dbType;
    private final String keyHeader;
    private final String displayName;
    private final KindDomain domain;
    /** 是否存在 Excel 行内子表明细（A 域收款/出库等一行含多条明细行）。 */
    private final boolean detailSubTable;
    /** confirm 提交后是否需要触发编排刷新（目前仅销售出库）。 */
    private final boolean orchestrates;
    /** 是否多 sheet 聚合（同一文件所有匹配模板的 sheet 都导入，sheet 名注入「供应商」列）。 */
    private final boolean multiSheet;

    ImportKindDescriptor(String route, String dbType, String keyHeader, String displayName,
                         KindDomain domain, boolean detailSubTable, boolean orchestrates) {
        this(route, dbType, keyHeader, displayName, domain, detailSubTable, orchestrates, false);
    }

    ImportKindDescriptor(String route, String dbType, String keyHeader, String displayName,
                         KindDomain domain, boolean detailSubTable, boolean orchestrates, boolean multiSheet) {
        this.route = route;
        this.dbType = dbType;
        this.keyHeader = keyHeader;
        this.displayName = displayName;
        this.domain = domain;
        this.detailSubTable = detailSubTable;
        this.orchestrates = orchestrates;
        this.multiSheet = multiSheet;
    }

    /** 前端路由 key / 控制器 kind 参数（小写），如 outbound / catalog。 */
    public String route() { return route; }

    /** 批次表 data_type 原值，如 OUTBOUND / WAREHOUSE_STOCK。 */
    public String dbType() { return dbType; }

    /**
     * 表头识别契约：A 域为氚云表单中作为业务主键的列名（可空含义见实现），
     * B 域固定列位、无表头识别，返回 null。
     */
    public String keyHeader() { return keyHeader; }

    /** 中文展示名（导入中心下拉/表格用）。 */
    public String displayName() { return displayName; }

    /** 归属域：业务事实或只读台账。 */
    public KindDomain domain() { return domain; }

    /** 是否有行内子表明细（决定暂存 rows_json 是否承载明细行）。 */
    public boolean hasDetailSubTable() { return detailSubTable; }

    /** confirm 后是否触发编排刷新（含自动跟踪）。 */
    public boolean orchestrates() { return orchestrates; }

    /** 是否多 sheet 聚合 kind（所有匹配模板 sheet 全导入 + sheet 名注入）。 */
    public boolean multiSheet() { return multiSheet; }

    /** 是否 B 域台账（ledger）。 */
    public boolean isLedger() { return domain == KindDomain.LEDGER; }

    /** 是否 A 域业务事实。 */
    public boolean isBusinessFact() { return domain == KindDomain.BUSINESS_FACT; }

    /**
     * 按路由 key 查找；未知返回 null（调用方自行组织错误文案以保持原有消息）。
     */
    public static ImportKindDescriptor findRoute(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (ImportKindDescriptor kind : values()) {
            // route 原值可能含驼峰（otherIn/otherOut），比较前两侧统一小写。
            // 旧 route 全小写，此修正对它们零影响（2026-09-17 新增 OTHER_IN/OTHER_OUT 时发现）。
            if (kind.route.toLowerCase(Locale.ROOT).equals(v)) return kind;
        }
        return null;
    }

    /**
     * 按批次表 data_type 反查；未知返回 null。
     */
    public static ImportKindDescriptor findDbType(String dbType) {
        if (dbType == null) return null;
        for (ImportKindDescriptor kind : values()) {
            if (kind.dbType.equals(dbType)) return kind;
        }
        return null;
    }

    /** B 域台账种类（产品/仓库/经销商），用于统一读端点路由与批次列表归一判断。 */
    public static List<ImportKindDescriptor> ledgerKinds() {
        List<ImportKindDescriptor> list = new ArrayList<>();
        for (ImportKindDescriptor kind : values()) {
            if (kind.isLedger()) list.add(kind);
        }
        return list;
    }

    /** 判断 data_type 是否为 B 域台账（否则按 A 域业务事实处理）。 */
    public static boolean isLedgerDbType(String dbType) {
        ImportKindDescriptor kind = findDbType(dbType);
        return kind != null && kind.isLedger();
    }
}
