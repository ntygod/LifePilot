package com.lifepilot.agent.task.proactive.cot;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.ProactiveCandidate;
import com.lifepilot.agent.task.proactive.training.ProactiveFewShotLibrary;
import com.lifepilot.agent.task.proactive.training.ProactiveFewShotSample;
import com.lifepilot.agent.task.reminder.ReminderAction;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate 3 结构化推理组件 — 把主动引擎 FULL 阶段的"LLM 打分+文案一次性生成"拆为
 * 四段结构化推理（observation → user-state → necessity → action），并在 prompt
 * 中拼接来自 {@link ProactiveFewShotLibrary} 的真实反馈样例作为 priming。
 *
 * <p>本组件只负责构造 prompt + 解析 action；不直接调 LLM（LLM 调用仍在
 * {@code AbstractLlmBehavior} 或各行为插件中完成），保持单一职责。</p>
 *
 * <p>参考：
 * <ul>
 *   <li>ContextAgent NeurIPS 2025（arXiv:2505.14668）：CoT 蒸馏 + think-before-action 把
 *       Acc-P 从 77% → 87%</li>
 *   <li>ProAgentBench（arXiv:2602.04482，2026）：真实反馈 SFT 比 synthetic 数据多 +16.7pp</li>
 * </ul>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class GateThreeReasoner {

    private static final Logger log = LoggerFactory.getLogger(GateThreeReasoner.class);

    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "<action>\\s*([A-Z_]+)\\s*</action>",
            Pattern.CASE_INSENSITIVE
    );

    private final ProactiveFewShotLibrary fewShotLibrary;
    private final AgentConfigProperties.TaskConfig taskConfig;
    @Nullable
    private final NotificationProperties notificationProperties;

    public GateThreeReasoner(ProactiveFewShotLibrary fewShotLibrary,
                             AgentConfigProperties.TaskConfig taskConfig,
                             @Nullable NotificationProperties notificationProperties) {
        this.fewShotLibrary = Objects.requireNonNull(fewShotLibrary);
        this.taskConfig = Objects.requireNonNull(taskConfig);
        this.notificationProperties = notificationProperties;
    }

    /** 是否对该候选应用 CoT 推理。 */
    public boolean shouldApply(ProactiveCandidate candidate) {
        if (!taskConfig.isProactiveCotEnabled()) return false;
        if (candidate == null) return false;
        return candidate.score() >= taskConfig.getProactiveCotMinScore();
    }

    /**
     * 构造结构化 CoT prompt。
     *
     * @param candidate   当前候选
     * @param ctx         心跳上下文
     * @param baseContent 已有的基础通知文案（LLM 可在此基础上精修）
     * @return prompt 字符串，供调用方拼接后发送给 LLM
     */
    public String buildStructuredPrompt(ProactiveCandidate candidate,
                                         ContextPacket ctx,
                                         String baseContent) {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        Objects.requireNonNull(ctx, "ctx 不能为空");

        String userId = resolveUserId();
        List<ProactiveFewShotSample> samples = List.of();
        try {
            samples = fewShotLibrary.getSamples(
                    userId, candidate.behaviorName(),
                    Math.max(1, taskConfig.getProactiveCotFewShotCount()));
        } catch (Exception e) {
            log.debug("CoT: few-shot 样本加载失败: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder(2048);
        sb.append("你是主动提醒引擎的决策推理组件。请对以下候选提醒做四段结构化思考后给出最终 action。\n\n");

        if (!samples.isEmpty()) {
            sb.append("以下是类似场景的历史决策参考（按效果从好到差）：\n");
            for (var s : samples) {
                sb.append("- type=").append(s.candidateType())
                  .append(", action=").append(s.historicalAction())
                  .append(", reward=").append(String.format("%.2f", s.reward()))
                  .append(", outcome=").append(s.positive() ? "positive" : "negative")
                  .append(", context=").append(s.contextDigest())
                  .append('\n');
            }
            sb.append('\n');
        }

        sb.append("<observation>\n");
        sb.append("候选标题：").append(nvl(candidate.title())).append('\n');
        sb.append("触发行为：").append(nvl(candidate.behaviorName())).append('\n');
        sb.append("候选分数：").append(String.format("%.3f", candidate.score())).append('\n');
        sb.append("boundary=").append(ctx.boundaryState())
          .append(", focus=").append(ctx.focusMode())
          .append(", 今日已推送=").append(ctx.actionsSentToday())
          .append("/").append(ctx.dailyMaxActions())
          .append('\n');
        sb.append("</observation>\n\n");

        sb.append("<user-state>\n（推断用户当前状态：是否处于任务边界、专注、空闲，200 字以内）\n</user-state>\n\n");
        sb.append("<necessity>\n（评估现在推送的必要性：证据是否充分、是否会打扰，200 字以内）\n</necessity>\n\n");
        sb.append("<action>\n（必须输出以下之一：SKIP / SOFT_PUSH / NORMAL_PUSH）\n</action>\n\n");

        sb.append("基础通知文案草稿：").append(nvl(baseContent)).append('\n');
        sb.append("请按四段结构输出完整推理与最终 action。");
        return sb.toString();
    }

    /**
     * 从 LLM 响应中解析 action 段。解析失败返回 empty，调用方应降级为插件原 suggestedLevel。
     */
    public Optional<ReminderAction> parseAction(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return Optional.empty();
        Matcher matcher = ACTION_PATTERN.matcher(llmResponse);
        if (!matcher.find()) return Optional.empty();
        String token = matcher.group(1).toUpperCase();
        try {
            return Optional.of(ReminderAction.valueOf(token));
        } catch (IllegalArgumentException e) {
            log.debug("CoT: action 解析失败, token={}", token);
            return Optional.empty();
        }
    }

    private String resolveUserId() {
        if (notificationProperties == null) return "default";
        String uid = notificationProperties.getDefaultUserId();
        return (uid == null || uid.isBlank()) ? "default" : uid;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
