package com.lifepilot.eval.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 评估套件注解 — 标记按标签运行的评估套件。
 *
 * <p>标注在 JUnit 5 测试类上，指定标签过滤和通过阈值。
 * 运行时由 {@link EvalSuiteExtension} 驱动：按标签加载场景 → 批量评估 → 输出汇总报告。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * @SpringBootTest
 * @EvalSuite(tags = {"core", "tool-calling"}, passThreshold = 0.75)
 * class CoreSkillEvalSuite {
 *     @Test
 *     void 套件评估结果可用() {
 *         // 可从 ExtensionContext Store 获取 ReportSummary
 *     }
 * }
 * }</pre>
 *
 * @author zsg
 * @since 2026-08-01
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(EvalSuiteExtension.class)
public @interface EvalSuite {

    /** 标签过滤。 */
    String[] tags();

    /** 通过阈值（默认 0.7）。 */
    double passThreshold() default 0.7;
}
