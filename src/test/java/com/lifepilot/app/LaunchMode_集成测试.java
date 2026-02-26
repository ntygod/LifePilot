package com.lifepilot.app;

import java.nio.file.Path;
import java.util.UUID;

import com.lifepilot.interaction.cli.CliShell;
import com.lifepilot.interaction.cli.config.CliAutoConfiguration;
import com.lifepilot.interaction.tray.TrayManager;
import com.lifepilot.interaction.tray.TrayNotificationChannel;
import com.lifepilot.interaction.tray.config.TrayAutoConfiguration;
import com.lifepilot.interaction.tray.config.TrayConfigProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LaunchMode Spring Context 集成测试 — 验证各启动模式下 AutoConfiguration 的条件激活。
 *
 * <p>混合使用两种测试策略：
 * <ul>
 *   <li>{@link ApplicationContextRunner}：轻量级验证 AutoConfiguration 条件逻辑，
 *       无需启动完整 Spring 上下文</li>
 *   <li>{@code @SpringBootTest}：验证 TRAY 模式完整上下文加载和 WEB/CLI 属性注入</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
class LaunchMode_集成测试 {

    // ── CliAutoConfiguration 条件测试 ───────────────────────────

    @Nested
    class CliAutoConfiguration条件 {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CliAutoConfiguration.class));

        @Test
        void 默认启用_matchIfMissing() {
            // lifepilot.cli.enabled 未设置时，matchIfMissing=true → 激活
            // 但缺少依赖 Bean 会导致 Bean 创建失败，验证配置类本身被激活即可
            runner.run(context ->
                    // CliAutoConfiguration 被激活（即使内部 Bean 因缺少依赖而失败）
                    assertThat(context).hasFailed()
            );
        }

        @Test
        void 显式禁用_不激活() {
            runner.withPropertyValues("lifepilot.cli.enabled=false")
                    .run(context -> {
                        // 配置类未激活，不会尝试创建任何 Bean
                        assertThat(context).hasNotFailed();
                        assertThat(context.containsBean("cliShell")).isFalse();
                        assertThat(context.containsBean("cliTerminal")).isFalse();
                    });
        }
    }

    // ── TrayAutoConfiguration 条件测试 ──────────────────────────

    @Nested
    class TrayAutoConfiguration条件 {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TrayAutoConfiguration.class))
                .withBean(ConfigurableApplicationContext.class, () -> {
                    // 提供 Mock ConfigurableApplicationContext
                    return org.mockito.Mockito.mock(ConfigurableApplicationContext.class);
                });

        @Test
        void 默认不激活() {
            // lifepilot.tray.enabled 未设置时，havingValue="true" → 不激活
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.containsBean("trayManager")).isFalse();
                assertThat(context.containsBean("trayNotificationChannel")).isFalse();
            });
        }

        @Test
        void 显式启用_激活() {
            runner.withPropertyValues("lifepilot.tray.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(TrayManager.class)).isNotNull();
                        assertThat(context.getBean(TrayNotificationChannel.class)).isNotNull();
                    });
        }

        @Test
        void 显式禁用_不激活() {
            runner.withPropertyValues("lifepilot.tray.enabled=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.containsBean("trayManager")).isFalse();
                    });
        }
    }

    // ── CLI 模式 — 完整上下文验证 ───────────────────────────────

    @Nested
    @SpringBootTest(
            webEnvironment = WebEnvironment.NONE,
            properties = {
                    "spring.main.web-application-type=none",
                    "lifepilot.app.launch-mode=cli"
            }
    )
    @ActiveProfiles("test")
    class CLI模式属性验证 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-cli-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-cli-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        }

        @Autowired
        ApplicationContext context;

        @Test
        void Web服务器未启动() {
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                    .isEqualTo("none");
        }

        @Test
        void LaunchMode配置值为cli() {
            assertThat(context.getEnvironment().getProperty("lifepilot.app.launch-mode"))
                    .isEqualTo("cli");
        }

        @Test
        void Tray未激活() {
            assertThatThrownBy(() -> context.getBean(TrayManager.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    // ── WEB 模式 — 完整上下文验证 ───────────────────────────────

    @Nested
    @SpringBootTest(
            properties = {
                    "lifepilot.cli.enabled=false",
                    "lifepilot.app.launch-mode=web"
            }
    )
    @ActiveProfiles("test")
    class WEB模式属性验证 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-web-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-web-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        }

        @Autowired
        ApplicationContext context;

        @Test
        void CLI未激活() {
            assertThatThrownBy(() -> context.getBean(CliShell.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }

        @Test
        void Tray未激活() {
            assertThatThrownBy(() -> context.getBean(TrayManager.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }

        @Test
        void LaunchMode配置值为web() {
            assertThat(context.getEnvironment().getProperty("lifepilot.app.launch-mode"))
                    .isEqualTo("web");
        }
    }

    // ── TRAY 模式 — 完整上下文验证 ──────────────────────────────

    @Nested
    @SpringBootTest(
            properties = {
                    "lifepilot.cli.enabled=false",
                    "lifepilot.tray.enabled=true",
                    "lifepilot.app.launch-mode=tray"
            }
    )
    @ActiveProfiles("test")
    class TRAY模式属性验证 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-tray-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-tray-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        }

        @Autowired
        ApplicationContext context;

        @Test
        void TrayManager_注册成功() {
            assertThat(context.getBean(TrayManager.class)).isNotNull();
        }

        @Test
        void TrayNotificationChannel_注册成功() {
            assertThat(context.getBean(TrayNotificationChannel.class)).isNotNull();
        }

        @Test
        void CLI未激活() {
            assertThatThrownBy(() -> context.getBean(CliShell.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }

        @Test
        void LaunchMode配置值为tray() {
            assertThat(context.getEnvironment().getProperty("lifepilot.app.launch-mode"))
                    .isEqualTo("tray");
        }
    }

    // ── FULL 模式 — 属性验证 ────────────────────────────────────

    @Nested
    @SpringBootTest(
            properties = {
                    "lifepilot.app.launch-mode=full"
            }
    )
    @ActiveProfiles("test")
    class FULL模式属性验证 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-full-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "lm-full-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        }

        @Autowired
        ApplicationContext context;

        @Test
        void LaunchMode配置值为full() {
            assertThat(context.getEnvironment().getProperty("lifepilot.app.launch-mode"))
                    .isEqualTo("full");
        }

        @Test
        void Tray默认未激活() {
            // FULL 模式不设置 lifepilot.tray.enabled=true，Tray 不激活
            assertThatThrownBy(() -> context.getBean(TrayManager.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }
}
