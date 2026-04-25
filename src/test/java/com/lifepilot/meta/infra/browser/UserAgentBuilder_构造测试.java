package com.lifepilot.meta.infra.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserAgentBuilder 单元测试 — 覆盖 auto 模式拼接、显式 UA 原样透传、边界输入处理。
 *
 * @author zsg
 * @since 2026-04-24
 */
class UserAgentBuilder_构造测试 {

    @Test
    void 配置_auto_时用_Chromium_版本拼_UA() {
        String ua = UserAgentBuilder.build("auto", "135.0.7000.0");
        assertThat(ua).contains("Chrome/135.0.7000.0 Safari/537.36");
        assertThat(ua).startsWith("Mozilla/5.0");
        assertThat(ua).contains("Windows NT 10.0; Win64; x64");
        assertThat(ua).contains("AppleWebKit/537.36");
    }

    @Test
    void 配置_auto_大小写不敏感() {
        assertThat(UserAgentBuilder.build("AUTO", "1")).contains("Chrome/1 ");
        assertThat(UserAgentBuilder.build("Auto", "1")).contains("Chrome/1 ");
        assertThat(UserAgentBuilder.build(" auto ", "1")).contains("Chrome/1 ");
    }

    @Test
    void 配置空字符串或_null_也按_auto_处理() {
        assertThat(UserAgentBuilder.build("", "1")).contains("Chrome/1 ");
        assertThat(UserAgentBuilder.build(null, "1")).contains("Chrome/1 ");
        assertThat(UserAgentBuilder.build("   ", "1")).contains("Chrome/1 ");
    }

    @Test
    void 显式_UA_字符串原样使用() {
        String custom = "MyCustomBot/1.0";
        assertThat(UserAgentBuilder.build(custom, "135.0")).isEqualTo(custom);
    }

    @Test
    void 显式_UA_与_Chromium_版本号无关() {
        String custom = "Mozilla/5.0 Custom Agent";
        assertThat(UserAgentBuilder.build(custom, null)).isEqualTo(custom);
        assertThat(UserAgentBuilder.build(custom, "")).isEqualTo(custom);
    }

    @Test
    void 配置_auto_但_Chromium_版本为空时返回_null() {
        // 语义：auto + 拿不到版本号 → 返回 null，上层应不调用 setUserAgent()，保留 Chromium 真实 UA
        assertThat(UserAgentBuilder.build("auto", null)).isNull();
        assertThat(UserAgentBuilder.build("auto", "")).isNull();
        assertThat(UserAgentBuilder.build("auto", "   ")).isNull();
        assertThat(UserAgentBuilder.build(null, null)).isNull();
    }
}
