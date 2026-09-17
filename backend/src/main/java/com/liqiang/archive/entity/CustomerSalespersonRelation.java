package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "customer_salesperson_relations", indexes = {
        @Index(name = "idx_csr_customer", columnList = "customer_id"),
        @Index(name = "idx_csr_salesperson", columnList = "salesperson_name")
})
public class CustomerSalespersonRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 关联公司成员（company_team_members.id），可为空（历史合同导入时只有姓名）。 */
    @Column(name = "salesperson_id")
    private Long salespersonId;

    /** 业务员姓名快照，合同导入与筛选都依赖它。 */
    @NotBlank(message = "业务员姓名不能为空")
    @Size(max = 50)
    @Column(name = "salesperson_name", nullable = false, length = 50)
    private String salespersonName;

    /** 角色：PRIMARY（当前负责人）/ HISTORY（历史）/ COLLABORATOR（协同）。 */
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String role = "COLLABORATOR";

    private LocalDate startDate;

    private LocalDate endDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CustomerSalespersonRelation() {
    }
}