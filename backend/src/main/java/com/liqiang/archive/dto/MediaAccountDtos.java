package com.liqiang.archive.dto;

import com.liqiang.archive.entity.MediaAccount;
import com.liqiang.archive.entity.MediaAccountSnapshot;
import com.liqiang.archive.entity.MediaAccountUpdate;
import com.liqiang.archive.entity.MediaPublishTask;

import java.time.LocalDate;
import java.util.List;

public class MediaAccountDtos {

    /** 安全地将字符串解析为 Long */
    private static Long parseLong(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 账号相关 ====================

    public record AccountSummary(
            Long id,
            String platform,
            String accountName,
            String accountId,
            String link,
            String note,
            String operator,
            String publishOwner,
            Long likesCount,
            Long followersCount,
            Long videoCount,
            LocalDate lastUpdatedAt,
            Boolean websiteOfficial,
            int updateCount,
            int weekUpdates,
            String latestUpdateTitle,
            String latestUpdateType
    ) {
        public static AccountSummary from(MediaAccount account) {
            return new AccountSummary(
                    account.getId(),
                    account.getPlatform(),
                    account.getAccountName(),
                    account.getAccountId(),
                    account.getLink(),
                    account.getNote(),
                    account.getOperator(),
                    account.getPublishOwner(),
                    account.getLikesCount(),
                    account.getFollowersCount(),
                    account.getVideoCount(),
                    account.getLastUpdatedAt(),
                    account.getWebsiteOfficial(),
                    0, 0, null, null  // 需要由 Service 填充
            );
        }

        /** 带更新统计的工厂方法 */
        public AccountSummary withUpdateStats(int updateCount, int weekUpdates) {
            return new AccountSummary(id, platform, accountName, accountId, link, note, operator, publishOwner,
                    likesCount, followersCount, videoCount, lastUpdatedAt, websiteOfficial, updateCount, weekUpdates,
                    latestUpdateTitle, latestUpdateType);
        }

        /** 带最新更新详情的工厂方法 */
        public AccountSummary withLatestUpdate(String title, String type) {
            return new AccountSummary(id, platform, accountName, accountId, link, note, operator, publishOwner,
                    likesCount, followersCount, videoCount, lastUpdatedAt, websiteOfficial, updateCount, weekUpdates,
                    title, type);
        }
    }

    public record AccountDetail(
            AccountSummary account,
            List<UpdateRecord> recentUpdates,
            List<SnapshotView> snapshots
    ) {
    }

    // ==================== 更新记录相关 ====================

    public record UpdateRecord(
            Long id,
            Long accountId,
            String accountName,
            String platform,
            LocalDate updateDate,
            String updateType,
            String title,
            String contentUrl,
            String note,
            String operator,
            Long publishTaskId
    ) {
        public static UpdateRecord from(MediaAccountUpdate update, String accountName, String platform) {
            return new UpdateRecord(
                    update.getId(),
                    update.getAccountId(),
                    accountName,
                    platform,
                    update.getUpdateDate(),
                    update.getUpdateType(),
                    update.getTitle(),
                    update.getContentUrl(),
                    update.getNote(),
                    update.getOperator(),
                    update.getPublishTaskId()
            );
        }

        public static UpdateRecord from(MediaAccountUpdate update) {
            return new UpdateRecord(
                    update.getId(),
                    update.getAccountId(),
                    null,
                    null,
                    update.getUpdateDate(),
                    update.getUpdateType(),
                    update.getTitle(),
                    update.getContentUrl(),
                    update.getNote(),
                    update.getOperator(),
                    update.getPublishTaskId()
            );
        }
    }

    /** 创建/编辑更新记录的请求体 */
    public record UpdatePayload(
            LocalDate updateDate,
            String updateType,
            String title,
            String contentUrl,
            String note,
            String operator
    ) {
    }

    // ==================== 快照相关 ====================

    public record UpdateImportResult(
            int totalRows,
            int imported,
            int skipped,
            int unmatched,
            int failed,
            List<String> unmatchedRows,
            List<String> failedRows
    ) {
    }

    public record UpdateImportAccountOption(
            Long id,
            String platform,
            String accountName,
            String accountId
    ) {
    }

    public record UpdateImportPreviewRow(
            int rowNumber,
            String platform,
            String accountId,
            String accountName,
            LocalDate updateDate,
            String title,
            String contentUrl,
            String note,
            String operator,
            Long matchedAccountId,
            String matchedAccountName,
            String matchedPlatform,
            boolean duplicate,
            String status,
            String message
    ) {
    }

    public record UpdateImportPreviewResult(
            int totalRows,
            int matched,
            int unmatched,
            int duplicates,
            List<UpdateImportPreviewRow> rows,
            List<UpdateImportAccountOption> accounts
    ) {
    }

    public record UpdateImportConfirmRow(
            boolean enabled,
            int rowNumber,
            Long accountId,
            LocalDate updateDate,
            String title,
            String contentUrl,
            String note,
            String operator
    ) {
    }

    public record UpdateImportConfirmRequest(
            List<UpdateImportConfirmRow> rows
    ) {
    }

    public record SnapshotView(
            Long id,
            Long likesCount,
            Long followersCount,
            Long videoCount,
            LocalDate snapshotDate
    ) {
        public static SnapshotView from(MediaAccountSnapshot snapshot) {
            return new SnapshotView(
                    snapshot.getId(),
                    snapshot.getLikesCount(),
                    snapshot.getFollowersCount(),
                    snapshot.getVideoCount(),
                    snapshot.getSnapshotDate()
            );
        }
    }

    // ==================== 统计相关 ====================

    public record PlatformStats(
            String platform,
            long accountCount,
            long totalFollowers,
            long totalLikes,
            long totalVideos,
            long updatesThisWeek,
            long updatesThisMonth
    ) {
    }

    public record MediaStats(
            long totalAccounts,
            long updatesToday,
            long updatesThisWeek,
            long updatesThisMonth,
            long staleAccountCount,
            List<PlatformStats> platforms
    ) {
    }

    /** 账号更新频率摘要 */
    public record AccountUpdateFrequency(
            Long accountId,
            String accountName,
            String platform,
            int totalUpdates,
            long updatesThisWeek,
            long updatesThisMonth,
            LocalDate lastUpdateDate,
            long daysSinceLastUpdate
    ) {
    }

    public record AccountUpdatePayload(
            Long likesCount,
            Long followersCount,
            Long videoCount,
            LocalDate lastUpdatedAt
    ) {
    }

    // ==================== Analytics 相关 ====================

    /** 日期趋势数据点 */
    public record DailyTrendPoint(
            LocalDate date,
            String platform,
            long count
    ) {
    }

    /** 平台更新量对比数据点 */
    public record PlatformComparePoint(
            String platform,
            long weekCount,
            long monthCount
    ) {
    }

    /** 账号活跃度热力图数据点 */
    public record AccountHeatPoint(
            Long accountId,
            String accountName,
            String platform,
            String yearWeek,
            long count
    ) {
    }

    /** 运营人员排行数据点 */
    public record OperatorRankPoint(
            String operator,
            long updateCount,
            long staleCount
    ) {
    }

    /** 单账号更新日历数据点 */
    public record AccountCalendarPoint(
            LocalDate date,
            long count
    ) {
    }

    /** Analytics 汇总响应 */
    public record AnalyticsData(
            List<DailyTrendPoint> dailyTrend,
            List<PlatformComparePoint> platformCompare,
            List<AccountHeatPoint> accountHeatmap,
            List<OperatorRankPoint> operatorRank,
            List<String> allPlatforms,
            List<String> allOperators,
            List<AccountSummary> staleAccounts
    ) {
    }

    // ==================== 单账号分析 ====================

    /** 单账号更新趋势数据点（不含平台字段） */
    public record AccountUpdateTrendPoint(
            LocalDate date,
            long count
    ) {
    }

    /** 单账号分析汇总 */
    public record AccountAnalyticsData(
            long totalUpdates,
            long weekUpdates,
            long monthUpdates,
            double avgUpdatesPerWeek,
            int currentStreak,
            int maxStreak,
            LocalDate firstUpdateDate,
            LocalDate lastUpdateDate,
            List<AccountUpdateTrendPoint> updateTrend,
            List<AccountCalendarPoint> calendarData
    ) {
    }

    // ==================== 批量操作 ====================

    /** 批量今日更新请求 */
    public record BatchUpdateRequest(
            String platform,
            String operator,
            String publishOwner,
            String keyword,
            List<Long> accountIds,
            LocalDate updateDate,
            String updateType,
            String title,
            String contentUrl,
            String note,
            String operatorName,
            Boolean skipExisting
    ) {
    }

    public record PublishTaskPayload(
            String taskName,
            LocalDate publishDate,
            String platform,
            String operator,
            String publishOwner,
            String keyword,
            List<Long> accountIds,
            String publishSoftware,
            String contentTitle,
            String contentTopic,
            String contentUrl,
            String editorName,
            String publisherName,
            String note,
            Boolean skipExisting
    ) {
    }

    public record PublishOwnerAssignRequest(
            String platform,
            String operator,
            String keyword,
            List<Long> accountIds,
            String publishOwner
    ) {
    }

    public record PublishTaskView(
            Long id,
            String taskName,
            LocalDate publishDate,
            String platform,
            String publishSoftware,
            String contentTitle,
            String contentTopic,
            String contentUrl,
            String editorName,
            String publisherName,
            String publishOwner,
            String note,
            Integer accountCount,
            Integer createdUpdateCount
    ) {
        public static PublishTaskView from(MediaPublishTask task) {
            return new PublishTaskView(
                    task.getId(),
                    task.getTaskName(),
                    task.getPublishDate(),
                    task.getPlatform(),
                    task.getPublishSoftware(),
                    task.getContentTitle(),
                    task.getContentTopic(),
                    task.getContentUrl(),
                    task.getEditorName(),
                    task.getPublisherName(),
                    task.getPublishOwner(),
                    task.getNote(),
                    task.getAccountCount(),
                    task.getCreatedUpdateCount()
            );
        }
    }
}
