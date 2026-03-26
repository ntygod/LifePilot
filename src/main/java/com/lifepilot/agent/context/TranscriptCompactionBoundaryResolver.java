package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * TranscriptCompactionBoundaryResolver 负责解析 transcript 中最新的压缩边界。
 * <p>压缩边界通过最新 {@code compaction_summary} 条目的 {@code firstKeptEntryId}
 * 建模。读取上下文时只保留该边界之后的原始 transcript，再额外注入压缩摘要。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class TranscriptCompactionBoundaryResolver {

    private final ObjectMapper objectMapper;

    public record CompactionBoundary(
            String summaryEntryId,
            @Nullable String firstKeptEntryId,
            @Nullable String summary,
            Instant createdAt
    ) {
    }

    public TranscriptCompactionBoundaryResolver(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public Optional<CompactionBoundary> resolveLatest(
            @Nullable List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        return rows.stream()
                .filter(row -> TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType()))
                .reduce((first, second) -> second)
                .map(row -> {
                    Map<String, Object> payload = readPayload(row.payloadJson());
                    return new CompactionBoundary(
                            row.id(),
                            stringValue(payload.get("firstKeptEntryId")),
                            stringValue(payload.get("summary")),
                            row.createdAt()
                    );
                });
    }

    public List<SessionTranscriptRepository.SessionTranscriptEntryRow> filterRowsForActiveContext(
            @Nullable List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            @Nullable CompactionBoundary boundary
    ) {
        if (rows == null || rows.isEmpty() || boundary == null
                || boundary.firstKeptEntryId() == null || boundary.firstKeptEntryId().isBlank()) {
            return rows == null ? List.of() : List.copyOf(rows);
        }
        int boundaryIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (boundary.firstKeptEntryId().equals(rows.get(i).id())) {
                boundaryIndex = i;
                break;
            }
        }
        if (boundaryIndex < 0) {
            return List.copyOf(rows);
        }
        return List.copyOf(rows.subList(boundaryIndex, rows.size()));
    }

    public String renderSection(@Nullable CompactionBoundary boundary) {
        if (boundary == null || boundary.summary() == null || boundary.summary().isBlank()) {
            return "";
        }
        return "\n历史压缩摘要:\n" + boundary.summary().trim() + '\n';
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

    @Nullable
    private String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
