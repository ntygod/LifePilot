package com.lifepilot.memory.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.eval.baseline.BaselineStore;
import com.lifepilot.memory.eval.baseline.RegressionDetector;
import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.judge.ExactMatchJudge;
import com.lifepilot.memory.eval.judge.F1Judge;
import com.lifepilot.memory.eval.judge.LlmAsJudge;
import com.lifepilot.memory.eval.loader.BenchmarkLoader;
import com.lifepilot.memory.eval.loader.LocomoLoader;
import com.lifepilot.memory.eval.loader.LongMemEvalLoader;
import com.lifepilot.memory.eval.report.JsonReporter;
import com.lifepilot.memory.eval.report.MarkdownReporter;
import com.lifepilot.memory.eval.runner.BenchmarkRunner;
import com.lifepilot.memory.eval.runner.MemoryQueryAdapter;

import java.util.List;

/**
 * 测试专用：用最小依赖拼装一个可运行的 {@link BenchmarkRunner}。
 *
 * <p>不启动真实 Spring Boot、不启动 IsolatedMemoryContext，适合集成冒烟测试中
 * 验证 harness 主流程（Loader + Judge + Probe + Report + Baseline）。</p>
 *
 * <p>真实评估应通过 AutoConfiguration Bean 装配 + 项目方提供的 MemoryQueryAdapter
 * 和 IsolatedMemoryContext-based RunContextFactory。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
final class IntegrationHarnessFactory {

    private IntegrationHarnessFactory() {}

    static BenchmarkRunner build(MemoryEvalProperties props, MemoryQueryAdapter adapter) {
        ObjectMapper mapper = new ObjectMapper();
        List<BenchmarkLoader> loaders = List.of(
                new LocomoLoader(props.getDatasetCacheDir(), mapper),
                new LongMemEvalLoader(props.getDatasetCacheDir(), mapper));
        BaselineStore baselineStore = new BaselineStore(props.getRegression().getBaselinePath());
        RegressionDetector detector = new RegressionDetector(props.getRegression());
        MarkdownReporter mdReporter = new MarkdownReporter(props.getReporting().getOutputDir());
        JsonReporter jsonReporter = new JsonReporter(props.getReporting().getOutputDir());
        BenchmarkRunner.RunContextFactory factory = bc -> new BenchmarkRunner.RunContext() {
            @Override public void replay(com.lifepilot.memory.eval.loader.BenchmarkCase ignored) { /* no-op */ }
            @Override public void close() { /* no-op */ }
        };
        return new BenchmarkRunner(
                loaders, props, baselineStore, detector, mdReporter, jsonReporter,
                factory, adapter,
                new ExactMatchJudge(), new F1Judge(),
                new LlmAsJudge(null, props.getJudge()));
    }
}
