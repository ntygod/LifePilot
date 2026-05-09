package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.baseline.BaselineStore;
import com.lifepilot.memory.eval.baseline.RegressionDetector;
import com.lifepilot.memory.eval.baseline.RegressionResult;
import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.judge.AnswerJudge;
import com.lifepilot.memory.eval.judge.ExactMatchJudge;
import com.lifepilot.memory.eval.judge.F1Judge;
import com.lifepilot.memory.eval.judge.JudgeVerdict;
import com.lifepilot.memory.eval.judge.LlmAsJudge;
import com.lifepilot.memory.eval.loader.BenchmarkCase;
import com.lifepilot.memory.eval.loader.BenchmarkLoader;
import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import com.lifepilot.memory.eval.probe.EvalMetrics;
import com.lifepilot.memory.eval.probe.LatencyProbe;
import com.lifepilot.memory.eval.probe.RetrievalProbe;
import com.lifepilot.memory.eval.probe.TokenProbe;
import com.lifepilot.memory.eval.report.BenchmarkReport;
import com.lifepilot.memory.eval.report.CaseDetail;
import com.lifepilot.memory.eval.report.EvalReport;
import com.lifepilot.memory.eval.report.JsonReporter;
import com.lifepilot.memory.eval.report.MarkdownReporter;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 评估主入口。由 JUnit 测试或 CLI 触发。
 *
 * <p>主流程：Loader → (IsolatedMemoryContext → Replayer → MemoryQueryAdapter.answer)
 * → Probe + Judge → EvalReport → RegressionDetector → 写报告。</p>
 *
 * <p>为了让本类可单测，集成侧（IsolatedMemoryContext / Replayer 构造）通过
 * {@link RunContextFactory} 抽象出去。{@link MemoryQueryAdapter} 同样注入。
 * 单测时传 fake 即可。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final List<BenchmarkLoader> loaders;
    private final MemoryEvalProperties properties;
    private final BaselineStore baselineStore;
    private final RegressionDetector regressionDetector;
    private final MarkdownReporter markdownReporter;
    private final JsonReporter jsonReporter;
    private final RunContextFactory runContextFactory;
    private final MemoryQueryAdapter queryAdapter;
    private final ExactMatchJudge exactMatchJudge;
    private final F1Judge f1Judge;
    private final LlmAsJudge llmAsJudge;

    public BenchmarkRunner(List<BenchmarkLoader> loaders,
                           MemoryEvalProperties properties,
                           BaselineStore baselineStore,
                           RegressionDetector regressionDetector,
                           MarkdownReporter markdownReporter,
                           JsonReporter jsonReporter,
                           RunContextFactory runContextFactory,
                           MemoryQueryAdapter queryAdapter,
                           ExactMatchJudge exactMatchJudge,
                           F1Judge f1Judge,
                           LlmAsJudge llmAsJudge) {
        this.loaders = List.copyOf(loaders);
        this.properties = properties;
        this.baselineStore = baselineStore;
        this.regressionDetector = regressionDetector;
        this.markdownReporter = markdownReporter;
        this.jsonReporter = jsonReporter;
        this.runContextFactory = runContextFactory;
        this.queryAdapter = queryAdapter;
        this.exactMatchJudge = exactMatchJudge;
        this.f1Judge = f1Judge;
        this.llmAsJudge = llmAsJudge;
    }

    /**
     * 执行一次完整评估。返回基线对比结果。
     */
    public RegressionResult run() {
        List<BenchmarkReport> benchmarkReports = new ArrayList<>();
        for (BenchmarkLoader loader : loaders) {
            if (!isBenchmarkEnabled(loader.name())) {
                log.info("Benchmark {} 未启用，跳过", loader.name());
                continue;
            }
            int maxCases = resolveMaxCases(loader.name());
            List<BenchmarkCase> cases = loader.load(maxCases);
            benchmarkReports.add(runBenchmark(loader.name(), cases));
        }

        EvalReport current = new EvalReport(
                Instant.now(), gitSha(), properties.getMode(), benchmarkReports, null);
        EvalReport baseline = baselineStore.loadOrNull();
        RegressionResult regression = regressionDetector.compare(current, baseline);
        EvalReport enriched = current.withRegression(regression);

        if (properties.getReporting().isMarkdown()) markdownReporter.write(enriched);
        if (properties.getReporting().isJson()) jsonReporter.write(enriched);
        if (properties.getRegression().isUpdateBaseline() && !regression.isFail()) {
            baselineStore.save(enriched);
        }
        return regression;
    }

    BenchmarkReport runBenchmark(String benchmarkName, List<BenchmarkCase> cases) {
        RetrievalProbe retrievalProbe = new RetrievalProbe();
        LatencyProbe latencyProbe = new LatencyProbe();
        TokenProbe tokenProbe = new TokenProbe();
        List<CaseDetail> details = new ArrayList<>();
        List<Float> llmScores = new ArrayList<>();
        List<Float> f1Scores = new ArrayList<>();
        List<Float> exactScores = new ArrayList<>();
        int skippedByJudge = 0;

        int caseCount = 0;
        for (BenchmarkCase bc : cases) {
            caseCount++;
            try (RunContext ctx = runContextFactory.open(bc)) {
                ctx.replay(bc);
                for (BenchmarkQuestion q : bc.questions()) {
                    long t0 = latencyProbe.start();
                    MemoryQueryAdapter.QueryResult qr;
                    try {
                        qr = queryAdapter.answer(bc.sessions(), q);
                    } catch (RuntimeException e) {
                        log.warn("单题召回失败 case={} question={} error={}",
                                bc.caseId(), q.questionId(), e.getMessage());
                        qr = new MemoryQueryAdapter.QueryResult("", List.of(), "");
                    }
                    latencyProbe.stop(t0);
                    retrievalProbe.record(q.query(), qr.retrievedEntityIds(),
                            q.evidenceSessionIds());
                    tokenProbe.record(qr.injectedContextText());

                    List<JudgeVerdict> verdicts = judge(q, qr.prediction());
                    for (JudgeVerdict v : verdicts) {
                        if (v.skipped()) {
                            skippedByJudge++;
                            continue;
                        }
                        llmScores.add(v.score());
                        if (F1Judge.NAME.equals(v.judgeName())) f1Scores.add(v.score());
                        if (ExactMatchJudge.NAME.equals(v.judgeName())) exactScores.add(v.score());
                    }
                    details.add(new CaseDetail(
                            bc.caseId(), q.questionId(), q.query(),
                            qr.prediction(), q.groundTruth(), verdicts,
                            computeLatencyMs(t0), estimateTokens(qr.injectedContextText())));
                }
            } catch (RuntimeException e) {
                log.error("Case 运行失败 benchmark={} caseId={} error={}",
                        benchmarkName, bc.caseId(), e.getMessage(), e);
            }
        }

        LatencyProbe.LatencySummary latSummary = latencyProbe.summary();
        EvalMetrics metrics = new EvalMetrics(
                details.size(),
                average(llmScores),
                average(f1Scores),
                average(exactScores),
                retrievalProbe.hitRate(),
                latSummary.p50Ms(), latSummary.p95Ms(), latSummary.p99Ms(),
                tokenProbe.averageTokensPerRecall(),
                tokenProbe.totalTokens(),
                skippedByJudge);
        return new BenchmarkReport(benchmarkName, caseCount, metrics, details);
    }

    List<JudgeVerdict> judge(BenchmarkQuestion q, String prediction) {
        List<JudgeVerdict> verdicts = new ArrayList<>();
        MemoryEvalProperties.Judge cfg = properties.getJudge();
        switch (q.questionType()) {
            case SINGLE_HOP, KNOWLEDGE_UPDATE, ABSTENTION -> {
                if (cfg.isExactMatchEnabled()) verdicts.add(exactMatchJudge.judge(prediction, q.groundTruth(), q));
            }
            case MULTI_HOP, TEMPORAL -> {
                if (cfg.isF1Enabled()) verdicts.add(f1Judge.judge(prediction, q.groundTruth(), q));
                if (cfg.isLlmEnabled() && llmAsJudge != null) {
                    verdicts.add(llmAsJudge.judge(prediction, q.groundTruth(), q));
                }
            }
            case OPEN_ENDED -> {
                if (cfg.isLlmEnabled() && llmAsJudge != null) {
                    verdicts.add(llmAsJudge.judge(prediction, q.groundTruth(), q));
                } else if (cfg.isF1Enabled()) {
                    verdicts.add(f1Judge.judge(prediction, q.groundTruth(), q));
                }
            }
        }
        return verdicts;
    }

    boolean isBenchmarkEnabled(String name) {
        MemoryEvalProperties.Benchmarks b = properties.getBenchmarks();
        return switch (name) {
            case "locomo" -> b.getLocomo().isEnabled();
            case "longmemeval" -> b.getLongMemEval().isEnabled();
            default -> true;
        };
    }

    int resolveMaxCases(String name) {
        MemoryEvalProperties.Benchmarks b = properties.getBenchmarks();
        boolean quick = properties.isQuickMode();
        return switch (name) {
            case "locomo" -> quick
                    ? b.getLocomo().getQuickMaxConversations()
                    : b.getLocomo().getMaxConversations();
            case "longmemeval" -> quick
                    ? b.getLongMemEval().getQuickMaxQuestions()
                    : b.getLongMemEval().getMaxQuestions();
            default -> -1;
        };
    }

    private static long computeLatencyMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static int estimateTokens(String text) {
        return com.lifepilot.memory.compression.TokenEstimator.estimate(text);
    }

    private static float average(List<Float> values) {
        if (values.isEmpty()) return 0f;
        double sum = 0;
        for (Float v : values) sum += v;
        return (float) (sum / values.size());
    }

    @Nullable
    static String gitSha() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--short", "HEAD");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String sha = reader.readLine();
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
                return sha != null && !sha.isBlank() ? sha.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // 集成侧抽象：让 Runner 可单测（fake RunContext）也可在真实 Spring 场景下运行
    // ---------------------------------------------------------------------

    /**
     * 单用例的运行上下文。实际实现负责启动隔离 SQLite、Replayer 等重资源。
     */
    public interface RunContext extends AutoCloseable {
        /** 将 case 的对话重放到 L0 transcript，触发记忆提取链路。 */
        void replay(BenchmarkCase benchmarkCase);
        @Override
        void close();
    }

    /**
     * 按 case 创建 {@link RunContext}。
     */
    public interface RunContextFactory {
        RunContext open(BenchmarkCase benchmarkCase);
    }
}
