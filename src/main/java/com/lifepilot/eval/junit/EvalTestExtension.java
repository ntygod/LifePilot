package com.lifepilot.eval.junit;

import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * JUnit 5 Extension — 驱动 {@link EvalTest} 注解的评估执行。
 *
 * <p>在 {@code beforeEach} 阶段：从 Spring ApplicationContext 获取 {@link EvalEngine}
 * 和 {@link ScenarioLoader}，加载指定场景并执行评估，断言综合评分 ≥ 通过阈值。
 * 失败时输出详细的维度评分、违规项和通过阈值。</p>
 *
 * <p>评估结果存储在 {@link ExtensionContext.Store} 中，供测试方法或
 * {@code afterEach} 阶段访问。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class EvalTestExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(EvalTestExtension.class);

    /** Store 中存储 EvalResult 的键。 */
    private static final String EVAL_RESULT_KEY = "evalResult";

    @Override
    public void beforeEach(ExtensionContext context) {
        // 获取 @EvalTest 注解
        var evalTest = context.getRequiredTestMethod().getAnnotation(EvalTest.class);
        if (evalTest == null) {
            return;
        }

        String scenarioId = evalTest.scenarioId();
        double passThreshold = evalTest.passThreshold();

        log.info("开始评估测试: scenarioId={}, passThreshold={}", scenarioId, passThreshold);

        // 从 Spring ApplicationContext 获取 Bean
        var appContext = SpringExtension.getApplicationContext(context);
        var evalEngine = appContext.getBean(EvalEngine.class);
        var scenarioLoader = appContext.getBean(ScenarioLoader.class);

        // 加载场景并执行评估
        var scenario = scenarioLoader.loadById(scenarioId);
        var result = evalEngine.evaluateScenario(scenario);

        // 存储结果供后续使用
        getStore(context).put(EVAL_RESULT_KEY, result);

        // 断言评分 ≥ 阈值
        if (!result.passed(passThreshold)) {
            fail(buildFailureMessage(result, passThreshold));
        }

        log.info("评估测试通过: scenarioId={}, overallScore={}", scenarioId, result.overallScore());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // 清理 Store 中的评估结果
        getStore(context).remove(EVAL_RESULT_KEY);
    }

    /**
     * 获取 Extension 的 Store。
     *
     * @param context 扩展上下文
     * @return Store 实例
     */
    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }

    /**
     * 构建评估失败的详细消息，包含维度评分、违规项和通过阈值。
     *
     * @param result        评估结果
     * @param passThreshold 通过阈值
     * @return 格式化的失败消息
     */
    private String buildFailureMessage(EvalResult result, double passThreshold) {
        var sb = new StringBuilder();
        sb.append("\n");
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  评估测试失败\n");
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("  场景 ID:     ").append(result.scenarioId()).append("\n");
        sb.append("  综合评分:    ").append(String.format("%.3f", result.overallScore())).append("\n");
        sb.append("  通过阈值:    ").append(String.format("%.3f", passThreshold)).append("\n");
        sb.append("───────────────────────────────────────────────────\n");

        // 各维度评分
        if (!result.dimensionScores().isEmpty()) {
            sb.append("  各维度评分:\n");
            result.dimensionScores().forEach((dim, score) ->
                    sb.append("    ").append(String.format("%-25s %.3f", dim + ":", score)).append("\n"));
            sb.append("───────────────────────────────────────────────────\n");
        }

        // 违规项
        if (!result.violations().isEmpty()) {
            sb.append("  违规项:\n");
            result.violations().forEach(v ->
                    sb.append("    ✗ ").append(v).append("\n"));
            sb.append("───────────────────────────────────────────────────\n");
        }

        // 改进建议
        if (!result.suggestions().isEmpty()) {
            sb.append("  改进建议:\n");
            result.suggestions().forEach(s ->
                    sb.append("    → ").append(s).append("\n"));
            sb.append("───────────────────────────────────────────────────\n");
        }

        // LLM Judge 结果
        if (result.llmJudgeScore() != null) {
            sb.append("  LLM Judge 评分: ").append(String.format("%.3f", result.llmJudgeScore())).append("\n");
            if (result.llmJudgeJustification() != null) {
                sb.append("  LLM Judge 理由: ").append(result.llmJudgeJustification()).append("\n");
            }
            sb.append("───────────────────────────────────────────────────\n");
        }

        sb.append("═══════════════════════════════════════════════════\n");
        return sb.toString();
    }
}
