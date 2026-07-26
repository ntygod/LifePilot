package com.lifepilot.memory.governance.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryUserBoundaryPolicy 单元测试。
 *
 * @author zsg
 * @since 2026-07-06
 */
class MemoryUserBoundaryPolicy_单元测试 {

    @Test
    void 不写长期记忆只关闭自动学习() {
        String text = "请直接回答，不要写入长期记忆。";

        assertThat(MemoryUserBoundaryPolicy.autoLearningBoundary(text).skip()).isTrue();
        assertThat(MemoryUserBoundaryPolicy.autoLearningBoundary(text).reason())
                .isEqualTo("user_memory_write_denied");
        assertThat(MemoryUserBoundaryPolicy.defaultMemoryReadBoundary(text).skip()).isFalse();
    }

    @Test
    void 不参考记忆关闭默认读取() {
        String text = "请直接回答，不要参考记忆。";

        assertThat(MemoryUserBoundaryPolicy.defaultMemoryReadBoundary(text).skip()).isTrue();
        assertThat(MemoryUserBoundaryPolicy.defaultMemoryReadBoundary(text).reason())
                .isEqualTo("user_memory_read_denied");
    }

    @Test
    void 主对话冒烟测试同时关闭读取和学习() {
        String text = "这是知微主对话闭环冒烟测试。";

        assertThat(MemoryUserBoundaryPolicy.defaultMemoryReadBoundary(text).skip()).isTrue();
        assertThat(MemoryUserBoundaryPolicy.autoLearningBoundary(text).skip()).isTrue();
        assertThat(MemoryUserBoundaryPolicy.defaultMemoryReadBoundary(text).reason())
                .isEqualTo("conversation_smoke_test");
        assertThat(MemoryUserBoundaryPolicy.autoLearningBoundary(text).reason())
                .isEqualTo("conversation_smoke_test");
    }
}
