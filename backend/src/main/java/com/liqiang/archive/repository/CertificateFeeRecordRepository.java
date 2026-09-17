package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CertificateFeeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CertificateFeeRecordRepository extends JpaRepository<CertificateFeeRecord, Long> {

    /** 查某证书的全部缴费记录，按缴费日期倒序 */
    List<CertificateFeeRecord> findByCertificateIdOrderByPaidDateDesc(Long certificateId);
}
