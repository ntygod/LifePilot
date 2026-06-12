package com.lifepilot.agent.initiative.express;

import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.model.InteractionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * 对话发起器 — 将想法转化为一段真正的对话并发起。
 *
 * <p>核心区别于旧系统：输出不是一条通知，而是发起一段完整对话。
 * 用户可以追问、让 Agent 行动、简单确认、拒绝或延后。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ConversationInitiator {

    private static final Logger log = LoggerFactory.getLogger(ConversationInitiator.class);

    @Nullable
    private final AgentOrchestrator agentOrchestrator;

    public ConversationInitiator(@Nullable AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    /**
     * 发起主动对话。
     *
     * @param thought 要表达的想法
     * @return 创建的对话 session ID（AgentOrchestrator 不可用时返回 null）
     */
    @Nullable
    public String initiate(Thought thought) {
        String opening = generateOpening(thought);
        String context = buildInitiativeContext(thought);

        if (agentOrchestrator == null) {
            log.info("主动对话: AgentOrchestrator 不可用，仅生成开场白: '{}'", opening);
            return null;
        }

        try {
            String sessionId = "initiative-" + UUID.randomUUID().toString().substring(0, 8);
            var request = new AgentRequest(
                    opening,
                    sessionId,
                    InteractionSource.system("initiative:" + thought.id()),
                    null, null, null, null,
                    context, // systemPrompt override
                    null, null, 0, null, null, null, null, null, null
            );

            AgentResponse response = agentOrchestrator.run(request);
            log.info("主动对话发起成功: intentKey={}, sessionId={}", thought.intentKey(), sessionId);
            return response.sessionId();
        } catch (Exception e) {
            log.warn("主动对话发起失败: intentKey={}, error={}", thought.intentKey(), e.getMessage());
            return null;
        }
    }

    /**
     * 为想法生成开场白。
     */
    public String generateOpening(Thought thought) {
        return switch (thought.kind()) {
            case REMINDER -> thought.summary() + "。需要我帮你安排一下吗？";
            case FOLLOW_UP -> {
                String excerpt = thought.evidence().isEmpty() ? thought.summary()
                        : thought.evidence().getFirst().excerpt();
                if (excerpt.length() > 50) excerpt = excerpt.substring(0, 50) + "...";
                yield "之前你提到过「" + excerpt + "」，最近进展怎么样？需要我帮忙推进吗？";
            }
            case INSIGHT -> "我注意到一个有意思的关联：" + thought.summary() + "。你觉得呢？";
            case PREPARATION -> thought.summary() + "。要不要提前准备一下？";
            case CONCERN -> thought.summary() + "。一切还好吗？";
            case SUGGESTION -> "有个想法想跟你说：" + thought.summary() + "。你觉得怎么样？";
        };
    }

    /**
     * 构建注入到新 session 的系统提示词上下文。
     */
    public String buildInitiativeContext(Thought thought) {
        var sb = new StringBuilder();
        sb.append("你正在发起一次主动对话。以下是你开口的依据：\n\n");

        for (int i = 0; i < thought.evidence().size(); i++) {
            Evidence ev = thought.evidence().get(i);
            sb.append("[证据 ").append(i + 1).append("] ");
            sb.append("来源：").append(ev.sourceType());
            if (ev.sourceId() != null) sb.append(" ").append(ev.sourceId());
            sb.append("\n  内容：").append(ev.excerpt());
            sb.append("\n  观察时间：").append(ev.observedAt()).append("\n\n");
        }

        sb.append("保持温和、建议式的语气。如果用户说\"不用了\"，尊重他的选择。");
        return sb.toString();
    }
}
