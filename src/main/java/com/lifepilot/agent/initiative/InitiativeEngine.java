package com.lifepilot.agent.initiative;

import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.express.ConversationInitiator;
import com.lifepilot.agent.initiative.model.Signal;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtState;
import com.lifepilot.agent.initiative.pool.ThoughtPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 主动引擎 — 事件驱动的主动思考 + 对话发起。
 *
 * <p>核心流程：Signal → Thinker → ThoughtPool → Gatekeeper → ConversationInitiator。</p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>宁可少说一句，不多说一句</li>
 *   <li>事件驱动，不定时轮询</li>
 *   <li>意图级去重，同一件事只说一次</li>
 *   <li>输出是对话，不是通知</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class InitiativeEngine {

    private static final Logger log = LoggerFactory.getLogger(InitiativeEngine.class);

    private final ThoughtPool thoughtPool;
    private final Gatekeeper gatekeeper;
    private final Thinker thinker;
    private final ConversationInitiator conversationInitiator;

    public InitiativeEngine(ThoughtPool thoughtPool,
                            Gatekeeper gatekeeper,
                            Thinker thinker,
                            ConversationInitiator conversationInitiator) {
        this.thoughtPool = thoughtPool;
        this.gatekeeper = gatekeeper;
        this.thinker = thinker;
        this.conversationInitiator = conversationInitiator;
    }

    /**
     * 处理外部信号 — 可能产生新想法。
     *
     * @param signal 外部事件信号
     */
    public void processSignal(Signal signal) {
        try {
            var thought = thinker.processSignal(signal);
            thought.ifPresent(t -> {
                var submitted = thoughtPool.submit(t);
                log.debug("主动引擎: 信号处理完成, signal={}, thought={}",
                        signal.getClass().getSimpleName(), submitted.intentKey());
            });
        } catch (Exception e) {
            log.warn("主动引擎: 信号处理失败, signal={}, error={}",
                    signal.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 尝试表达就绪的想法 — 由定时器或事件触发。
     *
     * @param context 门控上下文
     * @return 被表达的想法（如有）
     */
    @Nullable
    public Thought tryExpress(Gatekeeper.GatekeeperContext context) {
        // 清理过期想法
        thoughtPool.cleanup();
        // 成熟度演化：截止升温 / 停滞衰减，并应用状态迁移
        thoughtPool.evolve(java.time.Instant.now());

        // 获取就绪想法
        List<Thought> ready = thoughtPool.getReadyThoughts();
        if (ready.isEmpty()) return null;

        // 逐个评估门控
        for (Thought thought : ready) {
            var decision = gatekeeper.evaluate(thought, context);
            if (decision instanceof Gatekeeper.Decision.Express express) {
                try {
                    String sessionId = conversationInitiator.initiate(thought);
                    Thought expressedThought = thoughtPool.markExpressed(thought.id(), sessionId);
                    log.info("主动引擎: 想法表达, intentKey={}, urgency={}, sessionId={}",
                            thought.intentKey(), express.urgency(), sessionId);
                    return expressedThought;
                } catch (Exception e) {
                    log.warn("主动引擎: 想法表达失败, intentKey={}, error={}",
                            thought.intentKey(), e.getMessage());
                    return null;
                }
            }
            if (decision instanceof Gatekeeper.Decision.Dismiss dismiss) {
                thoughtPool.transition(thought.id(), ThoughtState.DISMISSED);
                log.debug("主动引擎: 想法放弃, intentKey={}, reason={}",
                        thought.intentKey(), dismiss.reason());
            }
            // Wait → 跳过，等下次
        }
        return null;
    }

    /**
     * 空闲思考 — 在用户不活跃时主动回顾记忆、发现关联。
     */
    public void idleThink() {
        try {
            var thoughts = thinker.idleThink();
            for (var thought : thoughts) {
                thoughtPool.submit(thought);
            }
            // 演化：让本轮新想法与旧想法的成熟度/状态在同一时点对齐
            thoughtPool.evolve(java.time.Instant.now());
            if (!thoughts.isEmpty()) {
                log.debug("主动引擎: 空闲思考产出 {} 个想法", thoughts.size());
            }
        } catch (Exception e) {
            log.warn("主动引擎: 空闲思考失败: {}", e.getMessage());
        }
    }

    public int activeThoughtCount() {
        return thoughtPool.activeCount();
    }

    /** 活跃想法快照 —— 供 dev 端点观测思考产物（不表达）。 */
    public List<Thought> snapshotActiveThoughts() {
        return thoughtPool.activeThoughts();
    }
}
