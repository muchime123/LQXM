package com.liqiang.archive.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * ERP 正式业务区（{@code erp} schema）的独立数据源装配（技术方案映射 DA1 / M0）。
 *
 * <p>与 {@link PrimaryDataSourceConfig} 的关系：主库三个 bean（datasource / jdbcTemplate /
 * transactionManager）由那个类显式标 {@code @Primary} 接管；本类的 bean 一律用
 * {@code @Qualifier} 指名注入，**不参与**现有代码的按类型注入。
 *
 * <p>迁移不走 Spring Boot 的 Flyway 自动配置：自动配置带 {@code @ConditionalOnMissingBean(Flyway.class)}，
 * 若在此注册 Flyway bean 会把**主库迁移**一并顶掉。因此改用 {@link ApplicationRunner} 在主库迁移完成后
 * 执行 erp 迁移——既不干扰主库管线，又能对失败做「阻断启动 / 仅告警」的显式选择。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ErpDataSourceProperties.class)
public class ErpDataSourceConfig {

    /** ERP 独立连接池（DA1：Hikari 各自命名，与主库 archive-pool 区分）。 */
    @Bean(name = "erpDataSource", destroyMethod = "close")
    public HikariDataSource erpDataSource(ErpDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("erp-pool");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setMinimumIdle(properties.getMinimumIdle());
        config.setConnectionTimeout(properties.getConnectionTimeoutMs());
        return new HikariDataSource(config);
    }

    /** ERP 专用 JdbcTemplate：所有 erp schema 的读写都经它，不混用主库连接。 */
    @Bean(name = "erpJdbcTemplate")
    public JdbcTemplate erpJdbcTemplate(@Qualifier("erpDataSource") DataSource erpDataSource) {
        return new JdbcTemplate(erpDataSource);
    }

    /** ERP 专用事务管理器：供 TransactionTemplate 编程式事务使用。 */
    @Bean(name = "erpTransactionManager")
    public PlatformTransactionManager erpTransactionManager(@Qualifier("erpDataSource") DataSource erpDataSource) {
        return new DataSourceTransactionManager(erpDataSource);
    }

    /**
     * 启动时执行 erp schema 迁移（独立 history 表 {@code erp_flyway_history}）。
     *
     * <p>执行时点在主库 Flyway（上下文启动期）之后，顺序天然满足「先主库后 ERP」。
     */
    @Bean
    public ApplicationRunner erpSchemaMigrator(@Qualifier("erpDataSource") DataSource erpDataSource,
                                               ErpDataSourceProperties properties) {
        return args -> {
            if (!properties.isMigrateOnStartup()) {
                log.warn("ERP schema 迁移已关闭（erp.datasource.migrate-on-startup=false），跳过");
                return;
            }
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(erpDataSource)
                        .locations("classpath:db/erp-migration")
                        .table("erp_flyway_history")
                        .schemas("erp")
                        .defaultSchema("erp")
                        .createSchemas(true)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .outOfOrder(true)
                        .load();
                flyway.migrate();
                log.info("ERP schema 迁移完成（locations=classpath:db/erp-migration, history=erp_flyway_history）");
            } catch (Exception ex) {
                if (properties.isFailFastOnMigrationError()) {
                    throw new IllegalStateException("ERP schema 迁移失败，已阻断启动：" + ex.getMessage(), ex);
                }
                log.error("ERP schema 迁移失败（fail-fast=false，应用继续启动）：{}", ex.getMessage(), ex);
            }
        };
    }
}
