package com.liqiang.archive.service;

import com.liqiang.archive.dto.ExpenseImportDtos.ImportGroupRow;
import com.liqiang.archive.dto.ExpenseImportDtos.ImportPreview;
import com.liqiang.archive.dto.ExpenseImportDtos.ImportResult;
import com.liqiang.archive.entity.DingtalkSyncRun;
import com.liqiang.archive.entity.ExpenseClaim;
import com.liqiang.archive.entity.ExpenseItem;
import com.liqiang.archive.entity.Employee;
import com.liqiang.archive.entity.EmployeeExternalMapping;
import com.liqiang.archive.repository.DingtalkSyncRunRepository;
import com.liqiang.archive.repository.EmployeeExternalMappingRepository;
import com.liqiang.archive.repository.EmployeeRepository;
import com.liqiang.archive.repository.ExpenseClaimRepository;
import com.liqiang.archive.repository.ExpenseItemRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 钉钉报销 Excel 导入（2026-08-26）。
 * <p>
 * 背景：钉钉标准版 OA 审批 API 有调用次数限制，不再全量拉取；
 * 改为在钉钉后台按需导出「日常报销」Excel 后，通过本服务导入归档。
 * <p>
 * Excel 约定（钉钉导出原样）：
 * <ul>
 *   <li>第 1 行为分组表头、第 2 行为明细列名，数据从第 3 行开始；</li>
 *   <li>每行 = 一条报销明细；同一「审批编号」的多行合并为一条报销单；</li>
 *   <li>金额为「报销金额（元）」列，明细日期为「费用发生日期」，费用类型为「费用类型」。</li>
 * </ul>
 * 幂等：优先按 external_source=DINGTALK + external_id=审批编号 查重；
 * 未命中再按 claim_no=审批编号 兜底（兼容 API 同步写入的单：其 external_id=钉钉实例ID）。
 * 内容 hash 与上次一致 → UNCHANGED；不一致 → 更新主单并重写明细。
 * 导入完成后写入一条 dingtalk_sync_runs（triggered_by=EXCEL_IMPORT）审计记录。
 */
@Service
public class ExpenseImportService {

