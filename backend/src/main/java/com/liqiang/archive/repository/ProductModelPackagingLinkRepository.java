package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductModelPackagingLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductModelPackagingLinkRepository extends JpaRepository<ProductModelPackagingLink, Long> {
    @Query("select l from ProductModelPackagingLink l join fetch l.productModel m " +
            "join fetch l.packagingVariant p join fetch p.model where m.id = :modelId order by p.model.name, p.variantName")
    List<ProductModelPackagingLink> findByProductModelId(@Param("modelId") Long modelId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ProductModelPackagingLink l where l.productModel.id = :modelId")
    void deleteByProductModelId(@Param("modelId") Long modelId);
}
