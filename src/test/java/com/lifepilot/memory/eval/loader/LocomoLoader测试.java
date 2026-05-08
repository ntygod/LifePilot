package com.lifepilot.memory.eval.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LocomoLoader} 单元测试。
 *
 * <p>使用 {@code src/test/resources/memory-eval/locomo-sample.json} 作为缩小版 fixture，
 * 验证 LoCoMo 格式解析正确、类型映射、maxCases 截断。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("LocomoLoader 单元测试")
class LocomoLoader测试 {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("加载 fixture: 解析 sessions 和 QA 并映射 category 为 QuestionType")
    void 加载fixture_解析正确(@TempDir Path tempDir) throws Exception {
        Path datasetDir = prepareFixture(tempDir);

        LocomoLoader loader = new LocomoLoader(datasetDir, objectMapper);
        assertThat(loader.name()).isEqualTo("locomo");

        List<BenchmarkCase> cases = loader.load(-1);
        assertThat(cases).hasSize(1);

        BenchmarkCase first = cases.get(0);
        assertThat(first.caseId()).isEqualTo("conv-test-1");
        assertThat(first.benchmark()).isEqualTo("locomo");
        assertThat(first.sessions()).hasSize(2);
        assertThat(first.sessions().get(0).sessionId()).isEqualTo("session_1");
        assertThat(first.sessions().get(0).messages()).hasSize(3);

        assertThat(first.questions()).hasSize(4);
        assertThat(first.questions().get(0).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.SINGLE_HOP);
        assertThat(first.questions().get(1).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.MULTI_HOP);
        assertThat(first.questions().get(2).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.TEMPORAL);
        assertThat(first.questions().get(3).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.ABSTENTION);
    }

    @Test
    @DisplayName("maxCases 截断: 只加载前 N 条")
    void maxCases_截断(@TempDir Path tempDir) throws Exception {
        Path datasetDir = prepareFixture(tempDir);

        LocomoLoader loader = new LocomoLoader(datasetDir, objectMapper);
        List<BenchmarkCase> cases = loader.load(0); // 0 表示不限，-1 同
        assertThat(cases).hasSize(1);

        cases = loader.load(1);
        assertThat(cases).hasSize(1);
    }

    @Test
    @DisplayName("数据集文件不存在: 抛 BenchmarkDatasetNotFoundException")
    void 数据集缺失_抛异常(@TempDir Path tempDir) {
        LocomoLoader loader = new LocomoLoader(tempDir, objectMapper);
        assertThatThrownBy(() -> loader.load(-1))
                .isInstanceOf(BenchmarkDatasetNotFoundException.class)
                .hasMessageContaining("locomo10.json")
                .hasMessageContaining("scripts/download-eval-datasets.sh");
    }

    private Path prepareFixture(Path tempDir) throws Exception {
        Path locomoDir = tempDir.resolve("locomo");
        Files.createDirectories(locomoDir);
        Path destFile = locomoDir.resolve("locomo10.json");
        try (InputStream in = getClass().getResourceAsStream(
                "/memory-eval/locomo-sample.json")) {
            assertThat(in).as("fixture 资源缺失").isNotNull();
            Files.copy(in, destFile);
        }
        return tempDir;
    }
}
