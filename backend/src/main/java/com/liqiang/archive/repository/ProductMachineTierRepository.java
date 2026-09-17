package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductMachineTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductMachineTierRepository extends JpaRepository<ProductMachineTier, Long> {

    List<ProductMachineTier> findByModelIdOrderBySortOrderAscIdAsc(Long modelId);

    Optional<ProductMachineTier> findFirstByModelIdAndTierCodeIgnoreCase(Long modelId, String tierCode);

    @Query("select t.model.id, count(t) from ProductMachineTier t where t.model.id in :modelIds group by t.model.id")
    List<Object[]> countByModelIds(@Param("modelIds") List<Long> modelIds);
}
