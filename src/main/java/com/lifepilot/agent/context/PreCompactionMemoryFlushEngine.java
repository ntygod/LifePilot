package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.memory.store.document.MemoryDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PreCompactionMemoryFlushEngine 负责在压缩前把即将被"让位"的 transcript 片段刷出到持久化记忆（durable memory）。
 * <p>当 {@link CompactionEngine} 确定要压缩某段对话后，会先调用本引擎将待淘汰的条目转化为
 * Markdown 格式的记忆文档存入 {@code memory_document} 表，并在 transcript 中追加一条
 * {@code MEMORY_FLUSH_EVENT} 作为刷出凭证。</p>
 * <p>这样即使压缩摘要丢失了细节，Agent 仍可通过记忆文档回查完整的原始片段。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class PreCompactionMemoryFlushEngine {

    private static final Logger log = LoggerFactory.getLogger(PreCompactionMemoryFlushEngine.class);

    /** 记忆文档分块的最大字符数，过长的文档按此大小切分为多个 chunk 便于检索 */
    private static final int CHUNK_MAX_CHARS = 1200;
    /** 文档路径键中时间戳的格式，使用 UTC 保证全局一致性 */
    private static final DateTimeFormatter PATH_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** 记忆文档的持久化存储 */
    private final MemoryDocumentRepository memoryDocumentRepository;
    /** 用于追加 MEMORY_FLUSH_EVENT 条目 */
    private final SessionTranscriptRepository transcriptRepository;
    /** 用于更新会话的记忆刷出时间戳 */
    private final SessionStoreRepository sessionStoreRepository;
    private final ObjectMapper objectMapper;

    public PreCompactionMemoryFlushEngine(MemoryDocumentRepository memoryDocumentRepository,
                                          SessionTranscriptRepository transcriptRepository,
                                          SessionStoreRepository sessionStoreRepository,
                                          ObjectMapper objectMapper) {
        this.memoryDocumentRepository = Objects.requireNonNull(memoryDocumentRepository);
        this.transcriptRepository = Objects.requireNonNull(transcriptRepository);
        this.sessionStoreRepository = Objects.requireNonNull(sessionStoreRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * 将指定的 transcript 条目刷出到持久化记忆文档。
     * <p>完整流程：
     * <ol>
     *   <li>将条目渲染为 Markdown 格式的记忆文档内容</li>
     *   <li>通过 {@link MemoryDocumentRepository#upsert} 写入文档主体</li>
     *   <li>将文档按 {@link #CHUNK_MAX_CHARS} 切分为检索分块</li>
     *   <li>在 transcript 中追加 {@code MEMORY_FLUSH_EVENT} 条目作为刷出凭证</li>
     *   <li>更新会话的记忆刷出时间戳</li>
     * </ol></p>
     *
     * @param sessionId 会话 ID
     * @param traceId   可选的链路追踪 ID
     * @param rows      即将被压缩淘汰的 transcript 条目列表
     * @return 生成的记忆文档 ID 列表（当前实现为单文档，列表长度为 1）；失败时返回空列表
     */
    public List<String> flush(String sessionId,
                              @Nullable String traceId,
                              List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        try {
            Instant now = Instant.now();
            String title = "压缩前会话片段";
            // 生成文档的路径键，格式：sessions/{sessionId}/flush/{时间戳}-{短ID}
            String pathLikeKey = buildPathLikeKey(sessionId, now, rows);
            // 将所有待刷出条目渲染为 Markdown 文档
            String contentMarkdown = buildMarkdown(sessionId, rows);

            // ── 第 1 步：写入记忆文档主体 ──
            String documentId = memoryDocumentRepository.upsert(
                    "transcript",
                    "pre_compaction_flush",
                    title,
                    pathLikeKey,
                    contentMarkdown,
                    sessionId,
                    rows.getFirst().id(),
                    now
            );
            // ── 第 2 步：按固定大小切分文档为检索分块 ──
            memoryDocumentRepository.replaceChunks(documentId, splitChunks(contentMarkdown), now);

            // ── 第 3 步：在 transcript 中追加刷出事件作为凭证 ──
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documentIds", List.of(documentId));
            payload.put("sourceEntryCount", rows.size());
            payload.put("sourceStartEntryId", rows.getFirst().id());
            payload.put("sourceEndEntryId", rows.getLast().id());
            transcriptRepository.appendEntry(
                    sessionId,
                    TranscriptEntryType.MEMORY_FLUSH_EVENT,
                    null,
                    null,
                    normalizeBlank(traceId),
                    false,
                    false,
                    payload,
                    now
            );
            // ── 第 4 步：更新会话级别的刷出时间戳 ──
            sessionStoreRepository.updateMemoryFlushAt(sessionId, now);
            return List.of(documentId);
        } catch (Exception e) {
            // 刷出为非关键路径，失败后静默降级，不阻断压缩流程
            log.warn("压缩前记忆刷出失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 将待刷出的 transcript 条目渲染为 Markdown 格式的记忆文档。
     * <p>文档结构：
     * <pre>
     * # 压缩前会话片段
     * - 会话ID / 条目数 / 首末时间 等元信息
     *
     * ## 原始片段
     * - [角色/类型] 内容（逐条列出）
     * </pre></p>
     */
    private String buildMarkdown(String sessionId,
                                 List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        StringBuilder buffer = new StringBuilder();
        // 文档头：元信息摘要
        buffer.append("# 压缩前会话片段\n\n")
                .append("- 会话ID: ").append(sessionId).append('\n')
                .append("- 条目数: ").append(rows.size()).append('\n')
                .append("- 首条时间: ").append(rows.getFirst().createdAt()).append('\n')
                .append("- 末条时间: ").append(rows.getLast().createdAt()).append("\n\n")
                .append("## 原始片段\n");

        // 逐条格式化并追加到文档体
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            String line = formatRow(row);
            if (!line.isBlank()) {
                buffer.append("- ").append(line).append('\n');
            }
        }
        return buffer.toString().trim();
    }

    /**
     * 将单条 transcript 条目格式化为记忆文档中的一行文本。
     * <p>按条目类型分派，输出格式统一为 {@code [类型标签] 内容预览}，
     * 与 {@link CompactionEngine#formatRow} 逻辑一致，但此处用于持久化存储而非 prompt 输入。</p>
     */
    private String formatRow(SessionTranscriptRepository.SessionTranscriptEntryRow row) {
        Map<String, Object> payload = readPayload(row.payloadJson());
        return switch (TranscriptEntryType.fromValue(row.entryType())) {
            // 用户/助手/系统消息 → [角色] 消息内容
            case USER_MESSAGE, ASSISTANT_MESSAGE, SYSTEM_EVENT -> {
                String content = stringValue(payload.get("content"));
                if (content == null || content.isBlank()) {
                    yield "";
                }
                yield "[" + normalizeRole(row.role()) + "] " + normalizeWhitespace(content);
            }
            // 工具调用 → [tool_call:工具ID] 输入参数预览
            case TOOL_CALL -> {
                String toolId = stringValue(payload.get("toolId"));
                String inputJson = stringValue(payload.get("inputJson"));
                yield "[tool_call:" + defaultValue(toolId, "tool") + "] 输入: "
                        + abbreviate(extractPayloadPreview(inputJson), 400);
            }
            // 工具结果 → [tool_result:工具ID] 成功/失败: 结果预览
            case TOOL_RESULT -> {
                String toolId = stringValue(payload.get("toolId"));
                String outputJson = stringValue(payload.get("outputJson"));
                boolean success = booleanValue(payload.get("success"));
                yield "[tool_result:" + defaultValue(toolId, "tool") + "] "
                        + (success ? "成功" : "失败") + ": "
                        + abbreviate(extractPayloadPreview(outputJson), 400);
            }
            // 产物引用 → [artifact:类型] 标题: 摘要
            case ARTIFACT_REF -> {
                String artifactType = stringValue(payload.get("artifactType"));
                String title = stringValue(payload.get("title"));
                String summary = stringValue(payload.get("summary"));
                yield "[artifact:" + defaultValue(artifactType, "artifact") + "] "
                        + defaultValue(title, "未命名产物")
                        + (summary != null && !summary.isBlank() ? ": " + normalizeWhitespace(summary) : "");
            }
            // 兜底：直接序列化 payload
            default -> "[" + row.entryType() + "] " + normalizeWhitespace(payload.toString());
        };
    }

    /**
     * 将 Markdown 文档按固定字符数切分为检索分块。
     * <p>采用简单的等长切分策略（按 {@link #CHUNK_MAX_CHARS}），
     * 切分后的 chunk 用于向量化存储和语义检索。</p>
     */
    private List<String> splitChunks(String contentMarkdown) {
        if (contentMarkdown == null || contentMarkdown.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < contentMarkdown.length(); start += CHUNK_MAX_CHARS) {
            int end = Math.min(contentMarkdown.length(), start + CHUNK_MAX_CHARS);
            chunks.add(contentMarkdown.substring(start, end));
        }
        return List.copyOf(chunks);
    }

    /**
     * 生成记忆文档的路径键，格式：{@code sessions/{sessionId}/flush/{时间戳}-{短ID}}。
     * <p>路径键用作文档的唯一标识，同一会话的多次刷出按时间排列便于追溯。</p>
     */
    private String buildPathLikeKey(String sessionId,
                                    Instant createdAt,
                                    List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        return "sessions/" + sanitizeKey(sessionId)
                + "/flush/" + PATH_TIME_FORMATTER.format(createdAt)
                + "-" + shortId(rows.getLast().id());
    }

    /** 清理路径键中的非法字符，只保留字母、数字、点、横线和下划线。 */
    private String sanitizeKey(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 截取 ID 的前 8 个字符作为短标识，用于路径键的可读性。 */
    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    // ──────────────────────────────────────────────────────
    // payload 解析与预览提取
    // ──────────────────────────────────────────────────────

    /** 反序列化 payload JSON 字符串为 Map，解析失败时返回空 Map 而非抛异常。 */
    private Map<String, Object> readPayload(@Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() { });
            return payload != null ? payload : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /**
     * 从 JSON 或纯文本中提取可读预览。
     * <p>如果输入是合法 JSON，先解析为对象再通过 {@link #summarizeObject} 提取语义字段；
     * 解析失败则直接当作纯文本返回。</p>
     */
    private String extractPayloadPreview(@Nullable String jsonOrText) {
        if (jsonOrText == null || jsonOrText.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(jsonOrText, Object.class);
            return normalizeWhitespace(summarizeObject(parsed));
        } catch (Exception e) {
            // 非 JSON，当作纯文本处理
            return normalizeWhitespace(jsonOrText);
        }
    }

    /**
     * 递归提取对象中最具语义的文本表示。
     * <p>优先级：
     * <ul>
     *   <li>Map 类型 → 按优先级查找 summary/content/message/result 等语义字段</li>
     *   <li>List 类型 → 取前 4 个元素用分号拼接</li>
     *   <li>String/其他 → 直接转字符串</li>
     * </ul></p>
     */
    private String summarizeObject(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            // 按优先级查找最具语义的字段
            for (String key : List.of("summary", "content", "message", "result", "text", "output", "title")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return summarizeObject(candidate);
                }
            }
            // 无语义字段，序列化整个 Map 作为兜底
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return map.toString();
            }
        }
        if (value instanceof List<?> list) {
            // 列表取前 4 个元素，避免预览过长
            return list.stream()
                    .limit(4)
                    .map(this::summarizeObject)
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
        return value.toString();
    }

    // ──────────────────────────────────────────────────────
    // 通用工具方法
    // ──────────────────────────────────────────────────────

    /** 截断文本到指定最大字符数，超长时追加 "..." 后缀。 */
    private String abbreviate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    /** 安全地将 payload 中的值转换为 boolean，null 或无法解析时返回 false。 */
    private boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    /** 安全地将 payload 中的值转换为 String，null 或空白时返回 null。 */
    @Nullable
    private String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 将连续空白字符（空格、换行、制表符等）合并为单个空格。 */
    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** 规范化角色名称为小写，null 或空白时返回 "message" 作为默认标签。 */
    private String normalizeRole(@Nullable String role) {
        return role == null || role.isBlank() ? "message" : role.trim().toLowerCase();
    }

    /** 返回值本身，null 或空白时返回指定的 fallback 默认值。 */
    private String defaultValue(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 将空白字符串规范化为 null，用于可选字段的存储。 */
    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
