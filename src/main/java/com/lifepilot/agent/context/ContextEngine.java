package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.conversation.ConversationTurnGrouper;
import com.lifepilot.conversation.ConversationTurnView;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceProperties;
import com.lifepilot.observability.context.ContextReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ContextEngine 负责 transcript-first 的上下文切片、artifact 注入与上下文报告落库。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(ContextEngine.class);

    private static final int DEFAULT_WORKSPACE_PROMPT_LIMIT = 3;
    private static final int DEFAULT_RECENT_TURN_LIMIT = 6;
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
            List<ConversationTurnView> recentTurns,
            List<WorkspaceItem> workspaceItems,
            String compactionSection,
            String artifactSection,
            String toolResultsSection,
            boolean pruningApplied,
            boolean compactionApplied,
            int artifactTokens,
            int toolResultTokens,
            Map<String, Object> debugPayload
    ) {
        public ContextSnapshot {
            recentTurns = List.copyOf(recentTurns);
            workspaceItems = List.copyOf(workspaceItems);
            debugPayload = Map.copyOf(debugPayload);
        }

        public static ContextSnapshot empty() {
            return new ContextSnapshot(
                    List.of(),
                    List.of(),
                    "",
                    "",
                    "",
                    false,
                    false,
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

        List<ConversationTurnView> recentTurns = loadRecentTurns(activeRows);
        List<WorkspaceItem> workspaceItems = loadWorkspaceItems(state.sessionId());
        SessionPruningEngine.PruningSnapshot toolResults = loadToolResults(activeRows, totalContextTokens);
        ArtifactSnapshot artifacts = loadArtifacts(state.sessionId());
        String compactionSection = compactionBoundaryResolver.renderSection(compactionBoundary);
        boolean compactionApplied = compactionBoundary != null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", state.sessionId());
        payload.put("recentTurnCount", recentTurns.size());
        payload.put("activeTranscriptEntryCount", activeRows.size());
        payload.put("workspaceItemCount", workspaceItems.size());
        payload.put("toolResultCount", toolResults.selectedCount());
        payload.put("toolResultTotalCount", toolResults.totalCount());
        payload.put("artifactCount", artifacts.artifactCount());
        if (compactionBoundary != null) {
            payload.put("compactionBoundaryEntryId", compactionBoundary.summaryEntryId());
            payload.put("compactionFirstKeptEntryId", compactionBoundary.firstKeptEntryId());
        }

        return new ContextSnapshot(
                recentTurns,
                workspaceItems,
                compactionSection,
                artifacts.section(),
                toolResults.section(),
                toolResults.pruningApplied(),
                compactionApplied,
                artifacts.tokenCount(),
                toolResults.tokenCount(),
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
            payload.put("compactionSection", snapshot.compactionSection());
            payload.put("toolResultsSection", snapshot.toolResultsSection());
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

    private List<ConversationTurnView> loadRecentTurns(
            List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        try {
            int turnLimit = resolveRecentTurnLimit();
            List<ConversationTurnView> conversationRows = rows.stream()
                    .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                    .map(this::toConversationTurnView)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ConversationTurnView::createdAt))
                    .toList();
            return ConversationTurnGrouper.flattenRecentCompleteTurns(conversationRows, turnLimit).stream()
                    .sorted(Comparator.comparing(ConversationTurnView::createdAt))
                    .toList();
        } catch (Exception e) {
            log.warn("context engine 构建最近轮次失败: error={}", e.getMessage());
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
            log.warn("context engine 构建工具结果片段失败: error={}", e.getMessage());
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

    private AgentConfigProperties.ContextConfig.SliceConfig sliceConfig() {
        AgentConfigProperties.ContextConfig.SliceConfig slice = config.getContext().getSlice();
        return slice != null ? slice : new AgentConfigProperties.ContextConfig.SliceConfig();
    }

    private AgentConfigProperties.ContextConfig.ReportConfig reportConfig() {
        AgentConfigProperties.ContextConfig.ReportConfig report = config.getContext().getReport();
        return report != null ? report : new AgentConfigProperties.ContextConfig.ReportConfig();
    }

    private int resolveRecentTurnLimit() {
        int configured = sliceConfig().getRecentTurnLimit();
        if (configured > 0) {
            return configured;
        }
        if (config.getSession().getMaxRecentTurns() > 0) {
            return config.getSession().getMaxRecentTurns();
        }
        return DEFAULT_RECENT_TURN_LIMIT;
    }

    private int resolveRecentArtifactLimit() {
        int configured = sliceConfig().getRecentArtifactLimit();
        return configured > 0 ? configured : DEFAULT_ARTIFACT_LIMIT;
    }

    @Nullable
    private ConversationTurnView toConversationTurnView(
            SessionTranscriptRepository.SessionTranscriptEntryRow row
    ) {
        if (row.role() == null || row.role().isBlank()) {
            return null;
        }
        Map<String, Object> payload = sessionPruningEngine.readPayload(row.payloadJson());
        String content = sessionPruningEngine.stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return null;
        }
        return new ConversationTurnView(
                row.sessionId(),
                row.role(),
                content,
                row.createdAt(),
                sessionPruningEngine.stringValue(payload.get("reasoningSummary"))
        );
    }
}
