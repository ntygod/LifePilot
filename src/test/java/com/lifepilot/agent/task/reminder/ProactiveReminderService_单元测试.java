package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ProactiveReminderService 单元测试。
 *
 * <p>验证主动提醒桥接层能够把主题决策转换为真实通知。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ProactiveReminderService_单元测试 {

    private ReminderSignalCollector signalCollector;
    private NotificationService notificationService;
    private NotificationRepository notificationRepository;
    private ReminderExecutionRepository executionRepository;
    private ReminderMessageGenerator messageGenerator;
    private ReminderOutcomeInferenceService outcomeInferenceService;
    private AgentConfigProperties config;
    private NotificationProperties notificationProperties;
    private ProactiveReminderService service;

    @BeforeEach
    void setUp() {
        signalCollector = mock(ReminderSignalCollector.class);
        notificationService = mock(NotificationService.class);
        notificationRepository = mock(NotificationRepository.class);
        executionRepository = mock(ReminderExecutionRepository.class);
        messageGenerator = mock(ReminderMessageGenerator.class);
        outcomeInferenceService = mock(ReminderOutcomeInferenceService.class);
        config = new AgentConfigProperties();
        notificationProperties = new NotificationProperties();
        notificationProperties.setDefaultUserId("default");
        config.getTask().setProactiveReminderQuietHoursStart(null);
        config.getTask().setProactiveReminderQuietHoursEnd(null);
        service = new ProactiveReminderService(
                signalCollector,
                new ReminderDecisionEngine(),
                notificationService,
                notificationRepository,
                executionRepository,
                null,
                messageGenerator,
                outcomeInferenceService,
                config,
                notificationProperties
        );
        when(messageGenerator.generate(anyString(), any(), any(), any()))
                .thenReturn(new ReminderMessage("请留意这件事，现在处理会更合适。", "fallback", null, null));
    }

    @Test
    void runOnce_存在高分候选_发送通知() {
        Instant now = Instant.now();
        ReminderTopicSnapshot topic = new ReminderTopicSnapshot(
                "entity:bill",
                "缴水费",
                List.of(new ReminderSignal(
                        "sig-1",
                        ReminderSignalKind.DEADLINE,
                        0.95f,
                        0.95f,
                        3,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(2)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今晚前完成"
                )),
                ReminderTopicState.empty()
        );

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(anyString(), any())).thenReturn(List.of(topic));
        when(notificationService.send(any())).thenReturn(List.of("n1"));

        ProactiveReminderRunResult result = service.runOnce();

        assertThat(result.remindersSent()).isEqualTo(1);
        verify(notificationService, times(1)).send(any());
        verify(executionRepository, times(1)).saveRun(any());
        verify(executionRepository, times(1)).saveDecision(any());
        verify(executionRepository, times(1)).saveEvidenceBatch(any());
        verify(executionRepository, times(1)).updateRun(any());
        verify(outcomeInferenceService, times(1)).inferRecentOutcomes(eq("default"), any());
    }

    @Test
    void runOnce_LLM文案生成成功_通知携带模型元数据() {
        Instant now = Instant.now();
        ReminderTopicSnapshot topic = new ReminderTopicSnapshot(
                "entity:report",
                "报销处理",
                List.of(new ReminderSignal(
                        "sig-report",
                        ReminderSignalKind.DEADLINE,
                        0.95f,
                        0.92f,
                        3,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(2)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今天把报销收尾"
                )),
                ReminderTopicState.empty()
        );

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(anyString(), any())).thenReturn(List.of(topic));
        when(messageGenerator.generate(anyString(), any(), any(), any()))
                .thenReturn(new ReminderMessage(
                        "你前面提过报销还没收尾，今天顺手处理掉会更省心。",
                        "llm",
                        "openai",
                        "gpt-5.4-mini"
                ));
        when(notificationService.send(any())).thenReturn(List.of("n-llm"));

        ProactiveReminderRunResult result = service.runOnce();

        assertThat(result.remindersSent()).isEqualTo(1);
        verify(notificationService).send(argThat(request ->
                "llm".equals(request.metadata().get("messageMode"))
                        && "openai".equals(request.metadata().get("messageProvider"))
                        && "gpt-5.4-mini".equals(request.metadata().get("messageModel"))
                        && request.content() instanceof ResponseContent.TextContent textContent
                        && textContent.text().contains("报销还没收尾")
        ));
    }

    @Test
    void runOnce_策略版本已解析_运行记录决策和通知都带版本信息() {
        Instant now = Instant.now();
        ReminderTopicSnapshot topic = new ReminderTopicSnapshot(
                "entity:bill",
                "缴水费",
                List.of(new ReminderSignal(
                        "sig-1",
                        ReminderSignalKind.DEADLINE,
                        0.95f,
                        0.95f,
                        3,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(2)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今晚前完成"
                )),
                ReminderTopicState.empty()
        );
        ReminderPolicyVersionService policyVersionService = mock(ReminderPolicyVersionService.class);
        ReminderPolicyVersionRecord versionRecord = new ReminderPolicyVersionRecord(
                "policy-v7",
                "default",
                7,
                "sig-7",
                "{\"dailyMaxReminders\":2}",
                "feedback+replay",
                "{}",
                now,
                now
        );
        ProactiveReminderService versionedService = new ProactiveReminderService(
                signalCollector,
                new ReminderDecisionEngine(),
                notificationService,
                notificationRepository,
                executionRepository,
                null,
                new ReminderPolicyTuner(),
                new ReminderOpportunityPolicySelector(config),
                new ReminderActionPolicySelector(),
                messageGenerator,
                outcomeInferenceService,
                null,
                policyVersionService,
                config,
                notificationProperties
        );

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(anyString(), any())).thenReturn(List.of(topic));
        when(notificationService.send(any())).thenReturn(List.of("n-policy"));
        when(policyVersionService.findLatestByUserId("default")).thenReturn(Optional.empty());
        when(policyVersionService.resolve(eq("default"), any(), any(), isNull(), any(), any()))
                .thenReturn(versionRecord);

        versionedService.runOnce();

        ArgumentCaptor<ReminderRunRecord> runCaptor = ArgumentCaptor.forClass(ReminderRunRecord.class);
        ArgumentCaptor<ReminderDecisionRecord> decisionCaptor = ArgumentCaptor.forClass(ReminderDecisionRecord.class);
        verify(executionRepository).saveRun(runCaptor.capture());
        verify(executionRepository).saveDecision(decisionCaptor.capture());
        verify(notificationService).send(argThat(request ->
                "policy-v7".equals(request.metadata().get("policyVersionId"))
                        && "7".equals(request.metadata().get("policyVersion"))
        ));
        assertThat(runCaptor.getValue().policyVersionId()).isEqualTo("policy-v7");
        assertThat(runCaptor.getValue().policyVersion()).isEqualTo(7);
        assertThat(decisionCaptor.getValue().policyVersionId()).isEqualTo("policy-v7");
        assertThat(decisionCaptor.getValue().policyVersion()).isEqualTo(7);
    }

    @Test
    void runOnce_机会学习认为值得提醒_会从跳过提升为轻提醒() {
        Instant now = Instant.now();
        ReminderTopicSnapshot topic = new ReminderTopicSnapshot(
                "commitment:expense",
                "整理报销",
                List.of(new ReminderSignal(
                        "sig-expense",
                        ReminderSignalKind.COMMITMENT,
                        0.70f,
                        0.72f,
                        2,
                        now.minus(Duration.ofHours(3)),
                        null,
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "这件事你最近反复提过"
                )),
                new ReminderTopicState(null, 0, 2, 1, 0, 0, 0, false)
        );
        ReminderCandidate lowScoreCandidate = new ReminderCandidate(
                "commitment:expense",
                "整理报销",
                ReminderCandidateType.COMMITMENT_GAP,
                "sig-expense",
                0.60f,
                0.70f,
                0.44f,
                0.76f,
                0.88f,
                0.02f,
                0.03f,
                0.52f,
                null,
                "你最近几次都提过这件事"
        );
        ReminderDecisionEngine mockedEngine = mock(ReminderDecisionEngine.class);
        ProactiveReminderService promotingService = new ProactiveReminderService(
                signalCollector,
                mockedEngine,
                notificationService,
                notificationRepository,
                executionRepository,
                null,
                new ReminderPolicyTuner(),
                new ReminderOpportunityPolicySelector(
                        new ReminderActionContextualBandit(0.15f, 6, 2, 1.0d),
                        0.72f,
                        0.34f,
                        0.08f
                ),
                new ReminderActionPolicySelector(
                        new ReminderActionContextualBandit(0.15f, 6, 2, 1.0d)
                ),
                messageGenerator,
                outcomeInferenceService,
                null,
                null,
                config,
                notificationProperties
        );

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(eq("default"), any())).thenReturn(List.of(topic));
        when(mockedEngine.evaluate(any(), any(), any())).thenReturn(List.of(
                new ReminderDecision(lowScoreCandidate, ReminderAction.SKIP, null, "候选得分不足")
        ));
        when(executionRepository.summarizeActionPerformanceByUserIdSince(eq("default"), any()))
                .thenReturn(List.of());
        when(executionRepository.findActionTrainingExamplesByUserIdSince(eq("default"), any(), anyInt()))
                .thenReturn(List.of(
                        new ReminderActionTrainingExample(
                                "COMMITMENT_GAP", ReminderAction.SOFT_PUSH,
                                0.53f, 0.62f, 0.72f, 0.42f, 0.78f, 0.86f,
                                0.02f, 0.03f, 0, 2, 1, 0, 0, 0, 0.92f
                        ),
                        new ReminderActionTrainingExample(
                                "COMMITMENT_GAP", ReminderAction.SOFT_PUSH,
                                0.55f, 0.64f, 0.74f, 0.45f, 0.80f, 0.88f,
                                0.02f, 0.03f, 0, 2, 1, 0, 0, 0, 0.90f
                        ),
                        new ReminderActionTrainingExample(
                                "COMMITMENT_GAP", ReminderAction.SOFT_PUSH,
                                0.52f, 0.63f, 0.71f, 0.40f, 0.79f, 0.87f,
                                0.03f, 0.04f, 0, 1, 1, 0, 0, 0, 0.88f
                        ),
                        new ReminderActionTrainingExample(
                                "COMMITMENT_GAP", ReminderAction.NORMAL_PUSH,
                                0.56f, 0.65f, 0.73f, 0.46f, 0.74f, 0.88f,
                                0.02f, 0.04f, 0, 1, 1, 0, 0, 0, 0.82f
                        ),
                        new ReminderActionTrainingExample(
                                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                                0.74f, 0.76f, 0.81f, 0.40f, 0.72f, 0.83f,
                                0.02f, 0.04f, 0, 2, 1, 0, 0, 0, 0.80f
                        ),
                        new ReminderActionTrainingExample(
                                "HABIT_WINDOW", ReminderAction.NORMAL_PUSH,
                                0.76f, 0.78f, 0.82f, 0.44f, 0.70f, 0.84f,
                                0.02f, 0.04f, 0, 1, 1, 0, 0, 0, 0.78f
                        )
                ));
        when(notificationService.send(any())).thenReturn(List.of("n-promoted"));

        ProactiveReminderRunResult result = promotingService.runOnce();

        assertThat(result.remindersSent()).isEqualTo(1);
        verify(notificationService).send(argThat(request ->
                "SOFT_PUSH".equals(request.metadata().get("action"))
        ));
    }

    @Test
    void runDeferredWakeups_只重评估指定主题并发送通知() {
        Instant now = Instant.now();
        ReminderTopicSnapshot deferredTopic = new ReminderTopicSnapshot(
                "topic:bill",
                "缴纳水费",
                List.of(new ReminderSignal(
                        "sig-bill",
                        ReminderSignalKind.DEADLINE,
                        0.92f,
                        0.88f,
                        3,
                        now.minus(Duration.ofHours(2)),
                        now.plus(Duration.ofHours(1)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今天需要完成缴费"
                )),
                ReminderTopicState.empty()
        );
        ReminderTopicSnapshot unrelatedTopic = new ReminderTopicSnapshot(
                "habit:exercise",
                "运动打卡",
                List.of(new ReminderSignal(
                        "sig-exercise",
                        ReminderSignalKind.HABIT,
                        0.70f,
                        0.65f,
                        2,
                        now.minus(Duration.ofHours(2)),
                        null,
                        null,
                        21,
                        23,
                        0.0f,
                        true,
                        false,
                        "晚间运动"
                )),
                ReminderTopicState.empty()
        );

        config.getTask().setProactiveReminderQuietHoursStart(null);
        config.getTask().setProactiveReminderQuietHoursEnd(null);
        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(anyString(), any())).thenReturn(List.of(deferredTopic, unrelatedTopic));
        when(executionRepository.findActionTrainingExamplesByUserIdSince(eq("default"), any(), anyInt()))
                .thenReturn(List.of());
        when(notificationService.send(any())).thenReturn(List.of("n2"));

        ProactiveReminderRunResult result = service.runDeferredWakeups(List.of(
                new ReminderDeferredWakeup(
                        "decision-1",
                        "run-1",
                        "default",
                        "topic:bill",
                        "缴纳水费",
                        "sig-bill",
                        "DUE_SOON",
                        now.minusSeconds(30)
                )
        ));

        assertThat(result.topicsCollected()).isEqualTo(1);
        assertThat(result.remindersSent()).isEqualTo(1);
        verify(notificationService, times(1)).send(any());
        verify(executionRepository, times(1)).saveDecision(any());
    }

    @Test
    void runDeferredWakeups_主题已消失_写入终止跳过决策() {
        Instant now = Instant.now();

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(anyString(), any())).thenReturn(List.of());

        ProactiveReminderRunResult result = service.runDeferredWakeups(List.of(
                new ReminderDeferredWakeup(
                        "decision-missing",
                        "run-missing",
                        "default",
                        "topic:missing",
                        "已消失主题",
                        "sig-missing",
                        "COMMITMENT_GAP",
                        now.minusSeconds(60)
                )
        ));

        assertThat(result.topicsCollected()).isZero();
        verify(notificationService, never()).send(any());
        verify(executionRepository, times(1)).saveDecision(any());
    }

    @Test
    void runOnce_动作学习偏好轻提醒_会调整最终动作() {
        Instant now = Instant.now();
        ReminderTopicSnapshot topic = new ReminderTopicSnapshot(
                "bill:water",
                "缴纳水费",
                List.of(new ReminderSignal(
                        "sig-bill",
                        ReminderSignalKind.DEADLINE,
                        0.90f,
                        0.88f,
                        3,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(2)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今晚前缴纳水费"
                )),
                ReminderTopicState.empty()
        );

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(eq("default"), any())).thenReturn(List.of(topic));
        when(executionRepository.summarizeActionPerformanceByUserIdSince(eq("default"), any()))
                .thenReturn(List.of(
                        new ReminderActionPerformanceStats("DUE_SOON", ReminderAction.NORMAL_PUSH, 5, 0, 1, 2, 2, 0),
                        new ReminderActionPerformanceStats("DUE_SOON", ReminderAction.SOFT_PUSH, 5, 2, 2, 0, 0, 1)
                ));
        when(executionRepository.findActionTrainingExamplesByUserIdSince(eq("default"), any(), anyInt()))
                .thenReturn(List.of());
        when(notificationService.send(any())).thenReturn(List.of("n-soft"));

        ProactiveReminderRunResult result = service.runOnce();

        assertThat(result.remindersSent()).isEqualTo(1);
        verify(notificationService).send(argThat(request ->
                "SOFT_PUSH".equals(request.metadata().get("action"))
        ));
        verify(executionRepository).findActionTrainingExamplesByUserIdSince(eq("default"), any(), anyInt());
    }

    @Test
    void runOnce_先做隐式结果推断再采集主题() {
        Instant now = Instant.now();
        ReminderTopicSnapshot topic = new ReminderTopicSnapshot(
                "entity:bill",
                "缴水费",
                List.of(new ReminderSignal(
                        "sig-order",
                        ReminderSignalKind.DEADLINE,
                        0.95f,
                        0.95f,
                        3,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(2)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今晚前完成"
                )),
                ReminderTopicState.empty()
        );

        when(notificationRepository.countSentByUserIdAndTypeSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(signalCollector.collect(anyString(), any())).thenReturn(List.of(topic));
        when(notificationService.send(any())).thenReturn(List.of("n-order"));

        service.runOnce();

        InOrder inOrder = inOrder(outcomeInferenceService, signalCollector);
        inOrder.verify(outcomeInferenceService).inferRecentOutcomes(eq("default"), any());
        inOrder.verify(signalCollector).collect(eq("default"), any());
    }
}
