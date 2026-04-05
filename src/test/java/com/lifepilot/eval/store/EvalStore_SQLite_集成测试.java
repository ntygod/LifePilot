package com.lifepilot.eval.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.eval.model.EvalResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvalStore SQLite 持久化集成测试。
 *
 * <p>使用内存 SQLite 验证 persist → findByScenarioId → findByRunId 完整流程，
 * 以及 JSON 字段序列化/反序列化正确性。轻量级测试，不加载 Spring 上下文。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
class EvalStore_SQLite_集成测试 {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS eval_results (
                eval_id                 TEXT PRIMARY KEY,
                trace_id                TEXT,
                scenario_id             TEXT NOT NULL,
                dimension_scores_json   TEXT NOT NULL,
                overall_score           REAL NOT NULL,
                violations_json         TEXT NOT NULL,
                suggestions_json        TEXT NOT NULL,
                llm_judge_score         REAL,
                llm_judge_justification TEXT,
                llm_judge_tokens_used   INTEGER DEFAULT 0,
                git_commit_hash         TEXT,
                git_branch              TEXT,
                eval_run_id             TEXT NOT NULL,
                evaluated_at            TEXT NOT NULL,
                created_at              TEXT NOT NULL,
                diagnostic_json         TEXT,
                run_metadata_json       TEXT
            )
            """;

    private EvalStore evalStore;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        // 使用 SingleConnectionDataSource 保持同一连接，避免内存 SQLite 跨连接丢失数据
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(CREATE_TABLE);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_eval_results_scenario_id ON eval_results(scenario_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_eval_results_eval_run_id ON eval_results(eval_run_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_eval_results_evaluated_at ON eval_results(evaluated_at)");
        var transactionManager = new DataSourceTransactionManager(dataSource);
        evalStore = new EvalStore(jdbcTemplate, new ObjectMapper(),
                Executors.newVirtualThreadPerTaskExecutor(), transactionManager);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void persist后_findByScenarioId能查到() {
        var result = buildResult("scenario-001", "run-001", Instant.now());
        evalStore.persist(result);

        List<EvalResult> found = evalStore.findByScenarioId("scenario-001", 10);
        assertThat(found).hasSize(1);

        var loaded = found.getFirst();
        assertThat(loaded.evalId()).isEqualTo(result.evalId());
        assertThat(loaded.traceId()).isEqualTo(result.traceId());
        assertThat(loaded.scenarioId()).isEqualTo("scenario-001");
        assertThat(loaded.overallScore()).isEqualTo(result.overallScore());
        assertThat(loaded.evalRunId()).isEqualTo("run-001");
        assertThat(loaded.gitCommitHash()).isEqualTo(result.gitCommitHash());
        assertThat(loaded.gitBranch()).isEqualTo(result.gitBranch());
    }

    @Test
    void JSON字段往返一致性_dimensionScores_violations_suggestions() {
        Map<String, Double> scores = Map.of(
                "toolSelection", 0.95,
                "parameterValidity", 0.80,
                "stepEfficiency", 1.0,
                "policyCompliance", 0.70,
                "tokenEfficiency", 0.60
        );
        List<String> violations = List.of("工具调用顺序不一致", "Token 超出预算");
        List<String> suggestions = List.of("优化工具选择策略", "减少冗余步骤");

        var result = EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId("trace-json-test")
                .scenarioId("scenario-json")
                .dimensionScores(scores)
                .overallScore(0.81)
                .violations(violations)
                .suggestions(suggestions)
                .llmJudgeScore(0.75)
                .llmJudgeJustification("回答质量良好")
                .llmJudgeTokensUsed(150)
                .evaluatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .gitCommitHash("abc123")
                .gitBranch("feature/test")
                .evalRunId("run-json")
                .build();

        evalStore.persist(result);

        var loaded = evalStore.findByScenarioId("scenario-json", 1).getFirst();
        assertThat(loaded.dimensionScores()).isEqualTo(scores);
        assertThat(loaded.violations()).isEqualTo(violations);
        assertThat(loaded.suggestions()).isEqualTo(suggestions);
        assertThat(loaded.llmJudgeScore()).isEqualTo(0.75);
        assertThat(loaded.llmJudgeJustification()).isEqualTo("回答质量良好");
        assertThat(loaded.llmJudgeTokensUsed()).isEqualTo(150);
    }

    @Test
    void findByScenarioId_按时间降序() {
        Instant base = Instant.parse("2026-08-01T00:00:00Z");
        var oldest = buildResult("scenario-order", "run-1", base);
        var middle = buildResult("scenario-order", "run-2", base.plus(1, ChronoUnit.HOURS));
        var newest = buildResult("scenario-order", "run-3", base.plus(2, ChronoUnit.HOURS));

        // 故意乱序插入
        evalStore.persist(middle);
        evalStore.persist(oldest);
        evalStore.persist(newest);

        List<EvalResult> found = evalStore.findByScenarioId("scenario-order", 10);
        assertThat(found).hasSize(3);
        assertThat(found.get(0).evaluatedAt()).isEqualTo(newest.evaluatedAt());
        assertThat(found.get(1).evaluatedAt()).isEqualTo(middle.evaluatedAt());
        assertThat(found.get(2).evaluatedAt()).isEqualTo(oldest.evaluatedAt());
    }

    @Test
    void findByScenarioId_limit生效() {
        Instant base = Instant.parse("2026-08-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            evalStore.persist(buildResult("scenario-limit", "run-" + i, base.plus(i, ChronoUnit.HOURS)));
        }

        List<EvalResult> found = evalStore.findByScenarioId("scenario-limit", 2);
        assertThat(found).hasSize(2);
    }

    @Test
    void findByRunId_查询正确() {
        evalStore.persist(buildResult("s1", "run-A", Instant.now()));
        evalStore.persist(buildResult("s2", "run-A", Instant.now()));
        evalStore.persist(buildResult("s3", "run-B", Instant.now()));

        List<EvalResult> runA = evalStore.findByRunId("run-A");
        assertThat(runA).hasSize(2);
        assertThat(runA).allMatch(r -> "run-A".equals(r.evalRunId()));

        List<EvalResult> runB = evalStore.findByRunId("run-B");
        assertThat(runB).hasSize(1);
        assertThat(runB.getFirst().evalRunId()).isEqualTo("run-B");
    }

    @Test
    void llmJudgeScore为null时正确处理() {
        var result = EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId("trace-null-judge")
                .scenarioId("scenario-null-judge")
                .dimensionScores(Map.of("toolSelection", 0.9))
                .overallScore(0.9)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeScore(null)
                .llmJudgeJustification(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .gitCommitHash(null)
                .gitBranch(null)
                .evalRunId("run-null")
                .build();

        evalStore.persist(result);

        var loaded = evalStore.findByScenarioId("scenario-null-judge", 1).getFirst();
        assertThat(loaded.llmJudgeScore()).isNull();
        assertThat(loaded.llmJudgeJustification()).isNull();
        assertThat(loaded.gitCommitHash()).isNull();
        assertThat(loaded.gitBranch()).isNull();
    }

    // ---- 辅助方法 ----

    private EvalResult buildResult(String scenarioId, String runId, Instant evaluatedAt) {
        return EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId("trace-" + UUID.randomUUID().toString().substring(0, 8))
                .scenarioId(scenarioId)
                .dimensionScores(Map.of("toolSelection", 0.85, "stepEfficiency", 0.90))
                .overallScore(0.87)
                .violations(List.of("轻微违规"))
                .suggestions(List.of("建议优化"))
                .llmJudgeScore(0.80)
                .llmJudgeJustification("评估合理")
                .llmJudgeTokensUsed(100)
                .evaluatedAt(evaluatedAt)
                .gitCommitHash("def456")
                .gitBranch("feature/agentic-evals")
                .evalRunId(runId)
                .build();
    }
}
