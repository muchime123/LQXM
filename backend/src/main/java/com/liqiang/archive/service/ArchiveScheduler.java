package com.liqiang.archive.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import com.liqiang.archive.repository.PackagingMoldRepository;
import com.liqiang.archive.repository.MoldVariantLinkRepository;
import com.liqiang.archive.repository.AuthSessionRepository;

/**
 * 统一归档/清理调度器，收口所有后台定时任务，按时间错峰执行：
 * <ul>
 *   <li>02:00 — 产品软删数据归档（迁入 _archive 表后物理删除主表行）</li>
 *   <li>03:00 — 回收站 30 天清理 + 操作日志 90 天清理</li>
 *   <li>04:00 — Office 预览 PDF 缓存清理（删除超 {@code app.preview.cache.retention-days} 天未访问的转换结果）</li>
 *   <li>05:00 — 包材模具数据完整性扫描与自动修复（双归属不一致回填 / 重复默认模具去重）</li>
 * </ul>

 * <p>
 * 所有 cron 表达式均可通过配置项覆盖，默认值即上述时点。
 * 产品归档额外支持 app.archive.soft-delete.enabled / retention-days 开关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    @PersistenceContext
    private EntityManager entityManager;

    private final CatalogTrashService catalogTrashService;
    private final FileStorageService fileStorageService;
    private final CertificateService certificateService;
    private final AuditLogService auditLogService;
    private final OfficePreviewService officePreviewService;
    private final PackagingMoldRepository packagingMoldRepo;
    private final MoldVariantLinkRepository moldVariantLinkRepo;
    private final AuthSessionRepository authSessionRepository;

    @Value("${app.archive.soft-delete.enabled:true}")
    private boolean archiveEnabled;

    @Value("${app.archive.soft-delete.retention-days:30}")
    private int retentionDays;

    /** Office 预览 PDF 缓存保留天数，超过则被 04:00 定时任务清理。 */
    @Value("${app.preview.cache.retention-days:30}")
    private int previewCacheRetentionDays;

    // ====== 02:00 产品软删归档 ======

    @Scheduled(cron = "${app.archive.soft-delete.cron:0 0 2 * * ?}")
    @Transactional
    public void archiveSoftDeletedProductRows() {
        if (!archiveEnabled) {
            log.info("[ArchiveScheduler] 调度已关闭，跳过本次归档");
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        log.info("[ArchiveScheduler] 开始归档 deleted_at < {}（保留 {} 天）的软删产品数据", cutoff, retentionDays);

        long assetsArchived = archiveTable("product_assets", "product_assets_archive", cutoff);
        long variantsArchived = archiveTable("product_variants", "product_variants_archive", cutoff);
        long modelsArchived = archiveTable("product_models", "product_models_archive", cutoff);

        log.info("[ArchiveScheduler] 完成：models={}, variants={}, assets={}",
                modelsArchived, variantsArchived, assetsArchived);
    }

    /**
     * 单表归档流程：
     * 1) INSERT INTO 归档表 SELECT 主表 WHERE deleted_at < cutoff AND 未被归档
     * 2) DELETE 主表 WHERE deleted_at < cutoff
     */
    private long archiveTable(String mainTable, String archiveTable, LocalDateTime cutoff) {
        try {
            String insertSql = String.format(
                    "INSERT INTO %s SELECT * FROM %s m WHERE m.deleted_at IS NOT NULL AND m.deleted_at < ?" +
                            " AND NOT EXISTS (SELECT 1 FROM %s a WHERE a.id = m.id)",
                    archiveTable, mainTable, archiveTable
            );
            int inserted = entityManager.createNativeQuery(insertSql)
                    .setParameter(1, cutoff)
                    .executeUpdate();

            String deleteSql = String.format(
                    "DELETE FROM %s WHERE deleted_at IS NOT NULL AND deleted_at < ?" +
                            " AND EXISTS (SELECT 1 FROM %s a WHERE a.id = %s.id)",
                    mainTable, archiveTable, mainTable
            );
            int deleted = entityManager.createNativeQuery(deleteSql)
                    .setParameter(1, cutoff)
                    .executeUpdate();

            if (inserted > 0 || deleted > 0) {
                log.info("[ArchiveScheduler] {} → {} ：归档 {} 行，清理 {} 行", mainTable, archiveTable, inserted, deleted);
            }
            return Math.max(inserted, deleted);
        } catch (Exception e) {
            log.warn("[ArchiveScheduler] 归档 {} 失败：{}", mainTable, e.getMessage());
            return 0;
        }
    }

    // ====== 03:00 回收站 + 操作日志清理 ======

    @Scheduled(cron = "${app.schedule.cleanup.cron:0 0 3 * * ?}")
    public void autoCleanup() {
        log.info("[ArchiveScheduler] 开始执行定时清理任务...");

        try {
            int trashCleaned = catalogTrashService.autoCleanTrash(30);
            log.info("[ArchiveScheduler] 回收站清理完成，永久删除了 {} 条过期记录", trashCleaned);
        } catch (Exception e) {
            log.error("[ArchiveScheduler] 回收站清理失败", e);
        }

        try {
            int certTrashCleaned = certificateService.autoCleanTrash(30);
            log.info("[ArchiveScheduler] 证书回收站清理完成，永久删除了 {} 条过期记录", certTrashCleaned);
        } catch (Exception e) {
            log.error("[ArchiveScheduler] 证书回收站清理失败", e);
        }

        try {
            int logsCleaned = auditLogService.autoCleanLogs(90);
            log.info("[ArchiveScheduler] 操作日志清理完成，删除了 {} 条过期日志", logsCleaned);
        } catch (Exception e) {
            log.error("[ArchiveScheduler] 操作日志清理失败", e);
        }
    }

    // ====== 03:30 登录会话清理（P7 会话表防无限增长） ======

    /** 清理过期（>1 天）或已撤销（>30 天）的登录会话。 */
    @Scheduled(cron = "${app.schedule.session-cleanup.cron:0 30 3 * * ?}")
    @Transactional
    public void cleanExpiredSessions() {
        try {
            int purged = authSessionRepository.purgeExpired(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(30));
            if (purged > 0) {
                log.info("[ArchiveScheduler] 登录会话清理完成，清除 {} 条过期/已撤销会话", purged);
            }
        } catch (Exception e) {
            log.error("[ArchiveScheduler] 登录会话清理失败", e);
        }
    }

    // ====== 04:00 Office 预览缓存清理 ======

    /**
     * 清理过期的 Office 预览 PDF 缓存（{@code .preview-cache/documents/} 下
     * 超 {@code app.preview.cache.retention-days} 天未更新的目录）。
     *
     * <p>缓存为可重建的派生数据，删除后下次预览会重新触发异步转换，不影响功能。
     */
    @Scheduled(cron = "${app.preview.cache.cleanup-cron:0 0 4 * * ?}")
    public void cleanPreviewCache() {
        try {
            officePreviewService.cleanStaleCache(fileStorageService.fileRoot().resolve(".preview-cache"), previewCacheRetentionDays);
            log.info("[ArchiveScheduler] Office 预览缓存清理完成（保留 {} 天）", previewCacheRetentionDays);
        } catch (Exception e) {
            log.error("[ArchiveScheduler] Office 预览缓存清理失败", e);
        }
    }

    // ====== 05:00 包材模具数据完整性扫描与自动修复 ======

    /**
     * 扫描并自动修复包材模具的数据完整性问题：
     * <ul>
     *   <li>双归属不一致：mold.model_id 与 mold.group.model_id 不一致 → 以 group.model_id 回填</li>
     *   <li>重复默认模具：同一 variant 存在多条未软删的 default link → 保留最早创建的一条，其余置为非默认</li>
     * </ul>
     * <p>
     * 修复策略以规格族(group)为单一真相来源，与 {@link PackagingMoldService#resolveOwnership} 的写路径保持一致。
     * 正常情况下不应有命中（写路径已做校验），此任务作为防御性兜底，处理历史遗留或并发竞态产生的脏数据。
     */
    @Scheduled(cron = "${app.schedule.mold-integrity.cron:0 0 5 * * ?}")
    @Transactional
    public void scanAndFixPackagingMoldIntegrity() {
        log.info("[ArchiveScheduler] 开始包材模具完整性扫描与修复...");

        int fixedOwnership = 0;
        int fixedDefaults = 0;

        try {
            // 1. 扫描双归属不一致
            List<Object[]> inconsistent = packagingMoldRepo.findMoldModelGroupMismatchesRaw();
            if (!inconsistent.isEmpty()) {
                log.warn("[ArchiveScheduler] 发现 {} 条模具双归属不一致，开始自动修复", inconsistent.size());
                inconsistent.stream().limit(10).forEach(row ->
                        log.warn("[ArchiveScheduler] 不一致 mold: id={}, code={}, moldModelId={}, groupId={}, groupModelId={}",
                                row[0], row[1], row[4], row[2], row[6]));
                // 批量回填：mold.model_id = group.model_id
                fixedOwnership = packagingMoldRepo.fixMoldModelFromGroup();
                if (fixedOwnership > 0) {
                    auditLogService.log("PackagingMold", null, null,
                            "PKG_MOLD_INTEGRITY_FIX", "双归属自动修复：回填 " + fixedOwnership + " 条 mold.model_id");
                    log.info("[ArchiveScheduler] 双归属修复完成，回填 {} 条记录", fixedOwnership);
                }
            }

            // 2. 扫描重复默认模具
            List<Object[]> dupDefaults = moldVariantLinkRepo.findDuplicateDefaultLinks();
            if (!dupDefaults.isEmpty()) {
                log.warn("[ArchiveScheduler] 发现 {} 个 variant 存在重复默认模具，开始自动修复", dupDefaults.size());
                dupDefaults.stream().limit(10).forEach(row ->
                        log.warn("[ArchiveScheduler] 重复默认 variantId={}, defaultCount={}", row[0], row[1]));
                // 批量去重：保留最早创建(id 最小)的默认关联，其余置为非默认
                fixedDefaults = moldVariantLinkRepo.fixDuplicateDefaultLinks();
                if (fixedDefaults > 0) {
                    auditLogService.log("MoldVariantLink", null, null,
                            "PKG_MOLD_INTEGRITY_FIX", "重复默认模具去重：清除 " + fixedDefaults + " 条多余默认标记");
                    log.info("[ArchiveScheduler] 重复默认模具修复完成，清除 {} 条多余默认标记", fixedDefaults);
                }
            }

            if (inconsistent.isEmpty() && dupDefaults.isEmpty()) {
                log.info("[ArchiveScheduler] 包材模具完整性扫描通过，无异常");
            } else {
                log.info("[ArchiveScheduler] 包材模具完整性修复完成：双归属回填 {} 条，重复默认清除 {} 条",
                        fixedOwnership, fixedDefaults);
            }
        } catch (Exception e) {
            log.error("[ArchiveScheduler] 包材模具完整性扫描与修复失败", e);
        }
    }
}
