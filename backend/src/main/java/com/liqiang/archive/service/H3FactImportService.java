package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.H3FactImportDtos.AffectedScope;
import com.liqiang.archive.dto.H3FactImportDtos.BatchProgress;
import com.liqiang.archive.dto.H3FactImportDtos.Preview;
import com.liqiang.archive.dto.H3FactImportDtos.Result;
import com.liqiang.archive.dto.H3FactImportDtos.RowPreview;
import com.liqiang.archive.dto.H3FactImportDtos.FieldChange;
import com.liqiang.archive.dto.H3FactImportDtos.StageAccepted;
import com.liqiang.archive.dto.H3FactImportDtos.StagingPreview;
import com.liqiang.archive.util.ChinaAddressParser;
import com.liqiang.archive.util.H3SnapshotKey;
import com.liqiang.archive.util.SupplierNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class H3FactImportService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(H3FactImportService.class);

    static {
        // 氚云全字段导出的 Excel（尤其是销售出库/生产工单）解压缩后内部 XML 可能超过 POI 默认的 100MB 数组分配上限
        // 此处全局放宽 POI 的反 ZipBomb 保护阈值：单记录最大 1GB、压缩比最低 0.001（即允许 1000 倍压缩）
        IOUtils.setByteArrayMaxOverride(1_000_000_000);
        ZipSecureFile.setMinInflateRatio(0.001);
    }

/** 业务事实种类：dbType / keyHeader 等元数据委托给统一数据源描述符 {@link ImportKindDescriptor}（单一事实源）。 */
public enum FactType {
PRODUCTION(ImportKindDescriptor.PRODUCTION),
OUTBOUND(ImportKindDescriptor.OUTBOUND),
RECEIPT(ImportKindDescriptor.RECEIPT),
SETTLEMENT(ImportKindDescriptor.SETTLEMENT),
SUPPLIER(ImportKindDescriptor.SUPPLIER),
PURCHASE_IN(ImportKindDescriptor.PURCHASE_IN),
OTHER_IN(ImportKindDescriptor.OTHER_IN),
OTHER_OUT(ImportKindDescriptor.OTHER_OUT),
TRANSFER(ImportKindDescriptor.TRANSFER),
INVOICE_PROFILE(ImportKindDescriptor.INVOICE_PROFILE),
INVOICE_REQUEST(ImportKindDescriptor.INVOICE_REQUEST),
PRODUCTION_REPORT(ImportKindDescriptor.PRODUCTION_REPORT),
INVOICE_INPUT(ImportKindDescriptor.INVOICE_INPUT),
INVOICE_SALES(ImportKindDescriptor.INVOICE_SALES),
SUPPLIER_PAYABLE(ImportKindDescriptor.SUPPLIER_PAYABLE),
CASHFLOW_JOURNAL(ImportKindDescriptor.CASHFLOW_JOURNAL),
CASHIER_LOG(ImportKindDescriptor.CASHIER_LOG);
final String dbType;
final String keyHeader;
final boolean multiSheet;
FactType(ImportKindDescriptor kind) {
    this.dbType = kind.dbType(); this.keyHeader = kind.keyHeader(); this.multiSheet = kind.multiSheet();
}
public static FactType from(String value) {
// 两种入参形态：A 域控制器传路由 key（purchase），批次表回读传 data_type（PURCHASE_IN）。
// 旧类型 route 与 dbType 仅大小写不同故侥幸兼容；新类型（PURCHASE_IN）必须显式双查。
String v = value == null ? "" : value.trim();
ImportKindDescriptor kind = ImportKindDescriptor.findRoute(v);
if (kind == null) kind = ImportKindDescriptor.findDbType(v);
if (kind == null || !kind.isBusinessFact()) throw new IllegalArgumentException("不支持的数据类型: " + value);
return switch (kind) {
case PRODUCTION -> PRODUCTION;
case OUTBOUND -> OUTBOUND;
case RECEIPT -> RECEIPT;
case SETTLEMENT -> SETTLEMENT;
case SUPPLIER -> SUPPLIER;
case PURCHASE_IN -> PURCHASE_IN;
case OTHER_IN -> OTHER_IN;
case OTHER_OUT -> OTHER_OUT;
case TRANSFER -> TRANSFER;
case INVOICE_PROFILE -> INVOICE_PROFILE;
case INVOICE_REQUEST -> INVOICE_REQUEST;
case PRODUCTION_REPORT -> PRODUCTION_REPORT;
case INVOICE_INPUT -> INVOICE_INPUT;
case INVOICE_SALES -> INVOICE_SALES;
case SUPPLIER_PAYABLE -> SUPPLIER_PAYABLE;
case CASHFLOW_JOURNAL -> CASHFLOW_JOURNAL;
case CASHIER_LOG -> CASHIER_LOG;
default -> throw new IllegalArgumentException("不支持的数据类型: " + value);
};
}
}

private record Group(String key, String externalId, Map<String, String> main,
List<Map<String, String>> rows, String hash) {}

private record ExistingFact(Long id, String hash, Timestamp sourceUpdatedAt) {}

private final JdbcTemplate jdbc;
private final H3EmployeeIdentityService h3EmployeeIdentityService;
private final SourceIdentityService sourceIdentityService;
private final ObjectMapper objectMapper;
private final SalesOutboundSettlementService settlementService;
private final SalesOutboundProfitService outboundProfitService;
private final SalesOutboundNatureService outboundNatureService;
private final H3PostImportAsyncService postImportAsyncService;
private final BatchImportPipelineService pipeline;
private final DataFormatter formatter = new DataFormatter();

/**
 * 2026-08 恢复：氚云生产任务工单、收款记录、多订单结清重新开放导入。
 * 生产任务写入 production_orders（含零部件制程）；收款记录/多订单结清写入 receipts 主单。
 * 这些事实仅作「氚云原始数据台账」，不再与销售出库互相关联（无 receipt_allocations、无业务单链接）。
 */
private void rejectStoppedFactTypes(FactType factType) {
    // 全类型开放：OUTBOUND / PRODUCTION / RECEIPT / SETTLEMENT 均可确认写库
}

private List<Group> parse(FactType type, MultipartFile file) throws IOException {
String name = file.getOriginalFilename();
if (name == null || name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
    return parseXlsxStreaming(type, file);
}
try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
    // multiSheet（供应商应付）：所有匹配模板 sheet 聚合，sheet 名注入「供应商」列 + 组键前缀
    if (type.multiSheet) {
        List<Group> all = new ArrayList<>();
        boolean anyMatched = false;
        // sheet 名注入列：供应商应付=「供应商」（sheet名=供应商）；流水日记账=「账户」（sheet名=账户，
        // 覆盖源表账户列——宋文温州银行等 sheet 存在账户列错填，以 sheet 名为确定性来源）。
        String sheetCol = type == FactType.CASHFLOW_JOURNAL ? "账户" : "供应商";
        for (Sheet sheet : workbook) {
            Header header = locateHeader(sheet, type);
            if (header == null) continue;
            anyMatched = true;
            String sheetName = sheet.getSheetName();
            for (Group g0 : groupRows(sheet, header, type)) {
                Map<String, String> main2 = new LinkedHashMap<>(g0.main());
                main2.put(sheetCol, sheetName);
                List<Map<String, String>> rows2 = new ArrayList<>(g0.rows().size());
                for (Map<String, String> r : g0.rows()) {
                    Map<String, String> c = new LinkedHashMap<>(r);
                    c.put(sheetCol, sheetName);
                    rows2.add(c);
                }
                // 流水日记账行级组键已在 groupRows 内含 sheet 名前缀，不再二次拼接
                String key2 = type == FactType.CASHFLOW_JOURNAL ? g0.key() : sheetName + "#" + g0.key();
                all.add(new Group(key2, externalIdOf(type, key2, main2), main2, rows2, hashGroup(key2, main2, rows2)));
            }
        }
        if (anyMatched) return all;
    } else {
for (Sheet sheet : workbook) {
Header header = locateHeader(sheet, type);
if (header != null) return groupRows(sheet, header, type);
}
    }
} catch (IOException | RuntimeException e) {
String rawMsg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
String msg = rawMsg;
if (rawMsg.contains("allocate an array") || rawMsg.contains("maximum length for this record type") || rawMsg.contains("Zip bomb")) {
    msg = "文件内容较大，Excel 内部数据超过默认解析限制（已放宽阈值）；请尝试将该导出文件拆分或精简导出字段后再导入";
} else if (rawMsg.contains("not a valid ZipFile") || rawMsg.contains("Invalid signature") || rawMsg.contains("NotOfficeXmlFileException") || rawMsg.contains("invalid stored block lengths")) {
    msg = "文件格式不是有效的 Excel（.xlsx/.xls），或文件已损坏。请重新从氚云以全字段导出方式下载再上传";
}
throw new IllegalArgumentException("无法解析 Excel 文件：" + msg);
}
throw new IllegalArgumentException("未找到“" + type.keyHeader + "”表头，请使用对应的氚云全字段导出文件");
}

/**
 * 使用 POI SAX 事件模型逐行解析 xlsx。氚云全字段销售出库解压后可能超过 1GB，
 * XSSFWorkbook 会为每个空白/样式单元格创建对象而耗尽堆内存。
 */
private List<Group> parseXlsxStreaming(FactType type, MultipartFile file) throws IOException {
    Path temp = Files.createTempFile("h3-fact-import-", ".xlsx");
    try {
        file.transferTo(temp);
        try (OPCPackage pkg = OPCPackage.open(temp.toFile())) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            Map<String, MutableGroup> mergedGroups = type.multiSheet ? new LinkedHashMap<>() : null;
            boolean anyMatched = false;
            while (sheets.hasNext()) {
                try (var sheet = sheets.next()) {
                    // multiSheet：SheetIterator 在 next() 后可取当前 sheet 名，注入每行「供应商」列
                    StreamingSheetHandler handler = new StreamingSheetHandler(type, type.multiSheet ? sheets.getSheetName() : null);
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, handler, formatter, false));
                    parser.parse(new InputSource(sheet));
                    if (handler.headerMatched()) {
                        if (!type.multiSheet) return toGroups(handler.groups(), type);
                        anyMatched = true;
                        // 聚合所有匹配模板的 sheet（组键已含 sheet 名，putIfAbsent 防重名冲突）
                        handler.groups().forEach(mergedGroups::putIfAbsent);
                    }
                }
            }
            if (anyMatched) return toGroups(mergedGroups, type);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // 记录完整堆栈便于定位（如文件损坏/格式不符/加密等具体原因）
            log.warn("流式解析 Excel 失败，原因：{}", e.getClass().getName() + ": " + e.getMessage(), e);
            throw new IllegalArgumentException("无法流式解析 Excel 文件[" + e.getClass().getSimpleName() + "]：" +
                    (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    } finally {
        Files.deleteIfExists(temp);
    }
    throw new IllegalArgumentException("未找到“" + type.keyHeader + "”表头，请使用对应的氚云全字段导出文件");
}

private final class StreamingSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
    private final FactType type;
    private final Map<String, MutableGroup> groups = new LinkedHashMap<>();
    private final Map<Integer, String> cells = new HashMap<>();
    private Map<String, Integer> columns;
    /** 列号 → 表头，用于 endRow 只保留本行实际有值的单元格。 */
    private Map<Integer, String> indexHeader;
    private MutableGroup current;
    /** multiSheet kind 的当前 sheet 名（注入「供应商/账户」列 + 组键前缀）；单 sheet kind 恒 null。 */
    private final String sheetName;
    /** 流水日记账同键序号（键=账户#日期#收入#支出#结存；handler 每 sheet 一个实例 → 序号按行序确定）。 */
    private final Map<String, Integer> journalKeySeq = new HashMap<>();

    private StreamingSheetHandler(FactType type, String sheetName) { this.type = type; this.sheetName = sheetName; }
    boolean headerMatched() { return columns != null; }
    Map<String, MutableGroup> groups() { return groups; }

    @Override public void startRow(int rowNum) { cells.clear(); }
    @Override public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
        int column = new org.apache.poi.ss.util.CellReference(cellReference).getCol();
        String value = clean(formattedValue);
        if (value != null && !"-".equals(value)) cells.put(column, value);
    }
    @Override public void endRow(int rowNum) {
        if (columns == null) {
            if (rowNum > 5) return;
            Map<String, Integer> candidate = new LinkedHashMap<>();
            // 按列号升序构建，保证「仓管员」两个同名列的先后稳定（cells 是 HashMap，遍历序不保证）
            cells.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> putHeader(candidate, type, e.getValue(), e.getKey()));
            if (candidate.containsKey(type.keyHeader) && matchesTemplate(type, candidate)) {
                columns = candidate;
                indexHeader = new HashMap<>(candidate.size() * 2);
                candidate.forEach((header, index) -> indexHeader.put(index, header));
            }
            return;
        }
        // 只保留本行实际有值的单元格：氚云全字段导出每行数百列，若像原来那样把整行的
        // 所有列（含大量空列）都塞进 Map，几十万行 × 几百列会产生数千万个空 entry，
        // 仅 LinkedHashMap 节点开销即可耗尽堆内存（java.lang.OutOfMemoryError）。
        Map<String, String> values = new LinkedHashMap<>(Math.max(8, cells.size() * 2));
        cells.forEach((index, value) -> {
            String header = indexHeader.get(index);
            if (header != null) values.put(header, value);
        });
        if (type.multiSheet && sheetName != null) {
            // multiSheet（2026-09-18 第 17/18 类）：sheet 名注入列按 kind——供应商应付=「供应商」、
            // 流水日记账=「账户」（覆盖源表账户列错填，sheet 名为确定性来源）。注入 + 组键防跨 sheet 串组。
            values.put(type == FactType.CASHFLOW_JOURNAL ? "账户" : "供应商", sheetName);
        }
        String key;
        if (type == FactType.CASHFLOW_JOURNAL) {
            // 流水日记账（2026-09-18 第 18 类）：无单号/数据ID，幂等必须行级。
            // 组键 = 账户#日期#收入#支出#结存#同键序号（同键最多 3 行实测）；
            // 「上期结存」伪行（无日期）单独成组 → save 转写账户维度表期初余额、不入分录。
            key = cashflowRowKey(values, sheetName, journalKeySeq);
            if (key == null) return;   // 排除行：非期初的空白填充行，不入组（否则会并进上一组污染其哈希）
        } else if (type == FactType.CASHIER_LOG) {
            // 收银通（2026-09-18 第 19 类）：时间#金额#付款人识别号（仅时间有同秒不同额，实测三元素零重复）。
            key = cashierRowKey(values);
            if (key == null) return;   // 排除行：无交易时间，不入组
        } else {
            key = clean(values.get(type.keyHeader));
            if (key != null && type.multiSheet && sheetName != null) key = sheetName + "#" + key;
        }
        if (key != null) current = groups.computeIfAbsent(key, MutableGroup::new);
        if (current == null || values.isEmpty()) return;
        current.mergeMain(values);
        current.rows.add(values);
    }
}

private List<Group> toGroups(Map<String, MutableGroup> mutableGroups, FactType type) {
    List<Group> result = new ArrayList<>(mutableGroups.size());
    for (MutableGroup group : mutableGroups.values()) {
        // 流式哈希：不再为整张表生成完整 JSON 字符串（大文件可达 GB 级），边序列化边计算 SHA-256。
        result.add(new Group(group.key, externalIdOf(type, group.key, group.main), group.main, group.rows,
                hashGroup(group.key, group.main, group.rows)));
    }
    return result;
}

/**
 * 外部幂等键取值。
 *
 * <p>默认取主表「数据ID(不可修改)」（氚云 UUID）。例外：采购入库导出件里子表
 * 「产品明细.数据ID(不可修改)」列在前、主表「数据ID」列在后，表头名相同被首命中吞掉——
 * bare「数据ID」落到的是首个明细行 UUID，语义错误且随首行变化。采购入库编号实测全局唯一，
 * 故 PURCHASE_IN 统一取业务单号作幂等键。
 */
private String externalIdOf(FactType type, String key, Map<String, String> main) {
    // 采购/调拨/供应商应付/流水日记账/收银通：无数据ID 或数据ID不可用 → 统一取组键作幂等键
    //（流水日记账=行级组键 账户#日期#金额#序号；收银通=时间#金额，实测零重复）
    if (type == FactType.PURCHASE_IN || type == FactType.TRANSFER || type == FactType.SUPPLIER_PAYABLE
            || type == FactType.CASHFLOW_JOURNAL || type == FactType.CASHIER_LOG) return key;
    String externalId = clean(main.get("数据ID(不可修改)"));
    return externalId == null ? key : externalId;
}

private record Header(int rowIndex, Map<String, Integer> columns) {}

/**
 * 表头列名归一键：把「原始表头名 → 列号」写入 built。
 *
 * <p>⚠️ 库存调拨主表有两个同名「仓管员」列（选择调出仓库之后、选择调入仓库之后各一），
 * putIfAbsent（首命中优先）会静默吞掉调入侧 → 仅 TRANSFER 把**第二个**改名为
 * 「仓管员(调入)」留存。调用方必须按列号升序遍历，否则调出/调入会颠倒。
 *
 * <p>流式（StreamingSheetHandler#endRow）与兜底（locateHeader）两条路径共用本方法，
 * 保证 rowValues 产出的 Map 与 source_hash 完全一致（避免两路径交替导入反复判 UPDATE）。
 */
private void putHeader(Map<String, Integer> built, FactType type, String raw, int index) {
    if (type == FactType.TRANSFER && "仓管员".equals(raw) && built.containsKey(raw)) {
        built.put("仓管员(调入)", index);
    } else if (type == FactType.PRODUCTION_REPORT && "报工人".equals(raw) && built.containsKey(raw)) {
        // 生产报工有两个同名「报工人」列（第 5 列=主报工 F0000005、第 6 列=辅助 F0000038 实测 329 行非空 22 人）
        built.put("报工人(辅助)", index);
    } else if (type == FactType.INVOICE_SALES && "备注".equals(raw) && built.containsKey(raw)) {
        // 销项发票（2026-09-18 第 16 类）有两个同名「备注」列：第 17 列=人工收款备注（11 行）/
        // 第 24 列=平台红冲备注（271 行，含「被红冲蓝字数电发票号码：X」红冲链），0 行同时非空 → 语义不同必须改名
        built.put("备注(平台)", index);
    } else if (type == FactType.SUPPLIER_PAYABLE && "付款方式".equals(raw) && built.containsKey(raw)) {
        // 供应商应付（2026-09-18 第 17 类）：点时激光 sheet 尾部另有同名「付款方式」列（人工分析列），
        // 首列（第 6 列）才是应付侧付款方式 → 第二列改名留存
        built.put("付款方式(尾)", index);
    } else if (type == FactType.CASHFLOW_JOURNAL && raw.replaceAll("\\s+", "").equals("客户供应商")) {
        // 流水日记账（2026-09-18 第 18 类）：源表头「客   户    供应商」夹带不规则空白 → 归一为无空格键
        built.put("客户供应商", index);
    } else if (type == FactType.CASHFLOW_JOURNAL && "人民币".equals(raw) && built.containsKey(raw)) {
        // 流水日记账外币 sheet（温州睿包美元/欧元）：两个「人民币」语义不同——第 14 列=收入折算、
        // 第 16 列=结汇折算 → 第二列改名留存（仓管员/报工人/备注同款陷阱）
        built.put("人民币(结汇)", index);
    } else {
        built.putIfAbsent(raw, index);
    }
}

private Header locateHeader(Sheet sheet, FactType type) {
int max = Math.min(sheet.getLastRowNum(), 5);
for (int r = 0; r <= max; r++) {
Row row = sheet.getRow(r);
if (row == null) continue;
Map<String, Integer> columns = new LinkedHashMap<>();
for (Cell cell : row) {
String value = formatter.formatCellValue(cell).trim();
            if (!value.isEmpty()) {
                putHeader(columns, type, value, cell.getColumnIndex());
            }
}
if (columns.containsKey(type.keyHeader) && matchesTemplate(type, columns)) return new Header(r, columns);
}
return null;
}

private boolean matchesTemplate(FactType type, Map<String, Integer> columns) {
return switch (type) {
case PRODUCTION -> columns.containsKey("产品名称") && columns.containsKey("计划类型");
case OUTBOUND -> columns.containsKey("出库产品明细.选择产品") || columns.containsKey("出库产品明细.产品名称") || columns.containsKey("产品名称");
case RECEIPT -> columns.containsKey("本次收款金额");
case SETTLEMENT -> columns.containsKey("出库单明细.销售出库编号");
// 供应商单表：状态列 + 对公银行列同时存在（实测表头，防误认其他表单）
case SUPPLIER -> columns.containsKey("供应商状态") && columns.containsKey("对公开户名");
// 采购入库：子表前缀「产品明细.」或主表供应商列存在即可
case PURCHASE_IN -> columns.containsKey("产品明细.产品编号") || columns.containsKey("选择供应商");
// 其他入库/其他出库：⚠️ 两份导出件的明细子表前缀**都**写作「其他出库明细.」（氚云表单复制串名），
// 前缀无法区分二者，只能认主表独有的仓库列：「选择入库仓库」vs「选择出库仓库」。
case OTHER_IN -> columns.containsKey("选择入库仓库") || columns.containsKey("入库仓库（隐）");
case OTHER_OUT -> columns.containsKey("选择出库仓库") || columns.containsKey("其他出库编号");
// 库存调拨：明细前缀「调拨明细.」正常无串名；主表「选择调出仓库」为独有列
case TRANSFER -> columns.containsKey("调拨明细.产品编号") || columns.containsKey("选择调出仓库");
// 开票资料：单表，户名+开户行+银行账号同时存在且无主表申请金额列（与开票申请区分）
case INVOICE_PROFILE -> columns.containsKey("户名") && columns.containsKey("开户行")
        && columns.containsKey("银行账号") && !columns.containsKey("本次申请开票金额");
// 开票申请：内容/收款子表前缀独有
case INVOICE_REQUEST -> columns.containsKey("开票内容.金额小计") || columns.containsKey("收款情况.收款金额");
// 生产报工（2026-09-17 第 15 类）：裸「生产工单编号」为独有列（生产任务单叫「生产工单流水号」），
// 叠加「报工时间」+「报工数量」三列同时存在，防误认其他表单
case PRODUCTION_REPORT -> columns.containsKey("报工时间") && columns.containsKey("生产工单编号")
        && columns.containsKey("报工数量");
// 进项/销项发票（2026-09-18 第 16 类，税务平台导出）：同一文件两个 sheet 表头不同。
// 销项独有收款域列（收款金额+业务员+发票状态）；进项按「销方识别号+无收款金额」与销项区分。
// 对公流水 sheet（同文件，属流水日记账域）无数电发票号码，keyHeader 检查即排除。
case INVOICE_INPUT -> columns.containsKey("销方识别号") && columns.containsKey("发票票种")
        && !columns.containsKey("收款金额");
case INVOICE_SALES -> columns.containsKey("收款金额") && columns.containsKey("业务员")
        && columns.containsKey("发票状态");
// 供应商应付（2026-09-18 第 17 类）：人工月度台账 8 列模板（月份/应付货款/结算货款/折让/付款日期/
// 付款方式/付款金额/剩余货款）。汇总（序号/供应商/期初余额/1..12）与封口机（无表头）被排除；
// 制版费/全自动气调机等复合 sheet 左侧即该模板，一并导入（右侧异构列不读）。
case SUPPLIER_PAYABLE -> columns.containsKey("月份") && columns.containsKey("应付货款")
        && columns.containsKey("结算货款") && columns.containsKey("折让")
        && columns.containsKey("付款日期") && columns.containsKey("付款方式")
        && columns.containsKey("付款金额") && columns.containsKey("剩余货款");
// 流水日记账（2026-09-18 第 18 类）：账户 sheet 12 列基线（日期/收入/支出/结存/摘要同现）。
// 汇总（无收入/支出精确列）、Sheet2 字典（无日期/结存）、收银通（无收入/结存）均被排除；
// 缺月份列（温州数创/宋文）与多汇率列（睿包外币）的变体同样命中——multiSheet 全聚合。
case CASHFLOW_JOURNAL -> columns.containsKey("日期") && columns.containsKey("收入")
        && columns.containsKey("支出") && columns.containsKey("结存") && columns.containsKey("摘要");
// 收银通收单流水（2026-09-18 第 19 类）：微信收单导出独有「订单总扣费(元)」+「付款人识别号」，
// 账户 sheet/汇总/Sheet2 均无此二列
case CASHIER_LOG -> columns.containsKey("交易完成时间") && columns.containsKey("订单总扣费(元)")
        && columns.containsKey("付款人识别号");
};
}

