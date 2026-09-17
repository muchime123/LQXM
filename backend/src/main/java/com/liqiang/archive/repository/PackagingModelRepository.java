package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackagingModelRepository extends JpaRepository<PackagingModel, Long> {

    Optional<PackagingModel> findByName(String name);

    @Query("SELECT m FROM PackagingModel m WHERE "
            + "(:keyword IS NULL OR :keyword = '' OR "
            + "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.material) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.applicable) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.modelCode) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:packagingKind IS NULL OR :packagingKind = '' OR m.packagingKind = :packagingKind) "
            + "AND (:material IS NULL OR :material = '' OR m.material = :material) "
            + "AND (:status IS NULL OR :status = '' OR m.status = :status)")
    Page<PackagingModel> searchFiltered(@Param("keyword") String keyword,
                                         @Param("packagingKind") String packagingKind,
                                         @Param("material") String material,
                                         @Param("status") String status,
                                         Pageable pageable);

    @Query("SELECT DISTINCT m.packagingKind FROM PackagingModel m WHERE m.packagingKind IS NOT NULL AND m.packagingKind <> '' ORDER BY m.packagingKind")
    List<String> findDistinctKinds();

    @Query("SELECT DISTINCT m.material FROM PackagingModel m WHERE m.material IS NOT NULL AND m.material <> '' ORDER BY m.material")
    List<String> findDistinctMaterials();

    @Query("SELECT DISTINCT m.status FROM PackagingModel m WHERE m.status IS NOT NULL AND m.status <> '' ORDER BY m.status")
    List<String> findDistinctStatuses();

    /** 已发布到官网的包材数量（官网工作台统计用）。 */
    long countByWebsiteEnabledTrue();
}
