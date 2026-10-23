package com.liqiang.archive.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 主库（{@code product_archive}）装配——显式接管，统一主数据源、JdbcTemplate 与事务管理器。
 *
 * <p><b>为什么仍然显式定义</b>：虽然 2026-10-23 已随 ERP 独立 schema 下线移除第二数据源，
 * 但显式声明可让「主库连接池名、JPA 事务管理器、JdbcTemplate」三者的绑定关系一目了然，
 * 且 {@code spring.datasource.hikari.*} 不再自动绑定，池参数仍需经 {@link DataSourceProperties} 配置。
 *
 * <p><b>已知副作用</b>：{@code spring.datasource.hikari.*} 不再自动绑定（本类经
 * {@link DataSourceProperties} 构建池）。需要调池参数请改本类，只改 yml 不生效。
 *
 * <p>事务管理器保持 {@link JpaTransactionManager}（与 Boot 自动配置一致），以确保现有
 * JPA Repository 的 EntityManager 生命周期、flush 时机与延迟加载行为不变。
 */
@Configuration
public class PrimaryDataSourceConfig {

    /** 主库连接参数（沿用 {@code spring.datasource.*}，含 .env 注入的账号密码）。 */
    @Bean(name = "dataSourceProperties")
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 主库连接池；池名固定为 {@code archive-pool}，便于监控中识别。 */
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("dataSourceProperties") DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("archive-pool");
        return dataSource;
    }

    /** 主库 JdbcTemplate（@Primary：现有全部注入点继续命中主库）。 */
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 主库事务管理器（@Primary）。 */
    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        return transactionManager;
    }
}
