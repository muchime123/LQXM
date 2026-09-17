package com.liqiang.archive.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 异步任务配置。为 Office 预览转换等 IO 密集型异步任务提供虚拟线程执行器。
 *
 * <p>仅为本特性提供命名执行器 {@code officePreviewExecutor}，不启用全局
 * {@code spring.threads.virtual.enabled}（会把 Tomcat 请求线程也变虚拟线程，爆炸半径过大）。
 * LibreOffice 的 {@code process.waitFor()} 是纯 IO 等待，虚拟线程不占平台线程，适合此场景。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** Office 预览转换专用执行器：每个任务一个虚拟线程。 */
    @Bean(name = "officePreviewExecutor")
    public Executor officePreviewExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 氚云导入专用执行器：每个导入批次一个虚拟线程（Excel 解析/哈希/数十万行落库
     * 是 IO+CPU 混合密集，虚拟线程在 IO 等待（写库/读磁盘）阶段让出平台线程，
     * 避免大文件导入期间阻塞 Tomcat 请求线程。
     * 与 officePreviewExecutor 分开命名，便于未来独立限流。
     */
    @Bean(name = "h3ImportExecutor")
    public Executor h3ImportExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("异步任务未捕获异常: {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }
}