private List<Group> groupRows(Sheet sheet, Header header, FactType type) {
Map<String, MutableGroup> groups = new LinkedHashMap<>();
MutableGroup current = null;
Map<String, Integer> journalKeySeq = new HashMap<>();
String journalSheet = type == FactType.CASHFLOW_JOURNAL ? sheet.getSheetName() : null;
for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
Row row = sheet.getRow(r);
if (row == null || blank(row)) continue;
Map<String, String> values = rowValues(row, header.columns());
String key;
if (type == FactType.CASHFLOW_JOURNAL) {
    // 与流式路径同口径：行级组键（账户#日期#收入#支出#结存#同键序号），保证两路径 source_hash 一致
    key = cashflowRowKey(values, journalSheet, journalKeySeq);
    if (key == null) continue;
} else if (type == FactType.CASHIER_LOG) {
    key = cashierRowKey(values);
    if (key == null) continue;
} else {
    key = clean(values.get(type.keyHeader));
}
if (key != null) current = groups.computeIfAbsent(key, MutableGroup::new);
if (current == null) continue;
current.mergeMain(values);
current.rows.add(values);
}
List<Group> result = new ArrayList<>();
for (MutableGroup group : groups.values()) {
// 流式哈希：不再为整张表生成完整 JSON 字符串（大文件可达 GB 级），边序列化边计算 SHA-256。
result.add(new Group(group.key, externalIdOf(type, group.key, group.main), group.main, group.rows,
        hashGroup(group.key, group.main, group.rows)));
}
return result;
}

private static class MutableGroup {
final String key; final Map<String, String> main = new LinkedHashMap<>();
final List<Map<String, String>> rows = new ArrayList<>();
MutableGroup(String key) { this.key = key; }
void mergeMain(Map<String, String> values) {
values.forEach((k, v) -> { if (v != null && !v.isBlank()) main.putIfAbsent(k, v); });
}
}

private Map<String, String> rowValues(Row row, Map<String, Integer> columns) {
// 与流式路径保持一致：只为实际有值的列创建 entry（不 put null），
// 保证两条解析路径产生的行 Map 与 source_hash 完全一致，避免流式/兜底交替导入时反复判为 UPDATE。
Map<String, String> values = new LinkedHashMap<>();
columns.forEach((name, index) -> {
Cell cell = row.getCell(index);
String value = cell == null ? null : formatter.formatCellValue(cell).trim();
if (value != null && !value.isEmpty() && !"-".equals(value)) values.put(name, value);
});
return values;
}

private boolean blank(Row row) {
for (Cell cell : row) if (!formatter.formatCellValue(cell).trim().isEmpty()) return false;
return true;
}

private Map<String, ExistingFact> loadExistingFacts(FactType type) {
Map<String, ExistingFact> result = new HashMap<>();
String sql = "SELECT id,external_id,source_hash,source_updated_at FROM " + table(type) + " WHERE external_source='H3YUN'";
if (type == FactType.CASHFLOW_JOURNAL) {
    // 流水日记账（第 18 类）的既有事实横跨两张表：分录表（行级组键）+ 账户档案表（期初行）。
    // 期初行组键 = 账户名#期初 → 用 CONCAT(account_name,'#期初') 合成同型事实键，否则期初行
    // 每轮都查不到 existing、恒判 CREATE，台账永不收敛（首次实测每轮多计 34 条 CREATE）。
    sql = "SELECT id,external_id,source_hash,source_updated_at FROM h3_cashflow_entries WHERE external_source='H3YUN'"
        + " UNION ALL "
        + "SELECT id,CONCAT(account_name,'#期初') AS external_id,source_hash,source_updated_at"
        + " FROM h3_cash_accounts WHERE external_source='H3YUN'";
}
jdbc.query(sql,
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            result.put(rs.getString("external_id"),
                    new ExistingFact(rs.getLong("id"), rs.getString("source_hash"), rs.getTimestamp("source_updated_at")));
        });
return result;
}

/**
 * 网页版与客户端导出会调整列顺序，而旧 source_hash 对 LinkedHashMap 的字段顺序敏感。
 * 氚云主记录修改时间相同代表同一业务版本，可避免仅因导出列顺序变化误报 UPDATE；
 * 修改时间变化时仍以哈希差异判定更新，确保真实业务变化能够写入。
 */
private boolean unchanged(ExistingFact existing, Group incoming) {
    if (existing == null) return false;
    if (Objects.equals(existing.hash(), incoming.hash())) return true;
    // 口径升级过渡：2026-09-18 起 source_hash 走 H3SnapshotKey 归一（表头空白/数值位数/日期分隔符）。
    // 已入库行存的仍是旧口径哈希，若旧口径命中，说明差异只来自归一化本身、业务未变 → 判 UNCHANGED，
    // 由调用方的 touch() 把哈希刷成新口径。惰性计算：稳态下现行口径先命中，这里不会执行，无额外开销。
    if (legacyHash(incoming).equals(existing.hash())) return true;
    // 采购/生产/销售/收款主表用「修改时间(必填)」；供应商导出件表头为「修改时间」（无后缀）
    Timestamp incomingUpdatedAt = dateTime(first(incoming.main(), "修改时间(必填)", "修改时间"));
    return incomingUpdatedAt != null && existing.sourceUpdatedAt() != null
            && existing.sourceUpdatedAt().toLocalDateTime().equals(incomingUpdatedAt.toLocalDateTime());
}

/**
 * 旧口径哈希（2026-09-18 之前：只排序字段名、不做表头/值归一）。
 *
 * <p>仅用于口径升级期的兼容回退——没有它，5 个无「修改时间」列的 kind
 * （进项/销项发票、供应商应付、流水日记账、收银通）在换口径后会把全部行误判 UPDATE，
 * 产生约 2.1 万行的一次性无谓写入。凡 {@code touch()} 已回写，即不再走本分支。
 *
 * <p><b>删除条件</b>：5 类各完成一轮重导（其 source_hash 全量刷成新口径）后，本方法连同
 * {@link #unchanged} 中的回退分支可一并移除。在此之前<b>不得删除</b>——删了会立刻触发上述风暴。
 */
private String legacyHash(Group group) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("key", group.key());
    payload.put("main", new TreeMap<>(group.main()));
    payload.put("rows", group.rows().stream().map(TreeMap::new).toList());
    return hash(payload);
}

private Long existingId(FactType type, Group group) {
List<Long> ids = jdbc.query("SELECT id FROM " + table(type) + " WHERE external_source='H3YUN' AND external_id=? LIMIT 2",
(rs, i) -> rs.getLong(1), group.externalId());
return ids.isEmpty() ? null : ids.get(0);
}

/**
 * 释放业务单号冲突：当导入数据按 (external_source, external_id) 判定为新建（CREATE），
 * 但业务单号（outbound_no/work_order_no/receipt_no）已被其他来源或旧记录占用时，
 * 清理这些冲突行（含子表），避免 INSERT ... ON DUPLICATE KEY UPDATE 命中单号唯一键而误更新其他记录，
 * 导致后续按 (H3YUN, external_id) 反查不到。冲突行视为历史残留，以氚云最新数据为准。
 */
private void clearConflicts(FactType type, Group group) {
String no = group.key();
if (no == null) return;
String sql = switch (type) {
case OUTBOUND -> "SELECT id FROM sales_outbounds WHERE outbound_no=? AND external_id<>?";
case PRODUCTION -> "SELECT id FROM production_orders WHERE work_order_no=? AND external_id<>?";
case RECEIPT, SETTLEMENT -> "SELECT id FROM receipts WHERE receipt_no=? AND external_id<>?";
case PURCHASE_IN -> "SELECT id FROM purchase_receipts WHERE source_no=? AND external_id<>?";
case OTHER_IN, OTHER_OUT -> "SELECT id FROM h3_other_stock_movements WHERE source_no=? AND external_id<>?";
case TRANSFER -> "SELECT id FROM h3_inventory_transfers WHERE source_no=? AND external_id<>?";
// 开票资料无业务单号（同供应商）：external_id 唯一即可，不走清理
case INVOICE_PROFILE -> null;
case INVOICE_REQUEST -> "SELECT id FROM h3_invoice_requests WHERE source_no=? AND external_id<>?";
// 供应商无业务单号；normalized_name 唯一索引冲突由 saveSupplier 显式定位自愈，不走清理
case SUPPLIER -> null;
// 生产报工无业务单号（source_no=数据ID UUID 兜底，每行唯一）：external_id 唯一即可，不走清理
case PRODUCTION_REPORT -> null;
// 进项/销项发票（2026-09-18 第 16 类）：source_no=数电发票号码=external_id，二者恒等不存在单号冲突，不走清理
case INVOICE_INPUT, INVOICE_SALES -> null;
// 供应商应付（第 17 类）：external_id=供应商#月份 恒唯一，不走清理
case SUPPLIER_PAYABLE -> null;
// 流水日记账（第 18 类）/收银通（第 19 类）：external_id=行级组键恒唯一（source_no 同值），不走清理
case CASHFLOW_JOURNAL, CASHIER_LOG -> null;
};
if (sql == null) return;
List<Long> ids = jdbc.query(sql, (rs, i) -> rs.getLong(1), no, group.externalId());
for (Long id : ids) {
switch (type) {
case OUTBOUND -> {
jdbc.update("DELETE FROM sales_outbound_items WHERE sales_outbound_id=?", id);
jdbc.update("DELETE FROM receipt_allocations WHERE sales_outbound_id=?", id);
}
case PRODUCTION -> jdbc.update("DELETE FROM production_order_components WHERE production_order_id=?", id);
case RECEIPT, SETTLEMENT -> {
jdbc.update("DELETE FROM receipt_allocations WHERE receipt_id=?", id);
}
case PURCHASE_IN -> jdbc.update("DELETE FROM purchase_receipt_items WHERE purchase_receipt_id=?", id);
case OTHER_IN, OTHER_OUT -> jdbc.update("DELETE FROM h3_other_stock_movement_items WHERE movement_id=?", id);
case TRANSFER -> jdbc.update("DELETE FROM h3_inventory_transfer_items WHERE transfer_id=?", id);
case INVOICE_REQUEST -> {
jdbc.update("DELETE FROM h3_invoice_request_items WHERE request_id=?", id);
jdbc.update("DELETE FROM h3_invoice_request_receipt_refs WHERE request_id=?", id);
}
case INVOICE_PROFILE -> { /* 不可达：sql==null 已 return */ }
case SUPPLIER -> { /* 不可达：sql==null 已 return */ }
case PRODUCTION_REPORT -> { /* 不可达：sql==null 已 return */ }
case INVOICE_INPUT, INVOICE_SALES -> { /* 不可达：sql==null 已 return */ }
case SUPPLIER_PAYABLE -> { /* 不可达：sql==null 已 return */ }
case CASHFLOW_JOURNAL, CASHIER_LOG -> { /* 不可达：sql==null 已 return */ }
}
jdbc.update("DELETE FROM " + table(type) + " WHERE id=?", id);
}
}

private String table(FactType type) {
return switch (type) {
case PRODUCTION -> "production_orders";
case OUTBOUND -> "sales_outbounds";
case RECEIPT, SETTLEMENT -> "receipts";
case SUPPLIER -> "h3_supplier_catalog";
case PURCHASE_IN -> "purchase_receipts";
case OTHER_IN, OTHER_OUT -> "h3_other_stock_movements";
case TRANSFER -> "h3_inventory_transfers";
case INVOICE_PROFILE -> "h3_customer_invoice_profiles";
case INVOICE_REQUEST -> "h3_invoice_requests";
case PRODUCTION_REPORT -> "h3_production_reports";
case INVOICE_INPUT -> "h3_input_invoices";
case INVOICE_SALES -> "h3_sales_invoices";
case SUPPLIER_PAYABLE -> "h3_supplier_payables";
case CASHFLOW_JOURNAL -> "h3_cashflow_entries";
case CASHIER_LOG -> "h3_cashier_log";
};
}

private void touch(FactType type, Long id, String sourceHash) {
jdbc.update("UPDATE " + table(type) + " SET source_hash=?,last_synced_at=NOW() WHERE id=?", sourceHash, id);
}

private Long saveProduction(Group g) {
Map<String, String> m = g.main();
Long customerId = customerId(first(m, "客户选择", "客户信息"));
Long salespersonId = memberId(m.get("业务员(必填)"));
Long employeeId = memberEmployeeId(m.get("业务员(必填)"));
// 新建时先释放被其他记录占用的同号工单，避免 ON DUPLICATE KEY UPDATE 命中 uk_po_work_order 误更新
if (existingId(FactType.PRODUCTION, g) == null) {
clearConflicts(FactType.PRODUCTION, g);
}
jdbc.update("""
INSERT INTO production_orders(external_source,external_id,work_order_no,status,plan_type,application_date,
required_completion_date,expected_completion_date,actual_completion_date,salesperson_id,employee_id,salesperson_name,
customer_id,customer_name,product_name,required_quantity,completed_quantity,warehoused_quantity,
production_progress,warehousing_progress,production_completed,warehousing_completed,box_size,box_shape,
box_material,mold_layout,voltage,plug_type,brand,optional_configuration,production_requirement,process_name,
production_line,order_receiver,warehouse_operator,linked_sales_order_no,linked_sales_order_ref,
product_order_external_id,department_name,source_created_by,source_created_at,source_updated_at,source_hash,last_synced_at)
VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
ON DUPLICATE KEY UPDATE work_order_no=VALUES(work_order_no),status=VALUES(status),plan_type=VALUES(plan_type),
application_date=VALUES(application_date),required_completion_date=VALUES(required_completion_date),
expected_completion_date=VALUES(expected_completion_date),actual_completion_date=VALUES(actual_completion_date),
salesperson_id=VALUES(salesperson_id),employee_id=VALUES(employee_id),salesperson_name=VALUES(salesperson_name),customer_id=VALUES(customer_id),
customer_name=VALUES(customer_name),product_name=VALUES(product_name),required_quantity=VALUES(required_quantity),
completed_quantity=VALUES(completed_quantity),warehoused_quantity=VALUES(warehoused_quantity),
production_progress=VALUES(production_progress),warehousing_progress=VALUES(warehousing_progress),
production_completed=VALUES(production_completed),warehousing_completed=VALUES(warehousing_completed),
box_size=VALUES(box_size),box_shape=VALUES(box_shape),box_material=VALUES(box_material),mold_layout=VALUES(mold_layout),
voltage=VALUES(voltage),plug_type=VALUES(plug_type),brand=VALUES(brand),optional_configuration=VALUES(optional_configuration),
production_requirement=VALUES(production_requirement),process_name=VALUES(process_name),production_line=VALUES(production_line),
order_receiver=VALUES(order_receiver),warehouse_operator=VALUES(warehouse_operator),linked_sales_order_no=VALUES(linked_sales_order_no),
linked_sales_order_ref=VALUES(linked_sales_order_ref),product_order_external_id=VALUES(product_order_external_id),
department_name=VALUES(department_name),source_created_by=VALUES(source_created_by),source_created_at=VALUES(source_created_at),
source_updated_at=VALUES(source_updated_at),source_hash=VALUES(source_hash),last_synced_at=NOW()
""", g.externalId(), g.key(), m.get("数据状态"), m.get("计划类型"), date(m.get("工单申请日期")),
date(m.get("订单要求生产完成日期(必填)")), date(m.get("预计生产完成日期")), date(m.get("实际生产完成日期")),
salespersonId, employeeId, m.get("业务员(必填)"), customerId, first(m,"客户选择","客户信息"), m.get("产品名称"),
decimal(m.get("需求数量")), decimal(m.get("生产完成数")), decimal(first(m,"入库数量_数值","入库数量")),
first(m,"生产进度","生产进度_停用"), m.get("入库进度"), bool(m.get("是否生产完成")), bool(m.get("是否入库完成")),
m.get("盒子尺寸"), first(m,"盒子形状","其他盒子形状"), m.get("盒子材质"), m.get("模具排列"),
m.get("电压(必填)"), first(m,"插头","其他插头"), first(m,"品牌","自选品牌名称"), m.get("可选配置"), m.get("备注"),
m.get("选择制程(必填)"), first(m,"产线选择(必填)","产线文本"), m.get("接单员"), m.get("入库员"),
m.get("销售订单编号"), m.get("关联销售订单"), m.get("产品订单子OBJid"), m.get("所属部门(必填)"),
m.get("创建人(必填)"), dateTime(m.get("创建时间(必填)")), dateTime(m.get("修改时间(必填)")), g.hash());
Long id = existingId(FactType.PRODUCTION, g);
if (id == null) throw new IllegalStateException("生产工单写入后未能定位（external_id=" + g.externalId() + ", 单号=" + g.key() + "）");
jdbc.update("DELETE FROM production_order_components WHERE production_order_id=?", id);
for (Map<String, String> row : g.rows()) {
if (empty(row.get("零部件制程.数据ID(不可修改)")) && empty(row.get("零部件制程.零部件名"))) continue;
jdbc.update("""
INSERT INTO production_order_components(production_order_id,external_id,sequence_no,external_title,
component_name,component_ref,component_serial_no,required_quantity,requirement_factor,stock_quantity,
stock_used_quantity,completed_quantity,completion_ratio,completed_at,final_process,completed,process_drawing,reporting_qr)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""", id, row.get("零部件制程.数据ID(不可修改)"), integer(row.get("零部件制程.序号")),
row.get("零部件制程.数据标题"), row.get("零部件制程.零部件名"), row.get("零部件制程.关联零部件"),
row.get("零部件制程.零部件流水号"), decimal(row.get("零部件制程.需求数量")),
decimal(row.get("零部件制程.需求系数")), decimal(row.get("零部件制程.库存数量")),
decimal(row.get("零部件制程.库存调用数量")), decimal(row.get("零部件制程.生产完成数量")),
row.get("零部件制程.生产完成比例"), dateTime(row.get("零部件制程.完成时间")),
bool(row.get("零部件制程.是否最终工序")), bool(row.get("零部件制程.生产完成")),
row.get("零部件制程.零部件工艺图"), row.get("零部件制程.报工二维码"));
}
return id;
}

