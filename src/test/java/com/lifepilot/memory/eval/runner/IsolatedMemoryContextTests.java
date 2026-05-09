package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IsolatedMemoryContext} 轻量单元测试（不启动真实 Spring Boot）。
 *
 * <p>真实启动子 context 的集成测试见 memory-eval Maven profile 下的 `*EvalTest`。
 * 本测试只验证配置校验、实例 ID 唯一性等轻量逻辑。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("IsolatedMemoryContext 轻量单元测试")
class IsolatedMemoryContextTests {

    @Test
    @DisplayName("start() 在 enabled=false 时抛 IllegalStateException")
    void start_未启用_抛异常() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setEnabled(false);

        IsolatedMemoryContext ctx = new IsolatedMemoryContext(props, Object.class);
        assertThatThrownBy(ctx::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifepilot.memory.eval.enabled=true");
    }

    @Test
    @DisplayName("每个实例有唯一 contextId（UUID）")
    void 多实例_UUID不冲突() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setEnabled(true);

        IsolatedMemoryContext a = new IsolatedMemoryContext(props, Object.class);
        IsolatedMemoryContext b = new IsolatedMemoryContext(props, Object.class);
        assertThat(a.contextId()).isNotEqualTo(b.contextId());
    }

    @Test
    @DisplayName("未 start 时 getBean 抛 IllegalStateException")
    void 未启动_getBean_抛异常() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setEnabled(true);
        IsolatedMemoryContext ctx = new IsolatedMemoryContext(props, Object.class);
        assertThatThrownBy(() -> ctx.getBean(Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未 start()");
    }
}
