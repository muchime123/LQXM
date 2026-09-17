package com.liqiang.archive.repository;

import com.liqiang.archive.entity.MediaAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MediaAccountRepository extends JpaRepository<MediaAccount, Long> {

    @Query("SELECT a FROM MediaAccount a WHERE " +
            "(:platform IS NULL OR a.platform = :platform) AND " +
            "(:operatorsEmpty = true OR a.operator IN :operators) AND " +
            "(:publishOwner IS NULL OR a.publishOwner = :publishOwner) AND " +
            "(:keyword IS NULL OR a.accountName LIKE %:keyword% OR a.accountId LIKE %:keyword% OR a.tags LIKE %:keyword%)")
    Page<MediaAccount> search(
            @Param("platform") String platform,
            @Param("operators") List<String> operators,
            @Param("operatorsEmpty") boolean operatorsEmpty,
            @Param("publishOwner") String publishOwner,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("SELECT a FROM MediaAccount a WHERE " +
            "(:platform IS NULL OR a.platform = :platform) AND " +
            "(:operatorsEmpty = true OR a.operator IN :operators) AND " +
            "(:publishOwner IS NULL OR a.publishOwner = :publishOwner) AND " +
            "(:keyword IS NULL OR a.accountName LIKE %:keyword% OR a.accountId LIKE %:keyword% OR a.tags LIKE %:keyword%)")
    List<MediaAccount> searchAll(
            @Param("platform") String platform,
            @Param("operators") List<String> operators,
            @Param("operatorsEmpty") boolean operatorsEmpty,
            @Param("publishOwner") String publishOwner,
            @Param("keyword") String keyword);

    @Query("SELECT a FROM MediaAccount a WHERE a.platform = :platform")
    Page<MediaAccount> findByPlatform(String platform, Pageable pageable);

    @Query("SELECT DISTINCT a.platform FROM MediaAccount a ORDER BY a.platform")
    List<String> findDistinctPlatforms();

    /** 按平台分组统计账号数（返回 [platform, count]） */
    @Query("SELECT a.platform, COUNT(a) FROM MediaAccount a GROUP BY a.platform")
    List<Object[]> countByPlatformGroupByPlatform();

    /** 按平台分组汇总粉丝/获赞/视频数（返回 [platform, sumFollowers, sumLikes, sumVideos]） */
    @Query("SELECT a.platform, " +
            "COALESCE(SUM(a.followersCount), 0), " +
            "COALESCE(SUM(a.likesCount), 0), " +
            "COALESCE(SUM(a.videoCount), 0) " +
            "FROM MediaAccount a GROUP BY a.platform")
    List<Object[]> sumMetricsByPlatform();

    /** 官网公开：返回所有标记为官方展示的账号 */
    List<MediaAccount> findByWebsiteOfficialTrueOrderByPlatformAscIdAsc();
}
