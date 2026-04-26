package com.lifepilot.sandbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.sandbox.booter.DockerBooter;
import com.lifepilot.sandbox.booter.ProcessBooter;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;

/**
 * 沙箱模块 Spring AutoConfiguration。
 *
 * <p>通过 {@code lifepilot.sandbox.enabled=true}（默认）激活，
 * 注册沙箱模块全部 Bean：SandboxBooter、CodeValidator、SandboxSessionManager、
 * SandboxRepository。</p>
 *
 * <p>根据 {@code lifepilot.sandbox.booter} 配置选择 ProcessBooter 或 DockerBooter。
 * Docker 模式下检查可用性，不可用则启动失败。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.sandbox.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SandboxConfigProperties.class)
public class SandboxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SandboxAutoConfiguration.class);

    /**
     * 注册 SandboxBooter Bean。
     *
     * <p>根据 {@code lifepilot.sandbox.booter} 配置选择实现：
     * "process" → {@link ProcessBooter}，"docker" → {@link DockerBooter}。
     * Docker 模式下检查可用性，不可用则抛出 {@link IllegalStateException} 阻止启动。</p>
     *
     * @param config 沙箱配置
     * @return SandboxBooter 实例
     */
    @Bean
    @ConditionalOnMissingBean
    SandboxBooter sandboxBooter(SandboxConfigProperties config) {
        String booterType = config.getBooter();
        SandboxBooter booter = switch (booterType) {
            // Task 13 会把 PythonRuntimeManager 抽成独立 @Bean 并通过参数注入；本任务先就地构造避免破坏编译
            case "process" -> new ProcessBooter(config, new PythonRuntimeManager(config));
            case "docker" -> {
                var dockerBooter = new DockerBooter(config);
                if (!dockerBooter.available()) {
                    throw new IllegalStateException(
                            "Docker Engine 不可用，请确认 Docker 已安装并正在运行（配置 lifepilot.sandbox.booter=docker）");
                }
                log.info("Docker 沙箱可用性检测通过");
                yield dockerBooter;
            }
            default -> throw new IllegalStateException("不支持的沙箱类型: " + booterType);
        };
        log.info("SandboxBooter 注册完成: type={}", booterType);
        return booter;
    }

    /**
     * 注册 CodeValidator Bean。
     *
     * @param config 沙箱配置
     * @return CodeValidator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    CodeValidator codeValidator(SandboxConfigProperties config) {
        log.info("CodeValidator 注册完成: enabled={}, rejectCritical={}",
                config.getValidator().isEnabled(), config.getValidator().isRejectCritical());
        return new CodeValidator(config);
    }

    /**
     * 注册 SandboxSessionManager Bean。
     *
     * @param config            沙箱配置
     * @param booter            沙箱启动器模板
     * @param sharedScheduler   共享调度器
     * @param workspaceResolver 工作目录解析器
     * @return SandboxSessionManager 实例
     */
    @Bean
    @ConditionalOnMissingBean
    SandboxSessionManager sandboxSessionManager(SandboxConfigProperties config, SandboxBooter booter,
                                                SharedScheduler sharedScheduler,
                                                WorkspaceResolver workspaceResolver) {
        return new SandboxSessionManager(config, booter, sharedScheduler, workspaceResolver);
    }

    /**
     * 注册 SandboxRepository Bean。
     *
     * <p>依赖 JdbcTemplate，仅在 JdbcTemplate 可用时注册。</p>
     *
     * @param jdbcTemplate Spring JdbcTemplate
     * @return SandboxRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    SandboxRepository sandboxRepository(JdbcTemplate jdbcTemplate) {
        log.info("SandboxRepository 注册完成");
        return new SandboxRepository(jdbcTemplate);
    }

}