    private static final String EXTERNAL_SOURCE = "DINGTALK";

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss")
    };

    private static final DateTimeFormatter[] DATETIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    };

    private final ExpenseClaimRepository claimRepo;
    private final ExpenseItemRepository itemRepo;
    private final EmployeeExternalMappingRepository mappingRepo;
    private final EmployeeRepository employeeRepo;
    private final DingtalkSyncRunRepository runRepo;
    private final DepartmentService departmentService;

    public ExpenseImportService(ExpenseClaimRepository claimRepo,
                                ExpenseItemRepository itemRepo,
                                EmployeeExternalMappingRepository mappingRepo,
                                EmployeeRepository employeeRepo,
                                DingtalkSyncRunRepository runRepo,
                                DepartmentService departmentService) {
        this.claimRepo = claimRepo;
        this.itemRepo = itemRepo;
        this.mappingRepo = mappingRepo;
        this.employeeRepo = employeeRepo;
        this.runRepo = runRepo;
        this.departmentService = departmentService;
    }

    // ==================== 对外接口 ====================

    /** 解析 Excel 并生成导入预览（不落库）。 */
    public ImportPreview preview(MultipartFile file) {
        String fileName = file == null ? null : file.getOriginalFilename();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            List<ParsedGroup> groups = parseGroups(wb);
            int create = 0, update = 0, unchanged = 0, error = 0, missed = 0;
            List<ImportGroupRow> rows = new ArrayList<>(groups.size());
            for (ParsedGroup g : groups) {
                if (g.error != null) {
                    error++;
                    rows.add(g.toRow("ERROR"));
                    continue;
                }
                derive(g);
                resolveExisting(g);
                String action = g.existing == null ? "CREATE"
                        : g.hash.equals(g.existing.getSourceHash()) ? "UNCHANGED" : "UPDATE";
                if ("CREATE".equals(action)) create++;
                else if ("UPDATE".equals(action)) update++;
                else unchanged++;
                if (g.mappingMissed) missed++;
                rows.add(g.toRow(action));
            }
            return new ImportPreview(fileName, totalDataRows(groups), groups.size(),
                    create, update, unchanged, error, missed, rows);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel 解析失败，请确认文件为钉钉「日常报销」导出格式：" + e.getMessage(), e);
        }
    }

    /** 解析 Excel 并落库；approvalNos 为 null/空时导入全部，否则只导入选中的审批编号。 */
    @Transactional
    public ImportResult confirm(MultipartFile file, List<String> approvalNos) {
        Set<String> selected = (approvalNos == null || approvalNos.isEmpty())
                ? null : new HashSet<>(approvalNos);
        List<String> errors = new ArrayList<>();
        int total = 0, created = 0, updated = 0, unchanged = 0, error = 0, missed = 0, items = 0;
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            List<ParsedGroup> groups = parseGroups(wb);
            for (ParsedGroup g : groups) {
                if (selected != null && (g.approvalNo == null || !selected.contains(g.approvalNo))) continue;
                total++;
                if (g.error != null) {
                    error++;
                    errors.add(g.error);
                    continue;
                }
                derive(g);
                deriveForPersist(g);
                resolveExisting(g);
                if (g.existing == null) {
                    createClaim(g);
                    created++;
                    items += g.items.size();
                } else if (!g.hash.equals(g.existing.getSourceHash())) {
                    updateClaim(g);
                    updated++;
                    items += g.items.size();
                } else {
                    unchanged++;
                }
                if (g.mappingMissed) missed++;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel 解析失败，请确认文件为钉钉「日常报销」导出格式：" + e.getMessage(), e);
        }
        // 写入同步审计记录（日常报销同步面板可见；EXCEL_IMPORT 触发类型）
        LocalDateTime now = LocalDateTime.now();
        DingtalkSyncRun run = new DingtalkSyncRun();
        run.setSyncType("DAILY_REIMBURSEMENT");
        run.setTriggeredBy("EXCEL_IMPORT");
        run.setTriggeredByName(file.getOriginalFilename());
        run.setStatus(error > 0 ? "PARTIAL" : "SUCCESS");
        run.setStartedAt(now);
        run.setFinishedAt(now);
        run.setDurationMs(0L);
        run.setFetchedCount(total);
        run.setCreatedCount(created);
        run.setUpdatedCount(updated);
        run.setUnchangedCount(unchanged);
        run.setFailedCount(error);
        run.setMappingMissed(missed);
        run.setErrorSummary(errors.isEmpty() ? null
                : String.join("；", errors.stream().limit(5).toList()));
        run.setExtraParams("Excel 导入：共 " + total + " 单、" + items + " 条明细（含选中单" + total + "）");
        runRepo.save(run);
        return new ImportResult(total, created, updated, unchanged, error, missed, items, errors);
    }

    /** 查重：先按 external_id=审批编号 精确匹配；未命中再按 claim_no 兜底（兼容 API 同步旧数据，其 external_id=钉钉实例ID）。 */
    private void resolveExisting(ParsedGroup g) {
        ExpenseClaim c = claimRepo.findByExternalSourceAndExternalId(EXTERNAL_SOURCE, g.approvalNo).orElse(null);
        if (c == null) {
            c = claimRepo.findByClaimNo(g.approvalNo).orElse(null);
            g.matchedViaClaimNo = c != null;
        }
        g.existing = c;
    }

    // ==================== 解析 ====================

    /** 按审批编号分组，保持首次出现顺序；第 1、2 行为表头，数据从第 3 行（索引 2）开始。 */
    private List<ParsedGroup> parseGroups(Workbook wb) {
        Sheet sheet = wb.getSheetAt(0);
        Map<String, ParsedGroup> byNo = new LinkedHashMap<>();
        List<ParsedGroup> order = new ArrayList<>();
        for (int r = 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            ParsedRow pr = parseRow(row);
            if (pr == null) continue; // 空行
            if (pr.group.approvalNo == null || pr.group.approvalNo.isBlank()) {
                ParsedGroup bad = pr.group;
                bad.error = "第 " + (r + 1) + " 行缺少「审批编号」";
                order.add(bad);
                continue;
            }
            ParsedGroup existing = byNo.get(pr.group.approvalNo);
            if (existing == null) {
                byNo.put(pr.group.approvalNo, pr.group);
                order.add(pr.group);
                if (pr.item != null) {
                    existing = pr.group;
                    existing.accumulateItem(pr.item);
                }
            } else if (pr.item != null) {
                existing.accumulateItem(pr.item);
            }
        }
        return order;
    }

    /** 解析单行；返回 null 表示整行为空。明细金额为空的明细行被忽略（不阻断整组）。 */
    private ParsedRow parseRow(Row row) {
        ParsedGroup g = new ParsedGroup();
        g.approvalNo = truncate(cellText(row.getCell(13)), 100);
        g.submitterName = truncate(cellText(row.getCell(9)), 100);
        g.remark = truncate(cellText(row.getCell(10)), 1000);
        g.created = cellDateTime(row.getCell(14));
        g.sourceCreatedBy = truncate(cellText(row.getCell(15)), 100);
        g.approvalResultText = cellText(row.getCell(17));
        g.approvalStatusText = cellText(row.getCell(18));
        g.updated = cellDateTime(row.getCell(19));
        g.finished = cellDate(row.getCell(20));
        g.department = truncate(cellText(row.getCell(21)), 100);
        g.title = truncate(cellText(row.getCell(22)), 200);

        // 明细（金额为空则忽略该明细行）
        ParsedItem item = new ParsedItem();
        item.externalId = truncate(cellText(row.getCell(1)), 100);
        item.amount = cellMoney(row.getCell(2));
        item.itemDate = cellDate(row.getCell(4));
        item.expenseType = truncate(cellText(row.getCell(5)), 50);
        item.itemDesc = truncate(cellText(row.getCell(6)), 500);
        item.invoiceNo = truncate(cellText(row.getCell(7)), 100);

        boolean rowEmpty = (g.approvalNo == null || g.approvalNo.isBlank())
                && (g.submitterName == null || g.submitterName.isBlank())
                && item.amount == null
                && (item.itemDesc == null || item.itemDesc.isBlank());
        if (rowEmpty) return null;

        if (item.amount != null) {
            return new ParsedRow(g, item);
        }
        // 金额为空的明细：忽略明细，但组仍可导入（后续行会补齐）
        return new ParsedRow(g, null);
    }

    // ==================== 派生字段 ====================

    private void derive(ParsedGroup g) {
        g.status = mapStatus(g.approvalResultText, g.approvalStatusText);
        g.category = guessCategory(g);
        g.claimDate = g.created == null ? null : g.created.toLocalDate();
        AtomicBoolean missed = new AtomicBoolean(false);
        g.employeeId = resolveEmployeeId(g.submitterName, missed, false);
        g.mappingMissed = missed.get();
        g.hash = computeHash(g);
    }

    private void deriveForPersist(ParsedGroup g) {
        AtomicBoolean missed = new AtomicBoolean(false);
        g.employeeId = resolveEmployeeId(g.submitterName, missed, true);
        g.mappingMissed = missed.get();
    }

    /** 审批状态映射：优先看「审批结果」列，「--」/空再看「审批状态」列。 */
    private static String mapStatus(String resultText, String statusText) {
        String r = norm(resultText);
        if (r.isEmpty() || "--".equals(r) || "-".equals(r)) {
            String s = norm(statusText);
            if (s.contains("审批中") || s.contains("待审批") || s.contains("进行中")) return "PENDING";
            if (s.contains("撤销") || s.contains("撤回")) return "CANCELED";
            if (s.contains("终止")) return "TERMINATED";
            if (s.contains("结束") || s.contains("完成")) return "APPROVED";
            return "PENDING";
        }
        if (r.contains("通过")) return "APPROVED";
        if (r.contains("拒绝") || r.contains("驳回")) return "REJECTED";
        if (r.contains("撤销") || r.contains("撤回")) return "CANCELED";
        if (r.contains("终止")) return "TERMINATED";
        return "PENDING";
    }

    /** 费用分类：优先按组内金额最大的明细「费用类型」列映射，无识别结果再回退标题关键词。 */
    private static String guessCategory(ParsedGroup g) {
        String byType = categoryFromExpenseType(g.dominantExpenseType());
        if (byType != null) return byType;
        String s = g.title == null ? "" : g.title;
        if (s.contains("差旅") || s.contains("机票") || s.contains("高铁") || s.contains("火车")
                || s.contains("住宿") || s.contains("出差")) return "TRAVEL";
        if (s.contains("招待") || s.contains("宴请")) return "ENTERTAIN";
        if (s.contains("办公") || s.contains("文具") || s.contains("打印") || s.contains("耗材")) return "OFFICE";
        if (s.contains("出租") || s.contains("滴滴") || s.contains("打车") || s.contains("地铁")
                || s.contains("交通")) return "TRANSPORT";
        if (s.contains("餐饮") || s.contains("餐费") || s.contains("午餐") || s.contains("聚餐")) return "MEAL";
        if (s.contains("对公") || s.contains("付款") || s.contains("货款")) return "CORP_PAY";
        return "OTHER";
    }

    /** 钉钉「费用类型」标准值 → 系统分类。 */
    private static String categoryFromExpenseType(String type) {
        if (type == null) return null;
        String t = type.trim();
        if (t.contains("差旅") || t.contains("机票") || t.contains("住宿")) return "TRAVEL";
        if (t.contains("招待") || t.contains("宴请")) return "ENTERTAIN";
        if (t.contains("办公") || t.contains("文具") || t.contains("耗材")) return "OFFICE";
        if (t.contains("打车") || t.contains("交通") || t.contains("出租") || t.contains("地铁")) return "TRANSPORT";
        if (t.contains("餐饮") || t.contains("餐费")) return "MEAL";
        if (t.contains("对公") || t.contains("货款") || t.contains("付款")) return "CORP_PAY";
        return null;
    }

    /** 员工映射：先按钉钉外部姓名精确匹配映射表，再按系统员工姓名唯一匹配。 */
    private Long resolveEmployeeId(String name, AtomicBoolean missed, boolean persistMapping) {
        if (name == null || name.isBlank()) {
            missed.set(true);
            return null;
        }
        String n = name.trim();
        List<Long> mapped = mappingRepo.findEmployeeIdsBySourceAndExternalName(EXTERNAL_SOURCE, n);
        if (mapped.size() == 1) return mapped.get(0);
        List<Employee> employees = employeeRepo.findByName(n);
        if (employees.size() == 1) {
            if (persistMapping) {
                // 补充 NAME_LINK 映射，便于后续钉钉同步复用（externalUserId 用 NAME_LINK# 前缀占位）
                try {
                    EmployeeExternalMapping m = new EmployeeExternalMapping();
                    m.setEmployeeId(employees.get(0).getId());
                    m.setSourceSystem(EXTERNAL_SOURCE);
                    m.setExternalUserId("NAME_LINK#" + n);
                    m.setExternalName(n);
                    mappingRepo.save(m);
                } catch (Exception ignore) {
                    // 已存在映射/唯一约束冲突时忽略
                }
            }
            return employees.get(0).getId();
        }
        missed.set(true);
        return null;
    }

    /** 内容 hash：与 DingtalkSyncService 同字段口径，排除 id/时间戳等自维护字段。 */
    private String computeHash(ParsedGroup g) {
        StringBuilder fp = new StringBuilder();
        for (ParsedItem it : g.items) {
            fp.append(nvl(it.externalId)).append('|')
              .append(it.amount == null ? "" : it.amount.toPlainString()).append('|')
              .append(nvl(it.expenseType)).append('|')
              .append(it.itemDate == null ? "" : it.itemDate.toString()).append('|')
              .append(nvl(it.itemDesc)).append('|')
              .append(nvl(it.invoiceNo)).append(';');
        }
        return sha256(String.join("||",
                nvl(g.approvalNo), nvl(g.title), nvl(g.status), nvl(g.category),
                nvl(g.submitterName), nvl(g.department),
                g.created == null ? "" : g.created.toString(),
                g.finished == null ? "" : g.finished.toString(),
                g.totalAmount == null ? "" : g.totalAmount.toPlainString(),
                nvl(g.remark),
                fp.toString()));
    }

    // ==================== 落库 ====================

    private void createClaim(ParsedGroup g) {
        ExpenseClaim c = new ExpenseClaim();
        c.setExternalSource(EXTERNAL_SOURCE);
        applyFields(c, g);
        c = claimRepo.save(c);
        saveItems(c.getId(), g);
    }

    private void updateClaim(ParsedGroup g) {
        ExpenseClaim c = g.existing;
        applyFields(c, g);
        claimRepo.save(c);
        itemRepo.deleteByExpenseClaimId(c.getId());
        saveItems(c.getId(), g);
    }

    private void applyFields(ExpenseClaim c, ParsedGroup g) {
        // 经 claim_no 兜底匹配到 API 同步旧单时保留其钉钉实例ID（external_id），避免破坏 API 同步的幂等键
        if (!g.matchedViaClaimNo) {
            c.setExternalId(g.approvalNo);
        }
        c.setClaimNo(g.approvalNo);
        c.setTitle(g.title);
        c.setApprovalStatus(g.status);
        c.setExpenseCategory(g.category);
        c.setEmployeeId(g.employeeId);
        c.setSubmitterNameSnapshot(g.submitterName);
        c.setSubmitterDeptSnapshot(g.department);
        c.setDepartmentName(g.department);
        c.setDepartmentId(departmentService.resolveOrCreateByName(g.department, "DINGTALK"));
        c.setClaimDate(g.claimDate);
        c.setApprovalFinishedDate(g.finished);
        c.setTotalAmount(g.totalAmount);
        c.setApprovedAmount(g.totalAmount); // P6.1 不区分申请/核定
        c.setRemark(g.remark);
        c.setSourceCreatedBy(g.sourceCreatedBy != null ? g.sourceCreatedBy : g.submitterName);
        c.setSourceCreatedAt(g.created);
        c.setSourceUpdatedAt(g.updated);
        c.setSourceHash(g.hash);
        c.setLastSyncedAt(LocalDateTime.now());
    }

    private void saveItems(Long claimId, ParsedGroup g) {
        int seq = 1;
        for (ParsedItem it : g.items) {
            ExpenseItem e = new ExpenseItem();
            e.setExpenseClaimId(claimId);
            e.setExternalId(it.externalId);
            e.setSequenceNo(seq++);
            e.setExpenseType(it.expenseType);
            e.setItemDate(it.itemDate);
            e.setItemDesc(it.itemDesc);
            e.setAmount(it.amount);
            e.setInvoiceNo(it.invoiceNo);
            itemRepo.save(e);
        }
    }

    // ==================== POI 单元格解析 ====================

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        try {
            if (cell.getCellType() == CellType.STRING) {
                String s = cell.getStringCellValue();
                return s == null ? "" : s.trim();
            }
            if (cell.getCellType() == CellType.NUMERIC) {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            }
            if (cell.getCellType() == CellType.BOOLEAN) {
                return String.valueOf(cell.getBooleanCellValue());
            }
            if (cell.getCellType() == CellType.FORMULA) {
                try {
                    return cell.getRichStringCellValue().getString().trim();
                } catch (Exception e1) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        return "";
                    }
                }
            }
        } catch (Exception ignore) {
            // 非法单元格按空处理
        }
        return "";
    }

    private static BigDecimal cellMoney(Cell cell) {
        String s = cellText(cell).replace(",", "").replace("￥", "").replace("¥", "").replace(" ", "").trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate cellDate(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
        } catch (Exception ignore) {
            // 数值日期解析失败则走文本
        }
        String s = cellText(cell);
        if (s.isEmpty()) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignore) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    private static LocalDateTime cellDateTime(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue();
            }
        } catch (Exception ignore) {
            // 数值日期解析失败则走文本
        }
        String s = cellText(cell);
        if (s.isEmpty()) return null;
        for (DateTimeFormatter f : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (DateTimeParseException ignore) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    private static int totalDataRows(List<ParsedGroup> groups) {
        int n = 0;
        for (ParsedGroup g : groups) {
            n += Math.max(1, g.items.size());
        }
        return n;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replace("\n", "").replace("\r", "");
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ==================== 内部结构 ====================

    private record ParsedRow(ParsedGroup group, ParsedItem item) {
    }

    private static final class ParsedGroup {
        String approvalNo;
        String title;
        String submitterName;
        String department;
        String remark;
        String sourceCreatedBy;
        String approvalResultText;
        String approvalStatusText;
        LocalDateTime created;
        LocalDateTime updated;
        LocalDate finished;
        final List<ParsedItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        String error;
        final Map<String, BigDecimal> typeAmounts = new LinkedHashMap<>();

        void accumulateItem(ParsedItem item) {
            items.add(item);
            if (item.amount != null) {
                totalAmount = totalAmount.add(item.amount);
                String t = item.expenseType == null ? "" : item.expenseType.trim();
                typeAmounts.merge(t, item.amount, BigDecimal::add);
            }
        }

        /** 组内金额最大的费用类型（用于分类），无明细时返回空串。 */
        String dominantExpenseType() {
            String best = "";
            BigDecimal bestAmt = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> e : typeAmounts.entrySet()) {
                if (e.getValue().compareTo(bestAmt) > 0) {
                    best = e.getKey();
                    bestAmt = e.getValue();
                }
            }
            return best;
        }

        // 派生
        String status;
        String category;
        LocalDate claimDate;
        Long employeeId;
        boolean mappingMissed;
        String hash;
        ExpenseClaim existing;
        boolean matchedViaClaimNo;

        ImportGroupRow toRow(String action) {
            return new ImportGroupRow(approvalNo, title, submitterName, department,
                    status == null ? "PENDING" : status, category, items.size(), totalAmount,
                    claimDate, finished, action, error, mappingMissed);
        }
    }

    private static final class ParsedItem {
        String externalId;
        BigDecimal amount;
        LocalDate itemDate;
        String expenseType;
        String itemDesc;
        String invoiceNo;
    }
}
