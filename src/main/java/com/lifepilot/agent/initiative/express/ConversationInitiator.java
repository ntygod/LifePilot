package com.lifepilot.agent.initiative.express;

import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.stream.Collectors;

/**
 * 对话发起器 — 将想法转化为一段真正的对话并发起。
 *
 * <p>核心区别于旧系统：输出不是一条通知，而是发起一段完整对话。
 * 用户可以追问、让 Agent 行动、简单确认、拒绝或延后。</p>
 *
 * <p>发起流程：
 * <ol>
 *   <li>构建对话上下文（想法 summary + evidence）</li>
 *   <li>生成开场白（当前用模板，后续可接 LLM）</li>
 *   <li>通过 AgentOrchestrator 创建新对话 session</li>
 *   <li>通过渠道路由投递开场白</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ConversationInitiator {

    private static final Logger log = LoggerFactory.getLogger(ConversationInitiator.class);

    /**
     * 为想法生成开场白。
     *
     * <p>开场白要求：
     * <ul>
     *   <li>说清楚"为什么现在跟你说这个"</li>
     *   <li>语气温和、建议式、不强迫</li>
     *   <li>不超过 3 句话</li>
     *   <li>结尾留出对话空间</li>
     * </ul></p>
     *
     * @param thought 要表达的想法
     * @return 开场白文本
     */
    public String generateOpening(Thought thought) {
        return switch (thought.kind()) {
            case REMINDER -> generateReminderOpening(thought);
            case FOLLOW_UP -> generateFollowUpOpening(thought);
            case INSIGHT -> generateInsightOpening(thought);
            case PREPARATION -> generatePreparationOpening(thought);
            case CONCERN -> generateConcernOpening(thought);
            case SUGGESTION -> generateSuggestionOpening(thought);
        };
    }

    /**
     * 构建注入到新 session 的系统提示词上下文。
     *
     * <p>包含想法的证据链，使 Agent 在后续对话中能够回溯原始信息。</p>
     */
    public String buildInitiativeContext(Thought thought) {
        var sb = new StringBuilder();
        sb.append("你正在发起一次主动对话。以下是你开口的依据：\n\n");

        for (int i = 0; i < thought.evidence().size(); i++) {
            Evidence ev = thought.evidence().get(i);
            sb.append("[证据 ").append(i + 1).append("] ");
            sb.append("来源：").append(ev.sourceType());
            if (ev.sourceId() != null) sb.append(" ").append(ev.sourceId());
            if (ev.spaceId() != null) sb.append("（").append(ev.spaceId()).append(" 空间）");
            sb.append("\n");
            sb.append("  内容：").append(ev.excerpt()).append("\n");
            sb.append("  观察时间：").append(ev.observedAt()).append("\n\n");
        }

        sb.append("如果用户追问细节，你可以使用 memory.recall 或 memory.search 工具检索原始信息。\n");
        sb.append("保持温和、建议式的语气，不要强迫用户。如果用户说\"不用了\"，尊重他的选择。");
        return sb.toString();
    }

    /**
     * 发起主动对话（骨架 — 后续集成 AgentOrchestrator）。
     *
     * @param thought 要表达的想法
     * @return 创建的对话 session ID（当前返回 null，待集成）
     */
    @Nullable
    public String initiate(Thought thought) {
        String opening = generateOpening(thought);
        String context = buildInitiativeContext(thought);

        log.info("主动对话发起: intentKey={}, opening='{}'", thought.intentKey(), opening);

        // TODO: 集成 AgentOrchestrator
        // var request = AgentRequest.builder()
        //     .source(InteractionSource.initiative(thought.id()))
        //     .systemPromptOverride(context)
        //     .userMessage(opening)
        //     .build();
        // var response = agentOrchestrator.run(request);
        // return response.sessionId();

        return null;
    }

    private String generateReminderOpening(Thought thought) {
        return thought.summary() + "。需要我帮你安排一下吗？";
    }

    private String generateFollowUpOpening(Thought thought) {
        String evidenceExcerpt = thought.evidence().isEmpty() ? ""
                : thought.evidence().getFirst().excerpt();
        if (evidenceExcerpt.length() > 50) {
            evidenceExcerpt = evidenceExcerpt.substring(0, 50) + "...";
        }
        return "之前你提到过「" + evidenceExcerpt + "」，最近进展怎么样？需要我帮忙推进吗？";
    }

    private String generateInsightOpening(Thought thought) {
        return "我注意到一个有意思的关联：" + thought.summary() + "。你觉得呢？";
    }

    private String generatePreparationOpening(Thought thought) {
        return thought.summary() + "。要不要提前准备一下？";
    }

    private String generateConcernOpening(Thought thought) {
        return thought.summary() + "。一切还好吗？";
    }

    private String generateSuggestionOpening(Thought thought) {
        return "有个想法想跟你说：" + thought.summary() + "。你觉得怎么样？";
    }
}
