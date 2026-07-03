package com.lifepilot.meta.infra.git;

import com.lifepilot.meta.config.MetaProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Git 工具契约测试。
 *
 * @author zsg
 * @since 2026-07-02
 */
class GitToolProvider_工具契约测试 {

    @Test
    void Git工具应使用独立命名空间且不冲突() {
        var config = new MetaProperties.Infra.Git();
        var provider = new GitToolProvider(new GitCommandExecutor(config), config);

        var tools = provider.buildGitTools();

        assertThat(tools).extracting(tool -> tool.id())
                .containsExactlyInAnyOrder("git.query", "git.mutate");
    }
}
