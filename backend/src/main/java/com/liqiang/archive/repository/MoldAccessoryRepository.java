package com.liqiang.archive.repository;

import com.liqiang.archive.entity.MoldAccessory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MoldAccessoryRepository extends JpaRepository<MoldAccessory, Long> {

    List<MoldAccessory> findByMoldIdOrderByAccessoryTypeAscIdAsc(Long moldId);

    List<MoldAccessory> findByMoldIdIn(Collection<Long> moldIds);

    @Query("SELECT a.mold.id, COUNT(a) FROM MoldAccessory a WHERE a.mold.id IN :moldIds GROUP BY a.mold.id")
    List<Object[]> countByMoldIds(@Param("moldIds") List<Long> moldIds);

    @Modifying
    @Query("UPDATE MoldAccessory a SET a.deletedAt = CURRENT_TIMESTAMP WHERE a.mold.id = :moldId")
    void softDeleteByMoldId(@Param("moldId") Long moldId);

    /**
     * 恢复指定模具的所有已软删配件。
     * <p>用于回收站恢复模具时，级联恢复随模具一起被软删的配件记录。
     */
    @Modifying
    @Query("UPDATE MoldAccessory a SET a.deletedAt = null WHERE a.mold.id = :moldId AND a.deletedAt IS NOT NULL")
    int restoreByMoldId(@Param("moldId") Long moldId);
}
