package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Sort;

import java.util.List;

public interface CompanyMilestoneRepository extends JpaRepository<CompanyMilestone, Long> {

    List<CompanyMilestone> findAll(Sort sort);
}
