package com.lifepilot.memory.eval;

import com.lifepilot.memory.eval.baseline.RegressionResult;
import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.runner.BenchmarkRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LongMemEval 评估冒烟测试。
 *
 * <p>与 {@link LocomoEvalTest} 同样只在 Maven profile {@code memory-eval-*} 下触发。
 * 使用缩小 fixture。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@Tag("memory-eval")
class LongMemEvalTest {

    @TempDir
    static Path sharedTempDir;

    @BeforeAll
    static void prepareFixture() throws IOException {
        Path lmeDir = sharedTempDir.resolve("longmemeval");
        Files.createDirectories(lmeDir);
        try (InputStream in = LongMemEvalTest.class.getResourceAsStream(
                "/memory-eval/longmemeval-sample.json")) {
            if (in == null) throw new IllegalStateException("缺少 fixture: /memory-eval/longmemeval-sample.json");
            Files.copy(in, lmeDir.resolve("longmemeval_s.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void longmemeval_冒烟评估_两次运行基线对比() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setEnabled(true);
        props.setMode("quick");
        props.setDatasetCacheDir(sharedTempDir);
        props.getReporting().setOutputDir(sharedTempDir.resolve("reports"));
        props.getRegression().setBaselinePath(sharedTempDir.resolve("baseline.json"));
        props.getBenchmarks().getLocomo().setEnabled(false); // 本测试只跑 LongMemEval
        props.getBenchmarks().getLongMemEval().setQuickMaxQuestions(3);

        // 第一次运行：无基线 → 写入基线
        props.getRegression().setUpdateBaseline(true);
        BenchmarkRunner runner = IntegrationHarnessFactory.build(props,
                (sessions, q) -> new com.lifepilot.memory.eval.runner.MemoryQueryAdapter.QueryResult(
                        q.groundTruth(), java.util.List.of("entity"), "stub context"));
        RegressionResult first = runner.run();
        assertThat(first.isNoBaseline()).isTrue();

        // 第二次运行：当前与 baseline 一致 → PASS
        props.getRegression().setUpdateBaseline(false);
        RegressionResult second = runner.run();
        assertThat(second.isPass()).isTrue();
        assertThat(second.diffs()).isNotEmpty();
    }
}
