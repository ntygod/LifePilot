package com.lifepilot.agent.learning.feedback;

import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 反馈处理器，消费消息反馈并调整关联记忆实体的 importanceScore。
 *
 * <p>当前反馈对象是助手 transcript 条目。处理流程为：
 * 查询注入 provenance、检查历史反馈、计算 delta、批量更新 importanceScore。</p>
 *
 * @author zsg
 * @since 2026-03-13
 */
public class FeedbackProcessor {

    private static final Logger log = LoggerFactory.getLogger(FeedbackProcessor.class);

    private final InjectionRecordRepository injectionRecordRepository;
    private final SemanticMemory semanticMemory;
    private final MessageFeedbackRepository feedbackRepository;
    private final AgentLearningProperties.Feedback feedbackConfig;

    public FeedbackProcessor(InjectionRecordRepository injectionRecordRepository,
                             SemanticMemory semanticMemory,
                             MessageFeedbackRepository feedbackRepository,
                             AgentLearningProperties properties) {
        this.injectionRecordRepository = Objects.requireNonNull(
                injectionRecordRepository, "injectionRecordRepository 不能为空");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository 不能为空");
        this.feedbackConfig = Objects.requireNonNull(
                Objects.requireNonNull(properties, "properties 不能为空").getFeedback(),
                "feedbackConfig 不能为空");
    }

    /**
     * 处理针对助手 transcript 条目的用户反馈。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @param feedbackType 反馈类型，'like' 或 'dislike'
     */
    public void processFeedbackForEntry(String assistantEntryId, String feedbackType) {
        if (assistantEntryId == null || assistantEntryId.isBlank()) {
            throw new IllegalArgumentException("assistantEntryId 不能为空");
        }
        if (!assistantEntryId.equals(assistantEntryId.trim())) {
            throw new IllegalArgumentException("assistantEntryId 不能包含首尾空白: " + assistantEntryId);
        }
        validateFeedbackType(feedbackType);
        List<String> entityIds = injectionRecordRepository.findEntityIdsBySourceEntryId(assistantEntryId);
        if (entityIds == null) {
            throw new IllegalStateException("注入记录查询结果不能为空: assistantEntryId=" + assistantEntryId);
        }
        if (entityIds.isEmpty()) {
            log.debug("无注入记录，跳过反馈处理: assistantEntryId={}", assistantEntryId);
            return;
        }
        validateEntityIds(entityIds, assistantEntryId);

        var existingFeedbacks = feedbackRepository.findByEntryId(assistantEntryId);
        if (existingFeedbacks == null) {
            throw new IllegalStateException("反馈历史查询结果不能为空: assistantEntryId=" + assistantEntryId);
        }
        if (existingFeedbacks.size() > 1) {
            var previousFeedback = existingFeedbacks.get(existingFeedbacks.size() - 2);
            String previousType = requiredHistoryFeedbackType(previousFeedback, assistantEntryId);
            if (feedbackType.equals(previousType)) {
                log.debug("同类型重复反馈，跳过调整: assistantEntryId={}, type={}", assistantEntryId, feedbackType);
                return;
            }
            float rollbackDelta = computeDelta(previousType) * -1;
            applyDelta(entityIds, rollbackDelta, assistantEntryId, "回滚(" + previousType + ")");
        }

        float delta = computeDelta(feedbackType);
        applyDelta(entityIds, delta, assistantEntryId, feedbackType);
    }

    private float computeDelta(String feedbackType) {
        float likeBoost = requireScoreDelta(feedbackConfig.getLikeBoost(), "likeBoost");
        float dislikePenalty = requireScoreDelta(feedbackConfig.getDislikePenalty(), "dislikePenalty");
        return switch (feedbackType) {
            case "like" -> likeBoost;
            case "dislike" -> -dislikePenalty;
            default -> throw new IllegalArgumentException("非法反馈类型: " + feedbackType);
        };
    }

    private void applyDelta(List<String> entityIds,
                            float delta,
                            String assistantEntryId,
                            String reason) {
        Map<String, TemporalEntity> entities = semanticMemory.findByIds(entityIds);
        if (entities == null) {
            throw new IllegalStateException("反馈关联实体查询结果不能为空: assistantEntryId=" + assistantEntryId);
        }
        var missingIds = entityIds.stream()
                .filter(entityId -> entities.get(entityId) == null)
                .toList();
        if (!missingIds.isEmpty()) {
            throw new IllegalStateException(
                    "反馈关联实体不存在: assistantEntryId=" + assistantEntryId
                            + ", entityIds=" + missingIds);
        }
        for (String entityId : entityIds) {
            TemporalEntity entity = entities.get(entityId);
            float oldScore = entity.importanceScore();
            if (!Float.isFinite(oldScore) || oldScore < 0.0f || oldScore > 1.0f) {
                throw new IllegalStateException("反馈关联实体 importanceScore 必须在 [0,1] 范围内: entityId="
                        + entityId + ", score=" + oldScore);
            }
            float newScore = Math.max(0.0f, Math.min(1.0f, oldScore + delta));
            SqliteBusyRetry.run(() -> semanticMemory.updateImportanceScore(
                    entityId, newScore, WeightSource.USER_FEEDBACK));
            log.debug("importanceScore 调整: entityId={}, assistantEntryId={}, reason={}, {} -> {}",
                    entityId, assistantEntryId, reason, oldScore, newScore);
        }
    }

    private static void validateFeedbackType(String feedbackType) {
        if (!"like".equals(feedbackType) && !"dislike".equals(feedbackType)) {
            throw new IllegalArgumentException("非法反馈类型: " + feedbackType);
        }
    }

    private static void validateEntityIds(List<String> entityIds, String assistantEntryId) {
        for (String entityId : entityIds) {
            if (entityId == null || entityId.isBlank()) {
                throw new IllegalStateException("注入记录包含空实体 ID: assistantEntryId=" + assistantEntryId);
            }
            if (!entityId.equals(entityId.trim())) {
                throw new IllegalStateException("注入记录实体 ID 不能包含首尾空白: assistantEntryId="
                        + assistantEntryId + ", entityId=" + entityId);
            }
        }
    }

    private static String requiredHistoryFeedbackType(Map<String, Object> feedback, String assistantEntryId) {
        if (feedback == null) {
            throw new IllegalStateException("反馈历史包含 null 记录: assistantEntryId=" + assistantEntryId);
        }
        Object rawType = feedback.get("type");
        if (!(rawType instanceof String type)) {
            throw new IllegalStateException("反馈历史缺少 type: assistantEntryId=" + assistantEntryId);
        }
        validateFeedbackType(type);
        return type;
    }

    private static float requireScoreDelta(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException("反馈配置 " + name + " 必须在 [0,1] 范围内: " + value);
        }
        return value;
    }
}
