package com.liqiang.archive.service;

import com.liqiang.archive.dto.MediaAccountDtos;
import com.liqiang.archive.dto.MediaAccountDtos.AccountAnalyticsData;
import com.liqiang.archive.dto.MediaAccountDtos.AccountDetail;
import com.liqiang.archive.dto.MediaAccountDtos.AccountSummary;
import com.liqiang.archive.dto.MediaAccountDtos.AccountUpdateFrequency;
import com.liqiang.archive.dto.MediaAccountDtos.AccountUpdateTrendPoint;
import com.liqiang.archive.dto.MediaAccountDtos.MediaStats;
import com.liqiang.archive.dto.MediaAccountDtos.PlatformStats;
import com.liqiang.archive.dto.MediaAccountDtos.SnapshotView;
import com.liqiang.archive.dto.MediaAccountDtos.UpdatePayload;
import com.liqiang.archive.dto.MediaAccountDtos.UpdateRecord;
import com.liqiang.archive.dto.MediaAccountDtos.DailyTrendPoint;
import com.liqiang.archive.dto.MediaAccountDtos.PlatformComparePoint;
import com.liqiang.archive.dto.MediaAccountDtos.AccountHeatPoint;
import com.liqiang.archive.dto.MediaAccountDtos.OperatorRankPoint;
import com.liqiang.archive.dto.MediaAccountDtos.AccountCalendarPoint;
import com.liqiang.archive.dto.MediaAccountDtos.AnalyticsData;
import com.liqiang.archive.dto.MediaAccountDtos.PublishTaskPayload;
import com.liqiang.archive.dto.MediaAccountDtos.PublishTaskView;
import com.liqiang.archive.dto.MediaAccountDtos.PublishOwnerAssignRequest;
import com.liqiang.archive.entity.MediaAccount;
import com.liqiang.archive.entity.MediaAccountSnapshot;
import com.liqiang.archive.entity.MediaAccountUpdate;
import com.liqiang.archive.entity.MediaPublishTask;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.MediaAccountSnapshotRepository;
import com.liqiang.archive.repository.MediaAccountUpdateRepository;
import com.liqiang.archive.repository.MediaPublishTaskRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MediaAccountService {

    private static final int STALE_THRESHOLD_DAYS = 14; // 超过14天未更新视为"待关注"
    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "platform", "accountName", "accountId", "link", "note", "operator", "publishOwner",
            "tags", "followersCount", "likesCount", "videoCount", "lastUpdatedAt", "websiteOfficial");

    private final MediaAccountRepository accountRepository;
    private final MediaAccountSnapshotRepository snapshotRepository;
    private final MediaAccountUpdateRepository updateRepository;
    private final MediaPublishTaskRepository publishTaskRepository;
    private final AuditLogService auditLogService;

    // ==================== 账号 CRUD ====================

    /** 获取全量运营人员列表 */
    @Transactional(readOnly = true)
    public List<String> getAllOperators() {
        return accountRepository.findAll().stream()
                .map(MediaAccount::getOperator)
                .filter(o -> o != null && !o.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /** 获取所有已使用的平台列表（从数据库动态查询） */
    @Transactional(readOnly = true)
    public List<String> getDistinctPlatforms() {
        return accountRepository.findDistinctPlatforms();
    }

    /** 计算字段名称集合（不能用于数据库排序，需内存排序） */
    private static final java.util.Set<String> COMPUTED_SORT_FIELDS = java.util.Set.of("updateCount", "weekUpdates");

    private static List<String> parseOperatorFilter(String operator) {
        if (operator == null || operator.isBlank()) return List.of();
        return java.util.Arrays.stream(operator.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getAllPublishOwners() {
        return accountRepository.findAll().stream()
                .map(MediaAccount::getPublishOwner)
                .filter(o -> o != null && !o.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AccountSummary> search(String platform, String operator, String publishOwner, String keyword, Pageable pageable) {
        String p = (platform != null && !platform.isBlank()) ? platform : null;
        List<String> operatorList = parseOperatorFilter(operator);
        List<String> queryOperators = operatorList.isEmpty() ? List.of("__NO_OPERATOR_FILTER__") : operatorList;
        String owner = (publishOwner != null && !publishOwner.isBlank()) ? publishOwner.trim() : null;
        String k = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        // 如果排序字段是计算字段（updateCount / weekUpdates），先在 DB 层去掉排序
        boolean hasComputedSort = pageable.getSort().stream()
                .anyMatch(order -> COMPUTED_SORT_FIELDS.contains(order.getProperty())
                        || "lastUpdatedAt".equals(order.getProperty()));
        Page<MediaAccount> page = hasComputedSort
                ? Page.empty(pageable)
                : accountRepository.search(p, queryOperators, operatorList.isEmpty(), owner, k, pageable);
        List<MediaAccount> accounts = hasComputedSort
                ? accountRepository.searchAll(p, queryOperators, operatorList.isEmpty(), owner, k)
                : page.getContent();
        long totalElements = hasComputedSort ? accounts.size() : page.getTotalElements();

        // 批量查询更新统计
        List<Long> accountIds = accounts.stream().map(MediaAccount::getId).toList();
        Map<Long, Long> countMap = new java.util.HashMap<>();
        Map<Long, Long> weekMap = new java.util.HashMap<>();
        if (!accountIds.isEmpty()) {
            updateRepository.countByAccountIds(accountIds)
                    .forEach(row -> countMap.put((Long) row[0], ((Number) row[1]).longValue()));
            // 本周更新次数（批量查询，自然周）
            LocalDate weekStart = LocalDate.now().with(DayOfWeek.MONDAY);
            updateRepository.countByAccountIdsAfterDate(accountIds, weekStart)
                    .forEach(row -> weekMap.put((Long) row[0], ((Number) row[1]).longValue()));
        }

        // 批量查询最新更新标题和类型
        Map<Long, String> titleMap = new java.util.HashMap<>();
        Map<Long, String> typeMap = new java.util.HashMap<>();
        Map<Long, LocalDate> latestDateMap = new java.util.HashMap<>();
        if (!accountIds.isEmpty()) {
            updateRepository.findLatestUpdateDateByAccountIds(accountIds)
                    .forEach(row -> latestDateMap.put((Long) row[0], (LocalDate) row[1]));
            List<LocalDate> latestDates = latestDateMap.values().stream().distinct().toList();
            if (!latestDates.isEmpty()) {
                updateRepository.findByAccountIdInAndUpdateDateInOrderByAccountIdAscUpdateDateDescIdDesc(accountIds, latestDates)
                        .forEach(u -> {
                            LocalDate latestDate = latestDateMap.get(u.getAccountId());
                            if (Objects.equals(latestDate, u.getUpdateDate()) && !titleMap.containsKey(u.getAccountId())) {
                                titleMap.put(u.getAccountId(), u.getTitle());
                                typeMap.put(u.getAccountId(), u.getUpdateType());
                            }
                        });
            }
        }

        List<AccountSummary> summaries = accounts.stream()
                .map(a -> {
                    int updateCount = countMap.getOrDefault(a.getId(), 0L).intValue();
                    int weekUpdates = weekMap.getOrDefault(a.getId(), 0L).intValue();
                    return accountSummaryFrom(a, latestDateMap.get(a.getId()))
                            .withUpdateStats(updateCount, weekUpdates)
                            .withLatestUpdate(titleMap.get(a.getId()), typeMap.get(a.getId()));
                })
                .toList();

        // 如果原请求要求按计算字段排序，在内存中排序
        if (hasComputedSort) {
            Comparator<AccountSummary> comparator = null;
            for (Sort.Order order : pageable.getSort()) {
                Comparator<AccountSummary> fieldComparator = switch (order.getProperty()) {
                    case "lastUpdatedAt" -> (a, b) -> compareDateNullsLast(a.lastUpdatedAt(), b.lastUpdatedAt(), order.getDirection());
                    case "updateCount" -> Comparator.comparingInt(AccountSummary::updateCount);
                    case "weekUpdates" -> Comparator.comparingInt(AccountSummary::weekUpdates);
                    default -> null;
                };
                if (fieldComparator != null) {
                    if (!"lastUpdatedAt".equals(order.getProperty()) && order.getDirection() == Sort.Direction.DESC) {
                        fieldComparator = fieldComparator.reversed();
                    }
                    comparator = (comparator == null) ? fieldComparator : comparator.thenComparing(fieldComparator);
                }
            }
            if (comparator != null) {
                summaries = summaries.stream()
                        .sorted(comparator.thenComparing(AccountSummary::id))
                        .toList();
            }
            int start = Math.min((int) pageable.getOffset(), summaries.size());
            int end = Math.min(start + pageable.getPageSize(), summaries.size());
            summaries = summaries.subList(start, end);
        }

        return new PageImpl<>(summaries, pageable, totalElements);
    }

    @Transactional(readOnly = true)
    public AccountDetail get(Long id) {
        MediaAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("账号不存在: " + id));

        // 最近20条更新记录
        List<UpdateRecord> recentUpdates = updateRepository
                .findByAccountIdOrderByUpdateDateDesc(id)
                .stream().limit(20)
                .map(u -> UpdateRecord.from(u, account.getAccountName(), account.getPlatform()))
                .toList();

        List<SnapshotView> snapshots = snapshotRepository.findByAccountIdOrderBySnapshotDateDesc(id)
                .stream().map(SnapshotView::from).toList();

        LocalDate latestUpdateDate = recentUpdates.isEmpty() ? null : recentUpdates.get(0).updateDate();
        return new AccountDetail(accountSummaryFrom(account, latestUpdateDate), recentUpdates, snapshots);
    }

    @Transactional
    public AccountSummary create(MediaAccount account) {
        AccountSummary saved = AccountSummary.from(accountRepository.save(account));
        auditLogService.log("MediaAccount", saved.id(), saved.accountName(),
                "MEDIA_CREATE", "创建自媒体账号 " + saved.platform() + " / " + saved.accountName());
        return saved;
    }

    @Transactional
    public AccountSummary update(Long id, MediaAccount payload) {
        MediaAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("账号不存在: " + id));
        applyPayload(account, payload);
        AccountSummary updated = AccountSummary.from(accountRepository.save(account));
        auditLogService.log("MediaAccount", id, updated.accountName(),
                "MEDIA_UPDATE", "更新自媒体账号 " + updated.platform() + " / " + updated.accountName());
        return updated;
    }

    @Transactional
    public AccountSummary updateMetrics(Long id, MediaAccountDtos.AccountUpdatePayload payload) {
        MediaAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("账号不存在: " + id));

        Long oldLikes = account.getLikesCount();
        Long oldFollowers = account.getFollowersCount();
        Long oldVideos = account.getVideoCount();

        account.setLikesCount(payload.likesCount());
        account.setFollowersCount(payload.followersCount());
        account.setVideoCount(payload.videoCount());
        accountRepository.save(account);

        // 只有数据变化时才创建快照
        LocalDate snapDate = payload.lastUpdatedAt() != null ? payload.lastUpdatedAt() : LocalDate.now();
        boolean changed = !Objects.equals(oldLikes, payload.likesCount())
                || !Objects.equals(oldFollowers, payload.followersCount())
                || !Objects.equals(oldVideos, payload.videoCount());
        if (changed && !snapshotRepository.existsByAccountIdAndSnapshotDate(id, snapDate)) {
            snapshotRepository.save(new MediaAccountSnapshot(
                    id, payload.likesCount(), payload.followersCount(), payload.videoCount(), snapDate));
        }

        auditLogService.log("MediaAccount", id, account.getAccountName(),
                "MEDIA_METRICS_UPDATE", "更新指标：获赞 " + oldLikes + "→" + payload.likesCount()
                        + "，粉丝 " + oldFollowers + "→" + payload.followersCount()
                        + "，视频 " + oldVideos + "→" + payload.videoCount());
        return AccountSummary.from(account);
    }

    @Transactional
    public void delete(Long id) {
        MediaAccount account = accountRepository.findById(id).orElse(null);
        String accountDesc = account != null ? account.getPlatform() + " / " + account.getAccountName() : "";
        updateRepository.deleteAllByAccountId(id);
        snapshotRepository.deleteAllByAccountId(id);
        accountRepository.deleteById(id);
        auditLogService.log("MediaAccount", id, accountDesc,
                "MEDIA_DELETE", "删除自媒体账号 " + accountDesc + "（含更新记录与快照）");
    }

    // ==================== 更新记录 CRUD ====================

    @Transactional(readOnly = true)
    public Page<UpdateRecord> getAccountUpdates(Long accountId, Pageable pageable) {
        MediaAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("账号不存在: " + accountId));
        Page<MediaAccountUpdate> page = updateRepository.findByAccountIdOrderByUpdateDateDesc(accountId, pageable);
        List<UpdateRecord> records = page.getContent().stream()
                .map(u -> UpdateRecord.from(u, account.getAccountName(), account.getPlatform()))
                .toList();
        return new PageImpl<>(records, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<UpdateRecord> getRecentUpdates(int days, Pageable pageable) {
        LocalDate after = LocalDate.now().minusDays(days);
        Page<MediaAccountUpdate> page = updateRepository.findByUpdateDateAfterOrderByUpdateDateDesc(after, pageable);
        // 批量获取关联账号信息
        List<Long> accountIds = page.getContent().stream()
                .map(MediaAccountUpdate::getAccountId).distinct().toList();
        Map<Long, MediaAccount> accountMap = accountRepository.findAllById(accountIds)
                .stream().collect(Collectors.toMap(MediaAccount::getId, Function.identity()));

        List<UpdateRecord> records = page.getContent().stream()
                .map(u -> {
                    MediaAccount acct = accountMap.get(u.getAccountId());
                    return UpdateRecord.from(u,
                            acct != null ? acct.getAccountName() : "未知",
                            acct != null ? acct.getPlatform() : "");
                }).toList();
        return new PageImpl<>(records, pageable, page.getTotalElements());
    }

    @Transactional
    public UpdateRecord addUpdate(Long accountId, UpdatePayload payload) {
        MediaAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("账号不存在: " + accountId));

        MediaAccountUpdate update = new MediaAccountUpdate();
        update.setAccountId(accountId);
        update.setUpdateDate(payload.updateDate() != null ? payload.updateDate() : LocalDate.now());
        update.setUpdateType(payload.updateType() == null || payload.updateType().isBlank() ? "更新" : payload.updateType());
        update.setTitle(payload.title());
        update.setContentUrl(payload.contentUrl());
        update.setNote(payload.note());
        update.setOperator(payload.operator());
        MediaAccountUpdate saved = updateRepository.save(update);

        // 更新账号的 lastUpdatedAt
        syncAccountLastUpdatedAt(accountId);

        auditLogService.log("MediaAccountUpdate", saved.getId(),
                account.getAccountName(),
                "MEDIA_UPDATE_ADD", "新增更新记录：" + saved.getTitle()
                        + "（账号 " + account.getPlatform() + " / " + account.getAccountName() + "）");
        return UpdateRecord.from(saved, account.getAccountName(), account.getPlatform());
    }

    @Transactional
    public UpdateRecord editUpdate(Long updateId, UpdatePayload payload) {
        MediaAccountUpdate update = updateRepository.findById(updateId)
                .orElseThrow(() -> new EntityNotFoundException("更新记录不存在: " + updateId));

        update.setUpdateDate(payload.updateDate());
        update.setUpdateType(payload.updateType() == null || payload.updateType().isBlank() ? "更新" : payload.updateType());
        update.setTitle(payload.title());
        update.setContentUrl(payload.contentUrl());
        update.setNote(payload.note());
        update.setOperator(payload.operator());
        MediaAccountUpdate saved = updateRepository.save(update);

        // 更新账号的 lastUpdatedAt 为最新一条记录的日期
        syncAccountLastUpdatedAt(update.getAccountId());
        MediaAccount account = accountRepository.findById(update.getAccountId()).orElse(null);

        auditLogService.log("MediaAccountUpdate", saved.getId(),
                account != null ? account.getAccountName() : "",
                "MEDIA_UPDATE_EDIT", "编辑更新记录 #" + saved.getId() + "：" + saved.getTitle());
        return UpdateRecord.from(saved,
                account != null ? account.getAccountName() : "",
                account != null ? account.getPlatform() : "");
    }

    @Transactional
    public void deleteUpdate(Long updateId) {
        MediaAccountUpdate update = updateRepository.findById(updateId)
                .orElseThrow(() -> new EntityNotFoundException("更新记录不存在: " + updateId));
        Long accountId = update.getAccountId();
        String title = update.getTitle();
        updateRepository.delete(update);

        // 更新账号的 lastUpdatedAt
        syncAccountLastUpdatedAt(accountId);
        auditLogService.log("MediaAccountUpdate", updateId, "",
                "MEDIA_UPDATE_DELETE", "删除更新记录 #" + updateId + "：" + title);
    }

    // ==================== 统计 ====================

    @Transactional
    public MediaAccountDtos.UpdateImportResult importUpdatesFromPublishExport(MultipartFile file) throws IOException {
        MediaAccountDtos.UpdateImportPreviewResult preview = previewUpdatesFromPublishExport(file);
        List<MediaAccountDtos.UpdateImportConfirmRow> rows = preview.rows().stream()
                .map(row -> new MediaAccountDtos.UpdateImportConfirmRow(
                        row.matchedAccountId() != null && !row.duplicate(),
                        row.rowNumber(),
                        row.matchedAccountId(),
                        row.updateDate(),
                        row.title(),
                        row.contentUrl(),
                        row.note(),
                        row.operator()))
                .toList();
        return confirmUpdateImport(new MediaAccountDtos.UpdateImportConfirmRequest(rows));
    }

    @Transactional(readOnly = true)
    public MediaAccountDtos.UpdateImportPreviewResult previewUpdatesFromPublishExport(MultipartFile file) throws IOException {
        List<MediaAccountDtos.UpdateImportPreviewRow> rows = new ArrayList<>();
        Map<String, MediaAccount> accountIndex = buildAccountMatchIndex();

        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet != null && sheet.getPhysicalNumberOfRows() > 1) {
                DataFormatter formatter = new DataFormatter();
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                Map<String, Integer> headers = readHeaderIndex(headerRow, formatter);

                for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null || isBlankRow(row, formatter)) continue;
                    rows.add(buildUpdateImportPreviewRow(row, rowIndex + 1, headers, formatter, accountIndex));
                }
            }
        }

        int matched = (int) rows.stream().filter(row -> row.matchedAccountId() != null).count();
        int duplicates = (int) rows.stream().filter(MediaAccountDtos.UpdateImportPreviewRow::duplicate).count();
        int unmatched = rows.size() - matched;
        List<MediaAccountDtos.UpdateImportAccountOption> accounts = accountRepository.findAll().stream()
                .map(a -> new MediaAccountDtos.UpdateImportAccountOption(a.getId(), a.getPlatform(), a.getAccountName(), a.getAccountId()))
                .toList();
        return new MediaAccountDtos.UpdateImportPreviewResult(rows.size(), matched, unmatched, duplicates, rows, accounts);
    }

    @Transactional
    public MediaAccountDtos.UpdateImportResult confirmUpdateImport(MediaAccountDtos.UpdateImportConfirmRequest request) {
        int totalRows = request.rows() == null ? 0 : request.rows().size();
        int imported = 0;
        int skipped = 0;
        int unmatched = 0;
        int failed = 0;
        List<String> unmatchedRows = new ArrayList<>();
        List<String> failedRows = new ArrayList<>();

        if (request.rows() == null) {
            return new MediaAccountDtos.UpdateImportResult(0, 0, 0, 0, 0, List.of(), List.of());
        }

        // 批量预取所有关联账号：一次 findAllById 替代循环内 N 次 findById（N+1 → 1）
        List<Long> accountIdsToFetch = request.rows().stream()
                .filter(MediaAccountDtos.UpdateImportConfirmRow::enabled)
                .map(MediaAccountDtos.UpdateImportConfirmRow::accountId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, MediaAccount> accountMap = accountRepository.findAllById(accountIdsToFetch).stream()
                .collect(Collectors.toMap(MediaAccount::getId, Function.identity()));

        // 批量预查现有 (accountId, updateDate, title) 记录，构建复合键集合用于内存去重
        Set<String> existingKeys = new java.util.HashSet<>();
        if (!accountIdsToFetch.isEmpty()) {
            // 收集所有可能的 updateDate（含 null → today 的归一化）
            Set<LocalDate> datesToCheck = request.rows().stream()
                    .filter(r -> r.enabled() && r.accountId() != null)
                    .map(r -> r.updateDate() != null ? r.updateDate() : LocalDate.now())
                    .collect(Collectors.toSet());
            if (!datesToCheck.isEmpty()) {
                updateRepository.findExistingByAccountIdsAndDates(accountIdsToFetch, datesToCheck)
                        .forEach(row -> existingKeys.add(buildDedupeKey((Long) row[0], (LocalDate) row[1], (String) row[2])));
            }
        }

        List<MediaAccountUpdate> updatesToSave = new ArrayList<>();
        Set<Long> affectedAccountIds = new LinkedHashSet<>();
        for (MediaAccountDtos.UpdateImportConfirmRow row : request.rows()) {
            if (!row.enabled()) {
                skipped++;
                continue;
            }
            if (row.accountId() == null) {
                unmatched++;
                unmatchedRows.add("第 " + row.rowNumber() + " 行：未选择账号");
                continue;
            }
            try {
                MediaAccount account = accountMap.get(row.accountId());
                if (account == null) {
                    throw new EntityNotFoundException("账号不存在: " + row.accountId());
                }
                LocalDate updateDate = row.updateDate() != null ? row.updateDate() : LocalDate.now();
                String title = trimToLength(blankToNull(row.title()), 200);
                if (existingKeys.contains(buildDedupeKey(account.getId(), updateDate, title))) {
                    skipped++;
                    continue;
                }
                MediaAccountUpdate update = new MediaAccountUpdate();
                update.setAccountId(account.getId());
                update.setUpdateDate(updateDate);
                update.setUpdateType("统一发布");
                update.setTitle(title);
                update.setContentUrl(trimToLength(blankToNull(row.contentUrl()), 500));
                update.setNote(trimToLength(blankToNull(row.note()), 500));
                update.setOperator(firstNonBlank(row.operator(), account.getPublishOwner(), account.getOperator()));
                updatesToSave.add(update);
                affectedAccountIds.add(account.getId());
                imported++;
            } catch (Exception ex) {
                failed++;
                if (failedRows.size() < 20) {
                    failedRows.add("第 " + row.rowNumber() + " 行：" + ex.getMessage());
                }
            }
        }

        if (!updatesToSave.isEmpty()) {
            updateRepository.saveAll(updatesToSave);
        }
        syncAccountsLastUpdatedAt(affectedAccountIds);

        auditLogService.log("MediaAccountUpdate", null, "批量导入",
                "MEDIA_UPDATE_IMPORT", "导入更新记录：" + imported + " 条新增，"
                        + skipped + " 条跳过，" + unmatched + " 条未匹配，" + failed + " 条失败");
        return new MediaAccountDtos.UpdateImportResult(totalRows, imported, skipped, unmatched, failed, unmatchedRows, failedRows);
    }

    /** 复合去重键：accountId|updateDate|title（title 为 null 时用空串占位） */
    private static String buildDedupeKey(Long accountId, LocalDate updateDate, String title) {
        return accountId + "|" + updateDate + "|" + (title != null ? title : "");
    }

    @Transactional(readOnly = true)
    public MediaStats stats() {
        long total = accountRepository.count();
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate monthStart = today.withDayOfMonth(1);

        long updatesToday = updateRepository.countByUpdateDateAfter(today.minusDays(1));
        long updatesThisWeek = updateRepository.countByUpdateDateAfter(weekStart);
        long updatesThisMonth = updateRepository.countByUpdateDateAfter(monthStart);

        // 批量获取所有账号的最近一次更新日期，一次 SQL 解决 stale 计算
        List<MediaAccount> allAccounts = accountRepository.findAll();
        long staleCount = allAccounts.stream().filter(a -> {
            LocalDate lastDate = a.getLastUpdatedAt();
            return lastDate == null || ChronoUnit.DAYS.between(lastDate, today) > STALE_THRESHOLD_DAYS;
        }).count();

        List<PlatformStats> platformStats = buildPlatformStats(weekStart, monthStart);

        return new MediaStats(total, updatesToday, updatesThisWeek, updatesThisMonth, staleCount, platformStats);
    }

    /**
     * 批量构建各平台统计：4 次聚合查询替代原先的 4N 次（N=平台数）。
     * 各查询返回 [platform, ...]，按 platform 合并到内存 Map 后组装 DTO。
     */
    private List<PlatformStats> buildPlatformStats(LocalDate weekStart, LocalDate monthStart) {
        Map<String, Long> countMap = accountRepository.countByPlatformGroupByPlatform().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        Map<String, long[]> sumMap = accountRepository.sumMetricsByPlatform().stream()
                .collect(Collectors.toMap(
                        r -> (String) r[0],
                        r -> new long[]{
                                ((Number) r[1]).longValue(),
                                ((Number) r[2]).longValue(),
                                ((Number) r[3]).longValue()
                        }));
        Map<String, Long> weekMap = updateRepository.countByPlatformAfterDate(weekStart).stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));
        Map<String, Long> monthMap = updateRepository.countByPlatformAfterDate(monthStart).stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));

        return accountRepository.findDistinctPlatforms().stream()
                .map(p -> {
                    long[] sums = sumMap.getOrDefault(p, new long[]{0, 0, 0});
                    return new PlatformStats(
                            p,
                            countMap.getOrDefault(p, 0L),
                            sums[0],
                            sums[1],
                            sums[2],
                            weekMap.getOrDefault(p, 0L),
                            monthMap.getOrDefault(p, 0L));
                }).toList();
    }

    /** 获取更新频率摘要（可按平台/运营筛选）。批量查询，避免 N+1 */
    @Transactional(readOnly = true)
    public List<AccountUpdateFrequency> getUpdateFrequencies(String platform, String operator) {
        List<MediaAccount> accounts;
        if (platform != null && !platform.isBlank()) {
            accounts = accountRepository.findByPlatform(platform, Pageable.unpaged()).getContent();
        } else {
            accounts = accountRepository.findAll();
        }

        // 运营人员筛选
        if (operator != null && !operator.isBlank()) {
            accounts = accounts.stream()
                    .filter(a -> operator.equals(a.getOperator()))
                    .toList();
        }

        if (accounts.isEmpty()) return List.of();

        List<Long> ids = accounts.stream().map(MediaAccount::getId).toList();
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate monthStart = today.withDayOfMonth(1);

        // 批量查询：3 次 SQL 替代 55×4=220 次
        Map<Long, Long> totalMap = updateRepository.countByAccountIds(ids).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).longValue()));
        Map<Long, Long> weekMap = updateRepository.countByAccountIdsAfterDate(ids, weekStart).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).longValue()));
        Map<Long, Long> monthMap = updateRepository.countByAccountIdsAfterDate(ids, monthStart).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).longValue()));
        Map<Long, LocalDate> latestMap = new java.util.HashMap<>();
        updateRepository.findLatestUpdateDatePerAccount().forEach(r -> {
            Long id = (Long) r[0];
            if (ids.contains(id)) latestMap.put(id, (LocalDate) r[1]);
        });

        return accounts.stream()
                .map(a -> {
                    LocalDate lastUpdateDate = latestMap.get(a.getId());
                    long daysSince = lastUpdateDate != null
                            ? ChronoUnit.DAYS.between(lastUpdateDate, today) : -1;
                    return new AccountUpdateFrequency(
                            a.getId(), a.getAccountName(), a.getPlatform(),
                            totalMap.getOrDefault(a.getId(), 0L).intValue(),
                            weekMap.getOrDefault(a.getId(), 0L),
                            monthMap.getOrDefault(a.getId(), 0L),
                            lastUpdateDate, daysSince);
                })
                .sorted(Comparator.comparingLong(AccountUpdateFrequency::daysSinceLastUpdate))
                .toList();
    }

    // ==================== Analytics ====================

    @Transactional(readOnly = true)
    public AnalyticsData analytics(int days) {
        LocalDate afterDate = LocalDate.now().minusDays(days);

        // 1) 更新趋势（按日期+平台）
        List<DailyTrendPoint> dailyTrend = updateRepository.countByDateAndPlatform(afterDate)
                .stream()
                .map(row -> new DailyTrendPoint(
                        (LocalDate) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .toList();

        // 2) 平台更新量对比（本周/本月，自然周/自然月）
        LocalDate weekStart = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        Map<String, Long> weekMap = updateRepository.countByPlatformAfterDate(weekStart)
                .stream().collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue()));
        Map<String, Long> monthMap = updateRepository.countByPlatformAfterDate(monthStart)
                .stream().collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue()));
        List<PlatformComparePoint> platformCompare = accountRepository.findDistinctPlatforms().stream()
                .map(p -> new PlatformComparePoint(p,
                        weekMap.getOrDefault(p, 0L),
                        monthMap.getOrDefault(p, 0L)))
                .toList();

        // 3) 账号活跃度热力图
        List<AccountHeatPoint> accountHeatmap = updateRepository.countByAccountAndWeek(afterDate)
                .stream()
                .map(row -> new AccountHeatPoint(
                        (Long) row[0],
                        (String) row[1],
                        (String) row[2],
                        String.valueOf(row[3]),  // yearWeek 六位数字如 "202601"
                        ((Number) row[4]).longValue()))
                .toList();

        // 4) 批量获取所有账号最近一次更新日期（一次 SQL，替代 N+1）
        List<MediaAccount> allAccounts = accountRepository.findAll();
        Map<Long, LocalDate> latestDateMap = new java.util.HashMap<>();
        updateRepository.findLatestUpdateDatePerAccount()
                .forEach(row -> latestDateMap.put((Long) row[0], (LocalDate) row[1]));
        LocalDate today = LocalDate.now();

        // 5) 运营人员排行
        Map<String, Long> operatorUpdates = updateRepository.countByOperator(afterDate)
                .stream().collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue(),
                        (a, b) -> a));
        Map<String, Long> operatorStale = new java.util.HashMap<>();
        for (MediaAccount a : allAccounts) {
            if (a.getOperator() == null || a.getOperator().isBlank()) continue;
            LocalDate lastDate = latestDateMap.get(a.getId());
            boolean stale = lastDate == null || ChronoUnit.DAYS.between(lastDate, today) > STALE_THRESHOLD_DAYS;
            if (stale) operatorStale.merge(a.getOperator(), 1L, Long::sum);
        }
        List<OperatorRankPoint> operatorRank = operatorUpdates.entrySet().stream()
                .map(e -> new OperatorRankPoint(e.getKey(), e.getValue(), operatorStale.getOrDefault(e.getKey(), 0L)))
                .sorted((a, b) -> Long.compare(b.updateCount(), a.updateCount()))
                .toList();

        // 6) 所有平台列表 & 所有运营人员列表
        List<String> allPlatforms = accountRepository.findDistinctPlatforms();
        List<String> allOperators = allAccounts.stream()
                .map(MediaAccount::getOperator)
                .filter(o -> o != null && !o.isBlank())
                .distinct()
                .sorted()
                .toList();

        // 7) 待关注账号列表（复用 latestDateMap，无额外查询）
        List<AccountSummary> staleAccounts = allAccounts.stream()
                .filter(a -> {
                    LocalDate lastDate = latestDateMap.get(a.getId());
                    return lastDate == null || ChronoUnit.DAYS.between(lastDate, today) > STALE_THRESHOLD_DAYS;
                })
                .map(AccountSummary::from)
                .toList();

        return new AnalyticsData(dailyTrend, platformCompare,
                accountHeatmap, operatorRank, allPlatforms, allOperators, staleAccounts);
    }

    /** 获取单个账号的更新日历数据 */
    @Transactional(readOnly = true)
    public List<AccountCalendarPoint> accountCalendar(Long accountId, int days) {
        LocalDate afterDate = LocalDate.now().minusDays(days);
        List<MediaAccountUpdate> updates = updateRepository
                .findByAccountIdAndUpdateDateAfterOrderByUpdateDateDesc(accountId, afterDate);
        // 按日期聚合
        return updates.stream()
                .collect(Collectors.groupingBy(MediaAccountUpdate::getUpdateDate, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new AccountCalendarPoint(e.getKey(), e.getValue()))
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .toList();
    }

    // ==================== 单账号分析 ====================

    @Transactional(readOnly = true)
    public AccountAnalyticsData accountAnalytics(Long accountId, int days) {
        if (!accountRepository.existsById(accountId)) {
            throw new EntityNotFoundException("账号不存在: " + accountId);
        }

        LocalDate today = LocalDate.now();
        LocalDate afterDate = today.minusDays(days);

        // 1) 基础统计
        long totalUpdates = updateRepository.countByAccountId(accountId);
        long weekUpdates = updateRepository.countByAccountIdAndUpdateDateAfter(accountId, today.with(DayOfWeek.MONDAY));
        long monthUpdates = updateRepository.countByAccountIdAndUpdateDateAfter(accountId, today.withDayOfMonth(1));

        // 2) 更新趋势（按日期聚合）
        List<AccountUpdateTrendPoint> updateTrend = updateRepository
                .countByAccountIdAndDateAfterGroupByDate(accountId, afterDate).stream()
                .map(row -> new AccountUpdateTrendPoint((LocalDate) row[0], ((Number) row[1]).longValue()))
                .toList();

        // 3) 日历数据（复用已有逻辑）
        List<AccountCalendarPoint> calendarData = accountCalendar(accountId, days);

        // 4) 首次/最近更新日期
        List<MediaAccountUpdate> allUpdates = updateRepository.findByAccountIdOrderByUpdateDateDesc(accountId);
        LocalDate firstUpdateDate = allUpdates.isEmpty() ? null : allUpdates.get(allUpdates.size() - 1).getUpdateDate();
        LocalDate lastUpdateDate = allUpdates.isEmpty() ? null : allUpdates.get(0).getUpdateDate();

        // 5) 周均更新次数
        double avgUpdatesPerWeek = 0.0;
        if (firstUpdateDate != null && totalUpdates > 0) {
            long weeks = Math.max(1, ChronoUnit.WEEKS.between(firstUpdateDate, today));
            avgUpdatesPerWeek = Math.round(totalUpdates * 100.0 / weeks) / 100.0;
        }

        // 6) 连续更新天数计算
        int currentStreak = 0;
        int maxStreak = 0;
        if (!allUpdates.isEmpty()) {
            // 获取所有去重日期并排序
            List<LocalDate> dates = allUpdates.stream()
                    .map(MediaAccountUpdate::getUpdateDate)
                    .distinct()
                    .sorted()
                    .toList();

            // 当前连续天数（从今天往前数）
            LocalDate checkDate = today;
            for (int i = dates.size() - 1; i >= 0; i--) {
                if (dates.get(i).isEqual(checkDate) || dates.get(i).isEqual(checkDate.minusDays(1))) {
                    currentStreak++;
                    checkDate = dates.get(i).minusDays(1);
                } else {
                    break;
                }
            }

            // 最大连续天数
            int tempStreak = 1;
            maxStreak = 1;
            for (int i = 1; i < dates.size(); i++) {
                if (dates.get(i).equals(dates.get(i - 1).plusDays(1))) {
                    tempStreak++;
                    maxStreak = Math.max(maxStreak, tempStreak);
                } else {
                    tempStreak = 1;
                }
            }
        }

        return new AccountAnalyticsData(
                totalUpdates, weekUpdates, monthUpdates,
                avgUpdatesPerWeek, currentStreak, maxStreak,
                firstUpdateDate, lastUpdateDate,
                updateTrend, calendarData
        );
    }

    // ==================== Excel 导入 ====================

    @Transactional
    public List<AccountSummary> importFromExcel(MultipartFile file) throws IOException {
        Workbook wb = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = wb.getSheetAt(0);

        // 第一遍：解析所有行为 MediaAccount 实体（无 DB 访问）
        List<MediaAccount> accounts = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String accountName = cellStr(row, 5);
            String platform = cellStr(row, 1);
            if (accountName == null || accountName.isBlank() || platform == null || platform.isBlank()) continue;

            MediaAccount account = new MediaAccount();
            account.setPlatform(platform.trim());
            account.setAccountName(accountName.trim());
            account.setAccountId(cellStr(row, 6));
            account.setLink(cellStr(row, 2));
            account.setNote(cellStr(row, 3));
            account.setLikesCount(cellLong(row, 7));
            account.setFollowersCount(cellLong(row, 8));
            account.setVideoCount(cellLong(row, 9));
            account.setOperator(cellStr(row, 10));

            String dateStr = cellStr(row, 4);
            if (dateStr != null && !dateStr.isBlank()) {
                try {
                    account.setLastUpdatedAt(LocalDate.parse(dateStr.trim()));
                } catch (Exception e) {
                    account.setLastUpdatedAt(null);
                }
            }
            accounts.add(account);
        }

        // 批量保存账号（一次 saveAll 替代循环内 N 次 save，2N→2 次 DB round-trip）
        List<MediaAccount> savedAccounts = accountRepository.saveAll(accounts);

        // 批量构建快照：账号刚在本事务创建，(accountId, snapDate) 不可能已存在，故跳过 exists 检查
        List<MediaAccountSnapshot> snapshots = new ArrayList<>(savedAccounts.size());
        for (MediaAccount saved : savedAccounts) {
            LocalDate snapDate = saved.getLastUpdatedAt() != null ? saved.getLastUpdatedAt() : LocalDate.now();
            snapshots.add(new MediaAccountSnapshot(
                    saved.getId(), saved.getLikesCount(), saved.getFollowersCount(),
                    saved.getVideoCount(), snapDate));
        }
        if (!snapshots.isEmpty()) {
            snapshotRepository.saveAll(snapshots);
        }

        List<AccountSummary> result = savedAccounts.stream()
                .map(AccountSummary::from)
                .collect(Collectors.toList());
        wb.close();
        auditLogService.log("MediaAccount", null, "批量导入",
                "MEDIA_IMPORT", "Excel 导入自媒体账号 " + result.size() + " 个");
        return result;
    }

    // ==================== helpers ====================

    private void applyPayload(MediaAccount target, MediaAccount payload) {
        if (payload.getPlatform() != null) target.setPlatform(payload.getPlatform());
        if (payload.getAccountName() != null) target.setAccountName(payload.getAccountName());
        if (payload.getAccountId() != null) {
            // 空字符串转 null，避免唯一约束 uk_media_accounts_platform_account_id 冲突
            target.setAccountId(payload.getAccountId().isBlank() ? null : payload.getAccountId());
        }
        if (payload.getLink() != null) target.setLink(payload.getLink());
        if (payload.getNote() != null) target.setNote(payload.getNote());
        if (payload.getOperator() != null) target.setOperator(payload.getOperator());
        if (payload.getPublishOwner() != null) target.setPublishOwner(payload.getPublishOwner());
        if (payload.getLikesCount() != null) target.setLikesCount(payload.getLikesCount());
        if (payload.getFollowersCount() != null) target.setFollowersCount(payload.getFollowersCount());
        if (payload.getVideoCount() != null) target.setVideoCount(payload.getVideoCount());
        if (payload.getLastUpdatedAt() != null) target.setLastUpdatedAt(payload.getLastUpdatedAt());
        if (payload.getWebsiteOfficial() != null) target.setWebsiteOfficial(payload.getWebsiteOfficial());
    }

    private AccountSummary accountSummaryFrom(MediaAccount account, LocalDate latestUpdateDate) {
        AccountSummary summary = AccountSummary.from(account);
        return new AccountSummary(
                summary.id(),
                summary.platform(),
                summary.accountName(),
                summary.accountId(),
                summary.link(),
                summary.note(),
                summary.operator(),
                summary.publishOwner(),
                summary.likesCount(),
                summary.followersCount(),
                summary.videoCount(),
                latestUpdateDate,
                summary.websiteOfficial(),
                summary.updateCount(),
                summary.weekUpdates(),
                summary.latestUpdateTitle(),
                summary.latestUpdateType()
        );
    }

    @Transactional
    public int batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (ids == null || ids.isEmpty()) return 0;
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("未指定要修改的字段");
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) throw new IllegalArgumentException("不支持批量修改字段: " + key);
        }
        List<MediaAccount> accounts = accountRepository.findAllById(ids);
        for (MediaAccount account : accounts) applyBatchFields(account, fields);
        if (!accounts.isEmpty()) accountRepository.saveAll(accounts);
        if (!accounts.isEmpty()) {
            auditLogService.log("MediaAccount", null, "自媒体账号", "MEDIA_ACCOUNT_BATCH_EDIT",
                    "批量修改账号 " + accounts.size() + " 个，字段：" + fields.keySet());
        }
        return accounts.size();
    }

    @Transactional
    public int batchDeleteAccounts(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        List<MediaAccount> accounts = accountRepository.findAllById(ids);
        accountRepository.deleteAll(accounts);
        if (!accounts.isEmpty()) {
            auditLogService.log("MediaAccount", null, "自媒体账号", "MEDIA_ACCOUNT_BATCH_DELETE",
                    "批量删除账号 " + accounts.size() + " 个");
        }
        return accounts.size();
    }

    private void applyBatchFields(MediaAccount account, Map<String, Object> fields) {
        fields.forEach((key, value) -> {
            switch (key) {
                case "platform" -> account.setPlatform(textValue(value));
                case "accountName" -> account.setAccountName(textValue(value));
                case "accountId" -> account.setAccountId(blankToNull(value));
                case "link" -> account.setLink(blankToNull(value));
                case "note" -> account.setNote(blankToNull(value));
                case "operator" -> account.setOperator(blankToNull(value));
                case "publishOwner" -> account.setPublishOwner(blankToNull(value));
                case "tags" -> account.setTags(tagsValue(value));
                case "followersCount" -> account.setFollowersCount(longValue(value));
                case "likesCount" -> account.setLikesCount(longValue(value));
                case "videoCount" -> account.setVideoCount(longValue(value));
                case "lastUpdatedAt" -> account.setLastUpdatedAt(dateValue(value));
                case "websiteOfficial" -> account.setWebsiteOfficial(boolValue(value));
                default -> throw new IllegalArgumentException("不支持批量修改字段: " + key);
            }
        });
    }

    private String textValue(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private String blankToNull(Object value) { String text = textValue(value); return text == null || text.isBlank() ? null : text; }
    private Long longValue(Object value) { String text = textValue(value); return text == null || text.isBlank() ? null : Long.valueOf(text); }
    private LocalDate dateValue(Object value) { String text = textValue(value); return text == null || text.isBlank() ? null : LocalDate.parse(text); }
    private String tagsValue(Object value) {
        if (value instanceof Collection<?> items) return items.stream().map(String::valueOf).collect(Collectors.joining(","));
        return blankToNull(value);
    }
    private Boolean boolValue(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if (text.isBlank()) return null;
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private Map<String, Integer> readHeaderIndex(Row headerRow, DataFormatter formatter) {
        Map<String, Integer> headers = new LinkedHashMap<>();
        if (headerRow == null) return headers;
        for (Cell cell : headerRow) {
            String name = formatter.formatCellValue(cell).trim();
            if (!name.isBlank()) headers.put(name, cell.getColumnIndex());
        }
        return headers;
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).trim().isBlank()) return false;
        }
        return true;
    }

    private MediaAccountDtos.UpdateImportPreviewRow buildUpdateImportPreviewRow(
            Row row,
            int rowNumber,
            Map<String, Integer> headers,
            DataFormatter formatter,
            Map<String, MediaAccount> accountIndex) {
        String platform = excelText(row, headers, formatter, "平台");
        String accountId = excelText(row, headers, formatter, "账号ID");
        String accountName = excelText(row, headers, formatter, "账号昵称");
        MediaAccount account = matchAccount(accountIndex, platform, accountId, accountName);

        LocalDate updateDate = excelDate(row, headers, formatter, "发布结束时间");
        if (updateDate == null) updateDate = excelDate(row, headers, formatter, "发布时间");
        if (updateDate == null) updateDate = excelDate(row, headers, formatter, "更新时间");
        if (updateDate == null) updateDate = LocalDate.now();

        String title = excelText(row, headers, formatter, "标题");
        if (title == null || title.isBlank() || "-".equals(title)) {
            title = excelText(row, headers, formatter, "描述");
        }
        title = trimToLength(blankToNull(title), 200);
        String contentUrl = trimToLength(blankToNull(excelText(row, headers, formatter, "作品ID")), 500);
        String note = buildPublishImportNote(row, headers, formatter);
        String operator = firstNonBlank(excelText(row, headers, formatter, "账号备注"),
                account != null ? account.getPublishOwner() : null,
                account != null ? account.getOperator() : null);
        boolean duplicate = account != null && updateRepository.existsByAccountIdAndUpdateDateAndTitle(account.getId(), updateDate, title);
        String status = account == null ? "unmatched" : duplicate ? "duplicate" : "matched";
        String message = account == null ? "未匹配账号" : duplicate ? "可能重复，默认不导入" : "已自动匹配";

        return new MediaAccountDtos.UpdateImportPreviewRow(
                rowNumber,
                platform,
                accountId,
                accountName,
                updateDate,
                title,
                contentUrl,
                note,
                operator,
                account != null ? account.getId() : null,
                account != null ? account.getAccountName() : null,
                account != null ? account.getPlatform() : null,
                duplicate,
                status,
                message
        );
    }

    private String excelText(Row row, Map<String, Integer> headers, DataFormatter formatter, String header) {
        Integer index = headers.get(header);
        if (index == null) return null;
        Cell cell = row.getCell(index);
        if (cell == null) return null;
        String value = formatter.formatCellValue(cell).trim();
        return value.isBlank() || "-".equals(value) ? null : value;
    }

    private LocalDate excelDate(Row row, Map<String, Integer> headers, DataFormatter formatter, String header) {
        Integer index = headers.get(header);
        if (index == null) return null;
        Cell cell = row.getCell(index);
        if (cell == null) return null;
        try {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
        } catch (RuntimeException ignored) {
        }
        String value = excelText(row, headers, formatter, header);
        if (value == null) return null;
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        );
        for (DateTimeFormatter format : formats) {
            try {
                return LocalDateTime.parse(value, format).toLocalDate();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, MediaAccount> buildAccountMatchIndex() {
        Map<String, MediaAccount> index = new HashMap<>();
        for (MediaAccount account : accountRepository.findAll()) {
            putAccountIndex(index, "pid", account.getPlatform(), account.getAccountId(), account);
            putAccountIndex(index, "pname", account.getPlatform(), account.getAccountName(), account);
            putAccountIndex(index, "pnameBase", account.getPlatform(), normalizeBaseName(account.getAccountName()), account);
            putAccountIndex(index, "id", null, account.getAccountId(), account);
            putAccountIndex(index, "name", null, account.getAccountName(), account);
            putAccountIndex(index, "nameBase", null, normalizeBaseName(account.getAccountName()), account);
        }
        return index;
    }

    private void putAccountIndex(Map<String, MediaAccount> index, String prefix, String platform, String value, MediaAccount account) {
        String key = matchKey(prefix, platform, value);
        if (key != null) index.putIfAbsent(key, account);
    }

    private MediaAccount matchAccount(Map<String, MediaAccount> index, String platform, String accountId, String accountName) {
        if (blankToNull(platform) != null) {
            return firstNonNull(
                    index.get(matchKey("pid", platform, accountId)),
                    index.get(matchKey("pname", platform, accountName)),
                    index.get(matchKey("pnameBase", platform, normalizeBaseName(accountName)))
            );
        }
        return firstNonNull(
                index.get(matchKey("id", null, accountId)),
                index.get(matchKey("name", null, accountName)),
                index.get(matchKey("nameBase", null, normalizeBaseName(accountName)))
        );
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private String matchKey(String prefix, String platform, String value) {
        String normalizedValue = normalizeMatch(value);
        if (normalizedValue == null) return null;
        String normalizedPlatform = normalizeMatch(platform);
        return prefix + "|" + (normalizedPlatform == null ? "" : normalizedPlatform) + "|" + normalizedValue;
    }

    private String normalizeMatch(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase().replaceAll("\\s+", "");
        return normalized.isBlank() || "-".equals(normalized) ? null : normalized;
    }

    private String normalizeBaseName(String value) {
        if (value == null) return null;
        return value
                .replaceAll("（[^）]*）", "")
                .replaceAll("\\([^)]*\\)", "")
                .trim();
    }

    private String buildPublishImportNote(Row row, Map<String, Integer> headers, DataFormatter formatter) {
        List<String> parts = new ArrayList<>();
        addNotePart(parts, "状态", excelText(row, headers, formatter, "状态"));
        addNotePart(parts, "发布类型", excelText(row, headers, formatter, "发布类型"));
        addNotePart(parts, "描述", excelText(row, headers, formatter, "描述"));
        addNotePart(parts, "视频路径", excelText(row, headers, formatter, "视频路径"));
        addNotePart(parts, "错误信息", excelText(row, headers, formatter, "错误信息"));
        return trimToLength(String.join("；", parts), 500);
    }

    private void addNotePart(List<String> parts, String label, String value) {
        String cleaned = blankToNull(value);
        if (cleaned != null) parts.add(label + "：" + cleaned);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String cleaned = blankToNull(value);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() || "-".equals(trimmed) ? null : trimmed;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private int compareDateNullsLast(LocalDate left, LocalDate right, Sort.Direction direction) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return direction == Sort.Direction.DESC ? right.compareTo(left) : left.compareTo(right);
    }

    private void syncAccountLastUpdatedAt(Long accountId) {
        MediaAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null) return;

        MediaAccountUpdate latest = updateRepository.findTopByAccountIdOrderByUpdateDateDesc(accountId);
        account.setLastUpdatedAt(latest != null ? latest.getUpdateDate() : null);
        accountRepository.save(account);
    }

    private void syncAccountsLastUpdatedAt(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) return;
        List<MediaAccount> accounts = accountRepository.findAllById(accountIds);
        for (MediaAccount account : accounts) {
            MediaAccountUpdate latest = updateRepository.findTopByAccountIdOrderByUpdateDateDesc(account.getId());
            account.setLastUpdatedAt(latest != null ? latest.getUpdateDate() : null);
        }
        if (!accounts.isEmpty()) {
            accountRepository.saveAll(accounts);
        }
    }

    private String cellStr(Row row, int col) {
        if (row.getCell(col) == null) return null;
        String val = row.getCell(col).toString().trim();
        val = val.replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (val.isEmpty()) return null;
        return val;
    }

    private Long cellLong(Row row, int col) {
        String val = cellStr(row, col);
        if (val == null) return null;
        try {
            return Long.parseLong(val.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 批量操作 ====================

    private List<MediaAccount> resolveAccounts(List<Long> accountIds, String platform, String operator, String publishOwner, String keyword) {
        if (accountIds != null && !accountIds.isEmpty()) {
            return accountRepository.findAllById(accountIds);
        }
        String p = platform != null && !platform.isBlank() ? platform : null;
        List<String> operatorList = parseOperatorFilter(operator);
        List<String> queryOperators = operatorList.isEmpty() ? List.of("__NO_OPERATOR_FILTER__") : operatorList;
        String owner = publishOwner != null && !publishOwner.isBlank() ? publishOwner.trim() : null;
        String k = keyword != null && !keyword.isBlank() ? keyword.trim() : null;
        return accountRepository.search(p, queryOperators, operatorList.isEmpty(), owner, k, Pageable.unpaged()).getContent();
    }

    @Transactional
    public int batchTodayUpdate(MediaAccountDtos.BatchUpdateRequest request) {
        LocalDate updateDate = request.updateDate() != null ? request.updateDate() : LocalDate.now();
        String updateType = request.updateType() != null && !request.updateType().isBlank()
                ? request.updateType()
                : "更新";
        boolean skipExisting = request.skipExisting() == null || request.skipExisting();
        List<MediaAccount> accounts = resolveAccounts(
                request.accountIds(), request.platform(), request.operator(), request.publishOwner(), request.keyword());
        List<MediaAccountUpdate> updatesToSave = new ArrayList<>();
        Set<Long> affectedAccountIds = new LinkedHashSet<>();
        int count = 0;
        for (MediaAccount a : accounts) {
            if (skipExisting && updateRepository.existsByAccountIdAndUpdateDate(a.getId(), updateDate)) {
                continue;
            }
            MediaAccountUpdate update = new MediaAccountUpdate();
            update.setAccountId(a.getId());
            update.setUpdateDate(updateDate);
            update.setUpdateType(updateType);
            update.setTitle(request.title());
            update.setContentUrl(request.contentUrl());
            update.setNote(request.note());
            update.setOperator(request.operatorName());
            updatesToSave.add(update);
            affectedAccountIds.add(a.getId());
            count++;
        }
        if (!updatesToSave.isEmpty()) {
            updateRepository.saveAll(updatesToSave);
        }
        syncAccountsLastUpdatedAt(affectedAccountIds);
        auditLogService.log("MediaAccountUpdate", null, "批量登记",
                "MEDIA_BATCH_UPDATE", "批量登记更新 " + count + " 条（日期 " + updateDate
                        + "，类型 " + updateType + "）");
        return count;
    }

    @Transactional
    public PublishTaskView createPublishTask(PublishTaskPayload request) {
        LocalDate publishDate = request.publishDate() != null ? request.publishDate() : LocalDate.now();
        String publishOwner = request.publishOwner() != null && !request.publishOwner().isBlank()
                ? request.publishOwner().trim()
                : null;
        if (publishOwner == null) {
            throw new IllegalArgumentException("请选择账号归属发布负责人");
        }
        List<MediaAccount> accounts = resolveAccounts(
                request.accountIds(), request.platform(), request.operator(), request.publishOwner(), request.keyword())
                .stream()
                .filter(a -> publishOwner.equals(a.getPublishOwner()))
                .toList();

        MediaPublishTask task = new MediaPublishTask();
        task.setTaskName(request.taskName() == null || request.taskName().isBlank()
                ? "批量发布记录"
                : request.taskName().trim());
        task.setPublishDate(publishDate);
        task.setPlatform(request.platform());
        task.setPublishSoftware(request.publishSoftware());
        task.setContentTitle(request.contentTitle());
        task.setContentTopic(request.contentTopic());
        task.setContentUrl(request.contentUrl());
        task.setEditorName(request.editorName());
        task.setPublishOwner(publishOwner);
        task.setPublisherName(request.publisherName() == null || request.publisherName().isBlank()
                ? publishOwner
                : request.publisherName());
        task.setNote(request.note());
        task.setAccountCount(accounts.size());
        task.setCreatedUpdateCount(0);
        MediaPublishTask savedTask = publishTaskRepository.save(task);

        boolean skipExisting = request.skipExisting() == null || request.skipExisting();
        // 批量构建 update 列表（避免循环内单条 save 的 N 次 DB round-trip）
        List<MediaAccountUpdate> updatesToSave = new ArrayList<>();
        for (MediaAccount a : accounts) {
            if (skipExisting && updateRepository.existsByAccountIdAndUpdateDate(a.getId(), publishDate)) {
                continue;
            }
            MediaAccountUpdate update = new MediaAccountUpdate();
            update.setAccountId(a.getId());
            update.setUpdateDate(publishDate);
            update.setUpdateType("统一发布");
            update.setTitle(request.contentTitle());
            update.setContentUrl(request.contentUrl());
            update.setNote(request.note());
            update.setOperator(task.getPublisherName());
            update.setPublishTaskId(savedTask.getId());
            updatesToSave.add(update);
        }
        if (!updatesToSave.isEmpty()) {
            updateRepository.saveAll(updatesToSave);
            Set<Long> affectedAccountIds = updatesToSave.stream()
                    .map(MediaAccountUpdate::getAccountId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            syncAccountsLastUpdatedAt(affectedAccountIds);
        }
        int count = updatesToSave.size();
        savedTask.setCreatedUpdateCount(count);
        publishTaskRepository.save(savedTask);
        auditLogService.log("MediaPublishTask", savedTask.getId(), savedTask.getTaskName(),
                "MEDIA_PUBLISH_TASK_CREATE", "创建发布任务 #" + savedTask.getId()
                        + "：" + savedTask.getTaskName() + "，关联账号 " + accounts.size()
                        + " 个，生成更新记录 " + count + " 条");
        return PublishTaskView.from(savedTask);
    }

    @Transactional
    public int assignPublishOwner(PublishOwnerAssignRequest request) {
        String publishOwner = request.publishOwner() == null ? "" : request.publishOwner().trim();
        List<MediaAccount> accounts = resolveAccounts(
                request.accountIds(), request.platform(), request.operator(), null, request.keyword());
        for (MediaAccount account : accounts) {
            account.setPublishOwner(publishOwner.isBlank() ? null : publishOwner);
        }
        accountRepository.saveAll(accounts);
        auditLogService.log("MediaAccount", null, "批量分配",
                "MEDIA_ASSIGN_OWNER", "批量分配发布负责人「" + (publishOwner.isBlank() ? "（清空）" : publishOwner)
                        + "」，账号 " + accounts.size() + " 个");
        return accounts.size();
    }

    // ==================== Excel 导出 ====================

    private static final String[] EXPORT_HEADERS = {
        "平台", "链接", "备注", "最近更新日期", "账号名称", "账号ID", "获赞数", "粉丝数", "视频数", "运营人员"
    };

    /** 按列索引写入单元格文本，null 安全 */
    private void writeCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        if (style != null) cell.setCellStyle(style);
    }

    @Transactional(readOnly = true)
    public void exportToExcel(OutputStream outputStream, String platform, String operator, String publishOwner, String keyword) throws IOException {
        String p = (platform != null && !platform.isBlank()) ? platform : null;
        List<String> operatorList = parseOperatorFilter(operator);
        List<String> queryOperators = operatorList.isEmpty() ? List.of("__NO_OPERATOR_FILTER__") : operatorList;
        String owner = (publishOwner != null && !publishOwner.isBlank()) ? publishOwner.trim() : null;
        String k = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        List<MediaAccount> accounts = accountRepository.search(p, queryOperators, operatorList.isEmpty(), owner, k, Pageable.unpaged()).getContent();

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("自媒体账号");
            sheet.setDefaultColumnWidth(18);

            // 表头样式
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);

            // 写入表头
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                writeCell(headerRow, i, EXPORT_HEADERS[i], headerStyle);
            }

            // 写入数据行
            int rowIdx = 1;
            for (MediaAccount a : accounts) {
                Row row = sheet.createRow(rowIdx++);
                writeCell(row, 0, a.getPlatform(), null);
                writeCell(row, 1, a.getLink(), null);
                writeCell(row, 2, a.getNote(), null);
                writeCell(row, 3, a.getLastUpdatedAt() != null ? a.getLastUpdatedAt().toString() : "", null);
                writeCell(row, 4, a.getAccountName(), null);
                writeCell(row, 5, a.getAccountId(), null);
                writeCell(row, 6, a.getLikesCount() != null ? a.getLikesCount().toString() : "", null);
                writeCell(row, 7, a.getFollowersCount() != null ? a.getFollowersCount().toString() : "", null);
                writeCell(row, 8, a.getVideoCount() != null ? a.getVideoCount().toString() : "", null);
                writeCell(row, 9, a.getOperator(), null);
            }

            // 自动调整列宽
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(outputStream);
        }
    }
}
