package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyTeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyTeamMemberRepository extends JpaRepository<CompanyTeamMember, Long> {

    List<CompanyTeamMember> findAllByOrderBySortOrderAsc();

    Optional<CompanyTeamMember> findByExternalUserId(String externalUserId);

    List<CompanyTeamMember> findByName(String name);
}
