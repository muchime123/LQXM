package com.liqiang.archive.repository;

import com.liqiang.archive.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** 刷新活跃时间：validateSession 每 5 分钟调一次，必须自带事务，否则登录用户空闲后首个请求会抛 TransactionRequiredException。 */
    @Transactional
    @Modifying
    @Query("UPDATE AuthSession s SET s.lastActiveAt = :now WHERE s.id = :id")
    void touchLastActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :now WHERE s.tokenHash = :hash AND s.revokedAt IS NULL")
    int revokeByTokenHash(@Param("hash") String hash, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :now WHERE s.employeeId = :employeeId AND s.revokedAt IS NULL")
    int revokeByEmployeeId(@Param("employeeId") Long employeeId, @Param("now") LocalDateTime now);

    /** 清理过期（超过 expiredBefore）或已撤销（超过 revokedBefore）的会话。 */
    @Transactional
    @Modifying
    @Query("DELETE FROM AuthSession s WHERE s.expiresAt < :expiredBefore"
            + " OR (s.revokedAt IS NOT NULL AND s.revokedAt < :revokedBefore)")
    int purgeExpired(@Param("expiredBefore") LocalDateTime expiredBefore,
                     @Param("revokedBefore") LocalDateTime revokedBefore);
}
