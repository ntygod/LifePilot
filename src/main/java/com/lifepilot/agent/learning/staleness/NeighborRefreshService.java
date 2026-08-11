package com.lifepilot.agent.learning.staleness;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 邻居刷新服务 —— 为被标记为 STALE_CANDIDATE 的邻居生成"刷新候选"。
 *
 * <p>首版实现：</p>
 * <ul>
 *     <li>配置 {@code neighbor-refresh-enabled} 默认 {@code false}，只有显式开启才会触发</li>
 *     <li>开启时记录结构化日志（便于后续用 log → trace 消费）；
 *         真正写入 {@code memory_extraction_candidates} 留到后续 spec 的 Consolidator 类组件消费</li>
 * </ul>
 *
 * <p>这样既给出了"邻居回链"的入口，又避免本 spec 绕过现有 AudnDecision 候选写入契约。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class NeighborRefreshService {

    private static final Logger log = LoggerFactory.getLogger(NeighborRefreshService.class);

    private final AgentLearningProperties.Staleness config;

    public NeighborRefreshService(AgentLearningProperties.Staleness config) {
        this.config = Objects.requireNonNull(config, "staleness 配置不能为空");
    }

    /**
     * @return 生成的刷新候选数量（开关关闭时返回 0）
     */
    public int writeRefreshCandidates(TemporalEntity triggeringEntity,
                                      List<TemporalEntity> neighbors) {
        if (!config.isNeighborRefreshEnabled()) return 0;
        Objects.requireNonNull(triggeringEntity, "触发实体不能为空");
        String triggeringId = requireCleanText(triggeringEntity.id(), "触发实体 ID 不能为空");
        Objects.requireNonNull(neighbors, "邻居列表不能为空");
        if (neighbors.isEmpty()) return 0;
        int count = 0;
        for (TemporalEntity neighbor : neighbors) {
            Objects.requireNonNull(neighbor, "邻居不能为空");
            String neighborId = requireCleanText(neighbor.id(), "邻居 ID 不能为空");
            if (neighbor.type() == null) {
                throw new IllegalStateException("邻居类型不能为空: id=" + neighborId);
            }
            log.info("neighbor-refresh-candidate: triggering={} neighbor={} neighborType={} suggested={}",
                    triggeringId, neighborId,
                    neighbor.type().name(),
                    buildSuggestedDescription(triggeringEntity, neighbor));
            count++;
        }
        return count;
    }

    private static String buildSuggestedDescription(TemporalEntity trigger, TemporalEntity neighbor) {
        String trig = descriptionOrName(trigger, "触发实体");
        String orig = descriptionOrName(neighbor, "邻居");
        return "原事实: " + safeTrim(orig, 80) + "；新事实暗示: " + safeTrim(trig, 80);
    }

    private static String descriptionOrName(TemporalEntity entity, String label) {
        if (entity.description() != null && !entity.description().isBlank()) {
            return entity.description();
        }
        return requireCleanText(entity.name(), label + "名称不能为空");
    }

    private static String safeTrim(String s, int max) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    private static String requireCleanText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(message + "，且不能包含首尾空白: " + value);
        }
        return value;
    }
}
