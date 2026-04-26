package com.lifepilot.sandbox.config;

import jakarta.annotation.Nullable;

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
import com.lifepilot.sandbox.guard.CommandGuard;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallHistoryRepository;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;

/**
 * 沙箱模块 Spring AutoConfiguration。
 *
 * <p>通过 {@code lifepilot.sandbox.enabled=true}（默认）激活，
 * 注册沙箱模块全部 Bean：SandboxBooter、CodeValidator、SandboxSessionManager、
 * SandboxRepository、PythonRuntimeManager、CommandGuard、RuntimeInstallHistoryRepository。</p>
 *
 * <p>根据 {@code lifepilot.sandbox.booter} 配置选择 ProcessBooter 或 DockerBooter。
 * Docker 模式下检查可用性，不可用则启动失败。</p>
 *
 * <p>PythonRuntimeManager 由容器统一管理，被 ProcessBooter / SandboxSessionManager /
 * CodeExecuteToolExecutor / RuntimeController 共享同一实例，避免 Task 11 早期临时
 * {@code new PythonRuntimeManager(config)} 造成的 installingState 等共享状态分裂。</p>
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
     * 注册捆绑 Python 运行时管理器。
     *
     * <p>容器中只允许一个 PythonRuntimeManager 实例，被 ProcessBooter / SandboxSessionManager /
     * CodeExecuteToolExecutor / RuntimeController 共享，保证 installingState 等共享状态唯一。
     * historyRepo 缺失（罕见的非数据库场景）时仍可注册，仅跳过审计写入。</p>
     *
     * @param config      沙箱配置
     * @param historyRepo 安装历史仓库（可选）
     * @return PythonRuntimeManager 实例
     */
    @Bean
    @ConditionalOnMissingBean
    PythonRuntimeManager pythonRuntimeManager(SandboxConfigProperties config,
                                              @Nullable RuntimeInstallHistoryRepository historyRepo) {
        log.info("PythonRuntimeManager 注册完成: historyRepo={}",
                historyRepo != null ? "available" : "absent");
        return new PythonRuntimeManager(config, historyRepo);
    }

    /**
     * 注册命令护栏。
     *
     * <p>process 后端路径在 CodeExecuteToolExecutor 内会先经过 CommandGuard 检查；
     * docker 后端被 CommandGuard 内部 bypass。yolo 模式下仅 HARDLINE 阻断生效。</p>
     *
     * @param config 沙箱配置
     * @return CommandGuard 实例
     */
    @Bean
    @ConditionalOnMissingBean
    CommandGuard commandGuard(SandboxConfigProperties config) {
        log.info("CommandGuard 注册完成: enabled={}, yoloMode={}",
                config.getRuntime().getCommandGuard().isEnabled(),
                config.getRuntime().getCommandGuard().isYoloMode());
        return new CommandGuard(config);
    }

    /**
     * 注册运行时安装历史仓库。
     *
     * <p>仅在 JdbcTemplate 可用时注册（非数据库测试场景下不注册，PythonRuntimeManager 仍可工作）。</p>
     *
     * @param jdbcTemplate Spring JdbcTemplate
     * @return RuntimeInstallHistoryRepository 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    RuntimeInstallHistoryRepository runtimeInstallHistoryRepository(JdbcTemplate jdbcTemplate) {
        log.info("RuntimeInstallHistoryRepository 注册完成");
        return new RuntimeInstallHistoryRepository(jdbcTemplate);
    }

    /**
     * 注册 SandboxBooter Bean。
     *
     * <p>根据 {@code lifepilot.sandbox.booter} 配置选择实现：
     * "process" → {@link ProcessBooter}，"docker" → {@link DockerBooter}。
     * Docker 模式下检查可用性，不可用则抛出 {@link IllegalStateException} 阻止启动。</p>
     *
     * <p>process 模式下注入容器中的 {@link PythonRuntimeManager} bean，与
     * SandboxSessionManager / CodeExecuteToolExecutor / RuntimeController 共享同一实例。</p>
     *
     * @param config               沙箱配置
     * @param pythonRuntimeManager 捆绑 Python 运行时管理器
     * @return SandboxBooter 实例
     */
    @Bean
    @ConditionalOnMissingBean
    SandboxBooter sandboxBooter(SandboxConfigProperties config,
                                PythonRuntimeManager pythonRuntimeManager) {
        String booterType = config.getBooter();
        SandboxBooter booter = switch (booterType) {
            case "process" -> new ProcessBooter(config, pythonRuntimeManager);
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
     * @param config               沙箱配置
     * @param booter               沙箱启动器模板
     * @param sharedScheduler      共享调度器
     * @param workspaceResolver    工作目录解析器
     * @param pythonRuntimeManager 捆绑 Python 运行时管理器（派生 ProcessBooter 时复用）
     * @return SandboxSessionManager 实例
     */
    @Bean
    @ConditionalOnMissingBean
    SandboxSessionManager sandboxSessionManager(SandboxConfigProperties config, SandboxBooter booter,
                                                SharedScheduler sharedScheduler,
                                                WorkspaceResolver workspaceResolver,
                                                PythonRuntimeManager pythonRuntimeManager) {
        return new SandboxSessionManager(config, booter, sharedScheduler, workspaceResolver, pythonRuntimeManager);
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
