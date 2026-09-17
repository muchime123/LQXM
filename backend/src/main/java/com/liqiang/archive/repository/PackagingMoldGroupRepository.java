package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingMoldGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackagingMoldGroupRepository extends JpaRepository<PackagingMoldGroup, Long> {

    List<PackagingMoldGroup> findByModelIdOrderByNameAsc(Long modelId);

    Optional<PackagingMoldGroup> findByModelIdAndName(Long modelId, String name);

    Optional<PackagingMoldGroup> findByModelIdAndNormalizedKey(Long modelId, String normalizedKey);

    /**
     * 同一系列下重复的规格族名称。
     * 列顺序: modelId, modelName, groupName, duplicateCount
     */
    @Query(value = "SELECT m.id, m.name, g.name, COUNT(*) cnt " +
            "FROM packaging_mold_groups g " +
            "INNER JOIN packaging_models m ON m.id = g.model_id " +
            "WHERE g.deleted_at IS NULL AND m.deleted_at IS NULL " +
            "GROUP BY m.id, m.name, g.name HAVING COUNT(*) > 1", nativeQuery = true)
    List<Object[]> findDuplicateGroupNamesRaw();
}
