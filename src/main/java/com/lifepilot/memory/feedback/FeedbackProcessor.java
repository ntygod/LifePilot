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
 * 反馈处理器 — 消费 message_feedback 数据，调整关联记忆实体的 importanceScore。
 *
 * <p>处理流程：查注入记录 → 幂等性检查 → 计算 delta → 批量更新 importanceScore。
 * 支持反馈类型切换时的回滚 + 重新调整。</p>
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
     * 处理用户反馈，调整关联实体的 importanceScore。
     *
     * @param messageId    AI 回复消息 ID
     * @param feedbackType 反馈类型（'like' 或 'dislike'）
     */
    public void processFeedback(String messageId, String feedbackType) {
        // 1. 查注入记录
        List<String> entityIds = injectionRecordRepository.findEntityIdsByMessageId(messageId);
        if (entityIds.isEmpty()) {
            log.debug("无注入记录，跳过反馈处理: messageId={}", messageId);
            return;
        }

        // 2. 幂等性检查 — 查询已有反馈记录
        var existingFeedbacks = feedbackRepository.findByMessageId(messageId);
        if (existingFeedbacks.size() > 1) {
            // 存在历史反馈（当前反馈已保存，所以 >1 表示有旧反馈）
            var previousFeedback = existingFeedbacks.get(existingFeedbacks.size() - 2);
            String previousType = (String) previousFeedback.get("type");
            if (feedbackType.equals(previousType)) {
                log.debug("同类型重复反馈，跳过调整: messageId={}, type={}", messageId, feedbackType);
                return;
            }
            // 不同类型 → 先回滚上次调整
            float rollbackDelta = computeDelta(previousType) * -1;
            applyDelta(entityIds, rollbackDelta, messageId, "回滚(" + previousType + ")");
        }

        // 3. 应用当前反馈调整
        float delta = computeDelta(feedbackType);
        applyDelta(entityIds, delta, messageId, feedbackType);
    }

    /** 根据反馈类型计算 delta。 */
    private float computeDelta(String feedbackType) {
        return "like".equals(feedbackType)
                ? feedbackConfig.getLikeBoost()
                : -feedbackConfig.getDislikePenalty();
    }

    /** 批量应用 delta 到实体的 importanceScore，裁剪到 [0.0, 1.0]。 */
    private void applyDelta(List<String> entityIds, float delta, String messageId, String reason) {
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
            log.debug("importanceScore 调整: entityId={}, messageId={}, reason={}, {} -> {}",
                    entityId, messageId, reason, oldScore, newScore);
        }
    }
}
