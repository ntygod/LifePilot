package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.task.proactive.QueuedActionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 排队动作 DTO，映射自 {@link QueuedActionRecord}。
 *
 * @param id        唯一标识
 * @param behavior  产出此动作的行为插件名称
 * @param topicKey  主题键
 * @param title     主题标题
 * @param content   消息内容
 * @param score     候选分数
 * @param metadata  扩展元数据（解析后的 Map）
 * @param shown     是否已展示
 * @param createdAt 创建时间
 * @param shownAt   展示时间
 * @author zsg
 * @since 2026-04-15
 */
public record QueuedActionDto(
        String id,
        String behavior,
        String topicKey,
        String title,
        String content,
        float score,
        @Nullable Map<String, Object> metadata,
        boolean shown,
        Instant createdAt,
        @Nullable Instant shownAt
) {

    private static final Logger log = LoggerFactory.getLogger(QueuedActionDto.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 {@link QueuedActionRecord} 转换为 DTO。
     *
     * @param record 排队动作记录
     * @return 排队动作 DTO
     */
    public static QueuedActionDto from(QueuedActionRecord record) {
        return new QueuedActionDto(
                record.id(),
                record.behavior(),
                record.topicKey(),
                record.title(),
                record.content(),
                record.score(),
                parseMetadata(record.metadata()),
                record.shown(),
                record.createdAt(),
                record.shownAt()
        );
    }

    /**
     * 解析 JSON 格式的 metadata 字符串为 Map。
     */
    @Nullable
    private static Map<String, Object> parseMetadata(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("解析排队动作元数据失败: metadata={}, error={}", metadataJson, e.getMessage());
            return null;
        }
    }
}