private Long saveReceipt(Group g, boolean settlement) {
    Map<String, String> m = g.main();
    Long customerId = customerId(first(m, "选择客户", "客户名称", "选择经销商"));
    Long salespersonId = memberId(first(m, "业务员", "归属业务员", "业务员(必填)"));
    // 收款记录（RECEIPT）与多订单结清（SETTLEMENT）同存 receipts 表；多订单结清标为 MULTI_OUTBOUND，
    // 收款记录标为 SINGLE_OUTBOUND。仅作氚云原始数据台账，不与销售出库分配（无 receipt_allocations 写入）。
    String receiptType = settlement ? "MULTI_OUTBOUND" : "SINGLE_OUTBOUND";
    String customerName = first(m, "选择客户", "客户名称", "选择经销商");
    String salespersonName = first(m, "业务员", "归属业务员", "业务员(必填)");
    if (existingId(FactType.RECEIPT, g) == null) {
        clearConflicts(FactType.RECEIPT, g);
    }
    jdbc.update("""
        INSERT INTO receipts(external_source,external_id,receipt_no,receipt_type,status,receipt_date,
        customer_id,customer_name,dealer_name,salesperson_id,salesperson_name,payment_method,
        receipt_amount,receivable_amount,allocated_amount,unallocated_amount,remark,attachment_info,
        department_name,source_created_by,source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE receipt_no=VALUES(receipt_no),receipt_type=VALUES(receipt_type),status=VALUES(status),
        receipt_date=VALUES(receipt_date),customer_id=VALUES(customer_id),customer_name=VALUES(customer_name),
        dealer_name=VALUES(dealer_name),salesperson_id=VALUES(salesperson_id),salesperson_name=VALUES(salesperson_name),
        payment_method=VALUES(payment_method),receipt_amount=VALUES(receipt_amount),receivable_amount=VALUES(receivable_amount),
        allocated_amount=VALUES(allocated_amount),unallocated_amount=VALUES(unallocated_amount),remark=VALUES(remark),
        attachment_info=VALUES(attachment_info),department_name=VALUES(department_name),
        source_created_by=VALUES(source_created_by),source_created_at=VALUES(source_created_at),
        source_updated_at=VALUES(source_updated_at),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, g.externalId(), g.key(), receiptType, m.get("数据状态"), date(m.get("收款日期")),
        customerId, customerName, m.get("选择经销商"), salespersonId, salespersonName,
        first(m, "支付方式", "收款方式", "收款渠道"), decimal(first(m, "本次收款金额", "收款金额")),
        decimal(first(m, "合计已收金额", "本次收款金额", "收款金额")), null, null,
        m.get("备注"), m.get("附件"), m.get("所属部门(必填)"), m.get("创建人(必填)"),
        dateTime(m.get("创建时间(必填)")), dateTime(m.get("修改时间(必填)")), g.hash());
    Long id = existingId(FactType.RECEIPT, g);
    if (id == null) throw new IllegalStateException("收款/结清写入后未能定位（external_id=" + g.externalId() + ", 单号=" + g.key() + "）");
    if (settlement) {
        saveSettlementOutboundLinks(id, g);
    } else {
        saveReceiptOutboundLinks(id, g);
    }
    return id;
}

/**
 * 保存多订单结清主单「出库单明细」关联（settlement_outbound_links）。
 *
 * <p>多订单结清 = 一笔多付的钱按订单顺序滚动结算（客户+业务员维度 FIFO），
 * 必须保留「这笔总款覆盖哪些出库单」明细，供台账展开核对与后续交叉校验；
 * 仅作只读参照，不写 receipt_allocations，避免污染正式回款核销口径。
 */
private void saveSettlementOutboundLinks(Long receiptId, Group g) {
    jdbc.update("DELETE FROM settlement_outbound_links WHERE receipt_id=?", receiptId);
    int seq = 0;
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, String> row : g.rows()) {
        String no = SalesOutboundSettlementService.normalizeOutboundNo(row.get("出库单明细.销售出库编号"));
        if (empty(no) || !seen.add(no)) continue;
        BigDecimal amount = decimal(first(row, "出库单明细.已收款金额", "出库单明细.收款金额", "出库单明细.金额"));
        jdbc.update("""
            INSERT INTO settlement_outbound_links(receipt_id, sequence_no, outbound_no, outbound_id, settled_amount)
            VALUES (?,?,?,?,?)
            """, receiptId, ++seq, no, outboundIdByNo(no), amount);
    }
}

/**
 * 保存单订单收款主单「关联销售出库」明细（receipt_outbound_links）。
 *
 * <p>收款记录（SINGLE_OUTBOUND）在氚云导出文件中按主单子行展开，
 * 每行携带「销售出库编号」及其「销售订单日期」；本方法按出库编号去重保存，
 * 供收款台账/详情展示「这笔收款对应哪张销售出库」。仅作只读参照，
 * 不写 receipt_allocations，避免污染正式回款核销口径。
 */
private void saveReceiptOutboundLinks(Long receiptId, Group g) {
    jdbc.update("DELETE FROM receipt_outbound_links WHERE receipt_id=?", receiptId);
    int seq = 0;
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, String> row : g.rows()) {
        String no = SalesOutboundSettlementService.normalizeOutboundNo(first(row, "销售出库编号"));
        if (empty(no) || !seen.add(no)) continue;
        // 订单日期列可能带时间部分（yyyy-MM-dd HH:mm:ss），仅取日期段
        String orderDate = first(row, "销售订单日期");
        java.sql.Date ord = empty(orderDate) ? null : date(orderDate.length() > 10 ? orderDate.substring(0, 10) : orderDate);
        jdbc.update("""
            INSERT INTO receipt_outbound_links(receipt_id, sequence_no, outbound_no, outbound_id, sales_order_date)
            VALUES (?,?,?,?,?)
            """, receiptId, ++seq, no, outboundIdByNo(no), ord);
    }
}

/** 按出库单号匹配销售出库ID；匹配不到或单号重复返回 null（仅作台账展示用，不应阻断导入）。 */
private Long outboundIdByNo(String outboundNo) {
    try {
        return jdbc.queryForObject("SELECT id FROM sales_outbounds WHERE outbound_no=?", Long.class, outboundNo);
    } catch (DataAccessException ignored) {
        return null;
    }
}

private Long saveOutbound(Group g) {
    Map<String, String> m = g.main();
    // 「客户名称」列直接读 m.get("客户名称") 时，氚云用户常把姓名合并填在「联系方式」/「客户信息」中而省去「客户名称」，
    // 这里用 resolveOutboundCustomerName 回退解析，保证 customer_id 与 customer_name 同步可用，避免下游销售出库分析找不到客户。
    String resolvedCustomerName = resolveOutboundCustomerName(m);
    Long customerId = customerId(first(m, "选择客户", resolvedCustomerName), m.get("客户详细地址"), m.get("联系方式"));
    Long salespersonId = memberId(m.get("业务员(必填)"));
Long employeeId = memberEmployeeId(m.get("业务员(必填)"));
Long warehouseId = h3WarehouseId(m.get("选择出库仓库"));
// 新建（external_id 不存在）时先释放被其他记录占用的同号出库单，避免 ON DUPLICATE KEY UPDATE 命中 uk_so_no 误更新
if (existingId(FactType.OUTBOUND, g) == null) {
clearConflicts(FactType.OUTBOUND, g);
}
jdbc.update("""
INSERT INTO sales_outbounds(external_source,external_id,outbound_no,status,outbound_status,ship_date,business_date,customer_id,
customer_name,dealer_name,salesperson_id,employee_id,salesperson_name,business_owner,region,contact_phone,customer_address,
shipping_method,free_shipping,freight,warehouse,warehouse_id,warehouse_keeper,payment_status,order_remark,collection_remark,
purchase_amount,sales_amount,source_gross_profit,received_amount,source_received_amount,outstanding_amount,invoiced_amount,uninvoiced_amount,
invoicer,external_title,department_name,source_created_by,source_created_at,source_updated_at,attachment_info,source_hash,last_synced_at)
VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
ON DUPLICATE KEY UPDATE outbound_no=VALUES(outbound_no),status=VALUES(status),outbound_status=VALUES(outbound_status),ship_date=VALUES(ship_date),business_date=VALUES(business_date),
customer_id=CASE
  WHEN sales_outbounds.customer_id IS NOT NULL AND EXISTS(
    SELECT 1 FROM customers live_customer
     WHERE live_customer.id=sales_outbounds.customer_id AND live_customer.deleted_at IS NULL
  ) THEN sales_outbounds.customer_id
  ELSE VALUES(customer_id)
END,customer_name=VALUES(customer_name),dealer_name=VALUES(dealer_name),salesperson_id=VALUES(salesperson_id),
employee_id=COALESCE(employee_id,VALUES(employee_id)),salesperson_name=VALUES(salesperson_name),business_owner=VALUES(business_owner),region=VALUES(region),contact_phone=VALUES(contact_phone),
customer_address=VALUES(customer_address),shipping_method=VALUES(shipping_method),free_shipping=VALUES(free_shipping),freight=VALUES(freight),
warehouse=VALUES(warehouse),warehouse_id=COALESCE(warehouse_id,VALUES(warehouse_id)),warehouse_keeper=VALUES(warehouse_keeper),payment_status=VALUES(payment_status),order_remark=VALUES(order_remark),
collection_remark=VALUES(collection_remark),purchase_amount=VALUES(purchase_amount),sales_amount=VALUES(sales_amount),
source_gross_profit=VALUES(source_gross_profit),received_amount=VALUES(received_amount),source_received_amount=VALUES(source_received_amount),outstanding_amount=VALUES(outstanding_amount),
invoiced_amount=VALUES(invoiced_amount),uninvoiced_amount=VALUES(uninvoiced_amount),invoicer=VALUES(invoicer),external_title=VALUES(external_title),
department_name=VALUES(department_name),source_created_by=VALUES(source_created_by),source_created_at=VALUES(source_created_at),
source_updated_at=VALUES(source_updated_at),attachment_info=VALUES(attachment_info),source_hash=VALUES(source_hash),last_synced_at=NOW()
""", g.externalId(), g.key(), m.get("数据状态"), SalesOutboundSettlementService.normalizeOutboundStatus(m.get("数据状态")), date(m.get("发货日期")), date(m.get("业务日期")), customerId,
resolvedCustomerName, m.get("选择经销商"), salespersonId, employeeId, m.get("业务员(必填)"), m.get("业务归属"), m.get("地区"),
m.get("联系方式"), m.get("客户详细地址"), m.get("选择运输方式"), bool(m.get("是否包邮")), decimal(m.get("运杂费")),
m.get("选择出库仓库"), warehouseId, m.get("仓管员"), m.get("客户付款状态"), m.get("订单备注"), m.get("收款备注"),
decimal(m.get("采购金额合计")), decimal(m.get("销售金额合计")), decimal(m.get("利润金额")), decimal(m.get("已收款金额")),
decimal(m.get("已收款金额")), decimal(m.get("未收款金额")), decimal(m.get("已开票金额")), decimal(m.get("未开票金额")), m.get("开票员"),
m.get("数据标题"), m.get("所属部门(必填)"), m.get("创建人(必填)"), dateTime(m.get("创建时间(必填)")),
dateTime(m.get("修改时间(必填)")), m.get("附件"), g.hash());
Long id = existingId(FactType.OUTBOUND, g);
if (id == null) throw new IllegalStateException("出库单写入后未能定位（external_id=" + g.externalId() + ", 单号=" + g.key() + "）");
jdbc.update("DELETE FROM sales_outbound_items WHERE sales_outbound_id=?", id);
for (Map<String, String> row : g.rows()) {
if (empty(row.get("出库产品明细.数据ID(不可修改)")) && empty(row.get("出库产品明细.选择产品")) && empty(row.get("出库产品明细.产品名称"))) continue;
// 氚云误点空行兜底：空明细行可能仍带数据ID（GUID），仅靠上面的三字段全空判断拦不住。
// 2026-09-17 第二版：销售总价常带 "0"/"0.00"（非空字符串），上一版要求金额字段也为空串，
// 拦不住「全空但销售总价=0」的行（实测 CK20260824107938 序号4 / CK20260817107371 序号2 重导复活）。
// 改为按解析语义判定：产品四要素+数量全空，且销售总价为空或等于 0，即整行垃圾。
if (blankDetailRow(row)) continue;
String category = row.get("出库产品明细.类目");
String name = first(row,"出库产品明细.选择产品","出库产品明细.产品名称");
String lineType = category != null && category.contains("费用") ? (name != null && name.contains("运费") ? "FREIGHT" : "SERVICE") : "PRODUCT";
String productCode = row.get("出库产品明细.产品编号");
String specification = row.get("出库产品明细.规格型号");
String unit = row.get("出库产品明细.单位");
Long salesProductId = "PRODUCT".equals(lineType)
        ? sourceIdentityService.resolveProduct(productCode, name, specification, unit)
        : null;
jdbc.update("""
INSERT INTO sales_outbound_items(sales_outbound_id,external_id,sequence_no,external_title,line_type,category,
product_code,sales_product_id,product_name,specification,unit,quantity,sales_unit_price,sales_total,purchase_unit_price,
purchase_total,source_profit,stock_quantity,remark,image_ref,attachment_info)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""", id, row.get("出库产品明细.数据ID(不可修改)"), integer(row.get("出库产品明细.序号")),
row.get("出库产品明细.数据标题"), lineType, category, productCode, salesProductId, name,
specification, unit, decimal(row.get("出库产品明细.出库数量")),
decimal(row.get("出库产品明细.销售单价")), decimal(row.get("出库产品明细.销售总价")),
decimal(row.get("出库产品明细.采购单价")), decimal(row.get("出库产品明细.采购总价")),
decimal(row.get("出库产品明细.单产品利润")), decimal(row.get("出库产品明细.库存数量")),
row.get("出库产品明细.产品备注"), row.get("出库产品明细.产品图片"), row.get("出库产品明细.附件"));
}

    enrichCustomerFromOutbound(customerId, m);
    enrichCustomerFromProduction(customerId, m);
    saveOutboundPaymentSnapshots(id, g);
    // 系统推断与人工确认分栏保存：重复导入仅刷新推断，不覆盖人工结论和原单关联。
    outboundNatureService.refreshInference(id);
    if ("CONFIRMED".equals(SalesOutboundSettlementService.normalizeOutboundStatus(m.get("数据状态")))) {
        outboundProfitService.recalculate(id, "氚云导入联动");
    }
    return id;
}

private Long h3WarehouseId(String sourceName) {
    String name = clean(sourceName);
    if (name == null) return null;
    List<Long> mapped = jdbc.query("""
        SELECT warehouse_id FROM h3_warehouse_name_mappings WHERE TRIM(source_name)=?
        """, (rs,n) -> rs.getLong(1), name);
    if (mapped.size() == 1) return mapped.getFirst();
    List<Long> exact = jdbc.query("SELECT id FROM warehouses WHERE active=1 AND TRIM(warehouse_name)=?",
            (rs,n) -> rs.getLong(1), name);
    return exact.size() == 1 ? exact.getFirst() : null;
}

private void syncUnchangedOutbound(Long outboundId, Group g) {
if (outboundId == null) return;
jdbc.update("UPDATE sales_outbounds SET employee_id=? WHERE id=? AND employee_id IS NULL", memberEmployeeId(g.main().get("业务员(必填)")), outboundId);
BigDecimal sourceReceived = decimal(g.main().get("已收款金额"));
BigDecimal sourceOutstanding = decimal(g.main().get("未收款金额"));
if (sourceReceived != null || sourceOutstanding != null) {
jdbc.update("UPDATE sales_outbounds SET received_amount=COALESCE(?,received_amount),source_received_amount=COALESCE(?,source_received_amount),outstanding_amount=COALESCE(?,outstanding_amount) WHERE id=?",
        sourceReceived, sourceReceived, sourceOutstanding, outboundId);
}
settlementService.refreshOutboundSnapshotById(outboundId);
outboundNatureService.refreshInference(outboundId);
if ("CONFIRMED".equals(SalesOutboundSettlementService.normalizeOutboundStatus(g.main().get("数据状态")))) {
    outboundProfitService.recalculate(outboundId, "氚云导入联动");
}
}

/**
 * 销售出库单 rows 中嵌入了「收款详细」子表（和出库产品明细做笛卡尔积展开，
 * 产品明细为 NULL 的行仍保留收款详细字段）。按收款详细.数据ID去重后，
 * 写入 sales_outbound_payment_snapshots（migration_batch='LIVE_IMPORT'）。
 *
 * <p>业务模型（2026-08-29 财务确认）：出库「收款详细」子表是财务审核通过后
 * 生成的收款记录，是回款计量的唯一权威事实；收款记录/多订单结清申请（receipts）
 * 仅是审核前的流程单据，不直接参与回款计量。本方法即该权威事实的实时导入入口。
 */
private void saveOutboundPaymentSnapshots(Long outboundId, Group g) {
    Map<String, String> m = g.main();
    Map<String, Map<String, String>> dedup = new LinkedHashMap<>();
    for (Map<String, String> row : g.rows()) {
        String recvExtId = row.get("收款详细.数据ID(不可修改)");
        if (empty(recvExtId)) continue;
        String seq = first(row, "收款详细.序号");
        if (empty(seq)) seq = "1";
        // 笛卡尔积中同一笔收款会重复出现，只要任意一条有金额即可
        Map<String, String> existing = dedup.get(recvExtId);
        if (existing == null || (empty(existing.get("收款详细.收款金额")) && !empty(row.get("收款详细.收款金额")))) {
            dedup.put(recvExtId, row);
        }
    }
    jdbc.update("DELETE FROM sales_outbound_payment_snapshots WHERE sales_outbound_id=? AND migration_batch='LIVE_IMPORT'",outboundId);
    if (dedup.isEmpty()) return;
    String createdBy = m.get("创建人(必填)");
    Timestamp createdAt = dateTime(m.get("创建时间(必填)"));
    String outboundNo = g.key();
    String sourceStatus = m.get("数据状态");
    for (Map<String, String> row : dedup.values()) {
        String recvExtId = row.get("收款详细.数据ID(不可修改)");
        BigDecimal recvAmt = decimal(row.get("收款详细.收款金额"));
        // 零金额也是源记录；负数可能是退款或抵扣，不能静默丢弃。
        if (recvAmt == null) continue;
        Date recvDate = date(first(row, "收款详细.收款日期", "发货日期"));
        String method = first(row, "收款详细.收款方式");
        String seq = first(row, "收款详细.序号", "1");
        String receiptNo = outboundNo + "#R" + seq;
        String snapshotStatus = "已生效".equals(sourceStatus) ? "CONFIRMED" : "PENDING";
        String composedExtId = "OE:" + recvExtId;
        jdbc.update("""
            INSERT INTO sales_outbound_payment_snapshots(
                sales_outbound_id,installment_index,legacy_receipt_id,legacy_external_source,
                legacy_external_id,legacy_receipt_no,legacy_status,receipt_date,received_amount,
                payment_method,source_created_by,source_created_at,remark,migration_batch)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'LIVE_IMPORT')
            ON DUPLICATE KEY UPDATE legacy_receipt_id=COALESCE(legacy_receipt_id, VALUES(legacy_receipt_id)),
                legacy_external_source=VALUES(legacy_external_source),legacy_external_id=VALUES(legacy_external_id),
                legacy_receipt_no=VALUES(legacy_receipt_no),legacy_status=VALUES(legacy_status),
                receipt_date=VALUES(receipt_date),received_amount=VALUES(received_amount),
                payment_method=VALUES(payment_method),source_created_by=VALUES(source_created_by),
                source_created_at=VALUES(source_created_at),remark=VALUES(remark),migration_batch='LIVE_IMPORT'
            """, outboundId, integer(seq) == null ? 1 : integer(seq), null, "H3YUN_OUTBOUND_SNAPSHOT",
            composedExtId, receiptNo, snapshotStatus, recvDate, recvAmt, method, createdBy, createdAt,
            m.get("收款备注"));
    }
}

// ==================== P0 供应商主数据 + 采购入库（2026-09-17，见 docs/氚云新增7表ERP适用性梳理与导入设计-20260917.md §2.3） ====================

/**
 * 保存供应商主数据行。
 *
 * <p>幂等键 = 外部ID（氚云数据ID UUID）。供应商无业务编号，主档可能改名/换 UUID：
 * 外部ID 未命中时再按归一化名称唯一索引定位，命中即自愈到同一主档行（UPDATE 而非新建）。
 */
private Long saveSupplier(Group g) {
    Map<String, String> m = g.main();
    String rawName = first(m, "供应商名称");
    if (rawName == null) throw new IllegalArgumentException("供应商名称为空（数据ID=" + g.externalId() + "）");
    String normalized = SupplierNameNormalizer.normalize(rawName);
    if (normalized.isEmpty()) throw new IllegalArgumentException("供应商名称归一化后为空：" + rawName);
    List<Long> byExt = jdbc.query(
            "SELECT id FROM h3_supplier_catalog WHERE external_source='H3YUN' AND external_id=?",
            (rs, n) -> rs.getLong(1), g.externalId());
    List<Long> byName = jdbc.query("SELECT id FROM h3_supplier_catalog WHERE normalized_name=?",
            (rs, n) -> rs.getLong(1), normalized);
    Long id = byExt.isEmpty() ? (byName.isEmpty() ? null : byName.getFirst()) : byExt.getFirst();
    String status = clean(m.get("供应商状态"));
    boolean internal = SupplierNameNormalizer.isInternal(normalized);
    boolean dirty = SupplierNameNormalizer.isDirty(normalized);
    Object[] args = new Object[]{g.externalId(), rawName, normalized, status, internal ? 1 : 0, dirty ? 1 : 0,
            clean(m.get("对私开户名")), clean(m.get("对私银行账号")), clean(m.get("对私开户行")),
            clean(m.get("对公开户名")), clean(m.get("对公银行账号")), clean(m.get("对公开户行")),
            clean(m.get("联系人")), clean(m.get("联系方式")), clean(m.get("供应商地址")), m.get("备注"),
            m.get("数据状态"), g.externalId(), m.get("数据标题"),
            first(m, "拥有者(必填)", "拥有者"), first(m, "所属部门(必填)", "所属部门"),
            first(m, "创建人", "创建人(必填)"),
            dateTime(first(m, "创建时间", "创建时间(必填)")),
            dateTime(first(m, "修改时间", "修改时间(必填)")), g.hash()};
    if (id == null) {
        jdbc.update("""
            INSERT INTO h3_supplier_catalog(external_source,external_id,name,normalized_name,supplier_status,
            is_internal,is_dirty_value,private_bank_name,private_bank_account,private_bank_branch,
            public_bank_name,public_bank_account,public_bank_branch,contact_name,contact_phone,address,remark,
            data_status,source_object_id,source_title,owner_name,department_name,created_by_name,
            source_created_at,source_updated_at,source_hash,last_synced_at)
            VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
            """, args);
    } else {
        jdbc.update("""
            UPDATE h3_supplier_catalog SET external_id=?,name=?,normalized_name=?,supplier_status=?,
            is_internal=?,is_dirty_value=?,private_bank_name=?,private_bank_account=?,private_bank_branch=?,
            public_bank_name=?,public_bank_account=?,public_bank_branch=?,contact_name=?,contact_phone=?,
            address=?,remark=?,data_status=?,source_object_id=?,source_title=?,owner_name=?,department_name=?,
            created_by_name=?,source_created_at=?,source_updated_at=?,source_hash=?,last_synced_at=NOW()
            WHERE id=?
            """, append(args, id));
    }
    return id;
}

/**
 * 保存采购入库主单 + 明细（整单删除重插，行级 UUID 幂等）。
 *
 * <p>按 LQ 2026-09-17 决策只落事实层：不写库存余额/流水，不做付款核销（导出件
 * 已付款恒 0、已收票全空，见设计文档 §1.2 实测）。供应商解析失败记原文、supplier_id 置空，
 * 由 missing_customer_count 暴露，不静默新建供应商。
 */
private Long savePurchaseReceipt(Group g) {
    Map<String, String> m = g.main();
    String supplierRef = first(m, "选择供应商");
    Long supplierId = resolveSupplierId(supplierRef, true);
    String warehouseName = clean(m.get("选择入库仓库"));
    Long warehouseId = h3WarehouseId(warehouseName);
    String address = clean(m.get("供应商地址"));
    // 空壳 JSON（{"adcode":"","adname":"","Detail":""}，实测 5278 行）视为空，防地址完整度虚高
    if (address != null && address.startsWith("{")) {
        String stripped = address.replaceAll("\"[a-zA-Z]+\"", "").replaceAll("[{}:,\\s]", "");
        if (stripped.isEmpty()) address = null;
    }
    if (existingId(FactType.PURCHASE_IN, g) == null) {
        clearConflicts(FactType.PURCHASE_IN, g);
    }
    jdbc.update("""
        INSERT INTO purchase_receipts(external_source,external_id,source_no,purchase_date,warehouse_id,warehouse_name,
        owner_company,supplier_id,supplier_name_raw,contact_name,contact_phone,supplier_address,
        total_amount,total_quantity,expected_arrival_date,keeper,unpaid_amount,uninvoiced_amount,paid_amount,
        payment_status,order_remark,payment_remark,data_status,source_title,owner_name,department_name,
        created_by_name,source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE source_no=VALUES(source_no),purchase_date=VALUES(purchase_date),
        warehouse_id=VALUES(warehouse_id),warehouse_name=VALUES(warehouse_name),owner_company=VALUES(owner_company),
        supplier_id=VALUES(supplier_id),supplier_name_raw=VALUES(supplier_name_raw),contact_name=VALUES(contact_name),
        contact_phone=VALUES(contact_phone),supplier_address=VALUES(supplier_address),total_amount=VALUES(total_amount),
        total_quantity=VALUES(total_quantity),expected_arrival_date=VALUES(expected_arrival_date),keeper=VALUES(keeper),
        unpaid_amount=VALUES(unpaid_amount),uninvoiced_amount=VALUES(uninvoiced_amount),paid_amount=VALUES(paid_amount),
        payment_status=VALUES(payment_status),order_remark=VALUES(order_remark),payment_remark=VALUES(payment_remark),
        data_status=VALUES(data_status),source_title=VALUES(source_title),owner_name=VALUES(owner_name),
        department_name=VALUES(department_name),created_by_name=VALUES(created_by_name),
        source_created_at=VALUES(source_created_at),source_updated_at=VALUES(source_updated_at),
        source_hash=VALUES(source_hash),last_synced_at=NOW()
        """,
        g.externalId(), g.key(), date(m.get("采购日期")), warehouseId, warehouseName,
        first(m, "所属部门(必填)", "所属部门"), supplierId, supplierRef,
        clean(m.get("联系人")), clean(m.get("联系方式")), address,
        normDecimal(m.get("合计采购金额")), normDecimal(m.get("合计采购数量")),
        date(m.get("需到货日期")), clean(m.get("仓管员")),
        normDecimal(m.get("未付款金额")), normDecimal(m.get("未收票金额")), normDecimal(m.get("已付款金额")),
        m.get("付款给供应商状态"), m.get("订单备注"), m.get("付款备注"), m.get("数据状态"),
        m.get("数据标题"), first(m, "拥有者(必填)", "拥有者"), first(m, "所属部门(必填)", "所属部门"),
        first(m, "创建人(必填)", "创建人"), dateTime(m.get("创建时间(必填)")),
        dateTime(first(m, "修改时间(必填)", "修改时间")), g.hash());
    Long id = existingId(FactType.PURCHASE_IN, g);
    if (id == null) throw new IllegalStateException("采购入库写入后未能定位（单号=" + g.key() + "）");
    jdbc.update("DELETE FROM purchase_receipt_items WHERE purchase_receipt_id=?", id);
    for (Map<String, String> row : g.rows()) {
        if (empty(first(row, "产品明细.产品编号", "产品明细.选择产品"))
                && empty(row.get("产品明细.入库数量(必填)"))) continue;
        String itemExt = clean(row.get("产品明细.数据ID(不可修改)"));
        String taskRaw = row.get("产品明细.选择生产任务单");
        // 只取首段编号（形如 SCGD20260005638    名称    规格…），关联不上记原文、不阻断
        String taskNo = null;
        if (!empty(taskRaw)) {
            String token = taskRaw.trim().split("\\s+")[0];
            taskNo = token.isEmpty() ? null : (token.length() > 64 ? token.substring(0, 64) : token);
        }
        jdbc.update("""
            INSERT INTO purchase_receipt_items(purchase_receipt_id,source_item_id,line_no,product_code,product_name,
            category,spec,unit,quantity,purchase_price,amount,stock_quantity,is_mold,has_process,task_salesperson,
            production_order_no,production_order_raw,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, itemExt, integer(row.get("产品明细.序号")), row.get("产品明细.产品编号"),
            first(row, "产品明细.选择产品"), row.get("产品明细.产品类目（隐）"), row.get("产品明细.规格型号"),
            row.get("产品明细.单位"), normDecimal(row.get("产品明细.入库数量(必填)")),
            normDecimal(row.get("产品明细.采购价格")), normDecimal(row.get("产品明细.采购金额小计")),
            normDecimal(row.get("产品明细.库存数量")), parseFlag(row.get("产品明细.是否为模具")),
            row.get("产品明细.是否有制程"), row.get("产品明细.任务单业务员"), taskNo, taskRaw,
            first(row, "产品明细.产品备注", "产品明细.采购入库备注"));
    }
    return id;
}

