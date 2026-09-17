package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductMachineTierPackagingLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductMachineTierPackagingLinkRepository extends JpaRepository<ProductMachineTierPackagingLink, Long> {
    @Query("select l from ProductMachineTierPackagingLink l join fetch l.machineTier t " +
            "join fetch l.packagingVariant p join fetch p.model where t.model.id = :modelId order by t.sortOrder, p.model.name, p.variantName")
    List<ProductMachineTierPackagingLink> findByProductModelId(@Param("modelId") Long modelId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ProductMachineTierPackagingLink l where l.machineTier.id = :tierId")
    void deleteByMachineTierId(@Param("tierId") Long tierId);
}
