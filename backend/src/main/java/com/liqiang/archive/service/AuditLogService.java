package com.liqiang.archive.service;

import com.liqiang.archive.entity.AuditLog;
import com.liqiang.archive.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final EntityManager entityManager;

    /** 记录一条操作日志 */
    @Transactional
    public void log(String entityType, Long entityId, String modelName, String action, String changes) {
        AuditLog entry = AuditLog.of(entityType, entityId, modelName, action, changes);
        auditLogRepository.save(entry);
    }

    /** 批量记录操作日志 */
    @Transactional
    public void logBatch(List<AuditLog> entries) {
        auditLogRepository.saveAll(entries);
    }

    /** 查询所有日志（按时间倒序） */
    @Transactional(readOnly = true)
    public List<AuditLog> listAll() {
        return auditLogRepository.findAllOrderByCreatedAtDesc();
    }

    /** 分页查询日志 */
    @Transactional(readOnly = true)
    public Page<AuditLog> listPaged(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** 带过滤条件的分页查询 */
    @Transactional(readOnly = true)
    public Page<AuditLog> listFiltered(String entityType, String action,
                                        LocalDateTime dateFrom, LocalDateTime dateTo,
                                        String keyword, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // --- data query ---
        CriteriaQuery<AuditLog> cq = cb.createQuery(AuditLog.class);
        Root<AuditLog> root = cq.from(AuditLog.class);
        Predicate[] predicates = buildPredicates(cb, root, entityType, action, dateFrom, dateTo, keyword);
        cq.where(predicates);
        cq.orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<AuditLog> dataQuery = entityManager.createQuery(cq);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());
        List<AuditLog> content = dataQuery.getResultList();

        // --- count query (separate, avoids loading all rows) ---
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<AuditLog> countRoot = countCq.from(AuditLog.class);
        Predicate[] countPredicates = buildPredicates(cb, countRoot, entityType, action, dateFrom, dateTo, keyword);
        countCq.where(countPredicates);
        countCq.select(cb.count(countRoot));

        long total = entityManager.createQuery(countCq).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private Predicate[] buildPredicates(CriteriaBuilder cb, Root<AuditLog> root,
                                         String entityType, String action,
                                         LocalDateTime dateFrom, LocalDateTime dateTo,
                                         String keyword) {
        List<Predicate> predicates = new ArrayList<>();

        if (entityType != null && !entityType.isBlank()) {
            predicates.add(cb.equal(root.get("entityType"), entityType));
        }
        if (action != null && !action.isBlank()) {
            predicates.add(cb.equal(root.get("action"), action));
        }
        if (dateFrom != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
        }
        if (dateTo != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            predicates.add(cb.or(
                    cb.like(root.get("modelName"), like),
                    cb.like(root.get("changes"), like)
            ));
        }

        return predicates.toArray(new Predicate[0]);
    }

    /** 查询某个实体的操作历史 */
    @Transactional(readOnly = true)
    public List<AuditLog> findByEntity(String entityType, Long entityId) {
        return auditLogRepository.findByEntity(entityType, entityId);
    }

    /** 查询某类型实体的操作历史 */
    @Transactional(readOnly = true)
    public List<AuditLog> findByEntityType(String entityType) {
        return auditLogRepository.findByEntityType(entityType);
    }

    /** 自动清理超过 N 天的操作日志 */
    @Transactional
    public int autoCleanLogs(int daysOld) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);
        auditLogRepository.deleteOlderThan(cutoff);
        // 无法直接获取删除行数，返回 -1 表示已执行
        return -1;
    }
}
