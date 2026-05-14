package com.lifepilot.agent.task.proactive.training;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderAction;
import com.lifepilot.agent.task.reminder.ReminderCandidateType;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderReplaySample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProactiveTrainingReplayService 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class ProactiveTrainingReplayService_单元测试 {

    private static final String USER = "u1";

    @Test
    void 正负例分层采样() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        // 默认阈值 pos=0.6, neg=0.2, topK=10

        var samples = List.of(
                sample("acted-1", ReminderAction.NORMAL_PUSH, true, 0.95f),  // reward=0.95 正例
                sample("acted-2", ReminderAction.SOFT_PUSH, true, 0.85f),    // 0.85 正例
                sample("dismissed-1", ReminderAction.NORMAL_PUSH, false, true, 0.0f),  // reward=0.18 负例
                sample("notRelevant-1", ReminderAction.NORMAL_PUSH, false, false, 0.0f, true),  // reward=0.0 负例
                sample("neutral-1", ReminderAction.SKIP, false, false, 0.0f)  // reward=0.42 既非正也非负
        );
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(samples);

        var service = new ProactiveTrainingReplayService(repo, cfg);
        var result = service.buildFewShotSamples(USER, Instant.now().minusSeconds(86400), 100);

        // 2 正例 + 2 负例 = 4 条
        assertThat(result).hasSize(4);
        assertThat(result.stream().filter(ProactiveFewShotSample::positive).count()).isEqualTo(2L);
        assertThat(result.stream().filter(s -> !s.positive()).count()).isEqualTo(2L);
    }

    @Test
    void top_K截断() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTrainingTopKPositive(1);
        cfg.setProactiveTrainingTopKNegative(1);

        var samples = List.of(
                sample("acted-1", ReminderAction.NORMAL_PUSH, true, 0.95f),
                sample("acted-2", ReminderAction.SOFT_PUSH, true, 0.85f),
                sample("acted-3", ReminderAction.NORMAL_PUSH, true, 0.75f),
                sample("dismissed-1", ReminderAction.NORMAL_PUSH, false, true, 0.0f),
                sample("dismissed-2", ReminderAction.SOFT_PUSH, false, true, 0.0f)
        );
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(samples);

        var service = new ProactiveTrainingReplayService(repo, cfg);
        var result = service.buildFewShotSamples(USER, Instant.now().minusSeconds(86400), 100);

        assertThat(result).hasSize(2);
        // 正例按 reward 降序，top-1 应当是 0.95
        var positive = result.stream().filter(ProactiveFewShotSample::positive).findFirst().orElseThrow();
        assertThat(positive.reward()).isEqualTo(0.95f);
    }

    @Test
    void 空列表直接返回() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();

        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of());

        var service = new ProactiveTrainingReplayService(repo, cfg);
        assertThat(service.buildFewShotSamples(USER, Instant.now(), 100)).isEmpty();
    }

    @Test
    void 无效参数返回空() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        var service = new ProactiveTrainingReplayService(repo, cfg);

        assertThat(service.buildFewShotSamples(null, Instant.now(), 100)).isEmpty();
        assertThat(service.buildFewShotSamples("", Instant.now(), 100)).isEmpty();
        assertThat(service.buildFewShotSamples(USER, Instant.now(), 0)).isEmpty();
    }

    @Test
    void 上下文摘要包含关键分数字段() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        var samples = List.of(sample("acted-1", ReminderAction.NORMAL_PUSH, true, 0.95f));
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(samples);

        var service = new ProactiveTrainingReplayService(repo, cfg);
        var result = service.buildFewShotSamples(USER, Instant.now(), 100);

        assertThat(result).hasSize(1);
        var digest = result.getFirst().contextDigest();
        assertThat(digest).contains("score=");
        assertThat(digest).contains("evi=");
        assertThat(digest).contains("timing=");
        assertThat(digest).contains("outcome=ACTED");
    }

    private ReminderReplaySample sample(String id, ReminderAction action, boolean acted, float actedReward) {
        return sample(id, action, acted, false, actedReward, false);
    }

    private ReminderReplaySample sample(String id, ReminderAction action, boolean acted, boolean dismissed, float actedReward) {
        return sample(id, action, acted, dismissed, actedReward, false);
    }

    private ReminderReplaySample sample(String id, ReminderAction action, boolean acted, boolean dismissed,
                                        float actedReward, boolean notRelevant) {
        return new ReminderReplaySample(
                id,
                Instant.now(),
                "topic-" + id,
                "Title " + id,
                "signal-" + id,
                ReminderCandidateType.DUE_SOON,
                action,
                action,  // baseAction
                null,
                "test",
                0.7f,  // finalScore
                0.6f, 0.7f, 0.5f, 0.8f, 0.9f,  // evidence, timing, urgency, userFit, actionability
                0.1f, 0.0f,  // duplicate, fatigue
                1, 2, 3, 4, 5, 0,  // topic stats
                acted, actedReward, false, dismissed, notRelevant, false
        );
    }
}
