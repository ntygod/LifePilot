package com.lifepilot.agent.orchestration;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.ChatTurnService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * Agent 执行持久化与 turn 状态辅助。
 *
 * <p>将 orchestrator 中的用户消息落库、助手结果落库、turn 绑定与状态回写收口，避免编排层继续混写持久化细节。
 *
 * @author zsg
 * @since 2026-03-25
 */
final class AgentExecutionPersistenceSupport {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionPersistenceSupport.class);
    private static final String TEST_SESSION_PREFIX = "test:";
    private static final String EVAL_SESSION_PREFIX = "eval-";

    private final AgentPersistenceHandler persistenceHandler;
    @Nullable
    private final ChatTurnService chatTurnService;

    AgentExecutionPersistenceSupport(AgentPersistenceHandler persistenceHandler,
                                     @Nullable ChatTurnService chatTurnService) {
        this.persistenceHandler = persistenceHandler;
        this.chatTurnService = chatTurnService;
    }

    /** 判断当前会话是否为测试/评测会话，这类会话不写入正式 transcript。 */
    boolean isTransientSession(@Nullable String sessionId) {
        return sessionId != null
                && (sessionId.startsWith(TEST_SESSION_PREFIX) || sessionId.startsWith(EVAL_SESSION_PREFIX));
    }

    /**
     * 持久化用户输入及其附件。
     *
     * @return 用户 transcript entryId；若当前会话不落库则返回 {@code null}
     */
    @Nullable
    String persistUserTurn(ReactAgentState state, AgentRequest request) {
        if (isTransientSession(request.sessionId())) {
            return null;
        }
        String userEntryId = persistenceHandler.persistUserMessageReturningId(state, request.action());
        persistenceHandler.persistUserMediaAttachments(
                userEntryId, state.sessionId(), request.mediaContents());
        return userEntryId;
    }

    /** 持久化同步链路的最终助手输出，并补齐注入记录、媒体附件等衍生数据。 */
    @Nullable
    String persistAssistantSync(ReactAgentState state,
                                @Nullable String reactStepsJson,
                                AgentLoopContext loopContext) {
        if (isTransientSession(state.sessionId())) {
            return null;
        }
        String assistantEntryId = persistenceHandler.persistAssistantMessage(
                state, reactStepsJson, loopContext.getCollectedArtifactRefs());
        persistAssistantArtifacts(state, assistantEntryId, loopContext);
        return assistantEntryId;
    }

    /** 持久化流式链路的最终助手输出，并同步保存 A2UI 与工具媒体产物。 */
    @Nullable
    String persistAssistantStreaming(ReactAgentState state,
                                     String finalContent,
                                     @Nullable String reasoningSummary,
                                     @Nullable String a2uiJson,
                                     @Nullable String reactStepsJson,
                                     AgentLoopContext loopContext) {
        if (isTransientSession(state.sessionId())) {
            return null;
        }
        String assistantEntryId = persistenceHandler.persistAssistantMessageWithA2ui(
                state, finalContent, reasoningSummary, a2uiJson, reactStepsJson,
                loopContext.getCollectedArtifactRefs());
        persistAssistantArtifacts(state, assistantEntryId, loopContext);
        return assistantEntryId;
    }

    /** 将 turn 与最新 trace 绑定，便于刷新历史或恢复执行时定位到正确轨迹。 */
    void bindTurnTrace(ReactAgentState state) {
        if (chatTurnService == null || state.turnId() == null || state.turnId().isBlank()) {
            return;
        }
        chatTurnService.bindTrace(state.sessionId(), state.turnId(), state.traceId());
    }

    /** 回写 turn 完成态，包括 assistant entry、trace 关系和最终 completionMode。 */
    void markTurnCompleted(ReactAgentState state,
                           @Nullable String assistantEntryId,
                           ChatTurnStatus turnStatus) {
        if (chatTurnService == null || state.turnId() == null || state.turnId().isBlank()) {
            return;
        }
        chatTurnService.markCompleted(
                state.sessionId(),
                state.turnId(),
                turnStatus,
                assistantEntryId,
                state.traceId(),
                state.resumedFromTraceId(),
                state.completionMode()
        );
    }

    /** 回写 turn 失败态，统一收口错误消息提取逻辑。 */
    void markTurnFailed(ReactAgentState state, Exception error) {
        if (chatTurnService == null || state.turnId() == null || state.turnId().isBlank()) {
            return;
        }
        String errorMessage = error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : error.getClass().getSimpleName();
        chatTurnService.markFailed(
                state.sessionId(),
                state.turnId(),
                state.traceId(),
                500,
                errorMessage
        );
    }

    /** 在挂起前保存工作区快照，确保后续恢复仍能拿到一致上下文。 */
    void saveWorkspaceForSuspend(ReactAgentState state) {
        persistenceHandler.saveWorkspaceForSuspend(state);
    }

    /** 在助手消息落库后统一补齐注入记录、工具媒体、工作区解析和异步后处理。 */
    private void persistAssistantArtifacts(ReactAgentState state,
                                           String assistantEntryId,
                                           AgentLoopContext loopContext) {
        log.debug("持久化助手产物: sessionId={}, entryId={}, traceId={}",
                state.sessionId(), assistantEntryId, state.traceId());
        persistenceHandler.persistInjectionRecord(assistantEntryId, state.sessionId(),
                state.traceId(), loopContext);
        var toolMedia = loopContext.getCollectedToolMedia();
        persistenceHandler.persistToolMediaAttachments(assistantEntryId, state.sessionId(), toolMedia);
        log.debug("工具媒体附件已持久化: entryId={}, mediaCount={}", assistantEntryId,
                toolMedia != null ? toolMedia.size() : 0);
        loopContext.clearToolMedia();
        persistenceHandler.resolveWorkspaceForTrace(state.sessionId(), state.traceId());
        persistenceHandler.asyncPostProcess(state);
        log.debug("助手产物持久化完成: entryId={}", assistantEntryId);
    }
}
