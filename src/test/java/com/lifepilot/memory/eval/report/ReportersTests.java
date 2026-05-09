package com.lifepilot.memory.eval.report;

import com.lifepilot.memory.eval.baseline.BaselineStore;
import com.lifepilot.memory.eval.baseline.MetricDiff;
import com.lifepilot.memory.eval.baseline.RegressionDetector;
import com.lifepilot.memory.eval.baseline.RegressionResult;
import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.judge.JudgeVerdict;
import com.lifepilot.memory.eval.probe.EvalMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告生成 + 基线对比单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("报告 & 基线 单元测试")
class ReportersTests {

    @Test
    @DisplayName("MarkdownReporter 写入：文件生成，含汇总表与明细段")
    void markdown_写入_包含汇总表(@TempDir Path tempDir) throws Exception {
        MarkdownReporter reporter = new MarkdownReporter(tempDir);
        EvalReport report = buildReport(Instant.parse("2026-05-09T02:14:00Z"), null);

        Path out = reporter.write(report);
        assertThat(out).isNotNull().exists();

        String content = Files.readString(out);
        assertThat(content).contains("Memory Eval Report");
        assertThat(content).contains("## 汇总");
        assertThat(content).contains("locomo");
        assertThat(content).contains("longmemeval");
        assertThat(content).contains("## locomo 明细");
    }

    @Test
    @DisplayName("JsonReporter 写入：snake_case 字段且可被 BaselineStore 读回")
    void json_写入_可被读回(@TempDir Path tempDir) throws Exception {
        JsonReporter reporter = new JsonReporter(tempDir);
        EvalReport report = buildReport(Instant.parse("2026-05-09T02:14:00Z"), null);
        Path out = reporter.write(report);
        assertThat(out).isNotNull().exists();

        String content = Files.readString(out);
        assertThat(content).contains("\"git_sha\"");
        assertThat(content).contains("\"llm_score\"");
        assertThat(content).contains("\"per_case_details\"");

        // 回读
        BaselineStore store = new BaselineStore(out);
        EvalReport loaded = store.loadOrNull();
        assertThat(loaded).isNotNull();
        assertThat(loaded.benchmarks()).hasSize(2);
    }

    @Test
    @DisplayName("BaselineStore.loadOrNull 不存在时返回 null")
    void baseline_不存在_返回null(@TempDir Path tempDir) {
        BaselineStore store = new BaselineStore(tempDir.resolve("missing.json"));
        assertThat(store.loadOrNull()).isNull();
    }

    @Test
    @DisplayName("RegressionDetector NO_BASELINE 路径")
    void regression_无基线() {
        MemoryEvalProperties.Regression cfg = new MemoryEvalProperties.Regression();
        RegressionDetector det = new RegressionDetector(cfg);
        RegressionResult r = det.compare(buildReport(Instant.now(), null), null);
        assertThat(r.isNoBaseline()).isTrue();
    }

    @Test
    @DisplayName("RegressionDetector PASS：指标未退化")
    void regression_pass() {
        MemoryEvalProperties.Regression cfg = new MemoryEvalProperties.Regression();
        RegressionDetector det = new RegressionDetector(cfg);

        EvalReport baseline = buildReport(Instant.parse("2026-05-08T00:00:00Z"), null);
        // current 保持一致
        EvalReport current = buildReport(Instant.parse("2026-05-09T00:00:00Z"), null);
        RegressionResult r = det.compare(current, baseline);
        assertThat(r.isPass()).isTrue();
        assertThat(r.diffs()).isNotEmpty();
        assertThat(r.diffs()).allMatch(d -> !d.violated());
    }

    @Test
    @DisplayName("RegressionDetector FAIL：LLM-Score 下降超阈值")
    void regression_fail_llm_score下降() {
        MemoryEvalProperties.Regression cfg = new MemoryEvalProperties.Regression();
        cfg.setLlmScoreTolerance(0.03f);
        RegressionDetector det = new RegressionDetector(cfg);

        EvalReport baseline = buildReport(Instant.parse("2026-05-08T00:00:00Z"), null);
        // 构造 current: locomo llm_score 从 0.72 掉到 0.60
        EvalReport current = new EvalReport(
                Instant.parse("2026-05-09T00:00:00Z"),
                null, "quick",
                List.of(
                        new BenchmarkReport("locomo", 5,
                                new EvalMetrics(20, 0.60f, 0.55f, 0.70f, 0.8f,
                                        50, 120, 200, 1800, 36000, 0),
                                List.of()),
                        new BenchmarkReport("longmemeval", 30,
                                new EvalMetrics(30, 0.75f, 0.70f, 0.80f, 0.75f,
                                        60, 110, 180, 1400, 42000, 0),
                                List.of())
                ), null);
        RegressionResult r = det.compare(current, baseline);
        assertThat(r.isFail()).isTrue();
        assertThat(r.diffs()).anyMatch(MetricDiff::violated);
        assertThat(r.diffs()).anyMatch(d -> d.metricName().endsWith("llm_score") && d.violated());
    }

    @Test
    @DisplayName("RegressionDetector FAIL：p95 延迟上升超阈值")
    void regression_fail_延迟上升() {
        MemoryEvalProperties.Regression cfg = new MemoryEvalProperties.Regression();
        cfg.setLatencyTolerance(0.20f);
        RegressionDetector det = new RegressionDetector(cfg);

        EvalReport baseline = buildReport(Instant.parse("2026-05-08T00:00:00Z"), null);
        EvalReport current = new EvalReport(
                Instant.parse("2026-05-09T00:00:00Z"),
                null, "quick",
                List.of(
                        new BenchmarkReport("locomo", 5,
                                new EvalMetrics(20, 0.72f, 0.68f, 0.70f, 0.8f,
                                        50, /* p95: 120 -> 200 即 +66% */ 200, 280, 1850, 37000, 0),
                                List.of()),
                        new BenchmarkReport("longmemeval", 30,
                                new EvalMetrics(30, 0.78f, 0.74f, 0.80f, 0.75f,
                                        60, 110, 180, 1420, 42600, 0),
                                List.of())
                ), null);
        RegressionResult r = det.compare(current, baseline);
        assertThat(r.isFail()).isTrue();
        assertThat(r.diffs()).anyMatch(d -> d.metricName().endsWith("p95_latency_ms") && d.violated());
    }

    // ---------------------------------------------------------------------

    private EvalReport buildReport(Instant ts, String gitSha) {
        return new EvalReport(
                ts, gitSha, "quick",
                List.of(
                        new BenchmarkReport(
                                "locomo", 5,
                                new EvalMetrics(20, 0.72f, 0.68f, 0.70f, 0.8f,
                                        50, 120, 200, 1850, 37000, 0),
                                List.of(
                                        new CaseDetail(
                                                "conv-1", "q1", "Q?", "A", "A",
                                                List.of(new JudgeVerdict(1.0f, "exact-match", false, null)),
                                                35, 120)
                                )),
                        new BenchmarkReport(
                                "longmemeval", 30,
                                new EvalMetrics(30, 0.78f, 0.74f, 0.80f, 0.75f,
                                        60, 110, 180, 1420, 42600, 0),
                                List.of())
                ), null);
    }
}
