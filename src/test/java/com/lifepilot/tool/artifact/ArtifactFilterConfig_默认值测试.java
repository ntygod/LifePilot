package com.lifepilot.tool.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArtifactFilterConfig} 默认值与紧凑构造器兜底行为测试。
 *
 * @author zsg
 * @since 2026-05-17
 */
class ArtifactFilterConfig_默认值测试 {

    @Test
    @DisplayName("defaultConfig 返回业界默认阈值与排除集合")
    void defaultConfig值正确() {
        ArtifactFilterConfig config = ArtifactFilterConfig.defaultConfig();

        assertThat(config.maxSizeMb()).isEqualTo(50);
        assertThat(config.maxCountPerTool()).isEqualTo(20);
        assertThat(config.excludedDirs())
                .containsExactlyInAnyOrder(".git", "node_modules", "__pycache__", ".venv", "target");
        assertThat(config.excludedExtensions())
                .containsExactlyInAnyOrder("tmp", "log", "swp", "swo");
    }

    @Test
    @DisplayName("紧凑构造器：阈值 ≤ 0 时回退默认值")
    void 阈值非正回退默认值() {
        ArtifactFilterConfig zero = new ArtifactFilterConfig(0, 0, null, null);
        assertThat(zero.maxSizeMb()).isEqualTo(50);
        assertThat(zero.maxCountPerTool()).isEqualTo(20);

        ArtifactFilterConfig negative = new ArtifactFilterConfig(-1, -10, null, null);
        assertThat(negative.maxSizeMb()).isEqualTo(50);
        assertThat(negative.maxCountPerTool()).isEqualTo(20);
    }

    @Test
    @DisplayName("紧凑构造器：null 集合回退默认集合")
    void null集合回退默认集合() {
        ArtifactFilterConfig config = new ArtifactFilterConfig(50, 20, null, null);
        assertThat(config.excludedDirs()).contains(".git", "node_modules");
        assertThat(config.excludedExtensions()).contains("tmp", "log");
    }

    @Test
    @DisplayName("紧凑构造器：自定义集合做防御性拷贝（不可变）")
    void 集合不可变() {
        java.util.Set<String> mutableDirs = new java.util.HashSet<>(java.util.Set.of("custom"));
        ArtifactFilterConfig config = new ArtifactFilterConfig(50, 20, mutableDirs, null);

        // 修改原始集合不影响 config
        mutableDirs.add("hacker");
        assertThat(config.excludedDirs()).containsExactly("custom");
    }
}
