package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.task.proactive.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 提醒行为插件 — 将现有提醒管线适配为 {@link ProactiveBehavior} 接口。
 *
 * <p>detect() 使用现有信号采集器和候选检测器。
 * reason() 使用现有消息生成器（可能调 LLM）。
 * 现有学习管线（Bandit、策略调优、回放）保持独立运行不变。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ReminderBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(ReminderBehavior.class);

    private final ReminderSignalCollector signalCollector;
    private final ReminderCandidateDetector candidateDetector;
    private final ReminderMessageGenerator messageGenerator;
    @Nullable
    private final ReminderOutcomeInferenceService outcomeInferenceService;

    /** 简化构造器（测试用）。 */
    public ReminderBehavior(ReminderSignalCollector signalCollector,
                            ReminderCandidateDetector candidateDetector,
                            ReminderMessageGenerator messageGenerator) {
        this(signalCollector, candidateDetector, messageGenerator, null);
    }

    public ReminderBehavior(ReminderSignalCollector signalCollector,
                            ReminderCandidateDetector candidateDetector,
                            ReminderMessageGenerator messageGenerator,
                            @Nullable ReminderOutcomeInferenceService outcomeInferenceService) {
        this.signalCollector = signalCollector;
        this.candidateDetector = candidateDetector;
        this.messageGenerator = messageGenerator;
        this.outcomeInferenceService = outcomeInferenceService;
    }

    @Override
    public String name() {
        return "reminder";
    }

    @Override
    public com.lifepilot.agent.task.proactive.behavior.BehaviorLayer layer() {
        return com.lifepilot.agent.task.proactive.behavior.BehaviorLayer.HABIT_DRIVEN;
    }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // 维护：隐式结果推断
        inferOutcomesQuietly(ctx);

        // 信号采集
        var runtimeCtx = toReminderContext(ctx);
        var policyConfig = new ReminderPolicyConfig();
        var topics = signalCollector.collect(ctx.userId(), runtimeCtx);

        // 候选检测 + 评分
        var result = new ArrayList<ProactiveCandidate>();
        for (var snapshot : topics) {
            // 插件级过滤：已静音
            if (snapshot.state().muted()) {
                continue;
            }
            var candidates = candidateDetector.detect(snapshot, runtimeCtx, policyConfig);
            for (var candidate : candidates) {
                result.add(toProactiveCandidate(candidate, snapshot, policyConfig));
            }
        }
        log.debug("ReminderBehavior.detect: topics={}, candidates={}", topics.size(), result.size());
        return result;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var runtimeCtx = toReminderContext(ctx);
        var actions = new ArrayList<ProactiveAction>();

        for (var candidate : candidates) {
            if (!(candidate.detail() instanceof ReminderCandidateDetail detail)) {
                log.warn("ReminderBehavior.reason: 无法识别的 detail 类型, id={}", candidate.id());
                continue;
            }
            // 构造 ReminderDecision（reason 阶段需要 decision 来生成消息）
            var action = candidate.score() >= 0.7f ? ReminderAction.NORMAL_PUSH : ReminderAction.SOFT_PUSH;
            var decision = new ReminderDecision(
                    detail.candidate(), action, null, detail.candidate().rationale());
            var message = messageGenerator.generate(ctx.userId(), decision, detail.snapshot(), runtimeCtx);
            if (message.body().isBlank()) {
                log.debug("ReminderBehavior.reason: 消息为空, topic={}", candidate.topicKey());
                continue;
            }
            actions.add(new ProactiveAction(
                    candidate,
                    message.body(),
                    scoreToDeliveryLevel(candidate.score()),
                    detail
            ));
        }
        log.debug("ReminderBehavior.reason: candidates={}, actions={}", candidates.size(), actions.size());
        return actions;
    }

    /** 将 ContextPacket 转为现有 ReminderRuntimeContext。 */
    private ReminderRuntimeContext toReminderContext(ContextPacket ctx) {
        return new ReminderRuntimeContext(
                ctx.now(), ctx.zoneId(),
                ctx.quietHoursStart(), ctx.quietHoursEnd(),
                ctx.actionsSentToday(), ctx.focusState());
    }

    /** 将现有 ReminderCandidate 转为框架 ProactiveCandidate。 */
    private ProactiveCandidate toProactiveCandidate(ReminderCandidate candidate,
                                                     ReminderTopicSnapshot snapshot,
                                                     ReminderPolicyConfig policyConfig) {
        return new ProactiveCandidate(
                UUID.randomUUID().toString(),
                name(),
                candidate.topicKey(),
                candidate.title(),
                candidate.finalScore(),
                candidate.rationale(),
                new ReminderCandidateDetail(snapshot, candidate, policyConfig)
        );
    }

    /** 分数 → 建议投递级别。 */
    private static DeliveryLevel scoreToDeliveryLevel(float score) {
        if (score >= 0.7f) return DeliveryLevel.INTERRUPT;
        if (score >= 0.5f) return DeliveryLevel.NOTIFY;
        if (score >= 0.3f) return DeliveryLevel.QUEUE;
        return DeliveryLevel.SILENT;
    }

    private void inferOutcomesQuietly(ContextPacket ctx) {
        if (outcomeInferenceService == null) return;
        try {
            outcomeInferenceService.inferRecentOutcomes(ctx.userId(), ctx.now());
        } catch (Exception e) {
            log.debug("ReminderBehavior: 隐式结果推断跳过: {}", e.getMessage());
        }
    }
}