/**
 * 供应商引用解析：别名表原文命中 → 剥状态后缀+归一化匹配主档；
 * 命中且 heal=true 时把原文落别名表（自愈，下次直接命中）。未命中返回 null（不静默新建供应商）。
 */
private Long resolveSupplierId(String rawRef, boolean heal) {
    String cleaned = clean(rawRef);
    if (cleaned == null) return null;
    String rawText = cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    List<Long> byAlias = jdbc.query("SELECT supplier_id FROM h3_supplier_aliases WHERE raw_text=?",
            (rs, n) -> rs.getLong(1), rawText);
    if (!byAlias.isEmpty()) return byAlias.getFirst();
    String normalized = SupplierNameNormalizer.normalize(cleaned);
    if (normalized.isEmpty()) return null;
    List<Long> sup = jdbc.query("SELECT id FROM h3_supplier_catalog WHERE normalized_name=?",
            (rs, n) -> rs.getLong(1), normalized);
    if (sup.isEmpty()) return null;
    if (heal) {
        jdbc.update("INSERT IGNORE INTO h3_supplier_aliases(supplier_id,raw_text,source) VALUES(?,?,?)",
                sup.getFirst(), rawText, "PURCHASE_IN");
    }
    return sup.getFirst();
}

/** 采购入库校验告警（预览与 confirm 共用）：Σ明细≠主表合计、缺采购价、供应商未解析。 */
private List<String> purchaseWarnings(Group g, boolean heal) {
    List<String> w = new ArrayList<>();
    Map<String, String> m = g.main();
    java.math.BigDecimal sumAmount = java.math.BigDecimal.ZERO;
    java.math.BigDecimal sumQty = java.math.BigDecimal.ZERO;
    boolean anyAmount = false, anyQty = false;
    int noPrice = 0;
    for (Map<String, String> row : g.rows()) {
        java.math.BigDecimal amt = normDecimal(row.get("产品明细.采购金额小计"));
        java.math.BigDecimal qty = normDecimal(row.get("产品明细.入库数量(必填)"));
        if (amt != null) { sumAmount = sumAmount.add(amt); anyAmount = true; }
        if (qty != null) { sumQty = sumQty.add(qty); anyQty = true; }
        if (qty != null && qty.signum() > 0) {
            java.math.BigDecimal price = normDecimal(row.get("产品明细.采购价格"));
            if (price == null || price.signum() <= 0) noPrice++;
        }
    }
    java.math.BigDecimal totalAmount = normDecimal(m.get("合计采购金额"));
    if (anyAmount && totalAmount != null
            && sumAmount.subtract(totalAmount).abs().compareTo(SUM_TOLERANCE) > 0) {
        w.add("Σ明细金额 " + sumAmount.stripTrailingZeros().toPlainString()
                + " ≠ 主表合计 " + totalAmount.stripTrailingZeros().toPlainString());
    }
    java.math.BigDecimal totalQty = normDecimal(m.get("合计采购数量"));
    if (anyQty && totalQty != null
            && sumQty.subtract(totalQty).abs().compareTo(SUM_TOLERANCE) > 0) {
        w.add("Σ明细数量 " + sumQty.stripTrailingZeros().toPlainString()
                + " ≠ 主表合计 " + totalQty.stripTrailingZeros().toPlainString());
    }
    if (noPrice > 0) w.add(noPrice + " 行缺采购价");
    String ref = first(m, "选择供应商");
    if (!empty(ref) && resolveSupplierId(ref, heal) == null) w.add("供应商未解析：" + ref);
    return w;
}

/**
 * 保存其他入库/其他出库主单 + 明细（整单删除重插，行级 UUID 幂等）。
 *
 * <p>两表同构合并落一张表 + direction 位：主表差异列按方向取不同中文列名
 * （其他入库：入库日期/选择入库仓库；其他出库：出库日期/业务员/选择出库仓库/合计数量/合计金额）。
 * ⚠️ 明细子表前缀两份导出件都是「其他出库明细.」（氚云表单复制串名），一律按该前缀取值。
 *
 * <p>按 LQ 2026-09-17 决策只落事实层：不写库存余额/流水（余额仍由台账快照合并驱动，L3 冻结）。
 */
