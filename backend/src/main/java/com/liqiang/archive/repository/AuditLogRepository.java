package com.liqiang.archive.repository;

import com.liqiang.archive.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 按时间倒序查询所有日志 */
    @Query("SELECT a FROM AuditLog a ORDER BY a.createdAt DESC")
    List<AuditLog> findAllOrderByCreatedAtDesc();

    /** 按时间倒序分页查询 */
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按实体类型和 ID 查询操作记录 */
    @Query("SELECT a FROM AuditLog a WHERE a.entityType = :entityType AND a.entityId = :entityId ORDER BY a.createdAt DESC")
    List<AuditLog> findByEntity(@Param("entityType") String entityType, @Param("entityId") Long entityId);

    /** 按实体类型查询 */
    @Query("SELECT a FROM AuditLog a WHERE a.entityType = :entityType ORDER BY a.createdAt DESC")
    List<AuditLog> findByEntityType(@Param("entityType") String entityType);

    /** 清理 N 天前的日志（可选）*/
    @Query("DELETE FROM AuditLog a WHERE a.createdAt < :cutoff")
    @org.springframework.data.jpa.repository.Modifying
    void deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);

    /** 筛选候选：去重后的实体类型 */
    @Query("SELECT DISTINCT a.entityType FROM AuditLog a ORDER BY a.entityType")
    List<String> findDistinctEntityTypes();

    /** 筛选候选：去重后的操作动作 */
    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> findDistinctActions();
}
