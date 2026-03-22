package com.lifepilot.eval.feedback;

import com.lifepilot.eval.feedback.EvalFeedback.FeedbackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 反馈持久化存储 — 基于 eval_feedback 表。
 *
 * @author zsg
 * @since 2026-03-22
 */
public class FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(FeedbackStore.class);

    private static final String INSERT_SQL = """
            INSERT INTO eval_feedback (feedback_id, eval_id, scenario_id, feedback_type,
                comment, golden_answer, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_EVAL_ID_SQL = """
            SELECT * FROM eval_feedback WHERE eval_id = ? ORDER BY created_at DESC
            """;

    private static final String FIND_GOLDEN_ANSWERS_SQL = """
            SELECT * FROM eval_feedback
            WHERE scenario_id = ? AND feedback_type = 'GOLDEN_ANSWER'
            ORDER BY created_at DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public FeedbackStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 持久化反馈。
     */
    public void persist(EvalFeedback feedback) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    feedback.feedbackId(),
                    feedback.evalId(),
                    feedback.scenarioId(),
                    feedback.feedbackType().name(),
                    feedback.comment(),
                    feedback.goldenAnswer(),
                    feedback.createdBy(),
                    feedback.createdAt().toString()
            );
            log.debug("反馈已持久化: feedbackId={}, evalId={}", feedback.feedbackId(), feedback.evalId());
        } catch (Exception e) {
            log.error("持久化反馈失败: feedbackId={}", feedback.feedbackId(), e);
        }
    }

    /**
     * 按评估结果 ID 查询反馈。
     */
    public List<EvalFeedback> findByEvalId(String evalId) {
        try {
            return jdbcTemplate.query(FIND_BY_EVAL_ID_SQL, new FeedbackRowMapper(), evalId);
        } catch (Exception e) {
            log.error("查询反馈失败: evalId={}", evalId, e);
            return List.of();
        }
    }

    /**
     * 查询指定场景的标注答案。
     */
    public List<EvalFeedback> findGoldenAnswers(String scenarioId) {
        try {
            return jdbcTemplate.query(FIND_GOLDEN_ANSWERS_SQL, new FeedbackRowMapper(), scenarioId);
        } catch (Exception e) {
            log.error("查询标注答案失败: scenarioId={}", scenarioId, e);
            return List.of();
        }
    }

    private static class FeedbackRowMapper implements RowMapper<EvalFeedback> {
        @Override
        public EvalFeedback mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvalFeedback(
                    rs.getString("feedback_id"),
                    rs.getString("eval_id"),
                    rs.getString("scenario_id"),
                    FeedbackType.valueOf(rs.getString("feedback_type")),
                    rs.getString("comment"),
                    rs.getString("golden_answer"),
                    rs.getString("created_by"),
                    Instant.parse(rs.getString("created_at"))
            );
        }
    }
}
