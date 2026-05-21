package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.memory.store.workspace.SessionWorkspaceService;
import com.lifepilot.memory.store.workspace.WorkspaceItem;
import com.lifepilot.memory.store.workspace.WorkspaceProperties;
import com.lifepilot.observability.context.ContextReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ContextEngine 负责 transcript-first 的上下文切片、历史消息流重建、artifact 注入与上下文报告落库。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextEngine.class);

    private static final int DEFAULT_WORKSPACE_PROMPT_LIMIT = 3;
    private static final int DEFAULT_ARTIFACT_LIMIT = 3;

    private final AgentConfigProperties config;
    private final SessionPruningEngine sessionPruningEngine;
    private final TranscriptCompactionBoundaryResolver compactionBoundaryResolver;
    @Nullable
    private final SessionTranscriptRepository transcriptRepository;
    @Nullable
    private final SessionWorkspaceService workspaceService;
    @Nullable
    private final WorkspaceProperties workspaceProperties;
    @Nullable
    private final SessionArtifactRepository artifactRepository;
    @Nullable
    private final ContextReportRepository contextReportRepository;

    public record ContextSnapshot(
            List<Message> historyMessages,
            List<WorkspaceItem> workspaceItems,
            String artifactSection,
            boolean pruningApplied,
            boolean compactionApplied,
            int historyTokens,
            int artifactTokens,
            int toolResultTokens,
            Map<String, Object> debugPayload
    ) {
        public ContextSnapshot {
            historyMessages = List.copyOf(historyMessages);
            workspaceItems = List.copyOf(workspaceItems);
            debugPayload = Map.copyOf(debugPayload);
        }

        public static ContextSnapshot empty() {
            return new ContextSnapshot(
                    List.of(),
                    List.of(),
                    "",
                    false,
                    false,
                    0,
                    0,
                    0,
                    Map.of()
            );
        }
    }

    private record ArtifactSnapshot(
            String section,
            int tokenCount,
            int artifactCount
    ) {
    }

    private record HistorySnapshot(
            List<Message> messages,
            int historyTokens,
            int toolResultTokens,
            int toolResultCount,
            int totalToolResultCount
    ) {
    }

    public ContextEngine(AgentConfigProperties config,
                         SessionPruningEngine sessionPruningEngine,
                         TranscriptCompactionBoundaryResolver compactionBoundaryResolver,
                         @Nullable SessionTranscriptRepository transcriptRepository,
                         @Nullable SessionWorkspaceService workspaceService,
                         @Nullable WorkspaceProperties workspaceProperties,
                         @Nullable SessionArtifactRepository artifactRepository,
                         @Nullable ContextReportRepository contextReportRepository) {
        this.config = config;
        this.sessionPruningEngine = sessionPruningEngine;
        this.compactionBoundaryResolver = compactionBoundaryResolver;
        this.transcriptRepository = transcriptRepository;
        this.workspaceService = workspaceService;
        this.workspaceProperties = workspaceProperties;
        this.artifactRepository = artifactRepository;
        this.contextReportRepository = contextReportRepository;
    }

    public ContextSnapshot load(ReactAgentState state, int totalContextTokens) {
        if (state.sessionId() == null || state.sessionId().isBlank()) {
            return ContextSnapshot.empty();
        }

        List<SessionTranscriptRepository.SessionTranscriptEntryRow> transcriptRows =
                loadTranscriptEntries(state.sessionId());
        TranscriptCompactionBoundaryResolver.CompactionBoundary compactionBoundary =
                compactionBoundaryResolver.resolveLatest(transcriptRows).orElse(null);
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeRows =
                compactionBoundaryResolver.filterRowsForActiveContext(transcriptRows, compactionBoundary);

        SessionPruningEngine.PruningSnapshot toolResults = loadToolResults(activeRows, totalContextTokens);
        HistorySnapshot history = buildHistorySnapshot(activeRows, compactionBoundary, toolResults, totalContextTokens);
        List<WorkspaceItem> workspaceItems = loadWorkspaceItems(state.sessionId());
        ArtifactSnapshot artifacts = loadArtifacts(state.sessionId());
        boolean compactionApplied = compactionBoundary != null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", state.sessionId());
        payload.put("historyMessageCount", history.messages().size());
        payload.put("activeTranscriptEntryCount", activeRows.size());
        payload.put("workspaceItemCount", workspaceItems.size());
        payload.put("toolResultCount", history.toolResultCount());
        payload.put("toolResultTotalCount", history.totalToolResultCount());
        payload.put("artifactCount", artifacts.artifactCount());
        if (compactionBoundary != null) {
            payload.put("compactionBoundaryEntryId", compactionBoundary.summaryEntryId());
            payload.put("compactionFirstKeptEntryId", compactionBoundary.firstKeptEntryId());
            payload.put("compactionKeyPointCount", compactionBoundary.keyPoints().size());
            payload.put("checkpointApplied", compactionBoundary.checkpoint().hasContent());
        }

        return new ContextSnapshot(
                history.messages(),
                workspaceItems,
                artifacts.section(),
                toolResults.pruningApplied(),
                compactionApplied,
                history.historyTokens(),
                artifacts.tokenCount(),
                history.toolResultTokens(),
                payload
        );
    }

    public void recordReport(ReactAgentState state,
                             ContextSnapshot snapshot,
                             AssembledContext context,
                             int contextWindow,
                             int reservedTokens) {
        if (contextReportRepository == null || !reportConfig().isContextReportEnabled()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(snapshot.debugPayload());
            payload.put("contextDegraded", context.degraded());
            payload.put("workingMemoryTokens", context.workingMemoryTokens());
            payload.put("injectedEntityIds", context.injectedEntityIds());
            payload.put("contextMessagesPreview", ContextMessageFormatter.serializeForPreview(context.contextMessages()));
            payload.put("historyPreview", ContextMessageFormatter.serializeForPreview(snapshot.historyMessages()));
            payload.put("artifactSection", snapshot.artifactSection());

            contextReportRepository.save(
                    state.sessionId(),
                    state.traceId(),
                    context.tokenBudget().systemPromptUsed(),
                    context.tokenBudget().historyUsed(),
                    context.tokenBudget().memoryUsed(),
                    snapshot.artifactTokens(),
                    context.tokenBudget().toolSchemaUsed(),
                    context.tokenBudget().toolResultUsed(),
                    snapshot.pruningApplied(),
                    snapshot.compactionApplied(),
                    contextWindow,
                    reservedTokens,
                    payload,
                    Instant.now()
            );
        } catch (Exception e) {
            log.warn("context report 落库失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
        }
    }

    private List<SessionTranscriptRepository.SessionTranscriptEntryRow> loadTranscriptEntries(String sessionId) {
        if (transcriptRepository == null) {
            return List.of();
        }
        try {
            return transcriptRepository.findBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("context engine 读取 transcript 历史失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private List<WorkspaceItem> loadWorkspaceItems(String sessionId) {
        if (workspaceService == null || sessionId.isBlank()) {
            return List.of();
        }
        if (workspaceProperties != null && !workspaceProperties.isEnabled()) {
            return List.of();
        }
        try {
            int maxItems = workspaceProperties != null
                    ? workspaceProperties.getPromptMaxItems()
                    : DEFAULT_WORKSPACE_PROMPT_LIMIT;
            return workspaceService.listActive(sessionId).stream()
                    .limit(Math.max(0, maxItems))
                    .toList();
        } catch (Exception e) {
            log.warn("context engine 读取工作区失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private SessionPruningEngine.PruningSnapshot loadToolResults(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            int totalContextTokens
    ) {
        if (rows == null || rows.isEmpty()) {
            return SessionPruningEngine.PruningSnapshot.empty();
        }
        try {
            return sessionPruningEngine.pruneToolResults(rows, totalContextTokens);
        } catch (Exception e) {
            log.warn("context engine 裁剪工具结果失败: error={}", e.getMessage());
            return SessionPruningEngine.PruningSnapshot.empty();
        }
    }

    private ArtifactSnapshot loadArtifacts(String sessionId) {
        if (artifactRepository == null) {
            return new ArtifactSnapshot("", 0, 0);
        }
        try {
            List<SessionArtifactRepository.SessionArtifactRow> artifacts = artifactRepository.findBySessionId(sessionId).stream()
                    .filter(row -> "ACTIVE".equalsIgnoreCase(row.status()))
                    .limit(resolveRecentArtifactLimit())
                    .toList();
            if (artifacts.isEmpty()) {
                return new ArtifactSnapshot("", 0, 0);
            }

            StringBuilder section = new StringBuilder("\n最近产物:\n");
            for (SessionArtifactRepository.SessionArtifactRow artifact : artifacts) {
                section.append("- [")
                        .append(artifact.artifactType())
                        .append("] ");
                if (artifact.title() != null && !artifact.title().isBlank()) {
                    section.append(artifact.title());
                } else {
                    section.append(artifact.id());
                }
                if (artifact.summary() != null && !artifact.summary().isBlank()) {
                    section.append(": ").append(artifact.summary());
                }
                section.append('\n');
            }
            return new ArtifactSnapshot(section.toString(), estimateTokens(section.toString()), artifacts.size());
        } catch (Exception e) {
            log.warn("context engine 读取 artifact 失败: sessionId={}, error={}", sessionId, e.getMessage());
            return new ArtifactSnapshot("", 0, 0);
        }
    }

    private HistorySnapshot buildHistorySnapshot(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            @Nullable TranscriptCompactionBoundaryResolver.CompactionBoundary compactionBoundary,
            SessionPruningEngine.PruningSnapshot toolResults,
            int totalContextTokens
    ) {
        if (rows == null || rows.isEmpty()) {
            return new HistorySnapshot(List.of(), 0, 0, 0, 0);
        }

        List<Message> messages = new ArrayList<>();
        int historyTokens = 0;
        int toolResultTokens = 0;
        int includedToolResultCount = 0;
        Set<String> selectedToolResultEntryIds = toolResults.selectedEntryIds();

        Message checkpointMessage = buildCheckpointMessage(compactionBoundary);
        if (checkpointMessage != null) {
            messages.add(checkpointMessage);
            historyTokens += estimateMessageTokens(checkpointMessage);
        }

        Message compactionMessage = buildCompactionSummaryMessage(compactionBoundary);
        if (compactionMessage != null) {
            messages.add(compactionMessage);
            historyTokens += estimateMessageTokens(compactionMessage);
        }

        List<SessionTranscriptRepository.SessionTranscriptEntryRow> historyRows =
                selectHistoryRows(rows, selectedToolResultEntryIds, totalContextTokens);
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : historyRows) {
            TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
            if (entryType == TranscriptEntryType.TOOL_RESULT
                    && !selectedToolResultEntryIds.contains(row.id())) {
                continue;
            }

            Message message = toHistoryMessage(row);
            if (message == null) {
                continue;
            }
            messages.add(message);
            if (entryType == TranscriptEntryType.TOOL_RESULT) {
                toolResultTokens += estimateMessageTokens(message);
                includedToolResultCount++;
                continue;
            }
            historyTokens += estimateMessageTokens(message);
        }

        return new HistorySnapshot(
                List.copyOf(messages),
                historyTokens,
                toolResultTokens,
                includedToolResultCount,
                toolResults.totalCount()
        );
    }

    private List<SessionTranscriptRepository.SessionTranscriptEntryRow> selectHistoryRows(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            Set<String> selectedToolResultEntryIds,
            int totalContextTokens
    ) {
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> visibleRows = rows.stream()
                .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                .filter(row -> TranscriptEntryType.COMPACTION_SUMMARY != TranscriptEntryType.fromValue(row.entryType()))
                .sorted(Comparator.comparing(SessionTranscriptRepository.SessionTranscriptEntryRow::createdAt))
                .toList();
        if (visibleRows.isEmpty()) {
            return List.of();
        }

        List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> turns = groupCompleteTurns(visibleRows);
        if (turns.isEmpty()) {
            return List.of();
        }

        int historyBudget = resolveHistoryBudget(totalContextTokens);
        int usedHistoryTokens = 0;
        List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> selectedTurns = new ArrayList<>();

        for (int i = turns.size() - 1; i >= 0; i--) {
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> turn = turns.get(i);
            int turnHistoryTokens = estimateTurnHistoryTokens(turn, selectedToolResultEntryIds);
            if (!selectedTurns.isEmpty() && usedHistoryTokens + turnHistoryTokens > historyBudget) {
                break;
            }
            selectedTurns.add(0, turn);
            usedHistoryTokens += turnHistoryTokens;
        }

        if (selectedTurns.isEmpty()) {
            selectedTurns.add(turns.getLast());
        }

        List<SessionTranscriptRepository.SessionTranscriptEntryRow> flattened = new ArrayList<>();
        selectedTurns.forEach(flattened::addAll);
        return List.copyOf(flattened);
    }

    private List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> groupCompleteTurns(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> turns = new ArrayList<>();
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> current = new ArrayList<>();
        boolean hasAssistantReply = false;

        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
            if (entryType == TranscriptEntryType.USER_MESSAGE) {
                if (!current.isEmpty() && hasAssistantReply) {
                    turns.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                current.add(row);
                hasAssistantReply = false;
                continue;
            }

            if (current.isEmpty()) {
                continue;
            }

            current.add(row);
            if (entryType == TranscriptEntryType.ASSISTANT_MESSAGE) {
                hasAssistantReply = true;
            }
        }

        if (!current.isEmpty() && hasAssistantReply) {
            turns.add(List.copyOf(current));
        }

        return List.copyOf(turns);
    }

    private int estimateTurnHistoryTokens(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> turn,
            Set<String> selectedToolResultEntryIds
    ) {
        int tokens = 0;
        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : turn) {
            TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
            if (entryType == TranscriptEntryType.TOOL_RESULT) {
                if (!selectedToolResultEntryIds.contains(row.id())) {
                    continue;
                }
                continue;
            }
            Message message = toHistoryMessage(row);
            if (message != null) {
                tokens += estimateMessageTokens(message);
            }
        }
        return tokens;
    }

    @Nullable
    private Message buildCheckpointMessage(
            @Nullable TranscriptCompactionBoundaryResolver.CompactionBoundary boundary
    ) {
        if (boundary == null || !boundary.checkpoint().hasContent()) {
            return null;
        }
        String section = renderCheckpoint(boundary.checkpoint(), boundary.keyPoints());
        if (section.isBlank()) {
            return null;
        }
        return new AssistantMessage(section);
    }

    @Nullable
    private Message buildCompactionSummaryMessage(
            @Nullable TranscriptCompactionBoundaryResolver.CompactionBoundary boundary
    ) {
        if (boundary == null || boundary.summary() == null || boundary.summary().isBlank()) {
            return null;
        }
        return new AssistantMessage("""
                <history_summary>
                历史压缩摘要:
                %s
                </history_summary>
                """.formatted(boundary.summary().trim()).strip());
    }

    private String renderCheckpoint(TaskCheckpoint checkpoint, List<String> keyPoints) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<task_checkpoint>\n")
                .append("任务检查点:\n");
        appendCheckpointLine(buffer, "目标", checkpoint.goal());
        appendCheckpointLine(buffer, "阶段", checkpoint.currentPhase());
        appendCheckpointItems(buffer, "已完成", checkpoint.completedItems(), 4);
        appendCheckpointItems(buffer, "未决事项", checkpoint.openItems(), 4);
        appendCheckpointItems(buffer, "关键决策", checkpoint.decisions(), 4);
        appendCheckpointItems(buffer, "约束", checkpoint.constraints(), 3);
        appendCheckpointItems(buffer, "风险", checkpoint.risks(), 3);
        appendCheckpointItems(buffer, "恢复计划", checkpoint.resumePlan(), 3);
        appendCheckpointItems(buffer, "关键要点", keyPoints, 5);
        if (!checkpoint.artifacts().isEmpty()) {
            List<String> artifactLines = checkpoint.artifacts().stream()
                    .limit(3)
                    .map(this::formatArtifactRef)
                    .filter(line -> !line.isBlank())
                    .toList();
            appendCheckpointItems(buffer, "关联产物", artifactLines, 3);
        }
        if (!checkpoint.neededContextRefs().isEmpty()) {
            appendCheckpointItems(buffer, "上下文引用", checkpoint.neededContextRefs(), 4);
        }
        buffer.append("</task_checkpoint>");
        return buffer.toString();
    }

    private void appendCheckpointLine(StringBuilder buffer, String label, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        buffer.append("- ").append(label).append(": ").append(value.trim()).append('\n');
    }

    private void appendCheckpointItems(StringBuilder buffer, String label, List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String joined = items.stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(Math.max(1, limit))
                .map(String::trim)
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        if (joined.isBlank()) {
            return;
        }
        buffer.append("- ").append(label).append(": ").append(joined).append('\n');
    }

    private String formatArtifactRef(TaskCheckpoint.ArtifactRef artifact) {
        if (artifact == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        if (artifact.type() != null && !artifact.type().isBlank()) {
            buffer.append('[').append(artifact.type().trim()).append("] ");
        }
        if (artifact.title() != null && !artifact.title().isBlank()) {
            buffer.append(artifact.title().trim());
        }
        if (artifact.summary() != null && !artifact.summary().isBlank()) {
            if (buffer.length() > 0) {
                buffer.append(": ");
            }
            buffer.append(artifact.summary().trim());
        }
        if (artifact.refId() != null && !artifact.refId().isBlank()) {
            if (buffer.length() > 0) {
                buffer.append(" ");
            }
            buffer.append("(ref=").append(artifact.refId().trim()).append(')');
        }
        return buffer.toString().trim();
    }

    private Message buildHistoryMarkerMessage() {
        return new AssistantMessage("""
                <history_transcript>
                以下消息为历史 transcript，按时间顺序排列。
                </history_transcript>
                """.strip());
    }

    @Nullable
    private Message toHistoryMessage(SessionTranscriptRepository.SessionTranscriptEntryRow row) {
        Map<String, Object> payload = sessionPruningEngine.readPayload(row.payloadJson());
        TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
        return switch (entryType) {
            case USER_MESSAGE -> buildUserMessage(payload);
            case ASSISTANT_MESSAGE -> buildAssistantMessage(payload);
            case TOOL_CALL -> buildToolCallMessage(payload);
            case TOOL_RESULT -> buildToolResultMessage(payload);
            case SYSTEM_EVENT, CUSTOM_CONTEXT, BRANCH_SUMMARY, MEMORY_FLUSH_EVENT -> buildContextMessage(payload);
            default -> null;
        };
    }

    @Nullable
    private Message buildUserMessage(Map<String, Object> payload) {
        String content = sessionPruningEngine.stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return null;
        }
        return new UserMessage(content);
    }

    @Nullable
    private Message buildAssistantMessage(Map<String, Object> payload) {
        String content = sessionPruningEngine.stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return null;
        }
        return new AssistantMessage(content);
    }

    @Nullable
    private Message buildToolCallMessage(Map<String, Object> payload) {
        String toolId = sessionPruningEngine.stringValue(payload.get("toolId"));
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        String callId = sessionPruningEngine.stringValue(payload.get("callId"));
        String inputJson = sessionPruningEngine.stringValue(payload.get("inputJson"));
        String safeName = toolId != null ? toolId.replaceAll("[^a-zA-Z0-9_-]", "_") : "tool";
        return buildAssistantToolCallMessage(List.of(new AssistantMessage.ToolCall(
                        callId != null ? callId : toolId,
                        "function",
                        safeName,
                        inputJson != null ? inputJson : "{}"
                )));
    }

    @Nullable
    private Message buildToolResultMessage(Map<String, Object> payload) {
        String toolId = sessionPruningEngine.stringValue(payload.get("toolId"));
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        String callId = sessionPruningEngine.stringValue(payload.get("callId"));
        String preview = sessionPruningEngine.formatToolResultPreview(payload);
        if (preview.isBlank()) {
            preview = booleanValue(payload.get("success")) ? "工具执行成功" : "工具执行失败";
        }
        String safeName = toolId != null ? toolId.replaceAll("[^a-zA-Z0-9_-]", "_") : "tool";
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        callId != null ? callId : toolId,
                        safeName,
                        preview
                )))
                .build();
    }

    @Nullable
    private Message buildContextMessage(Map<String, Object> payload) {
        String content = sessionPruningEngine.stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return null;
        }
        return new AssistantMessage(content);
    }

    private AssistantMessage buildAssistantToolCallMessage(List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder()
                .toolCalls(toolCalls)
                .build();
    }

    private int estimateMessageTokens(Message message) {
        return switch (message) {
            case UserMessage userMessage -> estimateTokens(userMessage.getText());
            case AssistantMessage assistantMessage -> {
                int tokens = estimateTokens(assistantMessage.getText());
                if (assistantMessage.hasToolCalls()) {
                    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                        tokens += estimateTokens(toolCall.name());
                        tokens += estimateTokens(toolCall.arguments());
                    }
                }
                yield tokens;
            }
            case ToolResponseMessage toolResponseMessage -> toolResponseMessage.getResponses().stream()
                    .mapToInt(response -> estimateTokens(response.name()) + estimateTokens(response.responseData()))
                    .sum();
            default -> estimateTokens(message.toString());
        };
    }

    private boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private int estimateTokens(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    private AgentConfigProperties.ContextConfig.ReportConfig reportConfig() {
        AgentConfigProperties.ContextConfig.ReportConfig report = config.getContext().getReport();
        return report != null ? report : new AgentConfigProperties.ContextConfig.ReportConfig();
    }

    private int resolveHistoryBudget(int totalContextTokens) {
        TokenBudget budget = TokenBudget.allocateDefault(
                Math.max(0, totalContextTokens),
                config.getContext().getTokenAllocation()
        );
        return Math.max(1, budget.historyBudget());
    }

    private int resolveRecentArtifactLimit() {
        AgentConfigProperties.ContextConfig.SliceConfig slice = config.getContext().getSlice();
        int configured = slice != null ? slice.getRecentArtifactLimit() : 0;
        return configured > 0 ? configured : DEFAULT_ARTIFACT_LIMIT;
    }
}
