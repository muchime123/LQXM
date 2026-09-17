package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductVariantPackagingLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductVariantPackagingLinkRepository extends JpaRepository<ProductVariantPackagingLink, Long> {
    @Query("select l from ProductVariantPackagingLink l " +
            "join fetch l.productVariant v join fetch l.packagingVariant p join fetch p.model " +
            "where v.model.id = :modelId order by v.id, p.model.name, p.variantName")
    List<ProductVariantPackagingLink> findByProductModelId(@Param("modelId") Long modelId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ProductVariantPackagingLink l where l.productVariant.id = :variantId")
    void deleteByProductVariantId(@Param("variantId") Long variantId);
}
