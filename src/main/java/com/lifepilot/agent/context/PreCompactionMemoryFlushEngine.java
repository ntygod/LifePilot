package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.memory.document.MemoryDocumentRepository;
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
 * PreCompactionMemoryFlushEngine 负责在压缩前把即将隐藏的 transcript 片段刷出到 durable memory。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class PreCompactionMemoryFlushEngine {

    private static final Logger log = LoggerFactory.getLogger(PreCompactionMemoryFlushEngine.class);

    private static final int CHUNK_MAX_CHARS = 1200;
    private static final DateTimeFormatter PATH_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final MemoryDocumentRepository memoryDocumentRepository;
    private final SessionTranscriptRepository transcriptRepository;
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

    public List<String> flush(String sessionId,
                              @Nullable String traceId,
                              List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        try {
            Instant now = Instant.now();
            String title = "压缩前会话片段";
            String pathLikeKey = buildPathLikeKey(sessionId, now, rows);
            String contentMarkdown = buildMarkdown(sessionId, rows);

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
            memoryDocumentRepository.replaceChunks(documentId, splitChunks(contentMarkdown), now);

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
            sessionStoreRepository.updateMemoryFlushAt(sessionId, now);
            return List.of(documentId);
        } catch (Exception e) {
            log.warn("压缩前记忆刷出失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private String buildMarkdown(String sessionId,
                                 List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("# 压缩前会话片段\n\n")
                .append("- 会话ID: ").append(sessionId).append('\n')
                .append("- 条目数: ").append(rows.size()).append('\n')
                .append("- 首条时间: ").append(rows.getFirst().createdAt()).append('\n')
                .append("- 末条时间: ").append(rows.getLast().createdAt()).append("\n\n")
                .append("## 原始片段\n");

        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            String line = formatRow(row);
            if (!line.isBlank()) {
                buffer.append("- ").append(line).append('\n');
            }
        }
        return buffer.toString().trim();
    }

    private String formatRow(SessionTranscriptRepository.SessionTranscriptEntryRow row) {
        Map<String, Object> payload = readPayload(row.payloadJson());
        return switch (TranscriptEntryType.fromValue(row.entryType())) {
            case USER_MESSAGE, ASSISTANT_MESSAGE, SYSTEM_EVENT -> {
                String content = stringValue(payload.get("content"));
                if (content == null || content.isBlank()) {
                    yield "";
                }
                yield "[" + normalizeRole(row.role()) + "] " + normalizeWhitespace(content);
            }
            case TOOL_CALL -> {
                String toolId = stringValue(payload.get("toolId"));
                String inputJson = stringValue(payload.get("inputJson"));
                yield "[tool_call:" + defaultValue(toolId, "tool") + "] 输入: "
                        + abbreviate(extractPayloadPreview(inputJson), 400);
            }
            case TOOL_RESULT -> {
                String toolId = stringValue(payload.get("toolId"));
                String outputJson = stringValue(payload.get("outputJson"));
                boolean success = booleanValue(payload.get("success"));
                yield "[tool_result:" + defaultValue(toolId, "tool") + "] "
                        + (success ? "成功" : "失败") + ": "
                        + abbreviate(extractPayloadPreview(outputJson), 400);
            }
            case ARTIFACT_REF -> {
                String artifactType = stringValue(payload.get("artifactType"));
                String title = stringValue(payload.get("title"));
                String summary = stringValue(payload.get("summary"));
                yield "[artifact:" + defaultValue(artifactType, "artifact") + "] "
                        + defaultValue(title, "未命名产物")
                        + (summary != null && !summary.isBlank() ? ": " + normalizeWhitespace(summary) : "");
            }
            default -> "[" + row.entryType() + "] " + normalizeWhitespace(payload.toString());
        };
    }

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

    private String buildPathLikeKey(String sessionId,
                                    Instant createdAt,
                                    List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        return "sessions/" + sanitizeKey(sessionId)
                + "/flush/" + PATH_TIME_FORMATTER.format(createdAt)
                + "-" + shortId(rows.getLast().id());
    }

    private String sanitizeKey(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

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

    private String extractPayloadPreview(@Nullable String jsonOrText) {
        if (jsonOrText == null || jsonOrText.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(jsonOrText, Object.class);
            return normalizeWhitespace(summarizeObject(parsed));
        } catch (Exception e) {
            return normalizeWhitespace(jsonOrText);
        }
    }

    private String summarizeObject(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("summary", "content", "message", "result", "text", "output", "title")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return summarizeObject(candidate);
                }
            }
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return map.toString();
            }
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .limit(4)
                    .map(this::summarizeObject)
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
        return value.toString();
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    @Nullable
    private String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String normalizeRole(@Nullable String role) {
        return role == null || role.isBlank() ? "message" : role.trim().toLowerCase();
    }

    private String defaultValue(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
