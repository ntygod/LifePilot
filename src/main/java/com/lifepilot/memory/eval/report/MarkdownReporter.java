package com.lifepilot.memory.eval.report;

import com.lifepilot.memory.eval.baseline.RegressionResult;
import com.lifepilot.memory.eval.probe.EvalMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * 生成 Markdown 报告。
 *
 * <p>输出到 {@code target/memory-eval/${timestamp}.md}。包含顶部汇总表格、
 * 基线对比段、每个 benchmark 明细。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class MarkdownReporter {

    private static final Logger log = LoggerFactory.getLogger(MarkdownReporter.class);
    private static final DateTimeFormatter FILENAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(java.time.ZoneOffset.UTC);

    private final Path outputDir;

    public MarkdownReporter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public Path write(EvalReport report) {
        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }
            String filename = FILENAME_FORMAT.format(report.timestamp()) + ".md";
            Path target = outputDir.resolve(filename);
            String content = render(report);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("Memory eval Markdown 报告写入: {}", target);
            return target;
        } catch (IOException e) {
            log.error("Memory eval Markdown 报告写入失败: dir={}", outputDir, e);
            return null;
        }
    }

    String render(EvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Memory Eval Report — ").append(DISPLAY_FORMAT.format(report.timestamp())).append('\n');
        sb.append('\n');
        sb.append("- mode: `").append(report.mode()).append("`\n");
        sb.append("- git sha: `").append(report.gitSha() == null ? "(unknown)" : report.gitSha()).append("`\n");
        sb.append('\n');

        // 汇总表
        sb.append("## 汇总\n\n");
        sb.append("| Benchmark | Cases | LLM-Score | F1 | ExactMatch | Hit-Rate | p50 | p95 | p99 | avg tokens |\n");
        sb.append("|-----------|------:|----------:|---:|-----------:|---------:|----:|----:|----:|-----------:|\n");
        for (BenchmarkReport br : report.benchmarks()) {
            EvalMetrics m = br.metrics();
            sb.append("| ").append(br.benchmarkName())
                    .append(" | ").append(br.caseCount())
                    .append(" | ").append(fmt(m.llmScore()))
                    .append(" | ").append(fmt(m.f1Score()))
                    .append(" | ").append(fmt(m.exactMatch()))
                    .append(" | ").append(fmt(m.hitRate()))
                    .append(" | ").append(m.p50LatencyMs()).append("ms")
                    .append(" | ").append(m.p95LatencyMs()).append("ms")
                    .append(" | ").append(m.p99LatencyMs()).append("ms")
                    .append(" | ").append(m.avgTokensPerRecall())
                    .append(" |\n");
        }
        sb.append('\n');

        // Regression 段
        RegressionResult regression = report.regressionResult();
        if (regression != null) {
            sb.append("## 基线对比\n\n");
            sb.append("状态：**").append(regression.status()).append("**\n\n");
            if (regression.baselineTimestamp() != null) {
                sb.append("基线生成时间：`")
                        .append(DISPLAY_FORMAT.format(regression.baselineTimestamp()))
                        .append("`\n\n");
            }
            if (!regression.diffs().isEmpty()) {
                sb.append("| 指标 | 基线 | 当前 | 容差 | 超限 |\n");
                sb.append("|------|-----:|-----:|-----:|:----:|\n");
                for (var d : regression.diffs()) {
                    sb.append("| ").append(d.metricName())
                            .append(" | ").append(fmt(d.baselineValue()))
                            .append(" | ").append(fmt(d.currentValue()))
                            .append(" | ").append(fmt(d.tolerance()))
                            .append(" | ").append(d.violated() ? "❌" : "✓")
                            .append(" |\n");
                }
                sb.append('\n');
            }
        }

        // 明细段
        for (BenchmarkReport br : report.benchmarks()) {
            sb.append("## ").append(br.benchmarkName()).append(" 明细\n\n");
            if (br.perCaseDetails().isEmpty()) {
                sb.append("_(无明细)_\n\n");
                continue;
            }
            for (CaseDetail cd : br.perCaseDetails()) {
                sb.append("### ").append(cd.caseId()).append(" / ").append(cd.questionId()).append('\n');
                sb.append("- query: ").append(truncate(cd.query(), 160)).append('\n');
                sb.append("- ground truth: ").append(truncate(cd.groundTruth(), 160)).append('\n');
                sb.append("- prediction: ").append(truncate(cd.prediction(), 160)).append('\n');
                sb.append("- latency: ").append(cd.latencyMs()).append("ms，tokens: ")
                        .append(cd.tokens()).append('\n');
                for (var v : cd.verdicts()) {
                    sb.append("  - `").append(v.judgeName()).append("` = ")
                            .append(fmt(v.score()))
                            .append(v.skipped() ? " (skipped)" : "")
                            .append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String fmt(float f) {
        return String.format(java.util.Locale.ROOT, "%.3f", f);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
