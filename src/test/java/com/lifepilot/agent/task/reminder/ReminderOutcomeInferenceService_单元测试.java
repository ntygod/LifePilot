package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceItemKind;
import com.lifepilot.memory.workspace.WorkspaceStatus;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.workflow.model.StepLog;
import com.lifepilot.workflow.model.StepState;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReminderOutcomeInferenceService 单元测试。
 *
 * <p>验证系统能够从工作区状态和后续对话中推断提醒已完成。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderOutcomeInferenceService_单元测试 {

    private ReminderExecutionRepository executionRepository;
    private ReminderOutcomeRepository outcomeRepository;
    private SessionWorkspaceService workspaceService;
    private SemanticMemory semanticMemory;
    private EpisodicMemory episodicMemory;
    private WorkflowRepository workflowRepository;
    private TraceQuery traceQuery;
    private AgentConfigProperties config;
    private ReminderOutcomeInferenceService service;

    @BeforeEach
    void setUp() {
        executionRepository = mock(ReminderExecutionRepository.class);
        outcomeRepository = mock(ReminderOutcomeRepository.class);
        workspaceService = mock(SessionWorkspaceService.class);
        semanticMemory = mock(SemanticMemory.class);
        episodicMemory = mock(EpisodicMemory.class);
        workflowRepository = mock(WorkflowRepository.class);
        traceQuery = mock(TraceQuery.class);
        config = new AgentConfigProperties();
        config.getTask().setProactiveReminderOutcomeInferenceLookbackDays(30);
        config.getTask().setProactiveReminderOutcomeInferenceBatchSize(20);
        service = new ReminderOutcomeInferenceService(
                executionRepository,
                outcomeRepository,
                workspaceService,
                semanticMemory,
                episodicMemory,
                workflowRepository,
                traceQuery,
                config
        );
    }

    @Test
    void inferRecentOutcomes_工作区已解决_落隐式完成() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        Instant decidedAt = now.minusSeconds(600);
        ReminderOutcomeInferenceCandidate candidate = new ReminderOutcomeInferenceCandidate(
                UUID.randomUUID().toString(),
                "notification-1",
                "default",
                "workspace:task-1",
                "提交报销",
                "COMMITMENT_GAP",
                decidedAt
        );
        WorkspaceItem resolvedItem = new WorkspaceItem(
                "ws-1",
                "web:conv-1",
                WorkspaceItemKind.TASK_STATE,
                "提交报销",
                "用户已完成报销",
                "{\"status\":\"done\"}",
                WorkspaceStatus.RESOLVED,
                90,
                "task-1",
                "trace-1",
                null,
                decidedAt.minusSeconds(120),
                now.minusSeconds(60)
        );

        when(executionRepository.findPendingOutcomeInferenceCandidates(eq("default"), any(), eq(20)))
                .thenReturn(List.of(candidate));
        when(workspaceService.findLatestByTaskOrItemId("task-1"))
                .thenReturn(Optional.of(resolvedItem));

        service.inferRecentOutcomes("default", now);

        verify(outcomeRepository, times(1)).saveOutcome(argThat(record ->
                record.topicKey().equals("workspace:task-1")
                        && record.outcomeType() == ReminderOutcomeType.ACTED
                        && record.evidenceSource() == ReminderOutcomeEvidenceSource.WORKSPACE_STATE
                        && record.notificationId().equals("notification-1")
                        && record.attributionScore() > 0.7f
        ));
    }

    @Test
    void inferRecentOutcomes_后续对话出现完成线索_落隐式完成() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        Instant decidedAt = now.minusSeconds(900);
        ReminderOutcomeInferenceCandidate candidate = new ReminderOutcomeInferenceCandidate(
                UUID.randomUUID().toString(),
                "notification-2",
                "default",
                "conversation:web:conv-2",
                "报销处理",
                "COMMITMENT_GAP",
                decidedAt
        );
        ConversationRecord record = new ConversationRecord(
                "conv-2",
                "web:conv-2",
                "把报销处理掉",
                "用户提到报销处理",
                List.of(
                        new MessageRecord(
                                "m-1",
                                "web:conv-2",
                                "user",
                                "报销我已经处理好了，刚刚提交了。",
                                null,
                                CompressionLevel.ORIGINAL,
                                false,
                                null,
                                18,
                                now.minusSeconds(120)
                        )
                ),
                decidedAt.minusSeconds(300),
                now.minusSeconds(120)
        );

        when(executionRepository.findPendingOutcomeInferenceCandidates(eq("default"), any(), eq(20)))
                .thenReturn(List.of(candidate));
        when(workspaceService.findLatestByTaskOrItemId(any())).thenReturn(Optional.empty());
        when(semanticMemory.findById(any())).thenReturn(Optional.empty());
        when(episodicMemory.getById("web:conv-2")).thenReturn(Optional.of(record));

        service.inferRecentOutcomes("default", now);

        verify(outcomeRepository, times(1)).saveOutcome(argThat(saved ->
                saved.topicKey().equals("conversation:web:conv-2")
                        && saved.outcomeType() == ReminderOutcomeType.ACTED
                        && saved.evidenceSource() == ReminderOutcomeEvidenceSource.CONVERSATION_MESSAGE
                        && saved.attributionScore() > 0.5f
                        && saved.evidenceJson() != null
                        && saved.evidenceJson().contains("messagePreview")
        ));
    }

    @Test
    void inferRecentOutcomes_关联工作流已完成_落隐式完成() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        Instant decidedAt = now.minusSeconds(900);
        ReminderOutcomeInferenceCandidate candidate = new ReminderOutcomeInferenceCandidate(
                UUID.randomUUID().toString(),
                "notification-3",
                "default",
                "workspace:trace-88",
                "整理报销",
                "COMMITMENT_GAP",
                decidedAt
        );
        WorkspaceItem activeItem = new WorkspaceItem(
                "ws-2",
                "web:conv-3",
                WorkspaceItemKind.TASK_STATE,
                "整理报销",
                "等待工作流执行完成",
                "{\"workflowName\":\"expense-follow-up\"}",
                WorkspaceStatus.ACTIVE,
                80,
                "trace-88",
                "trace-88",
                null,
                decidedAt.minusSeconds(60),
                decidedAt.plusSeconds(60)
        );
        WorkflowInstance completedInstance = WorkflowInstance.builder()
                .id("wf-inst-1")
                .workflowId("expense-follow-up")
                .state(WorkflowState.COMPLETED)
                .context(new WorkflowContext())
                .completedStepIds(java.util.Set.of("submit-expense"))
                .traceId("trace-88")
                .startedAt(decidedAt.minusSeconds(120))
                .completedAt(now.minusSeconds(120))
                .createdAt(decidedAt.minusSeconds(120))
                .updatedAt(now.minusSeconds(120))
                .build();

        when(executionRepository.findPendingOutcomeInferenceCandidates(eq("default"), any(), eq(20)))
                .thenReturn(List.of(candidate));
        when(workspaceService.findLatestByTaskOrItemId("trace-88"))
                .thenReturn(Optional.of(activeItem));
        when(workflowRepository.findLatestByInstanceIdOrTraceId("trace-88"))
                .thenReturn(Optional.of(completedInstance));

        service.inferRecentOutcomes("default", now);

        verify(outcomeRepository, times(1)).saveOutcome(argThat(saved ->
                saved.topicKey().equals("workspace:trace-88")
                        && saved.evidenceSource() == ReminderOutcomeEvidenceSource.WORKFLOW_INSTANCE
                        && saved.evidenceJson() != null
                        && saved.evidenceJson().contains("wf-inst-1")
        ));
    }

    @Test
    void inferRecentOutcomes_工作流步骤完成命中主题_落隐式完成() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        Instant decidedAt = now.minusSeconds(900);
        ReminderOutcomeInferenceCandidate candidate = new ReminderOutcomeInferenceCandidate(
                UUID.randomUUID().toString(),
                "notification-4",
                "default",
                "workspace:trace-99",
                "提交报销",
                "COMMITMENT_GAP",
                decidedAt
        );
        WorkspaceItem activeItem = new WorkspaceItem(
                "ws-3",
                "web:conv-4",
                WorkspaceItemKind.TASK_STATE,
                "提交报销",
                "等待工作流步骤执行",
                null,
                WorkspaceStatus.ACTIVE,
                80,
                "trace-99",
                "trace-99",
                null,
                decidedAt.minusSeconds(60),
                decidedAt.plusSeconds(60)
        );
        WorkflowInstance runningInstance = WorkflowInstance.builder()
                .id("wf-inst-2")
                .workflowId("expense-follow-up")
                .state(WorkflowState.RUNNING)
                .context(new WorkflowContext())
                .completedStepIds(java.util.Set.of("submit-expense"))
                .traceId("trace-99")
                .startedAt(decidedAt.minusSeconds(180))
                .createdAt(decidedAt.minusSeconds(180))
                .updatedAt(now.minusSeconds(60))
                .build();
        StepLog completedStep = new StepLog(
                "step-log-1",
                "wf-inst-2",
                "submit-expense",
                "tool",
                StepState.COMPLETED,
                1,
                null,
                "{\"message\":\"报销已提交\"}",
                null,
                decidedAt.plusSeconds(120),
                now.minusSeconds(90),
                1200L,
                0,
                now.minusSeconds(90)
        );

        when(executionRepository.findPendingOutcomeInferenceCandidates(eq("default"), any(), eq(20)))
                .thenReturn(List.of(candidate));
        when(workspaceService.findLatestByTaskOrItemId("trace-99"))
                .thenReturn(Optional.of(activeItem));
        when(workflowRepository.findLatestByInstanceIdOrTraceId("trace-99"))
                .thenReturn(Optional.of(runningInstance));
        when(workflowRepository.findStepLogs("wf-inst-2"))
                .thenReturn(List.of(completedStep));

        service.inferRecentOutcomes("default", now);

        verify(outcomeRepository, times(1)).saveOutcome(argThat(saved ->
                saved.topicKey().equals("workspace:trace-99")
                        && saved.evidenceSource() == ReminderOutcomeEvidenceSource.WORKFLOW_STEP_LOG
                        && saved.evidenceJson() != null
                        && saved.evidenceJson().contains("submit-expense")
        ));
    }

    @Test
    void inferRecentOutcomes_trace工具输出命中完成语义_落隐式完成() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        Instant decidedAt = now.minusSeconds(900);
        ReminderOutcomeInferenceCandidate candidate = new ReminderOutcomeInferenceCandidate(
                UUID.randomUUID().toString(),
                "notification-5",
                "default",
                "workspace:trace-100",
                "支付电费",
                "COMMITMENT_GAP",
                decidedAt
        );
        WorkspaceItem activeItem = new WorkspaceItem(
                "ws-4",
                "web:conv-5",
                WorkspaceItemKind.TASK_STATE,
                "支付电费",
                "等待工具执行支付",
                null,
                WorkspaceStatus.ACTIVE,
                85,
                "trace-100",
                "trace-100",
                null,
                decidedAt.minusSeconds(60),
                decidedAt.plusSeconds(60)
        );
        ToolCallStep toolCallStep = new ToolCallStep(
                3,
                now.minusSeconds(120),
                java.time.Duration.ofSeconds(2),
                "payment_tool",
                "submit_bill_payment",
                "{\"bill\":\"electricity\"}",
                "{\"message\":\"电费已支付成功\"}",
                true,
                null,
                RiskLevel.MEDIUM
        );

        when(executionRepository.findPendingOutcomeInferenceCandidates(eq("default"), any(), eq(20)))
                .thenReturn(List.of(candidate));
        when(workspaceService.findLatestByTaskOrItemId("trace-100"))
                .thenReturn(Optional.of(activeItem));
        when(workflowRepository.findLatestByInstanceIdOrTraceId("trace-100"))
                .thenReturn(Optional.empty());
        when(traceQuery.getSteps("trace-100"))
                .thenReturn(List.of(toolCallStep));

        service.inferRecentOutcomes("default", now);

        verify(outcomeRepository, times(1)).saveOutcome(argThat(saved ->
                saved.topicKey().equals("workspace:trace-100")
                        && saved.evidenceSource() == ReminderOutcomeEvidenceSource.TRACE_TOOL_OUTPUT
                        && saved.evidenceJson() != null
                        && saved.evidenceJson().contains("submit_bill_payment")
        ));
    }
}
