package com.lifepilot.agent.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具结果媒体数据提取器。
 *
 * <p>从工具执行结果中检测并提取媒体数据（如截图 Base64），
 * 用于通过 SSE MEDIA 事件独立传输，避免 ToolCallStep 截断丢失。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class MediaDataExtractor {

    private static final Logger log = LoggerFactory.getLogger(MediaDataExtractor.class);

    /** 已知的媒体字段映射：字段名 → mediaType。 */
    private static final Map<String, String> KNOWN_MEDIA_FIELDS = Map.of(
            "screenshot", "image/png"
    );

    /** 媒体字段最小长度阈值，低于此值视为普通文本而非 Base64 媒体数据。 */
    private static final int MIN_MEDIA_LENGTH = 1000;

    /** 媒体字段替换后的占位符。 */
    public static final String PLACEHOLDER = "[截图已成功获取，图片数据已嵌入到本次对话中供视觉分析]";

    private final ObjectMapper objectMapper;

    public MediaDataExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 提取结果。
     *
     * @param sanitizedOutput 替换媒体字段后的 output JSON
     * @param mediaItems      提取出的媒体数据列表
     */
    public record ExtractionResult(
            String sanitizedOutput,
            List<MediaItem> mediaItems
    ) {}

    /**
     * 单个媒体数据项。
     *
     * @param mediaType MIME 类型
     * @param encoding  编码方式（base64）
     * @param data      媒体数据
     * @param fieldName 原始字段名
     * @param metadata  附加元数据
     */
    public record MediaItem(
            String mediaType,
            String encoding,
            String data,
            String fieldName,
            Map<String, Object> metadata
    ) {}

    /**
     * 从工具输出 JSON 中提取媒体数据。
     *
     * <p>遍历已知媒体字段，检测 JSON 中是否存在对应字段且值长度超过阈值。
     * 提取媒体数据后，将原始字段值替换为占位符，返回清理后的 JSON 和媒体列表。</p>
     *
     * @param toolId     工具 ID
     * @param outputJson 工具原始输出 JSON
     * @return 提取结果（sanitizedOutput + mediaItems）
     */
    public ExtractionResult extract(String toolId, String outputJson) {
        if (outputJson == null || outputJson.isBlank()) {
            return new ExtractionResult(outputJson, List.of());
        }

        try {
            JsonNode root = objectMapper.readTree(outputJson);
            if (!root.isObject()) {
                return new ExtractionResult(outputJson, List.of());
            }

            ObjectNode objectNode = (ObjectNode) root;
            List<MediaItem> mediaItems = new ArrayList<>();

            // 收集非媒体字段作为 metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            objectNode.properties().forEach(entry -> {
                if (!KNOWN_MEDIA_FIELDS.containsKey(entry.getKey())) {
                    metadata.put(entry.getKey(), nodeToValue(entry.getValue()));
                }
            });

            // 检测并提取媒体字段
            for (var mediaEntry : KNOWN_MEDIA_FIELDS.entrySet()) {
                String fieldName = mediaEntry.getKey();
                String mediaType = mediaEntry.getValue();

                JsonNode fieldNode = objectNode.get(fieldName);
                if (fieldNode != null && fieldNode.isTextual()
                        && fieldNode.textValue().length() > MIN_MEDIA_LENGTH) {
                    mediaItems.add(new MediaItem(
                            mediaType,
                            "base64",
                            fieldNode.textValue(),
                            fieldName,
                            Map.copyOf(metadata)
                    ));
                    // 替换为占位符
                    objectNode.put(fieldName, PLACEHOLDER);
                }
            }

            if (mediaItems.isEmpty()) {
                return new ExtractionResult(outputJson, List.of());
            }

            String sanitized = objectMapper.writeValueAsString(objectNode);
            log.debug("媒体数据提取完成: toolId={}, 提取数量={}", toolId, mediaItems.size());
            return new ExtractionResult(sanitized, List.copyOf(mediaItems));

        } catch (Exception e) {
            log.warn("媒体数据提取失败，返回原始输出: toolId={}, error={}", toolId, e.getMessage());
            return new ExtractionResult(outputJson, List.of());
        }
    }

    /**
     * 将 JsonNode 转换为 Java 基本类型值。
     */
    private Object nodeToValue(JsonNode node) {
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble()) return node.doubleValue();
        return node.toString();
    }
}
