package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CustomerRegionAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerRegionAliasRepository extends JpaRepository<CustomerRegionAlias, Long> {

    List<CustomerRegionAlias> findAllByOrderByKindAscCreatedAtAsc();
}
