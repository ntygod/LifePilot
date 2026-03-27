package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CompactionEngine 负责在 transcript-first 架构下生成会话压缩摘要。
 * <p>该组件只追加 {@code compaction_summary} 条目，不改写旧 transcript；
 * 真正的“让位”通过 {@code firstKeptEntryId} 在读取上下文时生效。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class CompactionEngine {

    private static final Logger log = LoggerFactory.getLogger(CompactionEngine.class);

    private static final int DEFAULT_TRIGGER_THRESHOLD_PERCENT = 75;
    private static final int DEFAULT_KEEP_RECENT_TURNS = 2;
    private static final int DEFAULT_MIN_TURN_COUNT = 6;
    private static final int DEFAULT_MAX_SOURCE_ENTRIES = 80;
    private static final int DEFAULT_SUMMARY_MAX_CHARS = 500;
    private static final int DEFAULT_PAYLOAD_PREVIEW_CHARS = 400;
    private static final int DEFAULT_KEYPOINT_LIMIT = 8;
    private static final int DEFAULT_ARTIFACT_REF_LIMIT = 5;
    private static final int DEFAULT_RESUME_PLAN_LIMIT = 3;

    private final AgentConfigProperties config;
    private final SessionTranscriptRepository transcriptRepository;
    private final SessionStoreRepository sessionStoreRepository;
    private final TranscriptCompactionBoundaryResolver boundaryResolver;
    private final PromptRegistry promptRegistry;
    private final GenerationRouter generationRouter;
    private final ObjectMapper objectMapper;
    @Nullable
    private final PreCompactionMemoryFlushEngine memoryFlushEngine;

    public CompactionEngine(AgentConfigProperties config,
                            SessionTranscriptRepository transcriptRepository,
                            SessionStoreRepository sessionStoreRepository,
                            TranscriptCompactionBoundaryResolver boundaryResolver,
                            PromptRegistry promptRegistry,
                            GenerationRouter generationRouter,
                            ObjectMapper objectMapper) {
        this(config, transcriptRepository, sessionStoreRepository, boundaryResolver,
                promptRegistry, generationRouter, objectMapper, null);
    }

    public CompactionEngine(AgentConfigProperties config,
                            SessionTranscriptRepository transcriptRepository,
                            SessionStoreRepository sessionStoreRepository,
                            TranscriptCompactionBoundaryResolver boundaryResolver,
                            PromptRegistry promptRegistry,
                            GenerationRouter generationRouter,
                            ObjectMapper objectMapper,
                            @Nullable PreCompactionMemoryFlushEngine memoryFlushEngine) {
        this.config = Objects.requireNonNull(config);
        this.transcriptRepository = Objects.requireNonNull(transcriptRepository);
        this.sessionStoreRepository = Objects.requireNonNull(sessionStoreRepository);
        this.boundaryResolver = Objects.requireNonNull(boundaryResolver);
        this.promptRegistry = Objects.requireNonNull(promptRegistry);
        this.generationRouter = Objects.requireNonNull(generationRouter);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.memoryFlushEngine = memoryFlushEngine;
    }

    public boolean compactIfNeeded(@Nullable String sessionId, @Nullable String traceId) {
        if (sessionId == null || sessionId.isBlank() || !compactionConfig().isEnabled()) {
            return false;
        }
        try {
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> allRows =
                    transcriptRepository.findBySessionId(sessionId);
            if (allRows.isEmpty()) {
                return false;
            }

            TranscriptCompactionBoundaryResolver.CompactionBoundary boundary =
                    boundaryResolver.resolveLatest(allRows).orElse(null);
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeRows =
                    boundaryResolver.filterRowsForActiveContext(allRows, boundary);
            if (activeRows.isEmpty()) {
                return false;
            }

            List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeVisibleRows = activeRows.stream()
                    .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                    .filter(row -> !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType()))
                    .toList();
            if (!shouldCompact(activeVisibleRows)) {
                return false;
            }

            List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> completeTurns =
                    groupCompleteTurns(activeRows);
            if (completeTurns.size() < resolveMinTurnCount()) {
                return false;
            }

            int keepRecentTurns = resolveKeepRecentTurns();
            if (completeTurns.size() <= keepRecentTurns) {
                return false;
            }
            List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> keptTurns =
                    completeTurns.subList(Math.max(0, completeTurns.size() - keepRecentTurns), completeTurns.size());
            String firstKeptEntryId = keptTurns.getFirst().getFirst().id();

            List<SessionTranscriptRepository.SessionTranscriptEntryRow> compactableRows =
                    selectCompactableRows(activeRows, firstKeptEntryId);
            if (compactableRows.isEmpty()) {
                return false;
            }

            List<String> flushedDocumentIds = memoryFlushEngine != null
                    ? memoryFlushEngine.flush(sessionId, traceId, compactableRows)
                    : List.of();

            String promptInput = buildPromptInput(boundary != null ? boundary.summary() : null, compactableRows);
            if (promptInput.isBlank()) {
                return false;
            }

            String prompt = promptRegistry.render("memory/compression-summary", Map.of(
                    "conversation", promptInput
            ));
            LlmResponse response = generationRouter.call(
                    LlmScene.MEMORY_COMPRESSION,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    null);
            String summary = normalizeSummary(response.content());
            if (summary.isBlank()) {
                return false;
            }
            List<String> keyPoints = extractKeyPoints(summary);
            TaskCheckpoint checkpoint = buildCheckpoint(
                    summary,
                    keyPoints,
                    compactableRows,
                    flushedDocumentIds
            );

            Instant now = Instant.now();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", summary);
            payload.put("firstKeptEntryId", firstKeptEntryId);
            payload.put("sourceEntryCount", compactableRows.size());
            payload.put("sourceStartEntryId", compactableRows.getFirst().id());
            payload.put("sourceEndEntryId", compactableRows.getLast().id());
            if (boundary != null && boundary.summaryEntryId() != null) {
                payload.put("previousSummaryEntryId", boundary.summaryEntryId());
            }
            if (!keyPoints.isEmpty()) {
                payload.put("keyPoints", keyPoints);
            }
            if (checkpoint.hasContent()) {
                payload.put("checkpoint", checkpoint.toPayload());
            }
            if (!flushedDocumentIds.isEmpty()) {
                payload.put("memoryDocumentIds", flushedDocumentIds);
            }

            transcriptRepository.appendEntry(
                    sessionId,
                    TranscriptEntryType.COMPACTION_SUMMARY,
                    null,
                    null,
                    normalizeBlank(traceId),
                    false,
                    false,
                    payload,
                    now
            );
            sessionStoreRepository.incrementCompactionCount(sessionId, now);
            log.info("会话压缩完成: sessionId={}, sourceEntryCount={}, firstKeptEntryId={}",
                    sessionId, compactableRows.size(), firstKeptEntryId);
            return true;
        } catch (Exception e) {
            log.warn("会话压缩失败: sessionId={}, error={}", sessionId, e.getMessage());
            return false;
        }
    }

    private boolean shouldCompact(List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeVisibleRows) {
        if (activeVisibleRows.isEmpty()) {
            return false;
        }
        int triggerThresholdTokens = resolveTriggerThresholdTokens();
        if (triggerThresholdTokens <= 0) {
            return false;
        }
        int tokenEstimate = activeVisibleRows.stream()
                .mapToInt(row -> Math.max(0, row.tokenEstimate()))
                .sum();
        return tokenEstimate >= triggerThresholdTokens;
    }

    private List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> groupCompleteTurns(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> visibleRows = rows.stream()
                .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                .filter(row -> !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType()))
                .sorted(java.util.Comparator.comparing(SessionTranscriptRepository.SessionTranscriptEntryRow::createdAt))
                .toList();
        if (visibleRows.isEmpty()) {
            return List.of();
        }

        List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> turns = new ArrayList<>();
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> current = new ArrayList<>();
        boolean hasReply = false;

        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : visibleRows) {
            TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
            if (entryType == TranscriptEntryType.USER_MESSAGE) {
                if (!current.isEmpty() && hasReply) {
                    turns.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                current.add(row);
                hasReply = false;
                continue;
            }

            if (current.isEmpty()) {
                continue;
            }
            current.add(row);
            if (entryType == TranscriptEntryType.ASSISTANT_MESSAGE) {
                hasReply = true;
            }
        }

        if (!current.isEmpty() && hasReply) {
            turns.add(List.copyOf(current));
        }
        return List.copyOf(turns);
    }

    private List<SessionTranscriptRepository.SessionTranscriptEntryRow> selectCompactableRows(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeRows,
            String firstKeptEntryId
    ) {
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows = new ArrayList<>();
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : activeRows) {
            if (firstKeptEntryId.equals(row.id())) {
                break;
            }
            if (row.visibleToModel() && !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType())) {
                rows.add(row);
            }
        }
        int maxSourceEntries = resolveMaxSourceEntries();
        if (rows.size() <= maxSourceEntries) {
            return List.copyOf(rows);
        }
        log.info("会话压缩源条目过多，截断为最近 {} 条: sourceEntryCount={}", maxSourceEntries, rows.size());
        return List.copyOf(rows.subList(rows.size() - maxSourceEntries, rows.size()));
    }

    private String buildPromptInput(@Nullable String previousSummary,
                                    List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        StringBuilder buffer = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            buffer.append("[已压缩历史摘要]\n")
                    .append(previousSummary.trim())
                    .append("\n\n");
        }
        buffer.append("[新增待压缩内容]\n");
        rows.stream()
                .map(this::formatRow)
                .filter(line -> !line.isBlank())
                .forEach(line -> buffer.append(line).append('\n'));
        return buffer.toString().trim();
    }

    private String formatRow(SessionTranscriptRepository.SessionTranscriptEntryRow row) {
        Map<String, Object> payload = readPayload(row.payloadJson());
        return switch (TranscriptEntryType.fromValue(row.entryType())) {
            case USER_MESSAGE, ASSISTANT_MESSAGE, SYSTEM_EVENT -> formatMessageRow(row, payload);
            case TOOL_CALL -> formatToolCallRow(payload);
            case TOOL_RESULT -> formatToolResultRow(payload);
            case ARTIFACT_REF -> formatArtifactRow(payload);
            default -> formatGenericRow(row, payload);
        };
    }

    private String formatMessageRow(SessionTranscriptRepository.SessionTranscriptEntryRow row,
                                    Map<String, Object> payload) {
        String role = normalizeRole(row.role());
        String content = stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return "";
        }
        return "[" + (role.isBlank() ? "message" : role) + "] " + normalizeWhitespace(content);
    }

    private String formatToolCallRow(Map<String, Object> payload) {
        String toolId = stringValue(payload.get("toolId"));
        String inputJson = stringValue(payload.get("inputJson"));
        String preview = abbreviate(extractPayloadPreview(inputJson), DEFAULT_PAYLOAD_PREVIEW_CHARS);
        if ((toolId == null || toolId.isBlank()) && preview.isBlank()) {
            return "";
        }
        return "[tool_call:" + (toolId != null ? toolId : "tool") + "] 输入: " + preview;
    }

    private String formatToolResultRow(Map<String, Object> payload) {
        String toolId = stringValue(payload.get("toolId"));
        boolean success = booleanValue(payload.get("success"));
        String outputJson = stringValue(payload.get("outputJson"));
        String preview = abbreviate(extractPayloadPreview(outputJson), DEFAULT_PAYLOAD_PREVIEW_CHARS);
        if ((toolId == null || toolId.isBlank()) && preview.isBlank()) {
            return "";
        }
        return "[tool_result:" + (toolId != null ? toolId : "tool") + "] "
                + (success ? "成功" : "失败") + ": " + preview;
    }

    private String formatArtifactRow(Map<String, Object> payload) {
        String artifactType = stringValue(payload.get("artifactType"));
        String title = stringValue(payload.get("title"));
        String summary = stringValue(payload.get("summary"));
        if (artifactType == null && title == null && summary == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder("[artifact:");
        buffer.append(artifactType != null ? artifactType : "artifact").append("] ");
        if (title != null) {
            buffer.append(title);
        }
        if (summary != null && !summary.isBlank()) {
            if (title != null && !title.isBlank()) {
                buffer.append(": ");
            }
            buffer.append(normalizeWhitespace(summary));
        }
        return buffer.toString().trim();
    }

    private String formatGenericRow(SessionTranscriptRepository.SessionTranscriptEntryRow row,
                                    Map<String, Object> payload) {
        String preview = abbreviate(normalizeWhitespace(payload.toString()), DEFAULT_PAYLOAD_PREVIEW_CHARS);
        if (preview.isBlank()) {
            return "";
        }
        return "[" + row.entryType() + "] " + preview;
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

    private String normalizeSummary(@Nullable String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String normalized = normalizeWhitespace(summary);
        int maxChars = resolveSummaryMaxChars();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
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

    private String normalizeRole(@Nullable String role) {
        return role == null ? "" : role.trim().toLowerCase();
    }

    private AgentConfigProperties.ContextConfig.CompactionConfig compactionConfig() {
        AgentConfigProperties.ContextConfig.CompactionConfig compaction = config.getContext().getCompaction();
        return compaction != null ? compaction : new AgentConfigProperties.ContextConfig.CompactionConfig();
    }

    private int resolveTriggerThresholdTokens() {
        int percent = compactionConfig().getTriggerThresholdPercent();
        if (percent <= 0) {
            percent = DEFAULT_TRIGGER_THRESHOLD_PERCENT;
        }
        return Math.max(1, config.getContext().getMaxContextTokens() * percent / 100);
    }

    private int resolveKeepRecentTurns() {
        int configured = compactionConfig().getKeepRecentTurns();
        return configured > 0 ? configured : DEFAULT_KEEP_RECENT_TURNS;
    }

    private int resolveMinTurnCount() {
        int configured = compactionConfig().getMinTurnCount();
        return configured > 0 ? configured : DEFAULT_MIN_TURN_COUNT;
    }

    private int resolveMaxSourceEntries() {
        int configured = compactionConfig().getMaxSourceEntries();
        return configured > 0 ? configured : DEFAULT_MAX_SOURCE_ENTRIES;
    }

    private int resolveSummaryMaxChars() {
        int configured = compactionConfig().getSummaryMaxChars();
        return configured > 0 ? configured : DEFAULT_SUMMARY_MAX_CHARS;
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private List<String> extractKeyPoints(String summary) {
        if (summary.isBlank()) {
            return List.of();
        }
        try {
            String prompt = promptRegistry.render("memory/compression-keypoints", Map.of(
                    "summary", summary
            ));
            if (prompt == null || prompt.isBlank()) {
                return fallbackKeyPoints(summary);
            }
            LlmResponse response = generationRouter.call(
                    LlmScene.MEMORY_COMPRESSION,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    null
            );
            List<String> keyPoints = parseKeyPoints(response.content());
            return keyPoints.isEmpty() ? fallbackKeyPoints(summary) : keyPoints;
        } catch (Exception e) {
            log.debug("提取压缩关键要点失败，回退到启发式摘要切分: error={}", e.getMessage());
            return fallbackKeyPoints(summary);
        }
    }

    private List<String> parseKeyPoints(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    if (line.startsWith("-")) {
                        return line.substring(1).trim();
                    }
                    if (line.startsWith("•")) {
                        return line.substring(1).trim();
                    }
                    return line;
                })
                .filter(line -> !line.isEmpty())
                .limit(DEFAULT_KEYPOINT_LIMIT)
                .distinct()
                .toList();
    }

    private List<String> fallbackKeyPoints(String summary) {
        return splitSentences(summary).stream()
                .limit(DEFAULT_KEYPOINT_LIMIT)
                .toList();
    }

    private TaskCheckpoint buildCheckpoint(
            @Nullable String summary,
            List<String> keyPoints,
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            List<String> memoryDocumentIds
    ) {
        if ((summary == null || summary.isBlank()) && keyPoints.isEmpty() && rows.isEmpty()) {
            return TaskCheckpoint.empty();
        }

        LinkedHashSet<String> decisions = new LinkedHashSet<>();
        LinkedHashSet<String> openItems = new LinkedHashSet<>();
        LinkedHashSet<String> completedItems = new LinkedHashSet<>();
        LinkedHashSet<String> risks = new LinkedHashSet<>();
        for (String keyPoint : keyPoints) {
            classifyKeyPoint(keyPoint, decisions, openItems, completedItems, risks);
        }
        if (completedItems.isEmpty() && summary != null && !summary.isBlank()) {
            completedItems.add(summary);
        }

        LinkedHashSet<String> constraints = extractConstraintHints(rows);
        LinkedHashSet<TaskCheckpoint.ArtifactRef> artifacts = extractArtifactRefs(rows, memoryDocumentIds);
        LinkedHashSet<String> neededContextRefs = new LinkedHashSet<>();
        memoryDocumentIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> "memory:" + id)
                .forEach(neededContextRefs::add);
        artifacts.stream()
                .map(TaskCheckpoint.ArtifactRef::refId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> "artifact:" + id)
                .forEach(neededContextRefs::add);

        LinkedHashSet<String> resumePlan = buildResumePlan(openItems, decisions, artifacts);
        return new TaskCheckpoint(
                inferGoal(rows),
                inferPhase(rows),
                List.copyOf(completedItems),
                List.copyOf(openItems),
                List.copyOf(decisions),
                List.copyOf(constraints),
                List.copyOf(artifacts),
                List.copyOf(resumePlan),
                List.copyOf(risks),
                List.copyOf(neededContextRefs),
                buildDomainState(rows)
        );
    }

    private void classifyKeyPoint(
            String keyPoint,
            LinkedHashSet<String> decisions,
            LinkedHashSet<String> openItems,
            LinkedHashSet<String> completedItems,
            LinkedHashSet<String> risks
    ) {
        if (keyPoint == null || keyPoint.isBlank()) {
            return;
        }
        if (containsAny(keyPoint, "风险", "失败", "异常", "冲突", "问题", "注意")) {
            risks.add(keyPoint);
            return;
        }
        if (containsAny(keyPoint, "待", "未", "剩余", "后续", "下一步", "需要", "阻塞", "TODO")) {
            openItems.add(keyPoint);
            return;
        }
        if (containsAny(keyPoint, "决定", "采用", "改为", "选择", "确认", "统一", "约定")) {
            decisions.add(keyPoint);
            completedItems.add(keyPoint);
            return;
        }
        completedItems.add(keyPoint);
    }

    private LinkedHashSet<String> extractConstraintHints(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            TranscriptEntryType type = TranscriptEntryType.fromValue(row.entryType());
            if (type != TranscriptEntryType.USER_MESSAGE && type != TranscriptEntryType.ASSISTANT_MESSAGE) {
                continue;
            }
            String content = stringValue(readPayload(row.payloadJson()).get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            for (String sentence : splitSentences(content)) {
                if (containsAny(sentence, "必须", "不能", "不要", "仅", "限制", "约束", "优先", "避免")) {
                    constraints.add(sentence);
                }
                if (constraints.size() >= 4) {
                    return constraints;
                }
            }
        }
        return constraints;
    }

    private LinkedHashSet<TaskCheckpoint.ArtifactRef> extractArtifactRefs(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            List<String> memoryDocumentIds
    ) {
        LinkedHashSet<TaskCheckpoint.ArtifactRef> artifacts = new LinkedHashSet<>();
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            if (artifacts.size() >= DEFAULT_ARTIFACT_REF_LIMIT) {
                break;
            }
            Map<String, Object> payload = readPayload(row.payloadJson());
            TranscriptEntryType type = TranscriptEntryType.fromValue(row.entryType());
            if (type == TranscriptEntryType.ARTIFACT_REF) {
                artifacts.add(new TaskCheckpoint.ArtifactRef(
                        stringValue(payload.get("artifactType")),
                        stringValue(payload.get("title")),
                        stringValue(payload.get("summary")),
                        stringValue(payload.get("artifactId"))
                ));
                continue;
            }
            String artifactId = stringValue(payload.get("artifactId"));
            if (artifactId != null) {
                artifacts.add(new TaskCheckpoint.ArtifactRef(
                        "artifact",
                        stringValue(payload.get("toolId")),
                        abbreviate(formatToolResultRow(payload), 120),
                        artifactId
                ));
            }
        }
        for (String memoryDocumentId : memoryDocumentIds) {
            if (artifacts.size() >= DEFAULT_ARTIFACT_REF_LIMIT) {
                break;
            }
            if (memoryDocumentId == null || memoryDocumentId.isBlank()) {
                continue;
            }
            artifacts.add(new TaskCheckpoint.ArtifactRef(
                    "memory_document",
                    "压缩前会话片段",
                    "如需完整细节，可回看预刷新的 transcript 片段文档。",
                    memoryDocumentId
            ));
        }
        return artifacts;
    }

    private LinkedHashSet<String> buildResumePlan(
            LinkedHashSet<String> openItems,
            LinkedHashSet<String> decisions,
            LinkedHashSet<TaskCheckpoint.ArtifactRef> artifacts
    ) {
        LinkedHashSet<String> resumePlan = new LinkedHashSet<>();
        openItems.stream()
                .limit(DEFAULT_RESUME_PLAN_LIMIT)
                .map(item -> item.startsWith("继续") ? item : "继续跟进: " + item)
                .forEach(resumePlan::add);
        if (resumePlan.isEmpty() && !decisions.isEmpty()) {
            String latestDecision = decisions.stream().reduce((first, second) -> second).orElse(null);
            if (latestDecision != null) {
                resumePlan.add("沿用已确认决策继续执行: " + latestDecision);
            }
        }
        if (!artifacts.isEmpty()) {
            resumePlan.add("需要细节时优先回看关联产物或记忆引用，而不是重放整段历史。");
        }
        return resumePlan;
    }

    private Map<String, Object> buildDomainState(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> domainState = new LinkedHashMap<>();
        SessionTranscriptRepository.SessionTranscriptEntryRow first = rows.getFirst();
        SessionTranscriptRepository.SessionTranscriptEntryRow last = rows.getLast();
        domainState.put("sourceEntryCount", rows.size());
        domainState.put("sourceStartEntryId", first.id());
        domainState.put("sourceEndEntryId", last.id());
        domainState.put("lastEntryType", last.entryType());
        if (last.turnId() != null && !last.turnId().isBlank()) {
            domainState.put("lastTurnId", last.turnId());
        }
        List<String> toolIds = rows.stream()
                .map(row -> stringValue(readPayload(row.payloadJson()).get("toolId")))
                .filter(Objects::nonNull)
                .distinct()
                .limit(5)
                .toList();
        if (!toolIds.isEmpty()) {
            domainState.put("toolIds", toolIds);
        }
        return Map.copyOf(domainState);
    }

    @Nullable
    private String inferGoal(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            if (TranscriptEntryType.fromValue(row.entryType()) != TranscriptEntryType.USER_MESSAGE) {
                continue;
            }
            String content = stringValue(readPayload(row.payloadJson()).get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            return abbreviate(normalizeWhitespace(content), 160);
        }
        return null;
    }

    @Nullable
    private String inferPhase(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        TranscriptEntryType lastType = TranscriptEntryType.fromValue(rows.getLast().entryType());
        return switch (lastType) {
            case USER_MESSAGE -> "user_input";
            case ASSISTANT_MESSAGE -> "assistant_response";
            case TOOL_CALL -> "tool_call";
            case TOOL_RESULT -> "tool_result";
            case ARTIFACT_REF -> "artifact_sync";
            default -> "conversation";
        };
    }

    private List<String> splitSentences(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = normalizeWhitespace(text).split("[。；;\\n]");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return List.copyOf(sentences);
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
