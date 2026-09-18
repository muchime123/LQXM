package com.liqiang.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ERP 正式业务区（{@code erp} schema）的数据源配置。
 *
 * <p>DA1：与 {@code product_archive} 同实例、独立 schema、**独立连接池**（{@code erp-pool}）。
 * 迁移历史表独立为 {@code erp_flyway_history}，版本号从 V1 起独立编（不与主库 V1–V3xx 混编）。
 */
@Data
@ConfigurationProperties(prefix = "erp.datasource")
public class ErpDataSourceProperties {

    /** JDBC URL。默认指向同实例的 erp schema（库需先存在，见部署说明）。 */
    private String url = "jdbc:mysql://localhost:3306/erp"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false";

    private String username = "root";

    private String password = "";

    private String driverClassName = "com.mysql.cj.jdbc.Driver";

    /** 连接池上限：ERP 为短事务 OLTP，默认小于主库（主库承载导入等长任务）。 */
    private int maximumPoolSize = 10;

    private int minimumIdle = 2;

    private long connectionTimeoutMs = 10_000;

    /** 是否在应用启动时执行 erp schema 迁移。 */
    private boolean migrateOnStartup = true;

    /**
     * 迁移失败是否阻断启动。
     * 默认阻断：宁可起不来，也不要带着半个 schema 对外服务（ERP 过账依赖完整约束）。
     */
    private boolean failFastOnMigrationError = true;
}
