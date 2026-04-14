package com.lifepilot.agent.task.proactive.intent;

import com.lifepilot.memory.episodic.EpisodicMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 意图记忆服务 — 管理意图的提取、存储、检查和过期。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class IntentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(IntentMemoryService.class);
    private static final Duration EXTRACTION_LOOKBACK = Duration.ofDays(7);

    private final IntentRepository intentRepository;
    private final IntentExtractor intentExtractor;
    @Nullable
    private final EpisodicMemory episodicMemory;

    public IntentMemoryService(IntentRepository intentRepository,
                               IntentExtractor intentExtractor,
                               @Nullable EpisodicMemory episodicMemory) {
        this.intentRepository = intentRepository;
        this.intentExtractor = intentExtractor;
        this.episodicMemory = episodicMemory;
    }

    /** 获取用户活跃意图。 */
    public List<IntentRecord> getActiveIntents(String userId) {
        return intentRepository.findActiveByUserId(userId);
    }

    /** 保存新意图。 */
    public void saveIntent(IntentRecord intent) {
        intentRepository.save(intent);
        log.debug("意图已保存: type={}, goal={}", intent.intentType(), intent.goal());
    }

    /** 标记意图完成。 */
    public void fulfillIntent(String intentId) {
        intentRepository.updateStatus(intentId, IntentStatus.FULFILLED, Instant.now());
    }

    /** 递增检查计数。 */
    public void incrementCheckCount(String intentId) {
        intentRepository.incrementCheckCount(intentId);
    }

    /** 过期清理 — 在心跳中调用。 */
    public void expireStaleIntents() {
        var expired = intentRepository.findExpired(Instant.now());
        for (var intent : expired) {
            intentRepository.updateStatus(intent.id(), IntentStatus.EXPIRED, Instant.now());
            log.debug("意图已过期: id={}, goal={}", intent.id(), intent.goal());
        }
    }

    /**
     * 从近期对话中提取新意图 — 在心跳中调用。
     *
     * @return 新提取的意图数量
     */
    public int extractFromRecentConversations(String userId) {
        if (episodicMemory == null) return 0;

        try {
            var recent = episodicMemory.getRecent(EXTRACTION_LOOKBACK);
            var existingGoals = intentRepository.findActiveByUserId(userId).stream()
                    .map(IntentRecord::goal)
                    .toList();

            int count = 0;
            for (var conversation : recent) {
                var userMessages = conversation.messages().stream()
                        .filter(m -> "user".equals(m.role()))
                        .map(m -> m.content())
                        .toList();
                if (userMessages.isEmpty()) continue;

                var extracted = intentExtractor.extract(userId, conversation.sessionId(), userMessages);
                for (var intent : extracted) {
                    boolean duplicate = isDuplicateGoal(intent.goal(), existingGoals);
                    if (!duplicate) {
                        intentRepository.save(intent);
                        count++;
                    }
                }
            }
            if (count > 0) {
                log.info("意图提取完成: userId={}, newIntents={}", userId, count);
            }
            return count;
        } catch (Exception e) {
            log.debug("意图提取跳过: {}", e.getMessage());
            return 0;
        }
    }

    /** 简单去重：目标文本前缀匹配。 */
    private boolean isDuplicateGoal(String goal, List<String> existing) {
        String prefix = goal.substring(0, Math.min(4, goal.length()));
        return existing.stream().anyMatch(g -> g.contains(prefix)
                || prefix.length() >= 4 && goal.contains(g.substring(0, Math.min(4, g.length()))));
    }
}