private Long saveOtherMovement(Group g, boolean inbound) {
    FactType type = inbound ? FactType.OTHER_IN : FactType.OTHER_OUT;
    String direction = inbound ? "IN" : "OUT";
    Map<String, String> m = g.main();
    String warehouseName = clean(inbound ? m.get("选择入库仓库") : m.get("选择出库仓库"));
    Long warehouseId = h3WarehouseId(warehouseName);
    if (existingId(type, g) == null) {
        clearConflicts(type, g);
    }
    jdbc.update("""
        INSERT INTO h3_other_stock_movements(direction,external_source,external_id,source_no,movement_date,
        warehouse_id,warehouse_name,keeper,salesman,total_quantity,total_amount,remark,data_status,source_title,
        owner_name,department_name,created_by_name,source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES(?,'H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE direction=VALUES(direction),source_no=VALUES(source_no),
        movement_date=VALUES(movement_date),warehouse_id=VALUES(warehouse_id),warehouse_name=VALUES(warehouse_name),
        keeper=VALUES(keeper),salesman=VALUES(salesman),total_quantity=VALUES(total_quantity),
        total_amount=VALUES(total_amount),remark=VALUES(remark),data_status=VALUES(data_status),
        source_title=VALUES(source_title),owner_name=VALUES(owner_name),department_name=VALUES(department_name),
        created_by_name=VALUES(created_by_name),source_created_at=VALUES(source_created_at),
        source_updated_at=VALUES(source_updated_at),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """,
        direction, g.externalId(), g.key(), date(inbound ? m.get("入库日期") : m.get("出库日期")),
        warehouseId, warehouseName, clean(m.get("仓管员")), clean(m.get("业务员")),
        normDecimal(m.get("合计数量")), normDecimal(m.get("合计金额")),
        m.get("备注"), m.get("数据状态"), m.get("数据标题"),
        first(m, "拥有者(必填)", "拥有者"), first(m, "所属部门(必填)", "所属部门"),
        first(m, "创建人(必填)", "创建人"),
        dateTime(first(m, "创建时间(必填)", "创建时间")),
        dateTime(first(m, "修改时间(必填)", "修改时间")), g.hash());
    Long id = existingId(type, g);
    if (id == null) throw new IllegalStateException("其他入/出库写入后未能定位（单号=" + g.key() + "）");
    jdbc.update("DELETE FROM h3_other_stock_movement_items WHERE movement_id=?", id);
    for (Map<String, String> row : g.rows()) {
        if (empty(first(row, "其他出库明细.产品编号", "其他出库明细.选择产品"))
                && empty(first(row, "其他出库明细.入库数量", "其他出库明细.出库数量(必填)"))) continue;
        jdbc.update("""
            INSERT INTO h3_other_stock_movement_items(movement_id,source_item_id,line_no,product_code,product_name,
            category,spec,unit,quantity,stock_quantity,purchase_price,amount,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, clean(row.get("其他出库明细.数据ID(不可修改)")), integer(row.get("其他出库明细.序号")),
            row.get("其他出库明细.产品编号"),
            first(row, "其他出库明细.选择产品", "其他出库明细.产品名称（隐）"),
            first(row, "其他出库明细.类目", "其他出库明细.产品类目（隐）"),
            row.get("其他出库明细.规格"), row.get("其他出库明细.单位"),
            normDecimal(first(row, "其他出库明细.入库数量", "其他出库明细.出库数量(必填)")),
            normDecimal(row.get("其他出库明细.库存数量")),
            normDecimal(row.get("其他出库明细.采购价格")), normDecimal(row.get("其他出库明细.采购总价")),
            row.get("其他出库明细.产品备注"));
    }
    return id;
}

/**
 * 其他入/出库校验告警（预览与 confirm 共用）：仓库未匹配、Σ明细数量≠主表合计、缺单价。
 * 与 {@link #purchaseWarnings} 同风格：返回中文告警，由调用方按前缀归类计数。
 */
private List<String> otherMovementWarnings(Group g, boolean inbound) {
    List<String> w = new ArrayList<>();
    Map<String, String> m = g.main();
    String warehouseName = clean(inbound ? m.get("选择入库仓库") : m.get("选择出库仓库"));
    if (warehouseName == null) {
        w.add("仓库为空");
    } else if (h3WarehouseId(warehouseName) == null) {
        w.add("仓库未匹配：" + warehouseName);
    }
    java.math.BigDecimal sumQty = java.math.BigDecimal.ZERO;
    boolean anyQty = false;
    int noPrice = 0;
    for (Map<String, String> row : g.rows()) {
        java.math.BigDecimal qty = normDecimal(first(row, "其他出库明细.入库数量", "其他出库明细.出库数量(必填)"));
        if (qty != null) { sumQty = sumQty.add(qty); anyQty = true; }
        if (qty != null && qty.signum() > 0) {
            java.math.BigDecimal price = normDecimal(row.get("其他出库明细.采购价格"));
            if (price == null || price.signum() <= 0) noPrice++;
        }
    }
    // 主表合计数量列仅其他出库有
    java.math.BigDecimal totalQty = normDecimal(m.get("合计数量"));
    if (anyQty && totalQty != null
            && sumQty.subtract(totalQty).abs().compareTo(SUM_TOLERANCE) > 0) {
        w.add("Σ明细数量 " + sumQty.stripTrailingZeros().toPlainString()
                + " ≠ 主表合计 " + totalQty.stripTrailingZeros().toPlainString());
    }
    if (noPrice > 0) w.add(noPrice + " 行缺采购价");
    return w;
}

/**
 * 库存调拨落库（P2，2026-09-17）：一单两仓两仓管员，落 h3_inventory_transfers + 明细。
 *
 * <p>与 {@link #saveOtherMovement} 同口径：只落事实层，不动库存余额/流水（L3 冻结）。
 * 状态（已生效/草稿/已取消）只标 data_status 不丢弃，正式口径由下游按 status 过滤。
 *
 * <p>⚠️ 「仓管员(调入)」列名来自 {@link #locateHeader} 对重名列的重命名（源表第 6 列）。
 */
private Long saveTransfer(Group g) {
    Map<String, String> m = g.main();
    String outName = clean(first(m, "选择调出仓库", "调出仓库（隐）"));
    String inName = clean(first(m, "选择调入仓库", "调入仓库（隐）"));
    if (existingId(FactType.TRANSFER, g) == null) {
        clearConflicts(FactType.TRANSFER, g);
    }
    jdbc.update("""
        INSERT INTO h3_inventory_transfers(external_source,external_id,source_no,transfer_date,
        out_warehouse_id,out_warehouse_name,out_keeper,in_warehouse_id,in_warehouse_name,in_keeper,
        remark,data_status,source_title,owner_name,department_name,created_by_name,
        source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE source_no=VALUES(source_no),transfer_date=VALUES(transfer_date),
        out_warehouse_id=VALUES(out_warehouse_id),out_warehouse_name=VALUES(out_warehouse_name),
        out_keeper=VALUES(out_keeper),in_warehouse_id=VALUES(in_warehouse_id),
        in_warehouse_name=VALUES(in_warehouse_name),in_keeper=VALUES(in_keeper),
        remark=VALUES(remark),data_status=VALUES(data_status),source_title=VALUES(source_title),
        owner_name=VALUES(owner_name),department_name=VALUES(department_name),
        created_by_name=VALUES(created_by_name),source_created_at=VALUES(source_created_at),
        source_updated_at=VALUES(source_updated_at),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """,
        g.externalId(), g.key(), date(m.get("调拨日期")),
        h3WarehouseId(outName), outName, clean(m.get("仓管员")),
        h3WarehouseId(inName), inName, clean(m.get("仓管员(调入)")),
        m.get("备注"), m.get("数据状态"), m.get("数据标题"),
        first(m, "拥有者(必填)", "拥有者"), first(m, "所属部门(必填)", "所属部门"),
        first(m, "创建人(必填)", "创建人"),
        dateTime(first(m, "创建时间(必填)", "创建时间")),
        dateTime(first(m, "修改时间(必填)", "修改时间")), g.hash());
    Long id = existingId(FactType.TRANSFER, g);
    if (id == null) throw new IllegalStateException("库存调拨写入后未能定位（单号=" + g.key() + "）");
    jdbc.update("DELETE FROM h3_inventory_transfer_items WHERE transfer_id=?", id);
    for (Map<String, String> row : g.rows()) {
        if (empty(first(row, "调拨明细.产品编号", "调拨明细.产品名称"))
                && empty(row.get("调拨明细.调拨数量"))) continue;
        jdbc.update("""
            INSERT INTO h3_inventory_transfer_items(transfer_id,source_item_id,line_no,product_code,product_name,
            category,spec,unit,quantity,out_stock_quantity,judge_raw,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, clean(row.get("调拨明细.数据ID(不可修改)")), integer(row.get("调拨明细.序号")),
            row.get("调拨明细.产品编号"),
            first(row, "调拨明细.产品名称", "调拨明细.产品名称（隐）"),
            first(row, "调拨明细.类目", "调拨明细.产品类目（隐）"),
            row.get("调拨明细.规格"), row.get("调拨明细.单位"),
            normDecimal(row.get("调拨明细.调拨数量")),
            normDecimal(row.get("调拨明细.调出仓库数量")),
            // 「判断（隐）」实测恒 0、语义未知 → 存原文不解读（设计文档 §2.1）
            clean(row.get("调拨明细.判断（隐）")),
            row.get("调拨明细.产品备注"));
    }
    return id;
}

/**
 * 库存调拨校验告警（预览与 confirm 共用）：仓库未匹配、调出仓=调入仓、
 * 调拨数量 > 调出仓库数量快照、状态非「已生效」（草稿/已取消只标记不进正式口径）。
 */
private List<String> transferWarnings(Group g) {
    List<String> w = new ArrayList<>();
    Map<String, String> m = g.main();
    String outName = clean(first(m, "选择调出仓库", "调出仓库（隐）"));
    String inName = clean(first(m, "选择调入仓库", "调入仓库（隐）"));
    if (outName == null) w.add("调出仓库为空");
    else if (h3WarehouseId(outName) == null) w.add("调出仓库未匹配：" + outName);
    if (inName == null) w.add("调入仓库为空");
    else if (h3WarehouseId(inName) == null) w.add("调入仓库未匹配：" + inName);
    if (outName != null && outName.equals(inName)) w.add("调出仓=调入仓：" + outName);
    int overStock = 0;
    for (Map<String, String> row : g.rows()) {
        java.math.BigDecimal qty = normDecimal(row.get("调拨明细.调拨数量"));
        java.math.BigDecimal snap = normDecimal(row.get("调拨明细.调出仓库数量"));
        if (qty != null && snap != null && qty.subtract(snap).compareTo(SUM_TOLERANCE) > 0) overStock++;
    }
    if (overStock > 0) w.add(overStock + " 行调拨数量 > 调出仓库数量快照");
    String status = clean(m.get("数据状态"));
    if (status != null && !"已生效".equals(status)) w.add("数据状态=" + status + "（不进正式业务口径）");
    return w;
}

/** 调拨预览「对象」列：调出仓 → 调入仓（任一侧缺失用 ? 占位，避免拼出 "null"）。 */
private String transferRoute(Map<String, String> m) {
    String out = first(m, "选择调出仓库", "调出仓库（隐）");
    String in = first(m, "选择调入仓库", "调入仓库（隐）");
    if (out == null && in == null) return null;
    return (out == null ? "?" : out) + " → " + (in == null ? "?" : in);
}

private static final java.math.BigDecimal SUM_TOLERANCE = new java.math.BigDecimal("0.01");

/**
 * 数值归一（浮点残差治理，与 B 域台账口径一致）：
 * |x| < 1e-9 归零；小数超过 6 位四舍五入到 6 位。防止 8.88E-16 类 Excel 浮点噪声
 * 写库后与源数据「永远差一点」、重复导入永不收敛。
 */
private java.math.BigDecimal normDecimal(String v) {
    java.math.BigDecimal d = decimal(v);
    if (d == null) return null;
    if (d.abs().compareTo(new java.math.BigDecimal("0.000000001")) < 0) return java.math.BigDecimal.ZERO;
    return d.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
}

/** 0/1/是/否 → 布尔标记；空或不可识别返回 null。 */
private Integer parseFlag(String v) {
    String s = clean(v);
    if (s == null) return null;
    if (Set.of("1", "是", "true", "Y", "y").contains(s)) return 1;
    if (Set.of("0", "否", "false", "N", "n").contains(s)) return 0;
    return null;
}

private Object[] append(Object[] args, Object extra) {
    Object[] out = new Object[args.length + 1];
    System.arraycopy(args, 0, out, 0, args.length);
    out[args.length] = extra;
    return out;
}

/**
 * P3 开票资料：单表落库（仿 saveSupplier）；44 组同名户名是合法事实 → 只按 external_id（数据ID UUID）幂等。
 * customers 关联只读不动主数据：税号→credit_code 等值唯一命中才绑，其次户名精确等值唯一命中；
 * 未命中不是错误（外协/一次性户名），customer_id 留空、台账列表标记「未关联」。
 */
private Long saveInvoiceProfile(Group g) {
    Map<String, String> m = g.main();
    String rawName = first(m, "户名");
    if (rawName == null) rawName = "";
    String taxNo = clean(m.get("税号"));
    Long customerId = resolveInvoiceProfileCustomer(taxNo, rawName);
    Object[] args = new Object[]{g.externalId(), rawName, SupplierNameNormalizer.normalize(rawName),
            taxNo, clean(m.get("地址")), clean(m.get("电话")),
            clean(m.get("开户行")), clean(m.get("银行账号")), customerId, m.get("数据状态"),
            g.externalId(), m.get("数据标题"),
            first(m, "拥有者(必填)", "拥有者"), first(m, "所属部门(必填)", "所属部门"),
            first(m, "创建人", "创建人(必填)"),
            dateTime(first(m, "创建时间", "创建时间(必填)")),
            dateTime(first(m, "修改时间", "修改时间(必填)")), g.hash()};
    jdbc.update("""
        INSERT INTO h3_customer_invoice_profiles(external_source,external_id,customer_name,normalized_name,
        tax_no,address,phone,bank_name,bank_account,customer_id,data_status,source_object_id,source_title,
        owner_name,department_name,created_by_name,source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE customer_name=VALUES(customer_name),normalized_name=VALUES(normalized_name),
        tax_no=VALUES(tax_no),address=VALUES(address),phone=VALUES(phone),bank_name=VALUES(bank_name),
        bank_account=VALUES(bank_account),customer_id=VALUES(customer_id),data_status=VALUES(data_status),
        source_object_id=VALUES(source_object_id),source_title=VALUES(source_title),owner_name=VALUES(owner_name),
        department_name=VALUES(department_name),created_by_name=VALUES(created_by_name),
        source_created_at=VALUES(source_created_at),source_updated_at=VALUES(source_updated_at),
        source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, args);
    return jdbc.queryForObject(
            "SELECT id FROM h3_customer_invoice_profiles WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
}

private Long resolveInvoiceProfileCustomer(String taxNo, String rawName) {
    if (taxNo != null && !taxNo.isBlank()) {
        List<Long> byTax = jdbc.query("SELECT id FROM customers WHERE credit_code=?",
                (rs, n) -> rs.getLong(1), taxNo);
        if (byTax.size() == 1) return byTax.getFirst();
        if (byTax.size() > 1) return null;
    }
    if (rawName != null && !rawName.isBlank()) {
        List<Long> byName = jdbc.query("SELECT id FROM customers WHERE customer_name=?",
                (rs, n) -> rs.getLong(1), rawName.trim());
        if (byName.size() == 1) return byName.getFirst();
    }
    return null;
}

private String invoiceProfileWarning(Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    String status = m.get("数据状态");
    if (status != null && !"已生效".equals(status.trim())) w.add("数据状态=" + status.trim());
    if (empty(first(m, "户名"))) w.add("户名为空");
    return String.join("；", w);
}

/**
 * 生产报工（2026-09-17 第 15 类）：单表落库（仿 saveInvoiceProfile），幂等键 = 数据ID UUID。
 *
 * <p>粒度 = 生产工单 × 零部件制程（工序完工报工，非材料领料）。关联键 = 生产工单编号（SCGD…）。
 * 无业务单号 → source_no 统一存数据ID UUID 兜底（同开票申请 18 单先例），每行唯一、无冲突清理需求。
 * ⚠️ 列序与占位符逐一对位（坑 10）：29 个 ? 严格按 V311 列序排列，勿跨表抄 VALUES 模板。
 */
private Long saveProductionReport(Group g) {
    Map<String, String> m = g.main();
    Object[] args = new Object[]{
            g.externalId(),                                             // external_id
            g.externalId(),                                             // source_no（无单号 → UUID 兜底）
            dateTime(first(m, "报工时间")),                              // report_time
            clean(m.get("生产工单编号")),                                 // work_order_no
            clean(m.get("生产任务单")),                                   // work_order_text（拼接串原文）
            clean(m.get("零部件流水号")),                                 // component_serial
            first(m, "生产零部件", "零部件文本"),                          // component_name（F0000039 首命中）
            clean(m.get("报工数量")),                                     // report_qty
            clean(m.get("是否超额生产")),                                 // over_flag
            clean(m.get("超额生产数量")),                                 // over_qty
            clean(m.get("订单需求数量")),                                 // order_demand_qty
            clean(m.get("工单剩余需求数量")),                             // remaining_qty
            clean(m.get("报工人")),                                       // reporter（putIfAbsent 首=主报工人）
            clean(m.get("报工人(辅助)")),                                 // reporter_assist（putHeader 改名第二列）
            clean(m.get("报工人所属部门")),                               // reporter_dept
            clean(m.get("产线文本")),                                     // production_line
            clean(m.get("多行文本")),                                     // part_serials_text
            clean(m.get("生产工单中是否有该零部件")),                      // has_component_flag（实测全 0，存原文）
            clean(m.get("入库员")),                                       // stock_in_clerk
            clean(m.get("接单员")),                                       // order_clerk
            clean(m.get("是否自定义报工数量")),                           // is_custom_qty
            m.get("数据状态"),                                            // data_status
            m.get("数据标题"),                                            // data_title
            first(m, "拥有者(必填)", "拥有者"),                            // owner_name
            first(m, "所属部门(必填)", "所属部门"),                        // department_name
            first(m, "创建人", "创建人(必填)"),                            // created_by_name
            dateTime(first(m, "创建时间", "创建时间(必填)")),              // source_created_at
            dateTime(first(m, "修改时间", "修改时间(必填)")),              // source_updated_at
            g.hash()};                                                    // source_hash
    jdbc.update("""
        INSERT INTO h3_production_reports(external_source,external_id,source_no,report_time,work_order_no,
        work_order_text,component_serial,component_name,report_qty,over_flag,over_qty,order_demand_qty,remaining_qty,
        reporter,reporter_assist,reporter_dept,production_line,part_serials_text,has_component_flag,
        stock_in_clerk,order_clerk,is_custom_qty,data_status,data_title,owner_name,department_name,
        created_by_name,source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE source_no=VALUES(source_no),report_time=VALUES(report_time),
        work_order_no=VALUES(work_order_no),work_order_text=VALUES(work_order_text),
        component_serial=VALUES(component_serial),component_name=VALUES(component_name),
        report_qty=VALUES(report_qty),over_flag=VALUES(over_flag),over_qty=VALUES(over_qty),
        order_demand_qty=VALUES(order_demand_qty),remaining_qty=VALUES(remaining_qty),
        reporter=VALUES(reporter),reporter_assist=VALUES(reporter_assist),reporter_dept=VALUES(reporter_dept),
        production_line=VALUES(production_line),part_serials_text=VALUES(part_serials_text),
        has_component_flag=VALUES(has_component_flag),stock_in_clerk=VALUES(stock_in_clerk),
        order_clerk=VALUES(order_clerk),is_custom_qty=VALUES(is_custom_qty),data_status=VALUES(data_status),
        data_title=VALUES(data_title),owner_name=VALUES(owner_name),department_name=VALUES(department_name),
        created_by_name=VALUES(created_by_name),source_created_at=VALUES(source_created_at),
        source_updated_at=VALUES(source_updated_at),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, args);
    return jdbc.queryForObject(
            "SELECT id FROM h3_production_reports WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
}

/**
 * 生产报工告警：缺工单号（无法关联 production_orders）/ 缺工序 / 非已生效。
 * 报工人含 Anonymous（未登录扫码）不告警——是真实报工行为，保留原文。
 */
private String productionReportWarning(Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    String status = m.get("数据状态");
    if (status != null && !"已生效".equals(status.trim())) w.add("数据状态=" + status.trim());
    if (empty(m.get("生产工单编号"))) w.add("生产工单编号为空，无法关联生产工单");
    if (empty(first(m, "生产零部件", "零部件文本"))) w.add("生产零部件（工序）为空");
    return String.join("；", w);
}

// ==================== 进项/销项发票（2026-09-18 第 16 类，税务平台导出） ====================

/**
 * 进项发票：单表 2 382 行（幂等键=数电发票号码）。无修改时间列 → unchanged 全靠 source_hash
 * （税务平台再导出列序稳定，不会触发氚云那类「仅列序变化误报 UPDATE」问题）。
 */
private Long saveInvoiceInput(Group g) {
    Map<String, String> m = g.main();
    jdbc.update("""
        INSERT INTO h3_input_invoices(external_source,external_id,source_no,seller_tax_no,seller_name,
        buyer_tax_no,buyer_name,invoice_date,amount,tax_amount,total_amount,
        invoice_source,invoice_kind,invoice_status,is_positive,risk_level,drawer,remark,
        source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE seller_tax_no=VALUES(seller_tax_no),seller_name=VALUES(seller_name),
        buyer_tax_no=VALUES(buyer_tax_no),buyer_name=VALUES(buyer_name),invoice_date=VALUES(invoice_date),
        amount=VALUES(amount),tax_amount=VALUES(tax_amount),total_amount=VALUES(total_amount),
        invoice_source=VALUES(invoice_source),invoice_kind=VALUES(invoice_kind),
        invoice_status=VALUES(invoice_status),is_positive=VALUES(is_positive),risk_level=VALUES(risk_level),
        drawer=VALUES(drawer),remark=VALUES(remark),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, g.externalId(), g.externalId(),
            clean(m.get("销方识别号")), clean(m.get("销方名称")),
            clean(m.get("购方识别号")), clean(m.get("购买方名称")),
            dateTime(first(m, "开票日期")),
            normDecimal(m.get("金额")), normDecimal(m.get("税额")), normDecimal(m.get("价税合计")),
            clean(m.get("发票来源")), clean(m.get("发票票种")), clean(m.get("发票状态")),
            clean(m.get("是否正数发票")), clean(m.get("发票风险等级")), clean(m.get("开票人")),
            clean(m.get("备注")), g.hash());
    return jdbc.queryForObject(
            "SELECT id FROM h3_input_invoices WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
}

/**
 * 销项发票：主表 4 174 张 + 收款子表 512 行（组内无票号行，整删重插）。
 * ⚠️ source_item_id = 发票号#组内序号（源文件无数据ID/UUID，同文件行序稳定 → 幂等成立）。
 * 收款字段只落校验源（收款权威=销售出库，§9.3 拍板），不写 receipts、不回写任何余额。
 */
private Long saveInvoiceSales(Group g) {
    Map<String, String> m = g.main();
    jdbc.update("""
        INSERT INTO h3_sales_invoices(external_source,external_id,source_no,seller_tax_no,seller_name,
        buyer_tax_no,buyer_name,invoice_date,amount,tax_amount,total_amount,
        receipt_date,payment_method,receipt_amount,unreceived_amount,salesman,remark,remark_platform,
        invoice_source,invoice_kind,invoice_status,is_positive,risk_level,drawer,
        source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE seller_tax_no=VALUES(seller_tax_no),seller_name=VALUES(seller_name),
        buyer_tax_no=VALUES(buyer_tax_no),buyer_name=VALUES(buyer_name),invoice_date=VALUES(invoice_date),
        amount=VALUES(amount),tax_amount=VALUES(tax_amount),total_amount=VALUES(total_amount),
        receipt_date=VALUES(receipt_date),payment_method=VALUES(payment_method),
        receipt_amount=VALUES(receipt_amount),unreceived_amount=VALUES(unreceived_amount),
        salesman=VALUES(salesman),remark=VALUES(remark),remark_platform=VALUES(remark_platform),
        invoice_source=VALUES(invoice_source),invoice_kind=VALUES(invoice_kind),
        invoice_status=VALUES(invoice_status),is_positive=VALUES(is_positive),risk_level=VALUES(risk_level),
        drawer=VALUES(drawer),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, g.externalId(), g.externalId(),
            clean(m.get("销方识别号")), clean(m.get("销方名称")),
            clean(m.get("购方识别号")), clean(m.get("购买方名称")),
            dateTime(first(m, "开票日期")),
            normDecimal(m.get("金额")), normDecimal(m.get("税额")), normDecimal(m.get("价税合计")),
            date(first(m, "收款日期")), clean(m.get("付款方式")),
            normDecimal(m.get("收款金额")), normDecimal(m.get("未收款金额")),
            clean(m.get("业务员")), clean(m.get("备注")), clean(m.get("备注(平台)")),
            clean(m.get("发票来源")), clean(m.get("发票票种")), clean(m.get("发票状态")),
            clean(m.get("是否正数发票")), clean(m.get("发票风险等级")), clean(m.get("开票人")),
            g.hash());
    Long invoiceId = jdbc.queryForObject(
            "SELECT id FROM h3_sales_invoices WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
    jdbc.update("DELETE FROM h3_sales_invoice_receipts WHERE invoice_id=?", invoiceId);
    int seq = 0;
    for (Map<String, String> row : g.rows()) {
        if (!empty(row.get("数电发票号码"))) continue; // 主行
        seq++;
        jdbc.update("""
            INSERT INTO h3_sales_invoice_receipts(invoice_id,source_item_id,receipt_date,payment_method,
            receipt_amount,salesman,remark) VALUES(?,?,?,?,?,?,?)
            """, invoiceId, g.externalId() + "#" + seq,
                date(first(row, "收款日期")), clean(row.get("付款方式")),
                normDecimal(row.get("收款金额")), clean(row.get("业务员")), clean(row.get("备注")));
    }
    return invoiceId;
}

/**
 * 供应商应付月度台账（2026-09-18 第 17 类）：主表单写，无子表。
 * multiSheet：组键 = 供应商 sheet 名#月份（55+ 张 sheet 聚合），main 已含注入的「供应商」列。
 * ⚠️ 尾部 RK/费用明细行被吸附进组但不入库（RK 已由 purchase_receipts 覆盖，费用行是人工脏结构）。
 * 应付口径（§9）：合计应付=Σ月度应付货款−折让、剩余应付=合计应付−合计支付（文件月度行原值入库，不重算）。
 */
private Long saveSupplierPayable(Group g) {
    Map<String, String> m = g.main();
    Integer monthNo = monthNo(m.get("月份"));
    jdbc.update("""
        INSERT INTO h3_supplier_payables(external_source,external_id,source_no,supplier_name,month_no,month_raw,
        payable_amount,settled_amount,allowance,pay_date,pay_method,pay_amount,remaining_amount,
        source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE supplier_name=VALUES(supplier_name),month_no=VALUES(month_no),month_raw=VALUES(month_raw),
        payable_amount=VALUES(payable_amount),settled_amount=VALUES(settled_amount),allowance=VALUES(allowance),
        pay_date=VALUES(pay_date),pay_method=VALUES(pay_method),pay_amount=VALUES(pay_amount),
        remaining_amount=VALUES(remaining_amount),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, g.externalId(), g.externalId(), clean(m.get("供应商")), monthNo, clean(m.get("月份")),
            normDecimal(m.get("应付货款")), normDecimal(m.get("结算货款")), normDecimal(m.get("折让")),
            date(first(m, "付款日期")), clean(m.get("付款方式")), normDecimal(m.get("付款金额")),
            normDecimal(m.get("剩余货款")), g.hash());
    return jdbc.queryForObject(
            "SELECT id FROM h3_supplier_payables WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
}

/** 月份单元格 → 1..12；无法解析返回 null（入库 month_no=NULL 并告警）。 */
private Integer monthNo(String v) {
    if (empty(v)) return null;
    try {
        int n = Integer.parseInt(v.trim());
        return n >= 1 && n <= 12 ? n : null;
    } catch (NumberFormatException e) {
        return null;
    }
}

/** 供应商应付告警：①付款日期/金额不成对 ②月份无法解析（预览 + confirm 双阶段）。 */
private List<String> supplierPayableWarnings(Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    if (monthNo(m.get("月份")) == null) w.add("月份无法解析：" + first(m, "月份"));
    boolean hasPay = normDecimal(m.get("付款金额")) != null;
    boolean hasDate = !empty(first(m, "付款日期"));
    if (hasPay && !hasDate) w.add("有付款金额缺付款日期");
    if (hasDate && !hasPay) w.add("有付款日期缺付款金额");
    return w;
}

// ==================== 流水日记账 / 收银通（2026-09-18 第 18/19 类，校验源） ====================

/**
 * 流水日记账账户分录（第 18 类）：行级幂等（组键 = 账户#日期#收入#支出#结存#同键序号）。
 * multiSheet：账户 = sheet 名（源「账户」列有错填，不采信）。
 * 「上期结存」伪行（无日期、无收支）不入分录 → 转写 h3_cash_accounts 期初余额/币种。
 * 人工标注字段（月份/供应商/客户 供应商/报销人/是否开票）原样入库不解读（§4 拍板 2）。
 */
private Long saveCashflowEntry(Group g) {
    Map<String, String> m = g.main();
    String account = clean(m.get("账户"));
    LocalDate entryDate = cashflowDate(m);
    boolean hasMoney = !empty(first(m, "收入", "支出"));
    if (entryDate == null && !hasMoney) {
        // 伪行分支（2026-09-18 实测踩坑）：账户 sheet 顶部除「上期结存」行外，还有逐月重复的
        // 空白填充行（只有结存/月份、无日期无收支，实测 243 行）。若一律 upsert 账户期初余额，
        // 靠后的空白行会把已写入的期初余额覆盖成 NULL/月度结存（实测 15 个账户期初被清零）。
        // → 仅「摘要=上期结存」行写账户维度，其余伪行直接跳过（不入分录、不碰账户表）。
        if (!isOpeningBalanceRow(m)) return null;
        upsertCashAccount(account, normDecimal(m.get("结存")), g.hash());
        return jdbc.queryForObject(
                "SELECT id FROM h3_cash_accounts WHERE external_source='H3YUN' AND external_id=?",
                Long.class, account);
    }
    jdbc.update("""
        INSERT INTO h3_cashflow_entries(external_source,external_id,source_no,account_name,entry_date,month_label,
        summary,supplier_raw,counterparty_raw,reimbursed_by,invoiced,income,expense,balance,
        fx_rate,fx_cny,settle_rate,settle_cny,fx_gain_loss,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE account_name=VALUES(account_name),entry_date=VALUES(entry_date),
        month_label=VALUES(month_label),summary=VALUES(summary),supplier_raw=VALUES(supplier_raw),
        counterparty_raw=VALUES(counterparty_raw),reimbursed_by=VALUES(reimbursed_by),invoiced=VALUES(invoiced),
        income=VALUES(income),expense=VALUES(expense),balance=VALUES(balance),fx_rate=VALUES(fx_rate),
        fx_cny=VALUES(fx_cny),settle_rate=VALUES(settle_rate),settle_cny=VALUES(settle_cny),
        fx_gain_loss=VALUES(fx_gain_loss),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, g.externalId(), g.externalId(), account, entryDate == null ? null : Date.valueOf(entryDate),
            clean(m.get("月份")), clean(m.get("摘要")), clean(m.get("供应商")),
            // ⚠️ 表头归一后的键是「客户供应商」（putHeader 已剥不规则空白），此处曾写成带空格的
            // 「客户 供应商」→ 取值恒 null、counterparty_raw 全表 0 行（源侧 7 129 行！）。
            // 两个键都试，防后续改归一口径时再断。
            clean(first(m, "客户供应商", "客户 供应商")),
            clean(m.get("报销人")), clean(m.get("是否开票")),
            normDecimal(m.get("收入")), normDecimal(m.get("支出")), normDecimal(m.get("结存")),
            normDecimal(m.get("汇率")), normDecimal(m.get("人民币")), normDecimal(m.get("结汇汇率")),
            normDecimal(m.get("人民币(结汇)")), normDecimal(m.get("汇兑损益")), g.hash());
    return jdbc.queryForObject(
            "SELECT id FROM h3_cashflow_entries WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
}

/**
 * 收银通收单流水（第 19 类）：单表单写，无子表。
 * 幂等键 = 交易完成时间#交易金额#付款人识别号（实测 6088 行零重复；仅时间有 1 对同秒不同额）。
 * 手续费（订单总扣费）源值带负号，原样存（校验源不清洗）。
 */
private Long saveCashierRow(Group g) {
    Map<String, String> m = g.main();
    jdbc.update("""
        INSERT INTO h3_cashier_log(external_source,external_id,source_no,tx_time,status,amount,fee,
        payer_openid,summary,salesperson,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE tx_time=VALUES(tx_time),status=VALUES(status),amount=VALUES(amount),
        fee=VALUES(fee),payer_openid=VALUES(payer_openid),summary=VALUES(summary),salesperson=VALUES(salesperson),
        source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, g.externalId(), g.externalId(), cashierTime(first(m, "交易完成时间")), clean(m.get("交易状态")),
            normDecimal(m.get("交易金额(元)")), normDecimal(m.get("订单总扣费(元)")),
            clean(m.get("付款人识别号")), clean(m.get("摘要")), clean(m.get("业务员")), g.hash());
    return jdbc.queryForObject(
            "SELECT id FROM h3_cashier_log WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
}

/** 账户维度表 upsert：期初余额来自「上期结存」伪行；币种按 sheet 名判定（美元/欧元，其余 CNY）。 */
private void upsertCashAccount(String account, java.math.BigDecimal openingBalance, String hash) {
    if (empty(account)) return;
    jdbc.update("""
        INSERT INTO h3_cash_accounts(external_source,external_id,source_no,account_name,currency,
        opening_balance,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE currency=VALUES(currency),opening_balance=VALUES(opening_balance),
        source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, account, account, account, currencyOf(account), openingBalance, hash);
}

/**
 * 账户档案补建（只建不改，不碰已有期初余额）：账户 sheet 共 35 张，其中「浙江利强中国银行欧元」
 * 没有「上期结存」行 → 只在期初行 upsert 会让该账户整体缺档。这里按账户名补档、期初留空。
 */
private void ensureCashAccountExists(String account) {
    if (empty(account)) return;
    jdbc.update("""
        INSERT INTO h3_cash_accounts(external_source,external_id,source_no,account_name,currency,
        opening_balance,last_synced_at)
        VALUES('H3YUN',?,?,?,?,NULL,NOW())
        ON DUPLICATE KEY UPDATE account_name=VALUES(account_name)
        """, account, account, account, currencyOf(account));
}

/** confirm 前置一步：账户涉及面去重后建档（一次 confirm 只跑一遍，避免逐行补档的 7 500+ 次往返）。 */
private void ensureCashflowAccounts(List<Group> groups) {
    java.util.LinkedHashSet<String> accounts = new java.util.LinkedHashSet<>();
    for (Group g : groups) accounts.add(clean(g.main().get("账户")));
    for (String account : accounts) ensureCashAccountExists(account);
}

/** 币种按账户名判定（账户 sheet 命名含币种，如「浙江利强中国银行美元」）。 */
private static String currencyOf(String account) {
    return account.contains("美元") ? "USD" : account.contains("欧元") ? "EUR" : "CNY";
}

/** 账户分录日期三分支解析：日期列格式（date()）/ 带时间（dateTime() 取日）/ Excel 序列号（6816 实测 7 行）。 */
private LocalDate cashflowDate(Map<String, String> m) {
    String v = first(m, "日期");
    if (empty(v)) return null;
    // ⚠️ 流式路径的日期值来自 POI DataFormatter 的「按单元格显示格式」输出：源表日期列格式为
    // yyyy"年"m"月"d"日"，DataFormatter 会把引号一并产出（实测 7 183 行原文 = 2026"年"1"月"1"日"）。
    // 不剥引号则 date()/dateTime() 全部落空 → entry_date 大面积 NULL（首次 confirm 实测 7 183/7 588 行为 NULL）。
    String norm = v.replace("\"", "").replace("'", "").trim();
    Date d = date(norm);
    if (d != null) return d.toLocalDate();
    Timestamp ts = dateTime(norm);
    if (ts != null) return ts.toLocalDateTime().toLocalDate();
    String t = norm.replaceAll("(\\.0+)$", "");
    try {
        double num = Double.parseDouble(t);
        if (num >= 20000 && num < 80000) return LocalDate.of(1899, 12, 30).plusDays((long) num);
    } catch (NumberFormatException ignored) { }
    return null;
}

/**
 * 「上期结存」期初行判定。⚠️ 标记列不固定：多数账户 sheet 落在「摘要」列，但
 * 「浙江利强中国银行欧元」sheet 列序不同、标记落在「供应商」列（该 sheet 仅 1 行、结存=0）。
 * → 全字段扫描（本判定只用于「无日期且无收支」的行，真实分录行不会走到这里，无副作用）。
 */
private boolean isOpeningBalanceRow(Map<String, String> m) {
    for (String v : m.values()) {
        if (v != null && v.contains("上期结存")) return true;
    }
    return false;
}

private LocalDate cashflowDate(Group g) { return cashflowDate(g.main()); }

/** 真实分录行判定：有收入/支出即数据行（否则可能是「上期结存」伪行）。 */
private boolean isCashflowData(Group g) { return !empty(first(g.main(), "收入", "支出")); }

/** 收银通交易时间解析：dateTime() 优先，失败退 date()（取当日零点）。 */
private Timestamp cashierTime(String v) {
    Timestamp ts = dateTime(v);
    if (ts != null) return ts;
    Date d = date(v);
    return d != null ? Timestamp.valueOf(d.toLocalDate().atStartOfDay()) : null;
}

/**
 * 流水日记账行级组键：账户#日期#收入#支出#结存#n（n = 同键序号，seq 由调用路径持有、按行序确定；
 * 「上期结存」伪行 material 同样唯一）。external_id=组键，重导同行序不变 → 幂等成立。
 */
private String cashflowRowKey(Map<String, String> values, String sheetName, Map<String, Integer> seq) {
    // 排除行（返回 null = 不入组）：账户 sheet 顶部除「上期结存」期初行外，还有逐月空白填充行
    // （只有结存/月份，无日期无收支，实测 209 行）。它们既不是分录、也不代表期初余额；
    // 若让其成组，台账里永远查不到对应 existing → 每轮重导都计一次 CREATE，台账永不收敛。
    if (empty(values.get("日期")) && empty(values.get("收入")) && empty(values.get("支出"))) {
        // ⚠️ 必须与 isOpeningBalanceRow 同口径（全字段扫描）。此处曾硬取「摘要」列，
        // 导致标记落在「供应商」列的 浙江利强中国银行欧元 被判为空白行排除 → 该账户整体缺档。
        if (!isOpeningBalanceRow(values)) return null;
        // 期初行：组键 = 账户#期初（不掺结存数值，避免金额修订后被判为新行）。
        // 该键与账户档案一一对应：loadExistingFacts 会把 h3_cash_accounts 以 账户名+"#期初"
        // 合成事实键代入比对 → 重导时判 UNCHANGED，收敛成立（详见 loadExistingFacts 注释）。
        return sheetName + "#期初";
    }
    String material = sheetName + "#" + orDash(values.get("日期")) + "#" + orDash(values.get("收入"))
            + "#" + orDash(values.get("支出")) + "#" + orDash(values.get("结存"));
    return material + "#" + seq.merge(material, 1, Integer::sum);
}

/** 收银通行级组键：时间#金额#付款人识别号（实测零重复）。时间列为空返回 null（不产生游离组）。 */
private String cashierRowKey(Map<String, String> values) {
    String t = clean(values.get("交易完成时间"));
    if (t == null) return null;
    return t + "#" + orDash(values.get("交易金额(元)")) + "#" + orDash(values.get("付款人识别号"));
}

private static String orDash(String v) { return empty(v) ? "-" : v.trim(); }

/**
 * 流水日记账告警（预览 + confirm 双阶段）：①真实分录行日期无法解析 ②外币行缺汇率 ③同键多行（n>1）。
 * 「账户列与 sheet 名不一致」不告警——账户列本就不采信（sheet 名为确定性来源，设计稿 §1）。
 */
private List<String> cashflowJournalWarnings(Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    boolean hasMoney = !empty(first(m, "收入", "支出"));
    if (hasMoney && cashflowDate(m) == null) w.add("日期无法解析：" + first(m, "日期"));
    String acct = first(m, "账户");
    boolean fx = acct != null && (acct.contains("美元") || acct.contains("欧元"));
    if (fx && hasMoney && normDecimal(m.get("汇率")) == null && normDecimal(m.get("人民币")) == null) {
        w.add("外币行缺汇率/折算值");
    }
    String ext = g.externalId();
    if (ext != null) {
        int hashIdx = ext.lastIndexOf('#');
        if (hashIdx > 0 && ext.substring(hashIdx + 1).matches("\\d+")) {
            int n = Integer.parseInt(ext.substring(hashIdx + 1));
            if (n > 1) w.add("同键多行（该键第 " + n + " 行）");
        }
    }
    return w;
}

/** 收银通告警（D4 拍板）：缺摘要/业务员照常入库 + 告警计数，不回填不猜。 */
private List<String> cashierLogWarnings(Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    if (empty(m.get("摘要"))) w.add("缺摘要（付款人识别号未在 Sheet2 字典中）");
    if (empty(m.get("业务员"))) w.add("缺业务员");
    if (cashierTime(first(m, "交易完成时间")) == null) w.add("交易完成时间无法解析");
    return w;
}

/**
 * 发票台账告警（预览 + confirm 双阶段）：
 * ①金额勾稽 金额+税额 ≠ 价税合计（容差 0.005，红字票同式）；
 * ②销项已红冲但平台备注无红冲链引用（红冲关系无法回溯）；
 * ③销项收款子行缺收款金额（登记不完整）。
 */
private List<String> invoiceWarnings(FactType type, Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    BigDecimal amount = normDecimal(m.get("金额"));
    BigDecimal tax = normDecimal(m.get("税额"));
    BigDecimal total = normDecimal(m.get("价税合计"));
    if (amount != null && tax != null && total != null
            && amount.add(tax).subtract(total).abs().compareTo(new BigDecimal("0.005")) > 0) {
        w.add("金额+税额 " + amount.add(tax).stripTrailingZeros().toPlainString()
                + " ≠ 价税合计 " + total.stripTrailingZeros().toPlainString());
    }
    if (type == FactType.INVOICE_SALES) {
        String status = clean(m.get("发票状态"));
        boolean red = status != null && status.contains("已红冲");
        boolean hasRef = !empty(m.get("备注(平台)")) && m.get("备注(平台)").contains("被红冲蓝字数电发票号码");
        if (red && !hasRef) w.add("已红冲但平台备注无红冲链引用");
        if (!red && hasRef) w.add("平台备注含红冲引用但发票状态=" + status);
        for (Map<String, String> row : g.rows()) {
            if (!empty(row.get("数电发票号码"))) continue;
            if (empty(first(row, "收款金额", "付款方式", "收款日期"))) w.add("收款子行登记不完整（缺收款金额/方式/日期）");
        }
    }
    return w;
}

/**
 * P3 开票申请：主表 + 开票内容 + 收款引用三写（整删重插，行级 UUID 幂等）。
 *
 * <p>⚠️ 一行可同时携带内容与收款（实测 11537 行双子表同行）→ 逐行各取各的、按 source_item_id 去重。
 * ⚠️ 18 单流水号为空 → source_no 存 external_id 兜底（唯一键仍成立）。
 * 选择开票资料是户名文本（1035 去重值）→ 等值反查 profile，未命中留空不告警。
 * 出库匹配自由文本只存 outbound_ref_raw，不做概率勾稽（§3.4 定调）。
 */
private Long saveInvoiceRequest(Group g) {
    Map<String, String> m = g.main();
    String sourceNo = first(m, "流水号");
    if (empty(sourceNo)) sourceNo = g.externalId();
    String profileName = first(m, "选择开票资料");
    Long profileId = null;
    String profileExternalId = null;
    if (!empty(profileName)) {
        List<Map<String, Object>> hits = jdbc.queryForList(
                "SELECT id, external_id FROM h3_customer_invoice_profiles WHERE customer_name=? ORDER BY id LIMIT 1",
                profileName.trim());
        if (!hits.isEmpty()) {
            profileId = ((Number) hits.get(0).get("id")).longValue();
            profileExternalId = (String) hits.get(0).get("external_id");
        }
    }
    if (existingId(FactType.INVOICE_REQUEST, g) == null) {
        clearConflicts(FactType.INVOICE_REQUEST, g);
    }
    Object[] args = new Object[]{g.externalId(), sourceNo, m.get("发票类型"), m.get("选择已开发票申请红冲"),
            date(m.get("开票日期")), m.get("业务员"), profileExternalId, profileId,
            m.get("户名"), m.get("税号"),
            m.get("地址"), m.get("电话"), m.get("开户行"), m.get("银行账号"),
            normDecimal(m.get("本次申请开票金额")), normDecimal(m.get("已收款金额")),
            normDecimal(m.get("未收款金额")),
            m.get("选择出库单匹配开票内容、收款情况"), m.get("备注"), m.get("数据状态"),
            g.externalId(), m.get("数据标题"),
            first(m, "拥有者(必填)", "拥有者"), first(m, "所属部门(必填)", "所属部门"),
            first(m, "创建人", "创建人(必填)"),
            dateTime(first(m, "创建时间", "创建时间(必填)")),
            dateTime(first(m, "修改时间", "修改时间(必填)")), g.hash()};
    jdbc.update("""
        INSERT INTO h3_invoice_requests(external_source,external_id,source_no,invoice_type,redflush_ref,
        request_date,salesperson,profile_external_id,profile_id,customer_name,tax_no,
        snapshot_address,snapshot_phone,snapshot_bank_name,snapshot_bank_account,
        request_amount,received_amount,unreceived_amount,outbound_ref_raw,remark,data_status,
        source_object_id,source_title,owner_name,department_name,created_by_name,
        source_created_at,source_updated_at,source_hash,last_synced_at)
        VALUES('H3YUN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
        ON DUPLICATE KEY UPDATE source_no=VALUES(source_no),invoice_type=VALUES(invoice_type),
        redflush_ref=VALUES(redflush_ref),request_date=VALUES(request_date),salesperson=VALUES(salesperson),
        profile_external_id=VALUES(profile_external_id),profile_id=VALUES(profile_id),
        customer_name=VALUES(customer_name),tax_no=VALUES(tax_no),snapshot_address=VALUES(snapshot_address),
        snapshot_phone=VALUES(snapshot_phone),snapshot_bank_name=VALUES(snapshot_bank_name),
        snapshot_bank_account=VALUES(snapshot_bank_account),request_amount=VALUES(request_amount),
        received_amount=VALUES(received_amount),unreceived_amount=VALUES(unreceived_amount),
        outbound_ref_raw=VALUES(outbound_ref_raw),remark=VALUES(remark),data_status=VALUES(data_status),
        source_object_id=VALUES(source_object_id),source_title=VALUES(source_title),
        owner_name=VALUES(owner_name),department_name=VALUES(department_name),
        created_by_name=VALUES(created_by_name),source_created_at=VALUES(source_created_at),
        source_updated_at=VALUES(source_updated_at),source_hash=VALUES(source_hash),last_synced_at=NOW()
        """, args);
    Long requestId = jdbc.queryForObject(
            "SELECT id FROM h3_invoice_requests WHERE external_source='H3YUN' AND external_id=?",
            Long.class, g.externalId());
    jdbc.update("DELETE FROM h3_invoice_request_items WHERE request_id=?", requestId);
    jdbc.update("DELETE FROM h3_invoice_request_receipt_refs WHERE request_id=?", requestId);
    Map<String, Map<String, String>> items = new LinkedHashMap<>();
    Map<String, Map<String, String>> receipts = new LinkedHashMap<>();
    for (Map<String, String> row : g.rows()) {
        String iid = clean(row.get("开票内容.数据ID(不可修改)"));
        if (iid != null && !items.containsKey(iid)) items.put(iid, row);
        String rid = clean(row.get("收款情况.数据ID(不可修改)"));
        if (rid != null && !receipts.containsKey(rid)) receipts.put(rid, row);
    }
    for (Map.Entry<String, Map<String, String>> e : items.entrySet()) {
        Map<String, String> row = e.getValue();
        jdbc.update("""
            INSERT INTO h3_invoice_request_items(request_id,source_item_id,line_no,product_name,spec_model,
            unit,quantity,unit_price,amount) VALUES(?,?,?,?,?,?,?,?,?)
            """, requestId, e.getKey(), integer(row.get("开票内容.序号")),
            row.get("开票内容.产品名称"), row.get("开票内容.规格型号"), row.get("开票内容.单位"),
            normDecimal(row.get("开票内容.数量")), normDecimal(row.get("开票内容.单价")),
            normDecimal(row.get("开票内容.金额小计")));
    }
    for (Map.Entry<String, Map<String, String>> e : receipts.entrySet()) {
        Map<String, String> row = e.getValue();
        jdbc.update("""
            INSERT INTO h3_invoice_request_receipt_refs(request_id,source_item_id,raw_title,receipt_date,
            receipt_method,receipt_amount) VALUES(?,?,?,?,?,?)
            """, requestId, e.getKey(), row.get("收款情况.数据标题"),
            date(row.get("收款情况.收款日期")), row.get("收款情况.收款方式"),
            normDecimal(row.get("收款情况.收款金额")));
    }
    return requestId;
}

/**
 * P3 开票申请校验（预览 warnings + confirm missing_quantity 双阶段）：
 * Σ开票内容金额 vs 申请金额（实测基线 1 单）、已收+未收=申请（基线 0）、
 * 发票类型枚举（空容差，基线 9 单空）、非已生效状态（草稿 20/已取消 2/进行中 2，标记不计异常）。
 * 主表税号 vs profile 税号快照差属时点正常值，不告警（设计文档 §3.3）。
 */
private List<String> invoiceRequestWarnings(Group g) {
    Map<String, String> m = g.main();
    List<String> w = new ArrayList<>();
    BigDecimal requested = normDecimal(m.get("本次申请开票金额"));
    BigDecimal contentSum = BigDecimal.ZERO;
    int contentRows = 0;
    for (Map<String, String> row : g.rows()) {
        if (empty(row.get("开票内容.数据ID(不可修改)")) && empty(row.get("开票内容.产品名称"))) continue;
        BigDecimal amt = normDecimal(row.get("开票内容.金额小计"));
        if (amt != null) { contentSum = contentSum.add(amt); contentRows++; }
    }
    if (requested != null && contentRows > 0
            && contentSum.subtract(requested).abs().compareTo(new BigDecimal("0.005")) > 0) {
        w.add(contentRows + " 行内容合计 " + contentSum.stripTrailingZeros().toPlainString()
                + " ≠ 申请金额 " + requested.stripTrailingZeros().toPlainString());
    }
    BigDecimal received = normDecimal(m.get("已收款金额"));
    BigDecimal unreceived = normDecimal(m.get("未收款金额"));
    if (requested != null && received != null && unreceived != null
            && received.add(unreceived).subtract(requested).abs().compareTo(new BigDecimal("0.005")) > 0) {
        w.add("已收款+未收款 ≠ 申请金额");
    }
    String type = clean(m.get("发票类型"));
    if (type != null && !"增值税专用发票".equals(type) && !"普通发票".equals(type)) w.add("发票类型=" + type);
    String status = m.get("数据状态");
    if (status != null && !"已生效".equals(status.trim())) w.add("数据状态=" + status.trim());
    return w;
}

private int childCount(FactType type, Group group) {
return (int) group.rows().stream().filter(row -> switch (type) {
case PRODUCTION -> !empty(row.get("零部件制程.数据ID(不可修改)"))
|| !empty(row.get("零部件制程.零部件名"));
case OUTBOUND -> !empty(row.get("出库产品明细.数据ID(不可修改)"))
|| !empty(row.get("出库产品明细.选择产品"))
|| !empty(row.get("出库产品明细.产品名称"));
case RECEIPT -> !empty(first(row, "销售出库编号", "销售订单编号"));
case SETTLEMENT -> !empty(row.get("出库单明细.销售出库编号"));
case SUPPLIER -> false;
case PURCHASE_IN -> !empty(row.get("产品明细.数据ID(不可修改)"))
|| !empty(first(row, "产品明细.产品编号", "产品明细.选择产品"));
case OTHER_IN, OTHER_OUT -> !empty(row.get("其他出库明细.数据ID(不可修改)"))
|| !empty(first(row, "其他出库明细.产品编号", "其他出库明细.选择产品"));
case TRANSFER -> !empty(row.get("调拨明细.数据ID(不可修改)"))
|| !empty(first(row, "调拨明细.产品编号", "调拨明细.产品名称"));
case INVOICE_PROFILE -> false;
case INVOICE_REQUEST -> !empty(row.get("开票内容.数据ID(不可修改)"))
|| !empty(first(row, "开票内容.产品名称"));
case PRODUCTION_REPORT -> false;
// 销项发票收款子行（2026-09-18）：无数电发票号码的行 = 挂靠发票的多次收款登记（512 行实测）
case INVOICE_SALES -> empty(row.get("数电发票号码"));
case INVOICE_INPUT -> false;
// 供应商应付（第 17 类）：尾部 RK/费用明细行被吸附但不入库，不计子行
case SUPPLIER_PAYABLE -> false;
// 流水日记账（第 18 类）行级分组、收银通（第 19 类）单行组：均无子表
case CASHFLOW_JOURNAL, CASHIER_LOG -> false;
}).count();
}

private long createBatch(String fileName, String type, int count) {
KeyHolder holder = new GeneratedKeyHolder();
jdbc.update(con -> {
PreparedStatement ps = con.prepareStatement("INSERT INTO h3_import_batches(file_name,data_type,status,total_count) VALUES(?,?,'PROCESSING',?)", Statement.RETURN_GENERATED_KEYS);
ps.setString(1, fileName); ps.setString(2, type); ps.setInt(3, count); return ps;
}, holder);
return Objects.requireNonNull(holder.getKey()).longValue();
}

private void saveImportRow(long batchId, Group group, String action, Long businessId) {
jdbc.update("INSERT INTO h3_import_rows(batch_id,external_id,source_row_number,raw_data,source_hash,action,business_id,processed_at) VALUES(?,?,NULL,?,?,?,?,NOW())",
batchId, group.externalId(), groupJson(group), group.hash(), action, businessId);
}

private String groupJson(Group group) {
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("key", group.key());
payload.put("main", group.main());
payload.put("rows", group.rows());
return json(payload);
}

private Long customerId(String name) {
    return sourceIdentityService.resolveCustomer(name);
}
private Long customerId(String name, String address, String phone) {
    return sourceIdentityService.resolveCustomer(name, address, phone);
}

/**
 * 「源记录未变化（UNCHANGED）但本地客户档案已丢失」的缺口自愈。
 *
 * <p>UNCHANGED 分支不会重跑 {@link #saveOutbound}，若本地客户档案被清理导致 customer_id 被置空，
 * 该缺口在后续重导中永远不会被补齐。这里只补客户关联：仅当 customer_id 仍为空时重跑一次客户识别
 * 并回填，不触碰其它任何字段；识别不出（泛化名称、多候选）的保持为空，交销售出库关联工作台人工处理。
 */
private void healMissingOutboundCustomer(Long id, Group g) {
    Integer missing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales_outbounds WHERE id=? AND customer_id IS NULL", Integer.class, id);
    if (missing == null || missing == 0) return;
    Map<String, String> m = g.main();
    Long customerId = customerId(first(m, "选择客户", resolveOutboundCustomerName(m)),
            m.get("客户详细地址"), m.get("联系方式"));
    if (customerId == null) return;
    jdbc.update("UPDATE sales_outbounds SET customer_id=? WHERE id=? AND customer_id IS NULL", customerId, id);
}
private Long memberId(String name) { return empty(name) ? null : optionalLong("SELECT id FROM company_team_members WHERE name=? ORDER BY id LIMIT 1", clean(name)); }
private Long memberEmployeeId(String name) {
    return h3EmployeeIdentityService.resolveName(name);
}
private Long optionalLong(String sql, Object... args) { List<Long> ids = jdbc.query(sql, (rs,n)->rs.getLong(1), args); return ids.isEmpty()?null:ids.getFirst(); }
private String title(FactType type, Map<String,String> m) { return switch(type) { case PRODUCTION -> first(m,"产品名称","数据标题"); case OUTBOUND -> first(m,"客户名称","数据标题"); case RECEIPT,SETTLEMENT -> first(m,"客户名称","选择客户","数据标题"); case SUPPLIER -> first(m,"供应商名称","数据标题"); case PURCHASE_IN -> first(m,"数据标题","采购入库编号"); case OTHER_IN, OTHER_OUT -> first(m,"数据标题","流水号","其他出库编号"); case TRANSFER -> first(m,"数据标题","流水号"); case INVOICE_PROFILE, INVOICE_REQUEST -> first(m,"户名","数据标题"); case PRODUCTION_REPORT -> first(m,"数据标题","生产工单编号"); case INVOICE_INPUT -> first(m,"销方名称","数电发票号码"); case INVOICE_SALES -> first(m,"购买方名称","数电发票号码"); case SUPPLIER_PAYABLE -> first(m,"供应商")+" "+first(m,"月份")+"月"; case CASHFLOW_JOURNAL -> first(m,"账户")+" "+first(m,"日期"); case CASHIER_LOG -> first(m,"交易完成时间"); }; }
private String first(Map<String,String> m, String... keys) { for(String k:keys){String v=clean(m.get(k)); if(v!=null)return v;} return null; }
private static String clean(String v) { return v==null||v.isBlank()?null:v.trim(); }
private static boolean empty(String v) { return clean(v)==null; }

/**
 * 氚云出库单「误点空行」判定（2026-09-17 第二版，语义级）。
 *
 * <p>业务员在出库表单多点出的空明细行常仍带数据ID（GUID），且「销售总价」可能带
 * {@code 0}/{@code 0.00}——按字符串判空拦不住，会生成无法关联产品的空 PRODUCT 行，
 * 在销售出库关联工作台计为「缺库存产品」，且每次确认导入（整单删了重插）都会复活。
 *
 * <p>判定：选择产品/产品名称/类目/产品编号/出库数量全空，且销售总价为空或等于 0。
 * 任何一项有真实内容（尤其是金额非 0）都不算空行，保留给下游业务解析去提示。
 *
 * @return true = 整行垃圾，导入时直接跳过
 */
static boolean blankDetailRow(Map<String, String> row) {
    if (row == null) return true;
    if (!empty(row.get("出库产品明细.选择产品")) || !empty(row.get("出库产品明细.产品名称"))
            || !empty(row.get("出库产品明细.类目")) || !empty(row.get("出库产品明细.产品编号"))
            || !empty(row.get("出库产品明细.出库数量"))) return false;
    String total = row.get("出库产品明细.销售总价");
    if (empty(total)) return true;
    try {
        return new BigDecimal(total.replace(",", "").replace("¥", "").replace("￥", "")).signum() == 0;
    } catch (Exception e) {
        return false; // 解析不了就当真实数据，交给下游提示
    }
}
// ==================== 客户名解析（销售出库） ====================
// 氚云表单允许把"客户姓名 + 电话 + 公司 + 地址"都填在「联系方式」里，
// 而省略「客户名称」；「客户信息（输入名字电话地址自动生成）」则是这些
// 字段的自动合成文本。导入期先看「客户名称」，缺失时按下面的优先级
// 回退并解析出最像「姓名」的片段，避免下游销售出库分析找不到客户。
private static String firstNonBlank(String... vals) {
    if (vals == null) return null;
    for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
    return null;
}
private static final java.util.regex.Pattern CONTACT_PHONE_LOOSE =
        java.util.regex.Pattern.compile("(?:1[3-9]\\d{9})|(?:0\\d{2,3}-?\\d{7,8}(?:-\\d{1,6})?)|(?:400-?\\d{3}-?\\d{4})|(?:\\d{7,8}(?:-\\d{1,6})?)");
/**
 * 从合并字段（"姓名，电话，地址"）或联系方式字段（"姓名,18912620958"）中
 * 抽取最可能作为客户名的片段。规则：
 *  - 按全角逗号、半角逗号、分号、顿号、换行分割；
 *  - 跳过纯电话号码段（"18912620958"、"189-1262-0958" 等）；
 *  - 取首个至少含 2 个汉字/字母且总长 ≤ 40 的片段作为姓名；
 *  - 找不到则返回 null（由调用方走下一步兜底）。
 */
static String extractCustomerNameFromComposite(String composite) {
    if (composite == null || composite.isBlank()) return null;
    String[] segs = composite.split("[,，;；、\\n\\r]+");
    for (String seg : segs) {
        String t = seg.trim();
        if (t.isEmpty()) continue;
        if (isPhoneOnlySegment(t)) continue;
        String letters = t.codePoints()
                .mapToObj(cp -> String.valueOf((char) cp))
                .filter(s -> {
                    char c = s.charAt(0);
                    return Character.isLetter(c) || (c >= 0x4E00 && c <= 0x9FFF);
                })
                .reduce((a, b) -> a + b).orElse("");
        if (letters.length() >= 2 && letters.length() <= 40) {
            return t;
        }
    }
    return null;
}
private static boolean isPhoneOnlySegment(String segment) {
    if (segment == null) return false;
    String trimmed = segment.trim();
    if (trimmed.isEmpty()) return false;
    // 全是中划线 / 空格 / 加号 / 括号 / 数字，且数字段可达 7 位及以上 → 视为电话
    String digits = trimmed.replaceAll("\\D", "");
    if (digits.length() < 7) return false;
    String stripped = trimmed.replaceAll("[\\d\\s\\-+()（）]", "");
    if (stripped.isEmpty()) return true;  // 整段都是电话字符
    // 还有可能"姓名+电话连写"：用宽松手机号正则匹配后整段中电话段长度占主导
    java.util.regex.Matcher m = CONTACT_PHONE_LOOSE.matcher(trimmed);
    if (m.find() && m.group().length() >= 7 && digits.length() >= trimmed.length() * 0.6) {
        return true;
    }
    return false;
}
/**
 * 综合上述策略：在主键「客户名称」/「选择经销商」都为空时，按"客户信息（自动合成）"→"联系方式"
 * 的顺序回退解析出客户名；任何回填都会通过日志记下，便于排查。
 */
String resolveOutboundCustomerName(Map<String,String> m) {
    if (m == null) return null;
    String direct = firstNonBlank(clean(m.get("客户名称")), clean(m.get("选择经销商")));
    if (direct != null) return direct;
    String composite = firstNonBlank(
            m.get("客户信息（输入名字电话地址自动生成）"),
            m.get("客户信息(输入名字电话地址自动生成)"),
            m.get("客户信息"));
    if (composite != null) {
        String fromComposite = extractCustomerNameFromComposite(composite);
        if (fromComposite != null) {
            log.info("[氚云导入] 销售出库客户名由「客户信息」合并字段回填：external_id={} customer={}",
                    String.valueOf(m.get("销售出库编号")), fromComposite);
            return fromComposite;
        }
    }
    String contact = m.get("联系方式");
    if (contact != null) {
        String fromContact = extractCustomerNameFromComposite(contact);
        if (fromContact != null) {
            log.info("[氚云导入] 销售出库客户名由「联系方式」首段回填：external_id={} customer={}",
                    String.valueOf(m.get("销售出库编号")), fromContact);
            return fromContact;
        }
    }
    return null;
}
private BigDecimal decimal(String v) { if(empty(v))return null; try{return new BigDecimal(v.replace(",","").replace("¥","").replace("￥",""));}catch(Exception e){return null;} }
private Integer integer(String v) { BigDecimal n=decimal(v); return n==null?null:n.intValue(); }
private Boolean bool(String v) { if(empty(v))return null; return Set.of("是","已完成","完成","true","1").contains(v.trim()); }
private Date date(String v) { if(empty(v))return null; for(String p:List.of("yyyy-MM-dd","yyyy/M/d","yyyy/M/dd","yyyy/MM/d","yyyy.MM.dd","yyyy.M.d","yyyy年M月d日")){try{return Date.valueOf(LocalDate.parse(v.trim(),DateTimeFormatter.ofPattern(p)));}catch(Exception ignored){}} return null; }
private Timestamp dateTime(String v) { if(empty(v))return null; for(String p:List.of("yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy/M/d H:mm:ss","yyyy/M/d H:mm","yyyy/M/dd H:mm:ss","yyyy/MM/d H:mm:ss","yyyy.MM.dd HH:mm:ss","yyyy.M.d H:mm:ss","yyyy年M月d日 HH:mm:ss","yyyy-MM-dd'T'HH:mm:ss")){try{return Timestamp.valueOf(LocalDateTime.parse(v.trim(),DateTimeFormatter.ofPattern(p)));}catch(Exception ignored){}} return null; }
// ==================== 导入期客户档案增量补齐 ====================

private void enrichCustomerFromOutbound(Long customerId, Map<String,String> m) {
    if (customerId == null) return;
    String address = clean(m.get("客户详细地址"));
    String phone = clean(m.get("联系方式"));
    String salesperson = clean(m.get("业务员(必填)"));
    String shipDate = clean(m.get("发货日期"));
    // 地址非空优先，「地区」字段兜底：导入时即解析省/市/区县（治本，替代事后全量回填）。
    // 解析委托 ChinaAddressParser.parseLoose（区划字典文本扫描兜底），解析不出的层级留空不猜测；
    // 各字段仅在主档为空时补写，不覆盖人工已维护的值。
    // ⚠️ clean() 对 null/空白返回 null，此处一律用 empty()/null 安全判定，不得直接调 address.isEmpty()。
    String regionText = !empty(address) ? address : clean(m.get("地区"));
    if (!empty(regionText)) {
        ChinaAddressParser.ParsedAddress parsed = ChinaAddressParser.parseLoose(regionText);
        String province = parsed.province(), city = parsed.city(), district = parsed.district();
        jdbc.update("""
            UPDATE customers
            SET province = COALESCE(NULLIF(province,''), ?),
                city     = COALESCE(NULLIF(city,''), ?),
                district = COALESCE(NULLIF(district,''), ?),
                address  = COALESCE(NULLIF(address,''), ?),
                source   = COALESCE(NULLIF(source,''), 'H3YUN_SALES_OUTBOUND'),
                updated_at = NOW()
            WHERE id = ?
              AND ( (province IS NULL OR province = '') AND ? IS NOT NULL
                 OR (city IS NULL OR city = '')         AND ? IS NOT NULL
                 OR (district IS NULL OR district = '') AND ? IS NOT NULL
                 OR (address IS NULL OR address = '')   AND ? IS NOT NULL )
            """,
            cut(province,100), cut(city,100), cut(district,100),
            empty(address) ? null : (regionText.length() > 500 ? regionText.substring(0,500) : regionText),
            customerId,
            cut(province,100), cut(city,100), cut(district,100),
            empty(address) ? null : (regionText.length() > 500 ? regionText.substring(0,500) : regionText));
    }
    if (phone != null) {
        boolean hasPrimary = jdbc.queryForObject("SELECT COUNT(*) FROM customer_contacts WHERE customer_id=? AND is_primary=1", Integer.class, customerId) > 0;
        java.util.regex.Pattern phonePat = java.util.regex.Pattern.compile(
            "(?:1[3-9]\\d{9})" +
            "|(?:0\\d{2,3}-?\\d{7,8}(?:-\\d{1,6})?)" +
            "|(?:400-?\\d{3}-?\\d{4})" +
            "|(?:\\d{7,8}(?:-\\d{1,6})?)");
        java.util.regex.Matcher matcher = phonePat.matcher(phone);
        List<String> nums = new ArrayList<>();
        while (matcher.find()) {
            String n = matcher.group().trim();
            if (n.length() >= 5) nums.add(n);
        }
        if (nums.isEmpty()) {
            if (phone.replaceAll("[^0-9]","").length() >= 4) {
                String keep = phone.length() > 450 ? phone.substring(0, 450) : phone;
                Integer ex = jdbc.queryForObject("SELECT COUNT(*) FROM customer_contacts WHERE customer_id=? AND phone_raw=?", Integer.class, customerId, keep);
                if (ex == null || ex == 0) {
                    jdbc.update("INSERT INTO customer_contacts(customer_id,name,phone_raw,contact_type,is_primary,remark,created_at,updated_at) " +
                                "VALUES(?,?,?,'其他',?,?,NOW(),NOW())",
                                customerId, "导入联系人", keep, hasPrimary?0:1,
                                "原文=" + (phone.length() > 300 ? phone.substring(0,300) : phone));
                }
            }
        } else {
            for (int i = 0; i < nums.size(); i++) {
                String num = nums.get(i);
                String mobileCell = num.length() > 100 ? num.substring(0, 100) : num;
                Integer ex = jdbc.queryForObject("SELECT COUNT(*) FROM customer_contacts WHERE customer_id=? AND mobile=?", Integer.class, customerId, mobileCell);
                if (ex != null && ex > 0) continue;
                String cname = nums.size() > 1 ? ("导入联系人" + (i + 1)) : "导入联系人";
                jdbc.update("INSERT INTO customer_contacts(customer_id,name,mobile,contact_type,is_primary,remark,created_at,updated_at) " +
                            "VALUES(?,?,?,'其他',?,?,NOW(),NOW())",
                            customerId, cname, mobileCell, hasPrimary?0:1,
                            "从氚云销售出库联系方式导入；原文=" + (phone.length() > 300 ? phone.substring(0,300) : phone));
                hasPrimary = true;
            }
        }
    }
    if (salesperson != null) ensureSalespersonRelation(customerId, salesperson, shipDate);
}

private void enrichCustomerFromProduction(Long customerId, Map<String,String> m) {
    if (customerId == null) return;
    String salesperson = clean(m.get("业务员(必填)"));
    String appDate = clean(m.get("工单申请日期"));
    if (salesperson != null) ensureSalespersonRelation(customerId, salesperson, appDate);
}

/** 字段截断：省/市/区县列上限 100，超出部分丢弃。 */
private static String cut(String s, int max) {
    if (s == null || s.length() <= max) return s;
    return s.substring(0, max);
}

private void ensureSalespersonRelation(Long customerId, String salesperson, String startDateText) {
    String clean = clean(salesperson);
    if (clean == null) return;
    Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM customer_salesperson_relations WHERE customer_id=? AND salesperson_name=?",
            Integer.class, customerId, clean);
    if (exists != null && exists > 0) return;
    boolean hasPrimary = jdbc.queryForObject("SELECT COUNT(*) FROM customer_salesperson_relations WHERE customer_id=? AND role='PRIMARY'",
            Integer.class, customerId) > 0;
    Long memberId = optionalLong("SELECT id FROM company_team_members WHERE name=? ORDER BY id LIMIT 1", clean);
    Long empId = optionalLong("SELECT employee_id FROM company_team_members WHERE name=? AND employee_id IS NOT NULL ORDER BY id LIMIT 1", clean);
    LocalDate start = null;
    if (startDateText != null) {
        for (String p : List.of("yyyy-MM-dd","yyyy/M/d","yyyy/M/dd","yyyy/MM/d","yyyy.MM.dd","yyyy.M.d","yyyy年M月d日")) {
            try { start = LocalDate.parse(startDateText.trim(), DateTimeFormatter.ofPattern(p)); break; } catch (Exception ignored) {}
        }
    }
    jdbc.update("INSERT INTO customer_salesperson_relations(customer_id,salesperson_id,employee_id,salesperson_name,role,start_date,created_at,updated_at) VALUES(?,?,?,?,?,?,NOW(),NOW())",
            customerId, memberId, empId, clean, hasPrimary?"COLLABORATOR":"PRIMARY", start);
}

private String json(Object value) { try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("无法保存导入快照",e);} }

/**
 * 计算业务源哈希（{@code source_hash}）。
 *
 * <p>判等口径集中在 {@link com.liqiang.archive.util.H3SnapshotKey}（唯一实现，勿在本方法内另写归一）：
 * 表头归一（NFKC + 删内部空白）+ 值归一（数字规范十进制、日期规范形态）+ 键排序。
 * 这样同一份氚云数据即使换导出渠道（网页版/客户端）导致表头空白、数值位数、日期分隔符变化，
 * 也不会被判成 UPDATE。审计原文仍保存在 {@code h3_import_rows.raw_data}，不受影响。
 */
private String hashGroup(String key, Map<String, String> main, List<Map<String, String>> rows) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("key", key);
    payload.put("main", H3SnapshotKey.canonicalize(main));
    payload.put("rows", rows.stream().map(H3SnapshotKey::canonicalize).toList());
    return hash(payload);
}

private String hash(Object value) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // 通过 DigestOutputStream 边序列化边摘要，避免为超大 payload 分配等量内存的完整 JSON 字符串。
        try (DigestOutputStream dos = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            objectMapper.writeValue(dos, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException | IOException e) {
        throw new IllegalStateException(e);
    }
}

// ====================================================================
// V160/V161 异步导入管线：Excel 只解析一次，预览/确认从暂存读取
// ====================================================================

/**
 * Step 1a（同步）：仅创建批次记录，不落文件内容（字节由控制器持有，直接交给 async 方法）。
 *
 * <p>幂等：相同 file_hash + 同一 FactType，如已存在 PREVIEW_READY 批次（尚未确认导入），
 * 直接返回旧批次 id + hashHit=true，跳过重复解析。
 * COMPLETED 批次不复用——数据已落库，旧预览的 CREATE/UPDATE 计数已过期，
 * 需要创建新批次重新解析以获得正确的对比结果。
 *
 * @return StageAccepted：batchId（新或旧）、hashHit、当前 status 等
 */
public StageAccepted createBatchRecord(String type, String fileName, long fileSize, String fileHash) {
    FactType factType = FactType.from(type);
    // 幂等命中：同类型 + 同文件哈希 + 仍在预览阶段（尚未确认导入）
    // 注意：COMPLETED 批次不复用——数据已落库，旧预览的 CREATE/UPDATE 计数已过期，
    // 需要创建新批次重新解析，与当前数据库状态对比。
    Long cached = pipeline.findPreviewReadyBatchId(factType.dbType, fileHash);
    if (cached != null) {
        return new StageAccepted(cached, factType.dbType, fileName, true, "PREVIEW_READY",
                "文件哈希命中已有暂存结果，已跳过重复解析");
    }
    long batchId = pipeline.createBatch(fileName, fileSize, fileHash, factType.dbType, null);
    return new StageAccepted(batchId, factType.dbType, fileName, false, "UPLOADED",
            "文件已登记，等待后台异步解析任务启动（由控制器触发 @Async）");
}

/** 异步解析：调用方通过 Spring 代理调用 public 方法使 @Async 生效。 */
@Async("h3ImportExecutor")
public void asyncParseAndStage(Long batchId, FactType factType, byte[] fileBytes, String fileName) {
    try {
        updateProgress(batchId, "PARSING", 5, "PARSING");
        // 构造一个内存 MultipartFile 适配器复用现有 parse() 逻辑
        MultipartFile memFile = new InMemoryMultipartFile(fileName, fileBytes);
        List<Group> groups;
        try {
            groups = parse(factType, memFile);
        } catch (Exception e) {
            failBatch(batchId, "PARSING", "Excel 解析失败：" + safeMsg(e), e);
            return;
        }
        updateProgress(batchId, "PARSING", 50, "STAGING");

        // 落暂存表（分批 200 行一批，避免单 SQL 过大）
        final int batch = 200;
        for (int i = 0; i < groups.size(); i += batch) {
            int end = Math.min(groups.size(), i + batch);
            List<Group> slice = groups.subList(i, end);
            insertStagingSlice(batchId, i, slice);
            int pct = 50 + (int) ((end * 40L) / Math.max(1, groups.size()));
            updateProgress(batchId, "PARSING", pct, "STAGING");
        }

        // 生成预览 JSON 缓存 + 更新批次状态 PREVIEW_READY
        Preview preview = buildPreviewFromGroups(factType, fileName, groups);
        String fieldVersion = computeFieldVersion(factType, groups);
        String previewJson = json(preview);
        pipeline.markPreviewReady(batchId, previewJson, groups.size(), fieldVersion);
    } catch (Exception e) {
        failBatch(batchId, "STAGING", "暂存落库失败：" + safeMsg(e), e);
    }
}

/** 进度查询（轻量，只读一张表，供前端每 500ms 轮询）。 */
public BatchProgress getBatchProgress(Long batchId) {
    return pipeline.batchProgress(batchId);
}

/** Step 2：从暂存表读取预览结果（命中 preview_json 缓存直接返回，否则现场拼）。 */
public StagingPreview getPreviewFromStaging(Long batchId) {
    Map<String, Object> meta = jdbc.queryForMap(
        "SELECT id,data_type,file_name,status,preview_json FROM h3_import_batches WHERE id=?", batchId);
    if (meta == null) throw new IllegalArgumentException("批次不存在：" + batchId);
    String status = String.valueOf(meta.get("status"));
    if ("FAILED".equals(status)) {
        throw new IllegalStateException("批次解析失败，无法预览：请查看 error_detail");
    }
    if (!List.of("PREVIEW_READY","CONFIRMING","COMPLETED","PARTIAL_FAILED").contains(status)) {
        throw new IllegalStateException("批次尚未完成解析：当前 status=" + status);
    }
    String cached = (String) meta.get("preview_json");
    if (cached != null && !cached.isBlank()) {
        Preview p;
        try { p = objectMapper.readValue(cached, Preview.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("预览缓存损坏", e); }
        return new StagingPreview(batchId, p);
    }
    // 缓存失效回退路径：从 staging 表重建
    FactType ft = FactType.from(String.valueOf(meta.get("data_type")));
    List<Group> groups = loadStagingGroups(batchId);
    Preview p = buildPreviewFromGroups(ft, String.valueOf(meta.get("file_name")), groups);
    return new StagingPreview(batchId, p);
}

/** Step 3：从暂存表写入事实表（等价于旧 confirm()，但不再重新解析 Excel）。 */
@Transactional
public Result confirmFromStaging(Long batchId) {
    Map<String, Object> meta = jdbc.queryForMap(
        "SELECT id,data_type,file_name,status FROM h3_import_batches WHERE id=?", batchId);
    if (meta == null) throw new IllegalArgumentException("批次不存在：" + batchId);
    String status = String.valueOf(meta.get("status"));
    if (List.of("CONFIRMING","COMPLETED").contains(status)) {
        // 幂等：已经确认过，直接返回统计
        List<Result> cached = jdbc.query("SELECT * FROM h3_import_batches WHERE id=?", (rs, n) -> new Result(
                rs.getLong("id"), rs.getString("data_type"),
                rs.getInt("total_count"), rs.getInt("created_count"),
                rs.getInt("updated_count"), rs.getInt("unchanged_count"), 0), batchId);
        return cached.isEmpty()
                ? new Result(batchId, String.valueOf(meta.get("data_type")), 0, 0, 0, 0, 0)
                : cached.getFirst();
    }
    if (!"PREVIEW_READY".equals(status)) {
        throw new IllegalStateException("批次不在可确认状态：status=" + status);
    }
    FactType factType = FactType.from(String.valueOf(meta.get("data_type")));
    rejectStoppedFactTypes(factType);
    List<Group> groups = loadStagingGroups(batchId);
    // 流水日记账（第 18 类）：先把账户档案补齐（含无「上期结存」行的欧元账户），再逐组写分录/期初
    if (factType == FactType.CASHFLOW_JOURNAL) ensureCashflowAccounts(groups);
    Map<String, ExistingFact> existingFacts = loadExistingFacts(factType);
    // 已生效出库单变化跟踪：导入事务提交后，逐单对比「当前值 vs 基线」识别修正变化（单据级）
    final boolean trackChanges = factType == FactType.OUTBOUND;
    jdbc.update("UPDATE h3_import_batches SET status='CONFIRMING', progress_stage='WRITING', progress_pct=5 WHERE id=?", batchId);

    int created = 0, updated = 0, unchanged = 0, children = 0;
    int missingCustomer = 0, missingDate = 0, missingQuantity = 0;
    // V17x P5-5：收集受影响范围，供编排管线增量刷新
    Set<Long> writtenFactIds = new LinkedHashSet<>();
    Set<String> customerNames = new LinkedHashSet<>();
    Set<String> salespersonNames = new LinkedHashSet<>();
    for (int i = 0; i < groups.size(); i++) {
        Group group = groups.get(i);
        ExistingFact existing = existingFacts.get(group.externalId());
        String action = existing == null ? "CREATE" : unchanged(existing, group) ? "UNCHANGED" : "UPDATE";
        Long businessId;
        if ("UNCHANGED".equals(action)) {
            unchanged++;
            businessId = existing.id();
            touch(factType, businessId, group.hash());
            // source_hash 只说明氚云源记录未变化，不能保证本地派生/历史修复未改写主表金额。
            // 销售出库的收款主表值以本批原始快照为准；同步不改变本批 UNCHANGED 计数。
            if (factType == FactType.OUTBOUND) {
                syncUnchangedOutbound(businessId, group);
                healMissingOutboundCustomer(businessId, group);
            }
        } else {
            businessId = switch (factType) {
                case PRODUCTION -> saveProduction(group);
                case OUTBOUND -> saveOutbound(group);
                case RECEIPT -> saveReceipt(group, false);
                case SETTLEMENT -> saveReceipt(group, true);
                case SUPPLIER -> saveSupplier(group);
                case PURCHASE_IN -> savePurchaseReceipt(group);
                case OTHER_IN -> saveOtherMovement(group, true);
                case OTHER_OUT -> saveOtherMovement(group, false);
                case TRANSFER -> saveTransfer(group);
case INVOICE_PROFILE -> saveInvoiceProfile(group);
case INVOICE_REQUEST -> saveInvoiceRequest(group);
case PRODUCTION_REPORT -> saveProductionReport(group);
case INVOICE_INPUT -> saveInvoiceInput(group);
case INVOICE_SALES -> saveInvoiceSales(group);
case SUPPLIER_PAYABLE -> saveSupplierPayable(group);
case CASHFLOW_JOURNAL -> saveCashflowEntry(group);
case CASHIER_LOG -> saveCashierRow(group);
};
            if ("CREATE".equals(action)) created++; else updated++;
            children += childCount(factType, group);
            switch (factType) {
                case PRODUCTION -> {
                    if (empty(first(group.main(), "客户选择", "客户信息"))) missingCustomer++;
                    if (empty(group.main().get("工单申请日期"))) missingDate++;
                    if (empty(group.main().get("需求数量"))) missingQuantity++;
                }
                case OUTBOUND -> {
                    if (empty(first(group.main(), "客户名称", "选择经销商"))) missingCustomer++;
                    if (empty(group.main().get("发货日期"))) missingDate++;
                    boolean anyQty = group.rows().stream().anyMatch(r -> !empty(r.get("出库产品明细.出库数量")));
                    if (!anyQty) missingQuantity++;
                }
                case RECEIPT, SETTLEMENT -> {
                    if (empty(first(group.main(), "选择客户", "客户名称", "选择经销商"))) missingCustomer++;
                    if (empty(group.main().get("收款日期"))) missingDate++;
                }
                case SUPPLIER -> {
                    if (empty(first(group.main(), "供应商名称"))) missingCustomer++;
                }
                case PURCHASE_IN -> {
                    // missing_customer_count = 供应商解析失败单数（不静默新建，交人工核对）；
                    // Σ金额/数量差异、缺价行计入 missing_quantity_count。别名自愈只发生在 savePurchaseReceipt。
                    List<String> warnings = purchaseWarnings(group, false);
                    if (warnings.stream().anyMatch(w -> w.startsWith("供应商"))) missingCustomer++;
                    if (warnings.stream().anyMatch(w -> !w.startsWith("供应商"))) missingQuantity++;
                }
                case OTHER_IN, OTHER_OUT -> {
                    // 无客户/供应商概念：missing_customer 不动；校验告警（Σ数量不符/缺价/仓库未匹配）计入 missing_quantity
                    if (!otherMovementWarnings(group, factType == FactType.OTHER_IN).isEmpty()) missingQuantity++;
                    if (empty(first(group.main(), "入库日期", "出库日期"))) missingDate++;
                }
                case TRANSFER -> {
                    // 调拨同样无往来单位：仓库未匹配 / 调出仓=调入仓 / 调拨数量>调出库存快照 / 非已生效 计入 missing_quantity
                    if (!transferWarnings(group).isEmpty()) missingQuantity++;
                    if (empty(group.main().get("调拨日期"))) missingDate++;
                }
                case INVOICE_PROFILE -> {
                    if (empty(first(group.main(), "户名"))) missingCustomer++;
                }
                case INVOICE_REQUEST -> {
                    // 勾稽告警（Σ内容≠申请 / 收付不平 / 类型非法 / 非已生效）计入 missing_quantity；缺开票日期计入 missing_date
                    if (!invoiceRequestWarnings(group).isEmpty()) missingQuantity++;
                    if (empty(group.main().get("开票日期"))) missingDate++;
                }
                case PRODUCTION_REPORT -> {
                    // 报工告警（缺工单号/缺工序/非已生效）计入 missing_quantity；缺报工时间计入 missing_date
                    if (!productionReportWarning(group).isEmpty()) missingQuantity++;
                    if (empty(group.main().get("报工时间"))) missingDate++;
                }
                case INVOICE_INPUT, INVOICE_SALES -> {
                    // 发票台账（2026-09-18 第 16 类）：勾稽/红冲链/子行登记告警计入 missing_quantity；缺开票日期计入 missing_date
                    if (!invoiceWarnings(factType, group).isEmpty()) missingQuantity++;
                    if (empty(group.main().get("开票日期"))) missingDate++;
                }
                case SUPPLIER_PAYABLE -> {
                    // 供应商应付（第 17 类）：付款日期/金额不成对、月份无法解析计入 missing_quantity；无客户概念
                    if (!supplierPayableWarnings(group).isEmpty()) missingQuantity++;
                }
                case CASHFLOW_JOURNAL -> {
                    // 流水日记账（第 18 类）：日期解析失败/收支双空/缺账户 计入 missing_quantity；缺日期计入 missing_date
                    if (!cashflowJournalWarnings(group).isEmpty()) missingQuantity++;
                    if (isCashflowData(group) && cashflowDate(group) == null) missingDate++;
                }
                case CASHIER_LOG -> {
                    // 收银通（第 19 类）：缺摘要/业务员（openid 不在人工字典）、状态非成功 计入 missing_quantity
                    if (!cashierLogWarnings(group).isEmpty()) missingQuantity++;
                    if (group.main().get("交易完成时间") == null) missingDate++;
                }
            }
        }
        // 收集受影响范围（UNCHANGED 也纳入，touch 会更新 last_synced_at 以命中本批增量匹配）
        writtenFactIds.add(businessId);
        collectScopeFromGroup(factType, group, customerNames, salespersonNames);
        saveImportRow(batchId, group, action, businessId);
        if (i % 100 == 0) {
            int pct = 5 + (int) ((i * 90L) / Math.max(1, groups.size()));
            updateProgress(batchId, "CONFIRMING", Math.min(pct, 95), "WRITING");
        }
    }
    if (factType == FactType.OUTBOUND) restoreProtectedOutboundNature();
    // P0-A 状态机修正：事实表写完不标 COMPLETED，标 CONFIRMING/ORCHESTRATING，等编排跑完再切终态。
    // 这样前端只看 status 就能正确判断"是否可进业务中心"，避免编排仍跑却显示已完成。
    jdbc.update("""
        UPDATE h3_import_batches SET status='CONFIRMING', progress_stage='ORCHESTRATING', progress_pct=98,
               created_count=?,updated_count=?,unchanged_count=?,
               missing_customer_count=?,missing_date_count=?,missing_quantity_count=?
         WHERE id=?
        """, created, updated, unchanged, missingCustomer, missingDate, missingQuantity, batchId);

    // 主事务提交后再触发编排（保持与旧 confirm 同一策略）
    final AffectedScope scope = new AffectedScope(
            factType.dbType, batchId,
            new ArrayList<>(writtenFactIds),
            new ArrayList<>(customerNames), new ArrayList<>(salespersonNames));
    final long fid = batchId;
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
            postImportAsyncService.run(scope,fid,trackChanges);
        }
    });
    return new Result(batchId, factType.dbType, groups.size(), created, updated, unchanged, children);
}

/** 全量重导后按稳定出库编号恢复人工确认的销售/退货/换货性质；原单稍后导入时再次补链。 */
private void restoreProtectedOutboundNature() {
    Long runId = jdbc.queryForObject(
            "SELECT MAX(id) FROM h3_reset_runs WHERE status='EXECUTED'", Long.class);
    if (runId == null) return;
    jdbc.update("""
            UPDATE sales_outbounds o
            JOIN h3_reset_outbound_nature_snapshots s ON s.outbound_no=o.outbound_no AND s.reset_run_id=?
            LEFT JOIN sales_outbounds original ON original.outbound_no=s.original_outbound_no
                   AND original.external_source='H3YUN'
            SET o.business_type_confirmed=1,
                o.confirmed_business_type=s.confirmed_business_type,
                o.original_outbound_id=original.id,
                o.business_type_confirmed_by='重导保护快照',
                o.business_type_confirmed_at=NOW()
            WHERE o.external_source='H3YUN' AND o.business_type_confirmed=0
            """, runId);
    jdbc.update("""
            UPDATE sales_outbounds o
            JOIN h3_reset_outbound_nature_snapshots s ON s.outbound_no=o.outbound_no AND s.reset_run_id=?
            JOIN sales_outbounds original ON original.outbound_no=s.original_outbound_no
                   AND original.external_source='H3YUN'
            SET o.original_outbound_id=original.id
            WHERE o.external_source='H3YUN' AND o.business_type_confirmed_by='重导保护快照'
              AND o.original_outbound_id IS NULL AND s.original_outbound_no IS NOT NULL
            """, runId);
}


// ====================================================================
// 辅助方法（新管线内部用）
// ====================================================================

private void updateProgress(long batchId, String status, int pct, String stage) {
    pipeline.updateProgress(batchId, status, pct, stage);
}

private void failBatch(long batchId, String stage, String msg, Exception ex) {
    pipeline.markFailed(batchId, stage, msg, ex);
}

private String safeMsg(Exception e) {
    if (e == null) return "null";
    String raw = e.getMessage();
    if (raw == null || raw.isBlank()) return e.getClass().getSimpleName();
    return raw.length() > 2000 ? raw.substring(0, 2000) : raw;
}

/**
 * 从导入分组中提取"受影响范围"——客户名与业务员名，供编排管线增量缩小候选范围。
 * 仅收集非空值；去重由调用方的 Set 保证。
 */
private void collectScopeFromGroup(FactType factType, Group group,
                                   Set<String> customerNames, Set<String> salespersonNames) {
    if (group == null || group.main() == null) return;
    Map<String, String> m = group.main();
    String customer = switch (factType) {
        case PRODUCTION -> first(m, "客户选择", "客户信息");
        case OUTBOUND -> first(m, "选择客户", "客户名称");
        case RECEIPT -> m.get("客户名称");
        case SETTLEMENT -> first(m, "选择客户", "客户名称", "选择经销商");
        // 供应商/采购入库/其他入出库不进客户/业务员编排范围（无回款/利润联动）
        case SUPPLIER, PURCHASE_IN, OTHER_IN, OTHER_OUT, TRANSFER, INVOICE_PROFILE, INVOICE_REQUEST, PRODUCTION_REPORT,
                INVOICE_INPUT, INVOICE_SALES, SUPPLIER_PAYABLE, CASHFLOW_JOURNAL, CASHIER_LOG -> null;
    };
    String salesperson = switch (factType) {
        case PRODUCTION, OUTBOUND -> m.get("业务员(必填)");
        case RECEIPT -> m.get("业务员");
        case SETTLEMENT -> m.get("归属业务员");
        case SUPPLIER, PURCHASE_IN, OTHER_IN, OTHER_OUT, TRANSFER, INVOICE_PROFILE, INVOICE_REQUEST, PRODUCTION_REPORT, SUPPLIER_PAYABLE, CASHFLOW_JOURNAL -> null;
        // 流水日记账人工「报销人」仅作台账展示，不进编排范围
        case CASHIER_LOG -> null;
        // 销项发票业务员仅作台账展示，不进编排范围
        case INVOICE_INPUT -> null;
        case INVOICE_SALES -> m.get("业务员");
    };
    if (customer != null && !customer.isBlank()) customerNames.add(customer.trim());
    if (salesperson != null && !salesperson.isBlank()) salespersonNames.add(salesperson.trim());
}

/**
 * P0-C：预览行辅助字段提取——金额/客户/业务员，按 FactType 用不同 Excel 字段。
 * 金额统一返回非空字符串（前端按需要加 ¥ 前缀）；PRODUCTION 的"需求数量"也放这里作为关键数字。
 */
private String extractPreviewAmount(FactType factType, Map<String, String> m) {
    if (m == null) return null;
    return switch (factType) {
        case PRODUCTION -> first(m, "需求数量");
        case OUTBOUND -> first(m, "销售金额", "实际销售金额", "本次销售金额");
        case RECEIPT -> first(m, "本次收款金额", "收款金额");
        case SETTLEMENT -> first(m, "合计已收金额", "本次收款金额");
        case SUPPLIER -> null;
        case PURCHASE_IN -> first(m, "合计采购金额");
        // 其他入库主表无金额列（明细仅有单价）；其他出库取主表合计金额
        case OTHER_IN -> null;
        case OTHER_OUT -> first(m, "合计金额");
        case TRANSFER -> null;  // 调拨主表无金额列
case INVOICE_PROFILE -> null;  // 开票资料无金额列
case INVOICE_REQUEST -> first(m, "本次申请开票金额");
case PRODUCTION_REPORT -> first(m, "报工数量");
case INVOICE_INPUT, INVOICE_SALES -> first(m, "价税合计");
case SUPPLIER_PAYABLE -> first(m, "应付货款");
case CASHFLOW_JOURNAL -> first(m, "收入", "支出");
case CASHIER_LOG -> first(m, "交易金额(元)");
};
}
private String extractPreviewCustomer(FactType factType, Map<String, String> m) {
    if (m == null) return null;
    return switch (factType) {
        case PRODUCTION -> first(m, "客户选择", "客户信息");
        case OUTBOUND -> first(m, "选择客户", "客户名称");
        case RECEIPT -> m.get("客户名称");
        case SETTLEMENT -> first(m, "选择客户", "客户名称", "选择经销商");
        case SUPPLIER -> first(m, "供应商状态");
        case PURCHASE_IN -> first(m, "选择供应商");
        // 其他入/出库无往来单位，预览对象列显示仓库（单据的唯一空间维度）
        case OTHER_IN -> first(m, "选择入库仓库", "入库仓库（隐）");
        case OTHER_OUT -> first(m, "选择出库仓库", "出库仓库（隐）");
        case TRANSFER -> transferRoute(m);
case INVOICE_PROFILE, INVOICE_REQUEST -> first(m, "户名");
case PRODUCTION_REPORT -> first(m, "报工人所属部门", "产线文本");
// 发票台账预览「对象」列 = 交易对手方：进项=销方（供应商），销项=购买方（客户）
case INVOICE_INPUT -> first(m, "销方名称");
case INVOICE_SALES -> first(m, "购买方名称");
case SUPPLIER_PAYABLE -> first(m, "供应商");
case CASHFLOW_JOURNAL -> first(m, "账户");
case CASHIER_LOG -> first(m, "摘要", "付款人识别号");
};
}
private String extractPreviewSalesperson(FactType factType, Map<String, String> m) {
    if (m == null) return null;
    return switch (factType) {
        case PRODUCTION, OUTBOUND -> m.get("业务员(必填)");
        case RECEIPT -> m.get("业务员");
        case SETTLEMENT -> m.get("归属业务员");
        case SUPPLIER -> first(m, "拥有者(必填)", "拥有者");
        case PURCHASE_IN -> first(m, "仓管员");
        case OTHER_IN -> first(m, "仓管员");
        case OTHER_OUT -> first(m, "业务员", "仓管员");
        case TRANSFER -> first(m, "仓管员", "仓管员(调入)");
case INVOICE_PROFILE -> first(m, "拥有者(必填)", "拥有者");
case INVOICE_REQUEST -> first(m, "业务员");
case PRODUCTION_REPORT -> first(m, "报工人");
case INVOICE_INPUT -> null;
case INVOICE_SALES -> first(m, "业务员");
case SUPPLIER_PAYABLE -> null;
case CASHFLOW_JOURNAL -> first(m, "报销人");
case CASHIER_LOG -> first(m, "业务员");
};
}

private void insertStagingSlice(long batchId, int startSeq, List<Group> slice) {
    // 骨架统一收口：逐条 INSERT + ON DUPLICATE KEY UPDATE，批量事务由调用方控制（每批隐式提交）
    for (int j = 0; j < slice.size(); j++) {
        Group g = slice.get(j);
        pipeline.insertStagingRow(batchId, startSeq + j, g.key(), g.externalId(), g.hash(),
                json(g.main()), json(g.rows()));
    }
}

private Preview buildPreviewFromGroups(FactType factType, String fileName, List<Group> groups) {
    Map<String, ExistingFact> existing = loadExistingFacts(factType);
    Map<String, Map<String, Object>> previousSnapshots =
            loadPreviousSnapshots(factType, groups.stream().map(Group::externalId).toList());
    List<RowPreview> rows = new ArrayList<>(groups.size());
    int creates = 0, updates = 0, unch = 0;
    for (Group g : groups) {
        ExistingFact ef = existing.get(g.externalId());
        String action = ef == null ? "CREATE" : unchanged(ef, g) ? "UNCHANGED" : "UPDATE";
        if ("CREATE".equals(action)) creates++;
        else if ("UPDATE".equals(action)) updates++;
        else unch++;
        List<FieldChange> changes = "UPDATE".equals(action)
                ? diffSnapshot(previousSnapshots.get(g.externalId()), g) : List.of();
        // 采购入库：Σ明细 vs 主表合计、缺价、供应商未解析 → 预览即拦（不阻断，可见即可）
        // 其他入/出库（2026-09-17 P1）：仓库未匹配、Σ数量 vs 主表合计、缺价行 —— 同一策略
        String warnings = null;
        if (factType == FactType.PURCHASE_IN) {
            List<String> groupWarnings = purchaseWarnings(g, false);
            if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
        } else if (factType == FactType.OTHER_IN || factType == FactType.OTHER_OUT) {
            List<String> groupWarnings = otherMovementWarnings(g, factType == FactType.OTHER_IN);
            if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
        } else if (factType == FactType.TRANSFER) {
            // 库存调拨（2026-09-17 P2）：仓库未匹配 / 调出仓=调入仓 / 超调出库存快照 / 非已生效状态
            // ⚠️ 新增 FactType 时务必同时补此处与 confirm 段的填充点，否则预览告警静默为空（P1 踩过）
            List<String> groupWarnings = transferWarnings(g);
            if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
        }
            else if (factType == FactType.INVOICE_PROFILE) {
                // 开票资料：户名缺失/非已生效标记（未关联 customers 不是错误，台账列表展示）
                String pw = invoiceProfileWarning(g);
                if (!pw.isEmpty()) warnings = pw;
            } else if (factType == FactType.INVOICE_REQUEST) {
                List<String> groupWarnings = invoiceRequestWarnings(g);
                if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
            } else if (factType == FactType.PRODUCTION_REPORT) {
                // 生产报工（2026-09-17 第 15 类）：缺工单号/缺工序/非已生效 → 预览即标
                String pw = productionReportWarning(g);
                if (!pw.isEmpty()) warnings = pw;
            } else if (factType == FactType.INVOICE_INPUT || factType == FactType.INVOICE_SALES) {
                // 发票台账（2026-09-18 第 16 类）：勾稽/红冲链/收款子行 → 预览即标（⚠️ confirm 段同款填充点已补）
                List<String> groupWarnings = invoiceWarnings(factType, g);
                if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
            } else if (factType == FactType.SUPPLIER_PAYABLE) {
                // 供应商应付（2026-09-18 第 17 类）：付款日期/金额不成对、月份异常 → 预览即标（confirm 段同款已补）
                List<String> groupWarnings = supplierPayableWarnings(g);
                if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
            } else if (factType == FactType.CASHFLOW_JOURNAL) {
                // 流水日记账（2026-09-18 第 18 类）：日期解析失败/收支双空/账户缺失 → 预览即标（confirm 段同款已补）
                List<String> groupWarnings = cashflowJournalWarnings(g);
                if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
            } else if (factType == FactType.CASHIER_LOG) {
                // 收银通（2026-09-18 第 19 类）：缺摘要/业务员、状态非成功 → 预览即标（confirm 段同款已补）
                List<String> groupWarnings = cashierLogWarnings(g);
                if (!groupWarnings.isEmpty()) warnings = String.join("；", groupWarnings);
            }
        rows.add(new RowPreview(g.key(), g.externalId(), title(factType, g.main()), action, childCount(factType, g),
                extractPreviewAmount(factType, g.main()),
                extractPreviewCustomer(factType, g.main()),
                extractPreviewSalesperson(factType, g.main()), warnings, changes));
    }
    return new Preview(factType.dbType, fileName, groups.size(), creates, updates, unch, rows);
}

/**
 * 每个外部业务 ID 最近一次已确认的原始快照；用于本次 UPDATE 的新旧字段对照。
 *
 * <p>只查本次预览涉及的外部 ID。早前实现用 ROW_NUMBER() OVER 在没有 ID 条件下扫全表，
 * h3_import_rows 累积后单次要跑分钟到小时级，且长事务会把整库的元数据锁拖死
 * （表现为所有接口 Hang、连小表都读不出来）。改为「IN (外部 ID 分批) + GROUP BY 取 MAX(id)」，
 * 语义不变且能走 idx_h3ir_external。分块是为了避免超长 IN 列表。</p>
 */
private Map<String, Map<String, Object>> loadPreviousSnapshots(FactType factType, Collection<String> externalIds) {
    Map<String, Map<String, Object>> result = new HashMap<>();
    List<String> ids = externalIds.stream().filter(Objects::nonNull).distinct().toList();
    final int chunkSize = 500;
    for (int from = 0; from < ids.size(); from += chunkSize) {
        List<String> chunk = ids.subList(from, Math.min(from + chunkSize, ids.size()));
        String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
        String sql = """
                SELECT r.external_id, r.raw_data
                FROM h3_import_rows r
                JOIN (
                    SELECT r2.external_id AS eid, MAX(r2.id) AS latest_id
                    FROM h3_import_rows r2
                    JOIN h3_import_batches b2 ON b2.id = r2.batch_id
                    WHERE b2.data_type = ? AND r2.action IN ('CREATE','UPDATE','UNCHANGED')
                      AND r2.external_id IN (%s)
                    GROUP BY r2.external_id
                ) m ON m.latest_id = r.id
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>();
        args.add(factType.dbType);
        args.addAll(chunk);
        jdbc.query(sql, rs -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> snapshot = objectMapper.readValue(rs.getString("raw_data"), Map.class);
                result.put(rs.getString("external_id"), snapshot);
            } catch (Exception ignored) {
                // 单条历史 JSON 损坏不应阻塞整批导入；该行前端显示“无可用历史快照”。
            }
        }, args.toArray());
    }
    return result;
}

private List<FieldChange> diffSnapshot(Map<String, Object> oldSnapshot, Group current) {
    if (oldSnapshot == null) return List.of();
    Map<String, String> oldFlat = new LinkedHashMap<>();
    Map<String, String> newFlat = new LinkedHashMap<>();
    flattenSnapshot("主表", oldSnapshot.get("main"), oldFlat);
    flattenSnapshot("明细", oldSnapshot.get("rows"), oldFlat);
    flattenSnapshot("主表", current.main(), newFlat);
    flattenSnapshot("明细", current.rows(), newFlat);
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    paths.addAll(oldFlat.keySet());
    paths.addAll(newFlat.keySet());
    List<FieldChange> changes = new ArrayList<>();
    for (String path : paths) {
        String before = oldFlat.get(path);
        String after = newFlat.get(path);
        if (!Objects.equals(normalizeDiffValue(before), normalizeDiffValue(after))) {
            changes.add(new FieldChange(path, displayDiffValue(before), displayDiffValue(after)));
        }
    }
    return changes;
}

@SuppressWarnings("unchecked")
private void flattenSnapshot(String path, Object value, Map<String, String> target) {
    if (value instanceof Map<?, ?> map) {
        map.forEach((k, v) -> flattenSnapshot(path + " · " + k, v, target));
    } else if (value instanceof List<?> list) {
        for (int i = 0; i < list.size(); i++) flattenSnapshot(path + "[" + (i + 1) + "]", list.get(i), target);
    } else {
        target.put(path, value == null ? null : String.valueOf(value));
    }
}

private static String normalizeDiffValue(String value) {
    return value == null ? "" : value.trim();
}

private static String displayDiffValue(String value) {
    return value == null || value.isBlank() ? "空" : value;
}

private String computeFieldVersion(FactType type, List<Group> groups) {
    if (groups.isEmpty()) return null;
    // 用第 1 组 main 的 key 列表哈希作为字段版本（表头变更则 version 变）
    Map<String, Object> keys = new LinkedHashMap<>();
    keys.put("type", type.dbType);
    keys.put("mainKeys", new ArrayList<>(groups.getFirst().main().keySet()));
    // 子表 key 取前 5 行去重（避免全量哈希过大）
    Set<String> subKeys = new LinkedHashSet<>();
    for (int i = 0; i < Math.min(5, groups.size()); i++) {
        for (Map<String, String> r : groups.get(i).rows()) subKeys.addAll(r.keySet());
        if (subKeys.size() > 200) break;
    }
    keys.put("subKeysSample", new ArrayList<>(subKeys));
    return hash(keys).substring(0, 16);
}

private List<Group> loadStagingGroups(long batchId) {
    List<Group> result = new ArrayList<>();
    jdbc.query("""
        SELECT group_key,external_id,source_hash,main_json,rows_json
          FROM h3_import_staging_rows WHERE batch_id=? ORDER BY row_seq ASC
        """, rs -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> main = objectMapper.readValue(rs.getString("main_json"), Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, String>> rows = objectMapper.readValue(rs.getString("rows_json"), List.class);
                result.add(new Group(rs.getString("group_key"), rs.getString("external_id"),
                        main, rows, rs.getString("source_hash")));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("读取暂存行 JSON 失败：batch=" + batchId, e);
            }
        }, batchId);
    return result;
}

private String sha256(byte[] bytes) {
    try {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(d.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
    }
}

/** 内存 MultipartFile 适配器，避免在异步管线中重复落临时文件。 */
private record InMemoryMultipartFile(String originalFilename, byte[] bytes) implements MultipartFile {
    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return "application/octet-stream"; }
    @Override public boolean isEmpty() { return bytes.length == 0; }
    @Override public long getSize() { return bytes.length; }
    @Override public byte[] getBytes() { return bytes; }
    @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
    @Override public void transferTo(java.io.File dest) throws IOException {
        Files.write(dest.toPath(), bytes);
    }
}
}
