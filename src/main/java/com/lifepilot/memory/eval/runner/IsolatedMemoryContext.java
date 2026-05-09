package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 隔离评估环境 — 每个评估用例独立的 SQLite 与 Spring ApplicationContext。
 *
 * <p>生命周期：{@link #start()} → {@link #getBean(Class)} 使用 → {@link #close()}。
 * 推荐 try-with-resources 管理。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *     <li>每个实例创建独立临时 SQLite 文件（{@code java.io.tmpdir/zhiwei-eval-${uuid}.db}）
 *         和独立的向量 DB（{@code .db-vec}）。</li>
 *     <li>通过 {@link SpringApplicationBuilder} 启动独立子 ApplicationContext，
 *         通过 properties 覆盖主库 / 向量库 URL 指向临时文件。</li>
 *     <li>子 context 通过 {@code spring.main.web-application-type=NONE} 避免启动 Web 层，
 *         通过 {@code eval-isolated} profile 排除对评估不必要的周期任务。</li>
 *     <li>close() 关闭 context + 删除临时文件（{@code keep-db-on-failure=true} 时保留）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class IsolatedMemoryContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IsolatedMemoryContext.class);

    private final MemoryEvalProperties properties;
    private final Class<?> bootstrapClass;
    private final String[] additionalProfiles;

    private Path tempDbFile;
    private Path tempVectorDbFile;
    private ConfigurableApplicationContext context;
    private boolean failed;
    private final String contextId = UUID.randomUUID().toString();

    /**
     * 使用指定的 {@code @SpringBootApplication} 启动类。
     * 默认推荐 {@link com.lifepilot.LifePilotApplication}，但集成测试可以传入自定义最小启动类
     * 以缩短启动时间。
     */
    public IsolatedMemoryContext(MemoryEvalProperties properties,
                                 Class<?> bootstrapClass,
                                 String... additionalProfiles) {
        this.properties = properties;
        this.bootstrapClass = bootstrapClass;
        this.additionalProfiles = additionalProfiles;
    }

    public String contextId() {
        return contextId;
    }

    public void start() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "IsolatedMemoryContext 只能在 lifepilot.memory.eval.enabled=true 时启用");
        }
        if (context != null) {
            throw new IllegalStateException("IsolatedMemoryContext 已启动: " + contextId);
        }
        try {
            tempDbFile = Files.createTempFile("zhiwei-eval-main-" + contextId + "-", ".db");
            tempVectorDbFile = Files.createTempFile("zhiwei-eval-vec-" + contextId + "-", ".db");
        } catch (IOException e) {
            throw new IllegalStateException("创建临时 SQLite 失败", e);
        }

        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.datasource.url", "jdbc:sqlite:" + tempDbFile);
        overrides.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        overrides.put("lifepilot.memory.vector-db-url", "jdbc:sqlite:" + tempVectorDbFile);
        overrides.put("lifepilot.memory.eval.enabled", "true");
        // 评估期关闭可能干扰的定时任务
        overrides.put("lifepilot.memory.consolidation.trigger-mode", "CRON");
        overrides.put("lifepilot.memory.consolidation.cron", "-");
        overrides.put("lifepilot.memory.forgetting.cron", "-");
        overrides.put("lifepilot.memory.episodic-cleanup.cron", "-");
        overrides.put("lifepilot.memory.feedback.expiration-cron", "-");
        // 关闭代理服务器等重资源
        overrides.put("spring.main.web-application-type", "NONE");

        SpringApplicationBuilder builder = new SpringApplicationBuilder(bootstrapClass)
                .web(WebApplicationType.NONE)
                .profiles(mergeProfiles())
                .properties(overrides)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .registerShutdownHook(false);

        try {
            context = builder.run();
            log.info("隔离评估环境已启动: id={}, db={}, vector={}",
                    contextId, tempDbFile, tempVectorDbFile);
        } catch (RuntimeException e) {
            failed = true;
            cleanupFiles();
            throw e;
        }
    }

    private String[] mergeProfiles() {
        String[] base = {"memory-eval"};
        if (additionalProfiles == null || additionalProfiles.length == 0) return base;
        String[] merged = new String[base.length + additionalProfiles.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(additionalProfiles, 0, merged, base.length, additionalProfiles.length);
        return merged;
    }

    public <T> T getBean(Class<T> beanType) {
        requireStarted();
        return context.getBean(beanType);
    }

    public <T> java.util.Optional<T> getOptionalBean(Class<T> beanType) {
        requireStarted();
        try {
            return java.util.Optional.of(context.getBean(beanType));
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            return java.util.Optional.empty();
        }
    }

    public ConfigurableApplicationContext applicationContext() {
        requireStarted();
        return context;
    }

    public Path tempDbFile() { return tempDbFile; }
    public Path tempVectorDbFile() { return tempVectorDbFile; }

    /**
     * 标记当前运行失败（影响 close() 是否清理临时文件）。
     */
    public void markFailed() {
        this.failed = true;
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                SpringApplication.exit(context);
                context.close();
            }
        } catch (Exception e) {
            log.warn("关闭隔离 context 异常: id={}, error={}", contextId, e.getMessage());
        }
        cleanupFiles();
    }

    private void cleanupFiles() {
        boolean keep = failed && properties.getIsolation().isKeepDbOnFailure();
        if (!keep) {
            deleteIfExists(tempDbFile);
            deleteIfExists(tempVectorDbFile);
        } else {
            log.info("保留失败 case 的 SQLite 供调试: main={}, vector={}",
                    tempDbFile, tempVectorDbFile);
        }
    }

    private static void deleteIfExists(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 可能 Windows 上 sqlite 句柄未完全释放，留给 JVM 退出时清
        }
    }

    private void requireStarted() {
        if (context == null) {
            throw new IllegalStateException("IsolatedMemoryContext 尚未 start()");
        }
    }
}
