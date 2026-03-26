package com.lifepilot.memory.feedback;

import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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
    private final MemoryProperties.Feedback feedbackConfig;

    public FeedbackProcessor(InjectionRecordRepository injectionRecordRepository,
                             SemanticMemory semanticMemory,
                             MessageFeedbackRepository feedbackRepository,
                             MemoryProperties properties) {
        this.injectionRecordRepository = injectionRecordRepository;
        this.semanticMemory = semanticMemory;
        this.feedbackRepository = feedbackRepository;
        this.feedbackConfig = properties.getFeedback();
    }

    /**
     * 处理针对助手 transcript 条目的用户反馈。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @param feedbackType 反馈类型，'like' 或 'dislike'
     */
    public void processFeedbackForEntry(String assistantEntryId, String feedbackType) {
        List<String> entityIds = injectionRecordRepository.findEntityIdsBySourceEntryId(assistantEntryId);
        if (entityIds.isEmpty()) {
            log.debug("无注入记录，跳过反馈处理: assistantEntryId={}", assistantEntryId);
            return;
        }

        var existingFeedbacks = feedbackRepository.findByEntryId(assistantEntryId);
        if (existingFeedbacks.size() > 1) {
            var previousFeedback = existingFeedbacks.get(existingFeedbacks.size() - 2);
            String previousType = (String) previousFeedback.get("type");
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
        return "like".equals(feedbackType)
                ? feedbackConfig.getLikeBoost()
                : -feedbackConfig.getDislikePenalty();
    }

    private void applyDelta(List<String> entityIds,
                            float delta,
                            String assistantEntryId,
                            String reason) {
        Map<String, TemporalEntity> entities = semanticMemory.findByIds(entityIds);
        for (String entityId : entityIds) {
            TemporalEntity entity = entities.get(entityId);
            if (entity == null) {
                log.debug("实体不存在，跳过: entityId={}", entityId);
                continue;
            }
            float oldScore = entity.importanceScore();
            float newScore = Math.max(0.0f, Math.min(1.0f, oldScore + delta));
            semanticMemory.updateImportanceScore(entityId, newScore);
            log.debug("importanceScore 调整: entityId={}, assistantEntryId={}, reason={}, {} -> {}",
                    entityId, assistantEntryId, reason, oldScore, newScore);
        }
    }
}
