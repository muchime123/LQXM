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
 * 主库（{@code product_archive}）装配——**显式接管**，因为 ERP 引入了第二个数据源。
 *
 * <p><b>为什么必须在这里显式定义</b>（Spring Boot 3.3 的条件语义）：
 * <ul>
 *   <li>{@code DataSourceAutoConfiguration} 带 {@code @ConditionalOnMissingBean(DataSource.class)}：
 *       ERP 侧注册了 {@code erpDataSource} 后该条件不再成立，主库数据源不会被自动创建。</li>
 *   <li>{@code DataSourceTransactionManagerAutoConfiguration} 带
 *       {@code @ConditionalOnMissingBean(TransactionManager.class)}：ERP 侧 {@code erpTransactionManager}
 *       会把主库事务管理器顶掉。</li>
 *   <li>{@code JdbcTemplateAutoConfiguration} 带 {@code @ConditionalOnMissingBean(JdbcOperations.class)}：
 *       同理，若不显式提供主库 JdbcTemplate，容器里只剩 ERP 的 {@code erpJdbcTemplate}，
 *       现有 300+ 处注入会**静默连到 erp 库**（比启动失败更危险）。</li>
 * </ul>
 *
 * <p>三者都以 {@code @Primary} 补齐，ERP 侧对应 bean 用 {@code @Qualifier} 取名注入，互不误用。
 * 这是「同实例双数据源」（技术方案映射 DA1）的接入代价，全部集中在本类，便于审查与回滚。
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

    /** 主库连接池；池名固定，与 ERP 池 {@code erp-pool} 在监控中可区分（DA1）。 */
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
