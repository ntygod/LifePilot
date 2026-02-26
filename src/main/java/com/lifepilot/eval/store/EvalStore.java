package com.lifepilot.eval.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.eval.model.EvalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                evaluated_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_SCENARIO_SQL = """
            SELECT * FROM eval_results
            WHERE scenario_id = ?
            ORDER BY evaluated_at DESC
            LIMIT ?
            """;

    private static final String FIND_BY_RUN_ID_SQL = """
            SELECT * FROM eval_results
            WHERE eval_run_id = ?
            ORDER BY evaluated_at DESC
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvalStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步持久化评估结果（Virtual Thread）。
     *
     * <p>在 Virtual Thread 中执行 {@link #persist(EvalResult)}，
     * 异常在线程内捕获并记录 ERROR 日志，不阻断调用方。</p>
     *
     * @param result 评估结果
     */
    public void persistAsync(EvalResult result) {
        Thread.ofVirtual().name("eval-persist-" + result.evalId()).start(() -> {
            try {
                persist(result);
            } catch (Exception e) {
                log.error("异步持久化评估结果失败: evalId={}, scenarioId={}", result.evalId(), result.scenarioId(), e);
            }
        });
    }

    /**
     * 同步持久化评估结果。
     *
     * <p>写入失败时记录 ERROR 日志，不抛出异常，不阻断评估流程。</p>
     *
     * @param result 评估结果
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
                    createdAt
            );
            log.debug("评估结果已持久化: evalId={}, scenarioId={}", result.evalId(), result.scenarioId());
        } catch (Exception e) {
            log.error("持久化评估结果失败: evalId={}, scenarioId={}", result.evalId(), result.scenarioId(), e);
        }
    }

    /**
     * 按场景 ID 查询历史评估结果，按评估时间降序排列。
     *
     * @param scenarioId 场景 ID
     * @param limit      最大返回条数
     * @return 评估结果列表，查询失败或无结果时返回空列表
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
     *
     * @param evalRunId 评估运行 ID
     * @return 评估结果列表，查询失败或无结果时返回空列表
     */
    public List<EvalResult> findByRunId(String evalRunId) {
        try {
            return jdbcTemplate.query(FIND_BY_RUN_ID_SQL, new EvalResultRowMapper(), evalRunId);
        } catch (Exception e) {
            log.error("查询评估结果失败: evalRunId={}", evalRunId, e);
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

                // llm_judge_score 可能为 NULL
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
                        .build();
            } catch (JsonProcessingException e) {
                throw new SQLException("反序列化评估结果 JSON 字段失败: rowNum=" + rowNum, e);
            }
        }
    }
}
