package com.lifepilot.interaction.config;

import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayDeliveryProperties} 配置绑定与 {@code toFilterConfig()} 转换行为测试。
 *
 * <p>覆盖默认值、yaml 覆盖、{@code resolvePlatformMaxSizeMb} 兜底逻辑。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class GatewayDeliveryProperties_绑定测试 {

    /**
     * 默认值场景 —— 不提供任何配置时使用 record 上的 @DefaultValue。
     */
    @SpringBootTest(classes = DefaultValueConfig.class)
    @TestPropertySource(properties = "lifepilot.gateway.delivery.placeholder=ignored")
    static class 默认值绑定测试 {

        @org.springframework.beans.factory.annotation.Autowired
        private GatewayDeliveryProperties properties;

        @Test
        @DisplayName("@DefaultValue 默认值生效")
        void 默认值生效() {
            assertThat(properties.artifactMaxSizeMb()).isEqualTo(50);
            assertThat(properties.artifactMaxCountPerTool()).isEqualTo(20);
            assertThat(properties.artifactExcludedDirs())
                    .containsExactlyInAnyOrder(".git", "node_modules", "__pycache__", ".venv", "target");
            assertThat(properties.artifactExcludedExtensions())
                    .containsExactlyInAnyOrder("tmp", "log", "swp", "swo");

            assertThat(properties.platformMaxSize()).isNotNull();
            assertThat(properties.platformMaxSize().feishu()).isEqualTo(30);
            assertThat(properties.platformMaxSize().wecom()).isEqualTo(20);
            assertThat(properties.platformMaxSize().dingtalk()).isEqualTo(30);
            assertThat(properties.platformMaxSize().telegram()).isEqualTo(50);
            assertThat(properties.platformMaxSize().web()).isEqualTo(200);
        }

        @Test
        @DisplayName("toFilterConfig 转换正确")
        void toFilterConfig转换() {
            ArtifactFilterConfig config = properties.toFilterConfig();
            assertThat(config.maxSizeMb()).isEqualTo(50);
            assertThat(config.maxCountPerTool()).isEqualTo(20);
            assertThat(config.excludedDirs()).contains(".git", "target");
            assertThat(config.excludedExtensions()).contains("tmp", "log");
        }

        @Test
        @DisplayName("resolvePlatformMaxSizeMb 命中各平台")
        void platform_解析命中() {
            assertThat(properties.resolvePlatformMaxSizeMb("feishu")).isEqualTo(30);
            assertThat(properties.resolvePlatformMaxSizeMb("FEISHU")).isEqualTo(30);
            assertThat(properties.resolvePlatformMaxSizeMb("Feishu")).isEqualTo(30);
            assertThat(properties.resolvePlatformMaxSizeMb("wecom")).isEqualTo(20);
        }

        @Test
        @DisplayName("resolvePlatformMaxSizeMb 未匹配时回退兜底值")
        void platform_未匹配回退() {
            assertThat(properties.resolvePlatformMaxSizeMb("unknown"))
                    .isEqualTo(GatewayDeliveryProperties.FALLBACK_PLATFORM_MAX_SIZE_MB);
            assertThat(properties.resolvePlatformMaxSizeMb(null))
                    .isEqualTo(GatewayDeliveryProperties.FALLBACK_PLATFORM_MAX_SIZE_MB);
            assertThat(properties.resolvePlatformMaxSizeMb(""))
                    .isEqualTo(GatewayDeliveryProperties.FALLBACK_PLATFORM_MAX_SIZE_MB);
        }
    }

    /**
     * yaml 覆盖场景 —— 通过 @TestPropertySource 覆盖默认值。
     */
    @SpringBootTest(classes = DefaultValueConfig.class)
    @TestPropertySource(properties = {
            "lifepilot.gateway.delivery.artifact-max-size-mb=100",
            "lifepilot.gateway.delivery.artifact-max-count-per-tool=5",
            "lifepilot.gateway.delivery.platform-max-size.feishu=80",
            "lifepilot.gateway.delivery.platform-max-size.telegram=100"
    })
    static class yaml覆盖测试 {

        @org.springframework.beans.factory.annotation.Autowired
        private GatewayDeliveryProperties properties;

        @Test
        @DisplayName("yaml 覆盖生效")
        void yaml覆盖生效() {
            assertThat(properties.artifactMaxSizeMb()).isEqualTo(100);
            assertThat(properties.artifactMaxCountPerTool()).isEqualTo(5);
            assertThat(properties.platformMaxSize().feishu()).isEqualTo(80);
            assertThat(properties.platformMaxSize().telegram()).isEqualTo(100);
            // 未覆盖项保持默认
            assertThat(properties.platformMaxSize().wecom()).isEqualTo(20);
        }
    }

    /**
     * 测试用最小配置类 —— 启用 {@link GatewayDeliveryProperties} 绑定。
     */
    @org.springframework.boot.SpringBootConfiguration
    @EnableConfigurationProperties(GatewayDeliveryProperties.class)
    static class DefaultValueConfig {
    }
}
