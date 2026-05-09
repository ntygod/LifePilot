package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.baseline.BaselineStore;
import com.lifepilot.memory.eval.baseline.RegressionDetector;
import com.lifepilot.memory.eval.baseline.RegressionResult;
import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.judge.ExactMatchJudge;
import com.lifepilot.memory.eval.judge.F1Judge;
import com.lifepilot.memory.eval.judge.LlmAsJudge;
import com.lifepilot.memory.eval.loader.BenchmarkCase;
import com.lifepilot.memory.eval.loader.BenchmarkLoader;
import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import com.lifepilot.memory.eval.loader.BenchmarkSession;
import com.lifepilot.memory.eval.loader.LocomoLoader;
import com.lifepilot.memory.eval.report.JsonReporter;
import com.lifepilot.memory.eval.report.MarkdownReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BenchmarkRunner} 单元测试（纯 mock，不需 Spring 启动）。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("BenchmarkRunner 单元测试")
class BenchmarkRunnerTests {

    @Test
    @DisplayName("完整流程：Loader → fake Replayer → fake Answer → Judge → Report → NO_BASELINE")
    void 完整流程_无基线_最终状态NO_BASELINE(@TempDir Path tempDir) {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setEnabled(true);
        props.setMode("quick");
        props.getReporting().setOutputDir(tempDir);

        MemoryEvalProperties.Regression regCfg = props.getRegression();
        regCfg.setBaselinePath(tempDir.resolve("baseline.json"));

        // LocomoLoader 是 sealed 的 permits 之一；通过 subclass 的方式 mock 不被允许。
        // 这里直接复用 LocomoLoader，用临时 fixture 目录加载一个缩小的 case。
        Path cacheDir = prepareLocomoFixture(tempDir);
        LocomoLoader fakeLoader = new LocomoLoader(cacheDir,
                new com.fasterxml.jackson.databind.ObjectMapper());

        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger replayCount = new AtomicInteger();
        BenchmarkRunner.RunContextFactory factory = bc -> {
            openCount.incrementAndGet();
            return new BenchmarkRunner.RunContext() {
                @Override public void replay(BenchmarkCase benchmarkCase) { replayCount.incrementAndGet(); }
                @Override public void close() { closeCount.incrementAndGet(); }
            };
        };

        MemoryQueryAdapter adapter = (sessions, q) -> {
            // 用 fixture LocomoLoader 加载的 conv-test-1 有 4 题
            String pred = switch (q.questionId()) {
                case "conv-test-1-q1" -> "Max";
                case "conv-test-1-q2" -> "sit";
                case "conv-test-1-q3" -> "About a week earlier";
                case "conv-test-1-q4" -> "Unanswerable";
                default -> "";
            };
            return new MemoryQueryAdapter.QueryResult(
                    pred, List.of("entity-" + q.questionId()),
                    "上下文 for " + q.questionId());
        };

        BenchmarkRunner runner = new BenchmarkRunner(
                List.of(fakeLoader), props,
                new BaselineStore(regCfg.getBaselinePath()),
                new RegressionDetector(regCfg),
                new MarkdownReporter(tempDir),
                new JsonReporter(tempDir),
                factory, adapter,
                new ExactMatchJudge(), new F1Judge(),
                new LlmAsJudge(null, props.getJudge()));

        RegressionResult result = runner.run();
        assertThat(result.isNoBaseline()).isTrue();
        assertThat(openCount.get()).isEqualTo(1);
        assertThat(closeCount.get()).isEqualTo(1);
        assertThat(replayCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("judge 路由：SINGLE_HOP → ExactMatch，MULTI_HOP → F1，OPEN_ENDED → F1 降级")
    void judge_路由_按题目类型() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        BenchmarkRunner runner = minimalRunner(props);

        var singleHop = new BenchmarkQuestion("q-s", "?", "a",
                BenchmarkQuestion.QuestionType.SINGLE_HOP, List.of());
        var multiHop = new BenchmarkQuestion("q-m", "?", "a b c",
                BenchmarkQuestion.QuestionType.MULTI_HOP, List.of());
        var openEnded = new BenchmarkQuestion("q-o", "?", "some text",
                BenchmarkQuestion.QuestionType.OPEN_ENDED, List.of());

        assertThat(runner.judge(singleHop, "a"))
                .extracting("judgeName")
                .containsExactly("exact-match");
        assertThat(runner.judge(multiHop, "a b c"))
                .extracting("judgeName")
                .containsExactly("f1");
        assertThat(runner.judge(openEnded, "some"))
                .extracting("judgeName")
                .containsExactly("f1"); // LLM 未启用降级 F1
    }

    @Test
    @DisplayName("maxCases 按 mode 降级：quick → quickMax，full → max")
    void resolve_maxCases_按mode() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setMode("quick");
        BenchmarkRunner runner = minimalRunner(props);
        assertThat(runner.resolveMaxCases("locomo"))
                .isEqualTo(props.getBenchmarks().getLocomo().getQuickMaxConversations());
        assertThat(runner.resolveMaxCases("longmemeval"))
                .isEqualTo(props.getBenchmarks().getLongMemEval().getQuickMaxQuestions());

        props.setMode("full");
        assertThat(runner.resolveMaxCases("locomo"))
                .isEqualTo(props.getBenchmarks().getLocomo().getMaxConversations());
    }

    @Test
    @DisplayName("isBenchmarkEnabled：关闭对应开关即跳过")
    void benchmark_enabled_开关() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.getBenchmarks().getLocomo().setEnabled(false);
        BenchmarkRunner runner = minimalRunner(props);
        assertThat(runner.isBenchmarkEnabled("locomo")).isFalse();
        assertThat(runner.isBenchmarkEnabled("longmemeval")).isTrue();
    }

    // ----------------------------------------------------

    /** 把 test resources 下的 LoCoMo fixture 拷贝到临时缓存目录。 */
    private Path prepareLocomoFixture(Path tempDir) {
        try {
            Path dir = tempDir.resolve("locomo");
            java.nio.file.Files.createDirectories(dir);
            Path dest = dir.resolve("locomo10.json");
            try (var in = getClass().getResourceAsStream("/memory-eval/locomo-sample.json")) {
                if (in == null) throw new IllegalStateException("fixture 缺失");
                java.nio.file.Files.copy(in, dest);
            }
            return tempDir;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BenchmarkRunner minimalRunner(MemoryEvalProperties props) {
        return new BenchmarkRunner(
                List.of(new LocomoLoader(java.nio.file.Paths.get("dummy"),
                        new com.fasterxml.jackson.databind.ObjectMapper())),
                props,
                new BaselineStore(java.nio.file.Paths.get("dummy.json")),
                new RegressionDetector(props.getRegression()),
                new MarkdownReporter(java.nio.file.Paths.get(".")),
                new JsonReporter(java.nio.file.Paths.get(".")),
                bc -> null, (sessions, q) -> null,
                new ExactMatchJudge(), new F1Judge(),
                new LlmAsJudge(null, props.getJudge()));
    }
}
