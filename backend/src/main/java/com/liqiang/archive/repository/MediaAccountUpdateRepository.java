package com.liqiang.archive.repository;

import com.liqiang.archive.entity.MediaAccountUpdate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MediaAccountUpdateRepository extends JpaRepository<MediaAccountUpdate, Long> {

    Page<MediaAccountUpdate> findByAccountIdOrderByUpdateDateDesc(Long accountId, Pageable pageable);

    List<MediaAccountUpdate> findByAccountIdOrderByUpdateDateDesc(Long accountId);

    long countByAccountId(Long accountId);

    boolean existsByAccountIdAndUpdateDate(Long accountId, LocalDate updateDate);

    boolean existsByAccountIdAndUpdateDateAndTitle(Long accountId, LocalDate updateDate, String title);

    /** 批量查询多个账号的更新次数 */
    @Query("SELECT u.accountId, COUNT(u) FROM MediaAccountUpdate u WHERE u.accountId IN :ids GROUP BY u.accountId")
    List<Object[]> countByAccountIds(@Param("ids") List<Long> ids);

    /** 批量查询多个账号指定日期后的更新次数（用于周/月更新量） */
    @Query("SELECT u.accountId, COUNT(u) FROM MediaAccountUpdate u WHERE u.accountId IN :ids AND u.updateDate >= :afterDate GROUP BY u.accountId")
    List<Object[]> countByAccountIdsAfterDate(@Param("ids") List<Long> ids, @Param("afterDate") LocalDate afterDate);

    /** 批量查询所有账号的最近一次更新日期 */
    @Query("SELECT u.accountId, MAX(u.updateDate) FROM MediaAccountUpdate u GROUP BY u.accountId")
    List<Object[]> findLatestUpdateDatePerAccount();

    @Query("SELECT u.accountId, MAX(u.updateDate) FROM MediaAccountUpdate u WHERE u.accountId IN :ids GROUP BY u.accountId")
    List<Object[]> findLatestUpdateDateByAccountIds(@Param("ids") List<Long> ids);

    List<MediaAccountUpdate> findByAccountIdInAndUpdateDateInOrderByAccountIdAscUpdateDateDescIdDesc(
            List<Long> accountIds, List<LocalDate> updateDates);

    long countByAccountIdAndUpdateDateAfter(Long accountId, LocalDate afterDate);

    /**
     * 批量查询指定账号+日期范围内的现有更新记录（用于复合键 (accountId, updateDate, title) 去重）。
     * 返回 [accountId, updateDate, title] 元组列表，调用方在内存中按复合键判断。
     */
    @Query("SELECT u.accountId, u.updateDate, u.title FROM MediaAccountUpdate u " +
            "WHERE u.accountId IN :ids AND u.updateDate IN :dates")
    List<Object[]> findExistingByAccountIdsAndDates(@Param("ids") Collection<Long> ids,
                                                     @Param("dates") Collection<LocalDate> dates);

    /** 所有更新次数（最近N天） */
    long countByUpdateDateAfter(LocalDate afterDate);

    /** 查找某账号最近一次更新 */
    MediaAccountUpdate findTopByAccountIdOrderByUpdateDateDesc(Long accountId);

    /** 查找最近N天的更新记录（全局时间线） */
    Page<MediaAccountUpdate> findByUpdateDateAfterOrderByUpdateDateDesc(LocalDate afterDate, Pageable pageable);

    // ==================== Analytics 聚合查询 ====================

    /** 按日期+平台统计更新次数 */
    @Query("SELECT u.updateDate AS date, a.platform AS platform, COUNT(u) AS cnt " +
            "FROM MediaAccountUpdate u JOIN MediaAccount a ON u.accountId = a.id " +
            "WHERE u.updateDate >= :afterDate " +
            "GROUP BY u.updateDate, a.platform " +
            "ORDER BY u.updateDate")
    List<Object[]> countByDateAndPlatform(LocalDate afterDate);

    /** 按平台+时间段统计更新次数（本周/本月） */
    @Query("SELECT a.platform AS platform, COUNT(u) AS cnt " +
            "FROM MediaAccountUpdate u JOIN MediaAccount a ON u.accountId = a.id " +
            "WHERE u.updateDate >= :afterDate " +
            "GROUP BY a.platform")
    List<Object[]> countByPlatformAfterDate(LocalDate afterDate);

    /** 按账号+年周统计更新次数（热力图数据）。YEARWEEK 返回 6 位数字 YYYYWW，跨年不冲突 */
    @Query("SELECT u.accountId AS accountId, a.accountName AS accountName, a.platform AS platform, " +
            "FUNCTION('yearweek', u.updateDate) AS yw, COUNT(u) AS cnt " +
            "FROM MediaAccountUpdate u JOIN MediaAccount a ON u.accountId = a.id " +
            "WHERE u.updateDate >= :afterDate " +
            "GROUP BY u.accountId, a.accountName, a.platform, FUNCTION('yearweek', u.updateDate)")
    List<Object[]> countByAccountAndWeek(LocalDate afterDate);

    /** 按运营人员统计更新次数 */
    @Query("SELECT a.operator AS operator, COUNT(u) AS cnt " +
            "FROM MediaAccountUpdate u JOIN MediaAccount a ON u.accountId = a.id " +
            "WHERE u.updateDate >= :afterDate AND a.operator IS NOT NULL AND a.operator <> '' " +
            "GROUP BY a.operator " +
            "ORDER BY COUNT(u) DESC")
    List<Object[]> countByOperator(LocalDate afterDate);

    /** 查询指定日期范围内的所有更新记录（用于单账号时间线） */
    List<MediaAccountUpdate> findByAccountIdAndUpdateDateAfterOrderByUpdateDateDesc(Long accountId, LocalDate afterDate);

    /** 某账号按日期统计更新次数（单账号趋势图） */
    @Query("SELECT u.updateDate AS date, COUNT(u) AS cnt " +
            "FROM MediaAccountUpdate u " +
            "WHERE u.accountId = :accountId AND u.updateDate >= :afterDate " +
            "GROUP BY u.updateDate " +
            "ORDER BY u.updateDate")
    List<Object[]> countByAccountIdAndDateAfterGroupByDate(@Param("accountId") Long accountId, @Param("afterDate") LocalDate afterDate);

    /** 批量查询多个账号的最新一条更新记录（含标题、类型） */
    @Modifying
    @Query("DELETE FROM MediaAccountUpdate u WHERE u.accountId = :accountId")
    void deleteAllByAccountId(@Param("accountId") Long accountId);
}
