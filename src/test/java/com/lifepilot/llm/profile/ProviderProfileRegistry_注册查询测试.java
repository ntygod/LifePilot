package com.lifepilot.llm.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProviderProfileRegistry 注册与查询行为单测。
 *
 * @author zsg
 * @since 2026-04-27
 */
class ProviderProfileRegistry_注册查询测试 {

    private final ProviderProfileRegistry registry = new ProviderProfileRegistry();

    @Test
    void 启动时加载全部内置_profile() {
        registry.init();
        assertThat(registry.all()).hasSize(11);
    }

    @Test
    void 按_id_精确返回_profile() {
        registry.init();
        var profile = registry.get("deepseek-official");
        assertThat(profile.thinkingProtocol()).isEqualTo(ThinkingProtocolId.DEEPSEEK);
    }

    @Test
    void 未知_id_抛出_IllegalStateException() {
        registry.init();
        assertThatThrownBy(() -> registry.get("unknown-profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知的 ProviderProfile id");
    }
}
