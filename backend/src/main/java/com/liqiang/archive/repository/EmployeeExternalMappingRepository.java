package com.liqiang.archive.repository;

import com.liqiang.archive.entity.EmployeeExternalMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeExternalMappingRepository extends JpaRepository<EmployeeExternalMapping, Long> {

    List<EmployeeExternalMapping> findByEmployeeIdOrderBySourceSystemAscIsPrimaryDescIdAsc(Long employeeId);

    Optional<EmployeeExternalMapping> findBySourceSystemAndExternalUserId(String sourceSystem, String externalUserId);

    @Modifying
    @Query("DELETE FROM EmployeeExternalMapping m WHERE m.employeeId = :employeeId")
    int deleteAllByEmployeeId(@Param("employeeId") Long employeeId);

    @Query("SELECT m.employeeId FROM EmployeeExternalMapping m WHERE m.sourceSystem = :sourceSystem AND m.externalUserId = :externalUserId")
    Optional<Long> findEmployeeIdBySourceAndExternalUser(
            @Param("sourceSystem") String sourceSystem,
            @Param("externalUserId") String externalUserId);

    /** 通过外部姓名做兜底匹配：返回同系统下 external_name=:name 的所有员工 ID（按 is_primary 倒序）。 */
    @Query("SELECT m.employeeId FROM EmployeeExternalMapping m WHERE m.sourceSystem = :sourceSystem AND m.externalName = :name ORDER BY m.isPrimary DESC, m.id ASC")
    List<Long> findEmployeeIdsBySourceAndExternalName(@Param("sourceSystem") String sourceSystem, @Param("name") String name);
}
