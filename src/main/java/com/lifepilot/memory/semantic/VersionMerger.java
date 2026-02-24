package com.lifepilot.memory.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * 版本合并器 — 将新实体信息与已有实体合并生成新版本。
 *
 * <p>合并规则：
 * <ul>
 *   <li>新属性直接添加</li>
 *   <li>冲突属性按 extractionConfidence 决定保留</li>
 *   <li>description 取更长者</li>
 *   <li>无变化时 isNewVersion=false</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VersionMerger {

    private static final Logger log = LoggerFactory.getLogger(VersionMerger.class);

    /**
     * 合并新实体信息到已有实体。
     *
     * @param existing       已有实体
     * @param incoming       新实体信息
     * @param conversationId 来源会话 ID
     * @return 合并结果
     */
    public MergeResult merge(TemporalEntity existing, TemporalEntity incoming, String conversationId) {
        var conflicts = new LinkedHashMap<String, ConflictDetail>();
        var mergedProperties = new HashMap<>(existing.properties());
        boolean hasChanges = false;

        // 合并属性
        for (var entry : incoming.properties().entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            Object oldValue = mergedProperties.get(key);

            if (oldValue == null) {
                // 新属性直接添加
                mergedProperties.put(key, newValue);
                hasChanges = true;
            } else if (!Objects.equals(oldValue, newValue)) {
                // 冲突属性按 extractionConfidence 决定
                ConflictResolution resolution;
                if (incoming.extractionConfidence() > existing.extractionConfidence()) {
                    resolution = ConflictResolution.KEEP_NEW;
                    mergedProperties.put(key, newValue);
                } else if (incoming.extractionConfidence() < existing.extractionConfidence()) {
                    resolution = ConflictResolution.KEEP_OLD;
                    // 保留旧值，不修改
                } else {
                    resolution = ConflictResolution.KEEP_BOTH;
                    // 置信度相同时保留旧值
                }
                conflicts.put(key, new ConflictDetail(key, oldValue, newValue, resolution));
                hasChanges = true;
            }
        }

        // 合并 description：取更长者
        String mergedDescription = existing.description();
        if (incoming.description() != null) {
            int existingLen = existing.description() != null ? existing.description().length() : 0;
            if (incoming.description().length() > existingLen) {
                mergedDescription = incoming.description();
                hasChanges = true;
            }
        }

        if (!hasChanges) {
            log.debug("版本合并: 无变化, name={}, type={}", existing.name(), existing.type());
            return new MergeResult(existing, conflicts, false);
        }

        var now = Instant.now();
        var mergedEntity = new TemporalEntity(
                existing.id(),
                existing.type(),
                existing.name(),
                mergedDescription,
                mergedProperties,
                existing.version() + 1,
                true,
                now,
                null,
                conversationId,
                Math.max(existing.extractionConfidence(), incoming.extractionConfidence()),
                Math.max(existing.importanceScore(), incoming.importanceScore()),
                existing.accessCount(),
                existing.lastAccessedAt(),
                existing.createdAt(),
                now
        );

        log.debug("版本合并: 创建新版本, name={}, version={}", mergedEntity.name(), mergedEntity.version());
        return new MergeResult(mergedEntity, conflicts, true);
    }
}
