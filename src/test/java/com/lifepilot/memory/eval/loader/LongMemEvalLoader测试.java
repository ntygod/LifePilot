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
 * {@link LongMemEvalLoader} 单元测试。
 *
 * <p>使用 {@code src/test/resources/memory-eval/longmemeval-sample.json} 缩小版 fixture，
 * 验证格式解析、题型映射、maxCases 截断。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("LongMemEvalLoader 单元测试")
class LongMemEvalLoader测试 {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("加载 fixture: 每条 question 独立映射为 BenchmarkCase")
    void 加载fixture_每题一case(@TempDir Path tempDir) throws Exception {
        Path cacheDir = prepareFixture(tempDir);

        LongMemEvalLoader loader = new LongMemEvalLoader(cacheDir, objectMapper);
        assertThat(loader.name()).isEqualTo("longmemeval");

        List<BenchmarkCase> cases = loader.load(-1);
        assertThat(cases).hasSize(3);

        BenchmarkCase first = cases.get(0);
        assertThat(first.caseId()).isEqualTo("lme-1");
        assertThat(first.sessions()).hasSize(2);
        assertThat(first.questions()).hasSize(1);
        assertThat(first.questions().get(0).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.SINGLE_HOP);

        BenchmarkCase second = cases.get(1);
        assertThat(second.questions().get(0).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.KNOWLEDGE_UPDATE);

        BenchmarkCase third = cases.get(2);
        assertThat(third.questions().get(0).questionType())
                .isEqualTo(BenchmarkQuestion.QuestionType.ABSTENTION);
    }

    @Test
    @DisplayName("maxCases=2 截断")
    void maxCases_截断(@TempDir Path tempDir) throws Exception {
        Path cacheDir = prepareFixture(tempDir);

        LongMemEvalLoader loader = new LongMemEvalLoader(cacheDir, objectMapper);
        List<BenchmarkCase> cases = loader.load(2);
        assertThat(cases).hasSize(2);
    }

    @Test
    @DisplayName("数据集缺失: 抛异常且信息清晰")
    void 数据集缺失_抛异常(@TempDir Path tempDir) {
        LongMemEvalLoader loader = new LongMemEvalLoader(tempDir, objectMapper);
        assertThatThrownBy(() -> loader.load(-1))
                .isInstanceOf(BenchmarkDatasetNotFoundException.class)
                .hasMessageContaining("longmemeval_s.json");
    }

    private Path prepareFixture(Path tempDir) throws Exception {
        Path lmeDir = tempDir.resolve("longmemeval");
        Files.createDirectories(lmeDir);
        Path destFile = lmeDir.resolve("longmemeval_s.json");
        try (InputStream in = getClass().getResourceAsStream(
                "/memory-eval/longmemeval-sample.json")) {
            assertThat(in).as("fixture 资源缺失").isNotNull();
            Files.copy(in, destFile);
        }
        return tempDir;
    }
}
