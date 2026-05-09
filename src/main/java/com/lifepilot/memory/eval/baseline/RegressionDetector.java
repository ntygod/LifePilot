package com.lifepilot.memory.eval.baseline;

import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.probe.EvalMetrics;
import com.lifepilot.memory.eval.report.BenchmarkReport;
import com.lifepilot.memory.eval.report.EvalReport;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基线退化检测。
 *
 * <p>对比当前 {@link EvalReport} 与历史基线，超过各维度容差则状态 FAIL。</p>
 *
 * <p>规则：</p>
 * <ul>
 *     <li>LLM-Score 下降 &gt; {@code llm-score-tolerance}（绝对差，默认 0.03）</li>
 *     <li>p95 延迟上升 &gt; {@code latency-tolerance}（相对比例，默认 20%）</li>
 *     <li>平均 token 上升 &gt; {@code token-tolerance}（相对比例，默认 30%）</li>
 * </ul>
 *
 * <p>对比粒度 per benchmark：每个 benchmark 独立对比，任一 benchmark 的任一指标超限即 FAIL。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class RegressionDetector {

    private static final Logger log = LoggerFactory.getLogger(RegressionDetector.class);

    private final MemoryEvalProperties.Regression config;

    public RegressionDetector(MemoryEvalProperties.Regression config) {
        this.config = config;
    }

    public RegressionResult compare(EvalReport current, @Nullable EvalReport baseline) {
        if (baseline == null) return RegressionResult.noBaseline();

        List<MetricDiff> diffs = new ArrayList<>();
        Map<String, EvalMetrics> baselineByName = indexByName(baseline);

        boolean violated = false;
        for (BenchmarkReport br : current.benchmarks()) {
            EvalMetrics baselineMetrics = baselineByName.get(br.benchmarkName());
            if (baselineMetrics == null) {
                log.info("基线缺少 benchmark {}, 视作新增 benchmark，不计退化", br.benchmarkName());
                continue;
            }
            EvalMetrics cur = br.metrics();

            MetricDiff llmDiff = buildDiff(
                    br.benchmarkName() + ".llm_score",
                    baselineMetrics.llmScore(),
                    cur.llmScore(),
                    config.getLlmScoreTolerance(),
                    llmScoreViolated(baselineMetrics.llmScore(), cur.llmScore()));
            diffs.add(llmDiff);
            if (llmDiff.violated()) violated = true;

            MetricDiff latencyDiff = buildDiff(
                    br.benchmarkName() + ".p95_latency_ms",
                    baselineMetrics.p95LatencyMs(),
                    cur.p95LatencyMs(),
                    config.getLatencyTolerance(),
                    ratioViolated(baselineMetrics.p95LatencyMs(), cur.p95LatencyMs(),
                            config.getLatencyTolerance(), /*higherIsWorse=*/true));
            diffs.add(latencyDiff);
            if (latencyDiff.violated()) violated = true;

            MetricDiff tokenDiff = buildDiff(
                    br.benchmarkName() + ".avg_tokens",
                    baselineMetrics.avgTokensPerRecall(),
                    cur.avgTokensPerRecall(),
                    config.getTokenTolerance(),
                    ratioViolated(baselineMetrics.avgTokensPerRecall(), cur.avgTokensPerRecall(),
                            config.getTokenTolerance(), /*higherIsWorse=*/true));
            diffs.add(tokenDiff);
            if (tokenDiff.violated()) violated = true;
        }

        if (violated) {
            return RegressionResult.fail(diffs, baseline.timestamp());
        }
        return RegressionResult.pass(diffs, baseline.timestamp());
    }

    private boolean llmScoreViolated(float baselineVal, float currentVal) {
        // 下降超过容差视为退化
        return (baselineVal - currentVal) > config.getLlmScoreTolerance();
    }

    private boolean ratioViolated(float baselineVal, float currentVal,
                                  float tolerance, boolean higherIsWorse) {
        if (baselineVal <= 0f) return false; // 无法计算比例时不判定
        float ratio = (currentVal - baselineVal) / baselineVal;
        if (higherIsWorse) {
            return ratio > tolerance;
        } else {
            return -ratio > tolerance;
        }
    }

    private MetricDiff buildDiff(String name, float baseline, float current,
                                 float tolerance, boolean violated) {
        return new MetricDiff(name, baseline, current, tolerance, violated);
    }

    private static Map<String, EvalMetrics> indexByName(EvalReport report) {
        Map<String, EvalMetrics> map = new HashMap<>();
        for (BenchmarkReport br : report.benchmarks()) {
            map.put(br.benchmarkName(), br.metrics());
        }
        return map;
    }
}
