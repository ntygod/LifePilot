package com.lifepilot.sandbox.config;

import java.util.concurrent.ScheduledExecutorService;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.guard.CommandGuard;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallHistoryRepository;
import com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SandboxAutoConfiguration 条件注册行为测试。
 *
 * <p>覆盖 4 个核心场景：
 * <ol>
 *   <li>默认场景全部 Bean 注册</li>
 *   <li>{@code lifepilot.sandbox.enabled=false} 不注册任何 sandbox bean</li>
 *   <li>JdbcTemplate 缺失时 historyRepo / sandboxRepository 跳过，但 PythonRuntimeManager 仍注册</li>
 *   <li>用户自定义 PythonRuntimeManager bean 时 {@code @ConditionalOnMissingBean} 让步</li>
 * </ol>
 *
 * <p>内部 {@code @TestConfiguration} 类的 Bean 命名加 {@code sandboxTest*} 前缀避免与
 * 其他测试 / 主应用 Bean 冲突；两个内部类的 Bean 名不重复（带 {@code WithJdbc} /
 * {@code NoJdbc} 后缀），因为 {@code @SpringBootTest} 全应用上下文测试会扫描所有 nested
 * {@code @Configuration} 候选并尝试注册它们的 Bean。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
class SandboxAutoConfigurationTest {

    /** 默认场景：sandbox.enabled=true（默认） + JdbcTemplate 存在 → 全部 Bean 注册。 */
    @Test
    void 默认场景注册全部bean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SandboxAutoConfiguration.class))
                .withUserConfiguration(必要依赖配置.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SandboxBooter.class);
                    assertThat(context).hasSingleBean(CodeValidator.class);
                    assertThat(context).hasSingleBean(SandboxSessionManager.class);
                    assertThat(context).hasSingleBean(SandboxRepository.class);
                    assertThat(context).hasSingleBean(PythonRuntimeManager.class);
                    assertThat(context).hasSingleBean(CommandGuard.class);
                    assertThat(context).hasSingleBean(RuntimeInstallHistoryRepository.class);
                    assertThat(context).hasSingleBean(RuntimeInstallProgressEmitter.class);
                });
    }

    /** 沙箱禁用：lifepilot.sandbox.enabled=false → 不注册任何 sandbox bean。 */
    @Test
    void 沙箱禁用时不注册任何bean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SandboxAutoConfiguration.class))
                .withUserConfiguration(必要依赖配置.class)
                .withPropertyValues("lifepilot.sandbox.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SandboxBooter.class);
                    assertThat(context).doesNotHaveBean(CodeValidator.class);
                    assertThat(context).doesNotHaveBean(SandboxSessionManager.class);
                    assertThat(context).doesNotHaveBean(SandboxRepository.class);
                    assertThat(context).doesNotHaveBean(PythonRuntimeManager.class);
                    assertThat(context).doesNotHaveBean(CommandGuard.class);
                    assertThat(context).doesNotHaveBean(RuntimeInstallHistoryRepository.class);
                    assertThat(context).doesNotHaveBean(RuntimeInstallProgressEmitter.class);
                });
    }

    /** JdbcTemplate 缺失：historyRepo / sandboxRepository 不注册，PythonRuntimeManager 仍可注册（@Nullable 注入）。 */
    @Test
    void JdbcTemplate缺失时historyRepo不注册但其他bean正常() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SandboxAutoConfiguration.class))
                .withUserConfiguration(无JdbcTemplate配置.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PythonRuntimeManager.class);
                    assertThat(context).hasSingleBean(CommandGuard.class);
                    assertThat(context).hasSingleBean(RuntimeInstallProgressEmitter.class);
                    assertThat(context).hasSingleBean(CodeValidator.class);
                    assertThat(context).doesNotHaveBean(RuntimeInstallHistoryRepository.class);
                    assertThat(context).doesNotHaveBean(SandboxRepository.class);
                });
    }

    /** 用户提供自定义 PythonRuntimeManager bean → @ConditionalOnMissingBean 让步。 */
    @Test
    void 用户自定义PythonRuntimeManagerBean时跳过自动注册() {
        var customManager = mock(PythonRuntimeManager.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SandboxAutoConfiguration.class))
                .withUserConfiguration(必要依赖配置.class)
                .withBean(PythonRuntimeManager.class, () -> customManager)
                .run(context -> {
                    assertThat(context).hasSingleBean(PythonRuntimeManager.class);
                    assertThat(context.getBean(PythonRuntimeManager.class)).isSameAs(customManager);
                });
    }

    /**
     * 必要依赖：SharedScheduler / WorkspaceResolver / DataSource / JdbcTemplate。
     *
     * <p>Bean 名带 {@code WithJdbc} 后缀以与 {@link 无JdbcTemplate配置} 的同类型 Bean 区分，
     * 避免被 {@code @SpringBootTest} 全应用上下文测试同时扫描时产生 BeanDefinitionOverrideException。</p>
     */
    @TestConfiguration
    static class 必要依赖配置 {
        @Bean(name = "sandboxTestSharedSchedulerWithJdbc")
        SharedScheduler sharedScheduler() {
            var scheduler = mock(SharedScheduler.class);
            var executor = mock(ScheduledExecutorService.class);
            when(scheduler.cleanup()).thenReturn(executor);
            when(scheduler.debounce()).thenReturn(executor);
            when(scheduler.heartbeat()).thenReturn(executor);
            return scheduler;
        }

        @Bean(name = "sandboxTestWorkspaceResolverWithJdbc")
        WorkspaceResolver workspaceResolver() {
            return mock(WorkspaceResolver.class);
        }

        @Bean(name = "sandboxTestZhiweiPathsWithJdbc")
        ZhiweiPaths zhiweiPaths() {
            var paths = mock(ZhiweiPaths.class);
            when(paths.home(ZhiweiPaths.DIR_RUNTIME_PYTHON))
                    .thenReturn(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "zhiwei-test", "runtime", "python"));
            return paths;
        }

        @Bean(name = "sandboxTestDataSourceWithJdbc")
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean(name = "sandboxTestJdbcTemplateWithJdbc")
        JdbcTemplate jdbcTemplate(DataSource sandboxTestDataSourceWithJdbc) {
            return new JdbcTemplate(sandboxTestDataSourceWithJdbc);
        }
    }

    /** 无 JdbcTemplate 场景（仅基础依赖）。 */
    @TestConfiguration
    static class 无JdbcTemplate配置 {
        @Bean(name = "sandboxTestSharedSchedulerNoJdbc")
        SharedScheduler sharedScheduler() {
            var scheduler = mock(SharedScheduler.class);
            var executor = mock(ScheduledExecutorService.class);
            when(scheduler.cleanup()).thenReturn(executor);
            when(scheduler.debounce()).thenReturn(executor);
            when(scheduler.heartbeat()).thenReturn(executor);
            return scheduler;
        }

        @Bean(name = "sandboxTestWorkspaceResolverNoJdbc")
        WorkspaceResolver workspaceResolver() {
            return mock(WorkspaceResolver.class);
        }

        @Bean(name = "sandboxTestZhiweiPathsNoJdbc")
        ZhiweiPaths zhiweiPaths() {
            var paths = mock(ZhiweiPaths.class);
            when(paths.home(ZhiweiPaths.DIR_RUNTIME_PYTHON))
                    .thenReturn(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "zhiwei-test", "runtime", "python"));
            return paths;
        }
    }
}
