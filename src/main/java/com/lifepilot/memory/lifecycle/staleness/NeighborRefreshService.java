package com.lifepilot.memory.lifecycle.staleness;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    private final MemoryProperties.Staleness config;

    public NeighborRefreshService(MemoryProperties.Staleness config) {
        this.config = config;
    }

    /**
     * @return 生成的刷新候选数量（开关关闭时返回 0）
     */
    public int writeRefreshCandidates(TemporalEntity triggeringEntity,
                                      List<TemporalEntity> neighbors) {
        if (!config.isNeighborRefreshEnabled()) return 0;
        if (triggeringEntity == null || neighbors == null || neighbors.isEmpty()) return 0;
        int count = 0;
        for (TemporalEntity neighbor : neighbors) {
            if (neighbor == null) continue;
            // 日志式落地：触发实体 + 邻居 + 建议文本，后续由 Consolidator 消费
            log.info("neighbor-refresh-candidate: triggering={} neighbor={} neighborType={} suggested={}",
                    triggeringEntity.id(), neighbor.id(),
                    neighbor.type() == null ? "?" : neighbor.type().name(),
                    buildSuggestedDescription(triggeringEntity, neighbor));
            count++;
        }
        return count;
    }

    private static String buildSuggestedDescription(TemporalEntity trigger, TemporalEntity neighbor) {
        String trig = trigger.description() == null ? trigger.name() : trigger.description();
        String orig = neighbor.description() == null ? neighbor.name() : neighbor.description();
        return "原事实: " + safeTrim(orig, 80) + "；新事实暗示: " + safeTrim(trig, 80);
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
