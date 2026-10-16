package com.liqiang.archive.repository;

import com.liqiang.archive.entity.EmployeeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EmployeeFileRepository extends JpaRepository<EmployeeFile, Long> {

    List<EmployeeFile> findByEmployeeIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long employeeId);

    List<EmployeeFile> findByEmployeeIdOrderBySortOrderAscIdAsc(Long employeeId);

    @Modifying
    @Query("DELETE FROM EmployeeFile f WHERE f.employeeId = :employeeId")
    void deleteAllByEmployeeId(Long employeeId);
}
