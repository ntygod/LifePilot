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
 * LoCoMo 评估冒烟测试。
 *
 * <p>默认 {@code mvn test} 会跳过本测试（通过 surefire excludedGroups=memory-eval）。
 * 使用 {@code mvn test -Pmemory-eval-quick} 或 {@code -Pmemory-eval-full} 触发。</p>
 *
 * <p>使用 {@code src/test/resources/memory-eval/locomo-sample.json} 作为缩小 fixture
 * 代替真实 LoCoMo 下载数据集。通过 {@link IntegrationHarnessFactory} 拼装最小 Runner
 * 环境，避免启动完整 Spring Boot Web 栈。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@Tag("memory-eval")
class LocomoEvalTest {

    @TempDir
    static Path sharedTempDir;

    @BeforeAll
    static void prepareFixture() throws IOException {
        Path locomoDir = sharedTempDir.resolve("locomo");
        Files.createDirectories(locomoDir);
        try (InputStream in = LocomoEvalTest.class.getResourceAsStream(
                "/memory-eval/locomo-sample.json")) {
            if (in == null) throw new IllegalStateException("缺少 fixture: /memory-eval/locomo-sample.json");
            Files.copy(in, locomoDir.resolve("locomo10.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void locomo_冒烟评估_完成且报告写盘() {
        MemoryEvalProperties props = new MemoryEvalProperties();
        props.setEnabled(true);
        props.setMode("quick");
        props.setDatasetCacheDir(sharedTempDir);
        props.getReporting().setOutputDir(sharedTempDir.resolve("reports"));
        props.getRegression().setBaselinePath(sharedTempDir.resolve("baseline.json"));
        props.getBenchmarks().getLongMemEval().setEnabled(false); // 本测试只跑 LoCoMo
        props.getBenchmarks().getLocomo().setQuickMaxConversations(1);

        BenchmarkRunner runner = IntegrationHarnessFactory.build(props,
                (sessions, q) -> new com.lifepilot.memory.eval.runner.MemoryQueryAdapter.QueryResult(
                        // 返回 ground truth 作为"最优 agent"，冒烟期望结果全正确
                        q.groundTruth(), java.util.List.of("entity-" + q.questionId()),
                        "stub context"));

        RegressionResult result = runner.run();
        assertThat(result).isNotNull();
        assertThat(result.isNoBaseline()).isTrue(); // 首跑没 baseline

        // 报告目录至少有一个 md 或 json
        Path reportDir = sharedTempDir.resolve("reports");
        assertThat(reportDir).exists();
        try (var stream = Files.list(reportDir)) {
            assertThat(stream.count()).isGreaterThan(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
