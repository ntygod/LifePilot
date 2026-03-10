package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.agent.proactive.model.Urgency;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 主动推理引擎 — 两阶段推理管线编排。
 *
 * <p>Stage 1（RuleEngine）：确定性规则过滤，生成候选列表。
 * Stage 2（LLM）：精细判断候选是否值得发送，生成个性化内容。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ProactiveReasoner {

    private static final Logger log = LoggerFactory.getLogger(ProactiveReasoner.class);

    private final SignalCollector signalCollector;
    private final PolicyEngine ruleEngine;
    private final FrequencyStateManager frequencyStateManager;
    private final NotificationDispatcher notificationDispatcher;
    private final ResponseTracker responseTracker;
    private final LlmRouter llmRouter;
    private final ProactiveConfigProperties config;
    private final PromptRegistry promptRegistry;

    public ProactiveReasoner(SignalCollector signalCollector,
                              PolicyEngine ruleEngine,
                              FrequencyStateManager frequencyStateManager,
                              NotificationDispatcher notificationDispatcher,
                              ResponseTracker responseTracker,
                              LlmRouter llmRouter,
                              ProactiveConfigProperties config,
                              PromptRegistry promptRegistry) {
        this.signalCollector = signalCollector;
        this.ruleEngine = ruleEngine;
        this.frequencyStateManager = frequencyStateManager;
        this.notificationDispatcher = notificationDispatcher;
        this.responseTracker = responseTracker;
        this.llmRouter = llmRouter;
        this.config = config;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 定时触发主动推理。使用 Virtual Thread 执行。
     */
    @Scheduled(fixedDelayString = "${lifepilot.agent.proactive.interval-ms:1800000}")
    public void reason() {
        if (!config.isEnabled()) {
            return;
        }

        Thread.ofVirtual().name("proactive-reasoning").start(() -> {
            try {
                executeReasoningCycle();
            } catch (Exception e) {
                log.warn("主动推理周期异常: error={}", e.getMessage());
            }
        });
    }

    /** 执行一次完整的推理周期。 */
    private void executeReasoningCycle() {
        // 1. 收集信号
        var signals = signalCollector.collect();

        // 2. 规则过滤，生成候选列表
        var candidates = ruleEngine.evaluate(signals);
        if (candidates.isEmpty()) {
            log.debug("本次推理无候选提醒");
            return;
        }

        log.debug("规则引擎生成候选: count={}", candidates.size());

        // 3. 逐候选处理
        for (var candidate : candidates) {
            // 频率控制
            if (!frequencyStateManager.shouldSend(candidate.type(), candidate.urgency())) {
                log.debug("频率控制拒绝: type={}, urgency={}", candidate.type(), candidate.urgency());
                continue;
            }

            try {
                String content;

                if (candidate.urgency() == Urgency.HIGH) {
                    // HIGH 紧急度：模板渲染，跳过 LLM
                    content = renderHighUrgencyTemplate(candidate);
                } else {
                    // MEDIUM/LOW：LLM 评估
                    var prompt = buildEvaluationPrompt(candidate);
                    var llmResponse = llmRouter.call("proactive_reasoning", prompt, null);
                    content = llmResponse.content();

                    // LLM 判定不值得发送
                    if (content == null || content.isBlank() || content.contains("SKIP")) {
                        log.debug("LLM 判定跳过: type={}", candidate.type());
                        continue;
                    }
                }

                // 截断内容
                if (content.length() > config.getMaxContentLength()) {
                    content = content.substring(0, config.getMaxContentLength());
                }

                // 构造通知
                var notification = new ProactiveNotification(
                        UUID.randomUUID().toString(),
                        candidate.type(),
                        candidate.urgency(),
                        content,
                        "log",
                        "PENDING",
                        Instant.now()
                );

                // 分发通知
                notificationDispatcher.dispatch(notification);

                // 更新最后通知时间
                frequencyStateManager.updateLastNotified(candidate.type());

                // 开始追踪
                responseTracker.track(candidate.type());

            } catch (LlmUnavailableException e) {
                log.warn("LLM 不可用，跳过候选: type={}, error={}", candidate.type(), e.getMessage());
            } catch (Exception e) {
                log.warn("候选处理异常: type={}, error={}", candidate.type(), e.getMessage());
            }
        }

        // 清理过期追踪
        responseTracker.cleanupExpired();
    }

    /**
     * 渲染 HIGH 紧急度模板，失败时降级为 candidate.reason()。
     */
    private String renderHighUrgencyTemplate(com.lifepilot.agent.proactive.model.ProactiveCandidate candidate) {
        try {
            var templateKey = "proactive/high-urgency/" + candidate.type().name().toLowerCase();
            return promptRegistry.render(templateKey, Map.of("reason", candidate.reason()));
        } catch (Exception e) {
            log.warn("HIGH 紧急度模板渲染失败，降级为原始原因: type={}, error={}", candidate.type(), e.getMessage());
            return candidate.reason();
        }
    }

    /** 构建 LLM 评估提示词。 */
    private String buildEvaluationPrompt(com.lifepilot.agent.proactive.model.ProactiveCandidate candidate) {
        return promptRegistry.render("proactive/evaluation", Map.of(
                "maxContentLength", String.valueOf(config.getMaxContentLength()),
                "type", candidate.type(),
                "urgency", candidate.urgency(),
                "reason", candidate.reason()
        ));
    }
}
