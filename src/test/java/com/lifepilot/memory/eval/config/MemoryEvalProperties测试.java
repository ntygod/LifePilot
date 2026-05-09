package com.lifepilot.memory.eval.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryEvalProperties} 配置绑定测试。
 *
 * <p>覆盖三种场景：默认值、profile 覆盖、显式 {@code -D} 覆盖。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("记忆评估 Harness 配置绑定测试")
class MemoryEvalProperties测试 {

    @Test
    @DisplayName("默认配置：enabled=false，mode=quick，各子配置使用内置默认值")
    void 默认配置_使用内置默认值() {
        MemoryEvalProperties properties = new MemoryEvalProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getMode()).isEqualTo("quick");
        assertThat(properties.isQuickMode()).isTrue();
        assertThat(properties.isTriggerConsolidation()).isTrue();

        assertThat(properties.getBenchmarks().getLocomo().isEnabled()).isTrue();
        assertThat(properties.getBenchmarks().getLocomo().getMaxConversations()).isEqualTo(10);
        assertThat(properties.getBenchmarks().getLocomo().getQuickMaxConversations()).isEqualTo(2);

        assertThat(properties.getBenchmarks().getLongMemEval().getMaxQuestions()).isEqualTo(500);
        assertThat(properties.getBenchmarks().getLongMemEval().getQuickMaxQuestions()).isEqualTo(50);

        assertThat(properties.getJudge().isLlmEnabled()).isFalse();
        assertThat(properties.getJudge().getLlmScene()).isEqualTo("eval");

        assertThat(properties.getRegression().isEnabled()).isTrue();
        assertThat(properties.getRegression().getLlmScoreTolerance()).isEqualTo(0.03f);
        assertThat(properties.getRegression().getLatencyTolerance()).isEqualTo(0.20f);
        assertThat(properties.getRegression().getTokenTolerance()).isEqualTo(0.30f);
        assertThat(properties.getRegression().isUpdateBaseline()).isFalse();

        assertThat(properties.getIsolation().isUseTempSqlite()).isTrue();
        assertThat(properties.getIsolation().isKeepDbOnFailure()).isFalse();

        assertThat(properties.getReporting().getOutputDir())
                .isEqualTo(Paths.get("target", "memory-eval"));
    }

    @Test
    @DisplayName("profile 覆盖：模拟 application-memory-eval.yml 的典型值")
    void profile_激活_覆盖默认值() {
        Map<String, Object> props = Map.of(
                "lifepilot.memory.eval.enabled", "true",
                "lifepilot.memory.eval.mode", "full",
                "lifepilot.memory.eval.benchmarks.locomo.max-conversations", "5",
                "lifepilot.memory.eval.judge.llm-enabled", "true",
                "lifepilot.memory.eval.regression.llm-score-tolerance", "0.05"
        );
        MemoryEvalProperties bound = bind(props);

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getMode()).isEqualTo("full");
        assertThat(bound.isQuickMode()).isFalse();
        assertThat(bound.getBenchmarks().getLocomo().getMaxConversations()).isEqualTo(5);
        assertThat(bound.getJudge().isLlmEnabled()).isTrue();
        assertThat(bound.getRegression().getLlmScoreTolerance()).isEqualTo(0.05f);
    }

    @Test
    @DisplayName("CLI -D 覆盖：只覆盖命中的字段，其余保留默认")
    void CLI_D_覆盖_不影响未命中字段() {
        Map<String, Object> props = Map.of(
                "lifepilot.memory.eval.mode", "full",
                "lifepilot.memory.eval.regression.update-baseline", "true"
        );
        MemoryEvalProperties bound = bind(props);

        assertThat(bound.getMode()).isEqualTo("full");
        assertThat(bound.getRegression().isUpdateBaseline()).isTrue();
        // 未命中字段保留默认
        assertThat(bound.isEnabled()).isFalse();
        assertThat(bound.getBenchmarks().getLocomo().getMaxConversations()).isEqualTo(10);
        assertThat(bound.getJudge().isLlmEnabled()).isFalse();
    }

    private MemoryEvalProperties bind(Map<String, Object> raw) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(raw);
        return new Binder(source)
                .bind("lifepilot.memory.eval", MemoryEvalProperties.class)
                .orElseGet(MemoryEvalProperties::new);
    }
}
