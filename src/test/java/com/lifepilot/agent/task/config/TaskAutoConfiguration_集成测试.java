package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.agent.task.HeartbeatRunner;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.agent.task.reminder.ReminderMessageGenerator;
import com.lifepilot.agent.task.reminder.ReminderOutcomeInferenceService;
import com.lifepilot.agent.task.reminder.ReminderOutcomeRepository;
import com.lifepilot.agent.task.reminder.ReminderReplayEvaluationScheduler;
import com.lifepilot.agent.task.reminder.ReminderReplayReportRepository;
import com.lifepilot.agent.task.reminder.ReminderRetentionScheduler;
import com.lifepilot.agent.task.reminder.ReminderPolicyTuner;
import com.lifepilot.agent.task.reminder.ReminderTopicAliasRepository;
import com.lifepilot.agent.task.reminder.ReminderPolicyVersionRepository;
import com.lifepilot.agent.task.reminder.ReminderPolicyVersionService;
import com.lifepilot.agent.task.reminder.ReminderReplayService;
import com.lifepilot.agent.task.reminder.ReminderActionPolicySelector;
import com.lifepilot.agent.task.reminder.ReminderOpportunityPolicySelector;
import com.lifepilot.agent.task.reminder.ReminderSignalCollector;
import com.lifepilot.agent.task.reminder.ReminderWakeupScheduler;
import com.lifepilot.agent.task.reminder.ProactiveReminderService;
import com.lifepilot.agent.task.reminder.ReminderDecisionEngine;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * TaskAutoConfiguration 集成测试 — 验证 Bean 注册和构造函数注入。
 *
 * <p>轻量级测试，手动模拟 Bean 注册流程，验证 TaskAutoConfiguration
 * 注册的所有 Bean 能正确构造且依赖注入链完整。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class TaskAutoConfiguration_集成测试 {

    @Test
    void 所有Bean能正确构造_依赖注入链完整() {
        // 模拟依赖
        var jdbcTemplate = mock(JdbcTemplate.class);
        var agentOrchestrator = mock(AgentOrchestrator.class);
        var notificationService = mock(NotificationService.class);
        var config = new AgentConfigProperties();
        var notificationProperties = new com.lifepilot.notification.config.NotificationProperties();
        var sharedScheduler = mock(SharedScheduler.class);
        var scheduler = Executors.newScheduledThreadPool(1);
        var cleanupScheduler = Executors.newScheduledThreadPool(1);
        org.mockito.Mockito.when(sharedScheduler.heartbeat()).thenReturn(scheduler);
        org.mockito.Mockito.when(sharedScheduler.cleanup()).thenReturn(cleanupScheduler);

        var autoConfig = new TaskAutoConfiguration();

        // 验证 CronTaskRepository Bean
        CronTaskRepository repository = autoConfig.cronTaskRepository(jdbcTemplate);
        assertThat(repository).isNotNull();

        // 验证 CronScheduler Bean
        CronScheduler cronScheduler = autoConfig.cronScheduler(
                sharedScheduler, repository, agentOrchestrator, notificationService, notificationProperties);
        assertThat(cronScheduler).isNotNull();

        ReminderExecutionRepository reminderExecutionRepository = autoConfig.reminderExecutionRepository(jdbcTemplate);
        assertThat(reminderExecutionRepository).isNotNull();

        ReminderFeedbackRepository reminderFeedbackRepository = autoConfig.reminderFeedbackRepository(jdbcTemplate);
        assertThat(reminderFeedbackRepository).isNotNull();

        ReminderReplayReportRepository reminderReplayReportRepository =
                autoConfig.reminderReplayReportRepository(jdbcTemplate);
        assertThat(reminderReplayReportRepository).isNotNull();

        ReminderOutcomeRepository reminderOutcomeRepository = autoConfig.reminderOutcomeRepository(jdbcTemplate);
        assertThat(reminderOutcomeRepository).isNotNull();

        ReminderTopicAliasRepository reminderTopicAliasRepository =
                autoConfig.reminderTopicAliasRepository(jdbcTemplate);
        assertThat(reminderTopicAliasRepository).isNotNull();

        ReminderPolicyVersionRepository reminderPolicyVersionRepository =
                autoConfig.reminderPolicyVersionRepository(jdbcTemplate);
        assertThat(reminderPolicyVersionRepository).isNotNull();

        ReminderPolicyVersionService reminderPolicyVersionService =
                autoConfig.reminderPolicyVersionService(reminderPolicyVersionRepository);
        assertThat(reminderPolicyVersionService).isNotNull();

        ReminderSignalCollector reminderSignalCollector = autoConfig.reminderSignalCollector(
                null, null, null, null, mock(NotificationRepository.class),
                reminderFeedbackRepository, reminderOutcomeRepository, reminderTopicAliasRepository);
        assertThat(reminderSignalCollector).isNotNull();

        ReminderMessageGenerator reminderMessageGenerator = autoConfig.reminderMessageGenerator(
                null, null, config);
        assertThat(reminderMessageGenerator).isNotNull();

        ReminderPolicyTuner reminderPolicyTuner = autoConfig.reminderPolicyTuner();
        assertThat(reminderPolicyTuner).isNotNull();

        ReminderActionPolicySelector reminderActionPolicySelector = autoConfig.reminderActionPolicySelector(config);
        assertThat(reminderActionPolicySelector).isNotNull();

        ReminderOpportunityPolicySelector reminderOpportunityPolicySelector =
                autoConfig.reminderOpportunityPolicySelector(config);
        assertThat(reminderOpportunityPolicySelector).isNotNull();

        ReminderOutcomeInferenceService reminderOutcomeInferenceService = autoConfig.reminderOutcomeInferenceService(
                reminderExecutionRepository,
                reminderOutcomeRepository,
                null,
                null,
                null,
                mock(WorkflowRepository.class),
                mock(TraceQuery.class),
                config
        );
        assertThat(reminderOutcomeInferenceService).isNotNull();

        ReminderReplayService reminderReplayService = autoConfig.reminderReplayService(
                reminderExecutionRepository,
                reminderOpportunityPolicySelector,
                reminderActionPolicySelector,
                config
        );
        assertThat(reminderReplayService).isNotNull();

        ProactiveReminderService proactiveReminderService = autoConfig.proactiveReminderService(
                reminderSignalCollector,
                new ReminderDecisionEngine(),
                reminderPolicyTuner,
                reminderOpportunityPolicySelector,
                reminderActionPolicySelector,
                reminderMessageGenerator,
                notificationService,
                mock(NotificationRepository.class),
                reminderExecutionRepository,
                reminderFeedbackRepository,
                reminderOutcomeInferenceService,
                reminderReplayService,
                reminderPolicyVersionService,
                config,
                notificationProperties
        );
        assertThat(proactiveReminderService).isNotNull();

        ReminderWakeupScheduler reminderWakeupScheduler = autoConfig.reminderWakeupScheduler(
                sharedScheduler, reminderExecutionRepository, proactiveReminderService, config);
        assertThat(reminderWakeupScheduler).isNotNull();

        ReminderReplayEvaluationScheduler reminderReplayEvaluationScheduler =
                autoConfig.reminderReplayEvaluationScheduler(
                        sharedScheduler,
                        reminderExecutionRepository,
                        reminderReplayReportRepository,
                        reminderReplayService,
                        notificationProperties,
                        config
                );
        assertThat(reminderReplayEvaluationScheduler).isNotNull();

        ReminderRetentionScheduler reminderRetentionScheduler = autoConfig.reminderRetentionScheduler(
                sharedScheduler,
                reminderExecutionRepository,
                reminderFeedbackRepository,
                reminderReplayReportRepository,
                reminderTopicAliasRepository,
                config
        );
        assertThat(reminderRetentionScheduler).isNotNull();

        // 验证 HeartbeatRunner Bean
        HeartbeatRunner heartbeatRunner = autoConfig.heartbeatRunner(
                sharedScheduler, config, null);
        assertThat(heartbeatRunner).isNotNull();

        scheduler.shutdownNow();
        cleanupScheduler.shutdownNow();
    }

    @Test
    void CronTaskRepository_使用JdbcTemplate构造() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var autoConfig = new TaskAutoConfiguration();

        var repository = autoConfig.cronTaskRepository(jdbcTemplate);
        assertThat(repository).isInstanceOf(CronTaskRepository.class);
    }
}
