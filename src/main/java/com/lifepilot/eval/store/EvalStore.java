package com.lifepilot.eval.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.model.RunMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 评估结果持久化存储。
 *
 * <p>将 {@link EvalResult} 写入 SQLite 的 eval_results 表，
 * 支持同步/异步写入和按场景/运行 ID 查询。写入失败时记录 ERROR 日志，不阻断评估流程。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class EvalStore {

    private static final Logger log = LoggerFactory.getLogger(EvalStore.class);

    private static final TypeReference<Map<String, Double>> DIMENSION_SCORES_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private static final String INSERT_SQL = """
            INSERT INTO eval_results (
                eval_id, trace_id, scenario_id, dimension_scores_json, overall_score,
                violations_json, suggestions_json, llm_judge_score, llm_judge_justification,
                llm_judge_tokens_used, git_commit_hash, git_branch, eval_run_id,
                evaluated_at, created_at, diagnostic_json, run_metadata_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_SCENARIO_SQL = """
            SELECT eval_id, trace_id, scenario_id, dimension_scores_json, overall_score,
                   violations_json, suggestions_json, llm_judge_score, llm_judge_justification,
                   llm_judge_tokens_used, git_commit_hash, git_branch, eval_run_id,
                   evaluated_at, created_at, diagnostic_json, run_metadata_json
            FROM eval_results
            WHERE scenario_id = ?
            ORDER BY evaluated_at DESC
            LIMIT ?
            """;

    private static final String FIND_BY_RUN_ID_SQL = """
            SELECT eval_id, trace_id, scenario_id, dimension_scores_json, overall_score,
                   violations_json, suggestions_json, llm_judge_score, llm_judge_justification,
                   llm_judge_tokens_used, git_commit_hash, git_branch, eval_run_id,
                   evaluated_at, created_at, diagnostic_json, run_metadata_json
            FROM eval_results
            WHERE eval_run_id = ?
            ORDER BY evaluated_at DESC
            """;

    private static final String INSERT_RUN_SQL = """
            INSERT INTO eval_runs (eval_run_id, metadata_json, is_baseline, created_at)
            VALUES (?, ?, 0, ?)
            """;

    private static final String MARK_BASELINE_SQL = """
            UPDATE eval_runs SET is_baseline = 1 WHERE eval_run_id = ?
            """;

    private static final String CLEAR_BASELINE_SQL = """
            UPDATE eval_runs SET is_baseline = 0 WHERE is_baseline = 1
            """;

    private static final String FIND_LATEST_BASELINE_SQL = """
            SELECT eval_run_id FROM eval_runs
            WHERE is_baseline = 1
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String FIND_SCORE_HISTORY_SQL = """
            SELECT overall_score FROM eval_results
            WHERE scenario_id = ? AND eval_run_id != ?
            ORDER BY evaluated_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final TransactionTemplate transactionTemplate;

    public EvalStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                     ExecutorService executor, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 异步持久化评估结果（使用受管理的 ExecutorService）。
     */
    public void persistAsync(EvalResult result) {
        executor.submit(() -> {
            try {
                persist(result);
            } catch (Exception e) {
                log.error("异步持久化评估结果失败: evalId={}, scenarioId={}", result.evalId(), result.scenarioId(), e);
            }
        });
    }

    /**
     * 同步持久化评估结果。
     */
    public void persist(EvalResult result) {
        try {
            String dimensionScoresJson = objectMapper.writeValueAsString(result.dimensionScores());
            String violationsJson = objectMapper.writeValueAsString(result.violations());
            String suggestionsJson = objectMapper.writeValueAsString(result.suggestions());
            String evaluatedAt = result.evaluatedAt().toString();
            String createdAt = Instant.now().toString();

            jdbcTemplate.update(INSERT_SQL,
                    result.evalId(),
                    result.traceId(),
                    result.scenarioId(),
                    dimensionScoresJson,
                    result.overallScore(),
                    violationsJson,
                    suggestionsJson,
                    result.llmJudgeScore(),
                    result.llmJudgeJustification(),
                    result.llmJudgeTokensUsed(),
                    result.gitCommitHash(),
                    result.gitBranch(),
                    result.evalRunId(),
                    evaluatedAt,
                    createdAt,
                    result.diagnosticJson(),
                    result.runMetadataJson()
            );
            log.debug("评估结果已持久化: evalId={}, scenarioId={}", result.evalId(), result.scenarioId());
        } catch (Exception e) {
            log.error("持久化评估结果失败: evalId={}, scenarioId={}", result.evalId(), result.scenarioId(), e);
        }
    }

    /**
     * 按场景 ID 查询历史评估结果，按评估时间降序排列。
     */
    public List<EvalResult> findByScenarioId(String scenarioId, int limit) {
        try {
            return jdbcTemplate.query(FIND_BY_SCENARIO_SQL, new EvalResultRowMapper(), scenarioId, limit);
        } catch (Exception e) {
            log.error("查询评估结果失败: scenarioId={}, limit={}", scenarioId, limit, e);
            return List.of();
        }
    }

    /**
     * 查询指定运行的所有评估结果，按评估时间降序排列。
     */
    public List<EvalResult> findByRunId(String evalRunId) {
        try {
            return jdbcTemplate.query(FIND_BY_RUN_ID_SQL, new EvalResultRowMapper(), evalRunId);
        } catch (Exception e) {
            log.error("查询评估结果失败: evalRunId={}", evalRunId, e);
            return List.of();
        }
    }

    // ==================== eval_runs 表操作 ====================

    /**
     * 保存评估运行记录。
     */
    public void saveRun(String evalRunId, @Nullable RunMetadata metadata) {
        try {
            String metadataJson = metadata != null ? objectMapper.writeValueAsString(metadata) : null;
            jdbcTemplate.update(INSERT_RUN_SQL, evalRunId, metadataJson, Instant.now().toString());
            log.debug("评估运行记录已保存: evalRunId={}", evalRunId);
        } catch (Exception e) {
            log.error("保存评估运行记录失败: evalRunId={}", evalRunId, e);
        }
    }

    /**
     * 标记指定运行为基线（先清除旧基线，事务保护保证原子性）。
     */
    public void markAsBaseline(String evalRunId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(CLEAR_BASELINE_SQL);
                jdbcTemplate.update(MARK_BASELINE_SQL, evalRunId);
            });
            log.info("已标记基线运行: evalRunId={}", evalRunId);
        } catch (Exception e) {
            log.error("标记基线运行失败: evalRunId={}", evalRunId, e);
        }
    }

    /**
     * 查找最新的基线运行 ID。
     */
    public Optional<String> findLatestBaselineRunId() {
        try {
            List<String> ids = jdbcTemplate.queryForList(FIND_LATEST_BASELINE_SQL, String.class);
            return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
        } catch (Exception e) {
            log.error("查找基线运行失败", e);
            return Optional.empty();
        }
    }

    /**
     * 查询指定场景最近 N 次评估的 overallScore 历史（按时间降序）。
     *
     * @param scenarioId 场景 ID
     * @param excludeRunId 排除的运行 ID（避免包含当前运行的异步写入结果）
     * @param limit        最大返回条数
     * @return 评分列表（最新的在前）
     */
    public List<Double> findScoreHistory(String scenarioId, String excludeRunId, int limit) {
        try {
            return jdbcTemplate.queryForList(FIND_SCORE_HISTORY_SQL, Double.class, scenarioId, excludeRunId, limit);
        } catch (Exception e) {
            log.error("查询评分历史失败: scenarioId={}, limit={}", scenarioId, limit, e);
            return List.of();
        }
    }

    /**
     * 行映射器 — 将 ResultSet 行转换为 EvalResult record。
     */
    private class EvalResultRowMapper implements RowMapper<EvalResult> {

        @Override
        public EvalResult mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                Map<String, Double> dimensionScores = objectMapper.readValue(
                        rs.getString("dimension_scores_json"), DIMENSION_SCORES_TYPE);
                List<String> violations = objectMapper.readValue(
                        rs.getString("violations_json"), STRING_LIST_TYPE);
                List<String> suggestions = objectMapper.readValue(
                        rs.getString("suggestions_json"), STRING_LIST_TYPE);

                Double llmJudgeScore = rs.getObject("llm_judge_score") != null
                        ? rs.getDouble("llm_judge_score") : null;

                return EvalResult.builder()
                        .evalId(rs.getString("eval_id"))
                        .traceId(rs.getString("trace_id"))
                        .scenarioId(rs.getString("scenario_id"))
                        .dimensionScores(dimensionScores)
                        .overallScore(rs.getDouble("overall_score"))
                        .violations(violations)
                        .suggestions(suggestions)
                        .llmJudgeScore(llmJudgeScore)
                        .llmJudgeJustification(rs.getString("llm_judge_justification"))
                        .llmJudgeTokensUsed(rs.getInt("llm_judge_tokens_used"))
                        .evaluatedAt(Instant.parse(rs.getString("evaluated_at")))
                        .gitCommitHash(rs.getString("git_commit_hash"))
                        .gitBranch(rs.getString("git_branch"))
                        .evalRunId(rs.getString("eval_run_id"))
                        .diagnosticJson(rs.getString("diagnostic_json"))
                        .runMetadataJson(rs.getString("run_metadata_json"))
                        .build();
            } catch (JsonProcessingException e) {
                throw new SQLException("反序列化评估结果 JSON 字段失败: rowNum=" + rowNum, e);
            }
        }
    }
}
