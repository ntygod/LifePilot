package com.lifepilot.eval.junit;

import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.ScenarioLoader;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

/**
 * JUnit 5 Extension — 驱动 {@link EvalSuite} 注解的批量评估执行。
 *
 * <p>在 {@code beforeAll} 阶段：从 Spring ApplicationContext 获取 {@link EvalEngine}、
 * {@link ScenarioLoader} 和 {@link EvalReport}，按标签加载场景并批量执行评估，
 * 输出汇总报告到控制台。</p>
 *
 * <p>{@link ReportSummary} 存储在 {@link ExtensionContext.Store} 中，
 * 供测试方法通过 {@link #getSummary(ExtensionContext)} 访问。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class EvalSuiteExtension implements BeforeAllCallback, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger(EvalSuiteExtension.class);

    /** Store 中存储 ReportSummary 的键。 */
    private static final String SUMMARY_KEY = "summary";

    @Override
    public void beforeAll(ExtensionContext context) {
        // 获取 @EvalSuite 注解
        var evalSuite = context.getRequiredTestClass().getAnnotation(EvalSuite.class);
        if (evalSuite == null) {
            return;
        }

        String[] tags = evalSuite.tags();
        double passThreshold = evalSuite.passThreshold();

        log.info("开始评估套件: tags={}, passThreshold={}", List.of(tags), passThreshold);

        // 从 Spring ApplicationContext 获取 Bean
        var appContext = SpringExtension.getApplicationContext(context);
        var evalEngine = appContext.getBean(EvalEngine.class);
        var scenarioLoader = appContext.getBean(ScenarioLoader.class);
        var evalReport = appContext.getBean(EvalReport.class);

        // 按标签加载场景并批量评估
        var scenarios = scenarioLoader.loadByTags(List.of(tags));
        log.info("按标签加载场景: tags={}, 匹配场景数={}", List.of(tags), scenarios.size());

        var summary = evalEngine.evaluateBatch(scenarios);

        // 输出汇总报告到控制台
        evalReport.printToConsole(summary);

        // 存储结果供测试方法使用
        getStore(context).put(SUMMARY_KEY, summary);

        log.info("评估套件完成: 总场景={}, 通过={}, 失败={}, 平均分={}",
                summary.totalScenarios(), summary.passCount(), summary.failCount(),
                String.format("%.3f", summary.averageOverallScore()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // 清理 Store 中的汇总报告
        getStore(context).remove(SUMMARY_KEY);
    }

    /**
     * 从 ExtensionContext Store 获取评估汇总报告。
     *
     * <p>供测试方法在 {@code @EvalSuite} 标注的测试类中使用。</p>
     *
     * @param context 扩展上下文
     * @return 汇总报告，如果尚未执行则返回 null
     */
    public static ReportSummary getSummary(ExtensionContext context) {
        var store = context.getStore(
                ExtensionContext.Namespace.create(EvalSuiteExtension.class, context.getRequiredTestClass()));
        return store.get(SUMMARY_KEY, ReportSummary.class);
    }

    /**
     * 获取 Extension 的 Store。
     *
     * @param context 扩展上下文
     * @return Store 实例
     */
    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(
                ExtensionContext.Namespace.create(getClass(), context.getRequiredTestClass()));
    }
}
