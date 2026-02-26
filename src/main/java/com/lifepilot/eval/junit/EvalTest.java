package com.lifepilot.eval.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 评估测试注解 — 标记单个场景评估测试。
 *
 * <p>标注在 JUnit 5 测试方法上，指定场景 ID 和通过阈值。
 * 运行时由 {@link EvalTestExtension} 驱动：加载场景 → 执行评估 → 断言评分 ≥ 阈值。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * @SpringBootTest
 * class AgentEvalTest {
 *     @EvalTest(scenarioId = "weather-query-001", passThreshold = 0.8)
 *     void 天气查询场景评估() {}
 * }
 * }</pre>
 *
 * @author zsg
 * @since 2026-08-01
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(EvalTestExtension.class)
public @interface EvalTest {

    /** 场景 ID。 */
    String scenarioId();

    /** 通过阈值（默认 0.7）。 */
    double passThreshold() default 0.7;
}
