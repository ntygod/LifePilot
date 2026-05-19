package com.lifepilot.agent.initiative;

import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.model.Signal;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtState;
import com.lifepilot.agent.initiative.pool.ThoughtPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * 主动引擎 — 事件驱动的主动思考 + 对话发起。
 *
 * <p>替代旧的 {@code ProactiveEngine} 心跳巡检模式。核心流程：
 * Signal → Thinker → ThoughtPool → Gatekeeper → ConversationInitiator</p>
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
    @Nullable
    private final Thinker thinker;

    public InitiativeEngine(ThoughtPool thoughtPool,
                            Gatekeeper gatekeeper,
                            @Nullable Thinker thinker) {
        this.thoughtPool = thoughtPool;
        this.gatekeeper = gatekeeper;
        this.thinker = thinker;
    }

    /**
     * 处理外部信号 — 可能产生新想法。
     *
     * @param signal 外部事件信号
     */
    public void processSignal(Signal signal) {
        if (thinker == null) {
            log.debug("主动引擎: Thinker 不可用，跳过信号处理");
            return;
        }
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

        // 获取就绪想法
        List<Thought> ready = thoughtPool.getReadyThoughts();
        if (ready.isEmpty()) return null;

        // 逐个评估门控
        for (Thought thought : ready) {
            var decision = gatekeeper.evaluate(thought, context);
            if (decision instanceof Gatekeeper.Decision.Express express) {
                // 标记为已表达
                thoughtPool.transition(thought.id(), ThoughtState.EXPRESSED);
                log.info("主动引擎: 想法表达, intentKey={}, urgency={}",
                        thought.intentKey(), express.urgency());
                return thought;
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
        if (thinker == null) return;
        try {
            var thoughts = thinker.idleThink();
            for (var thought : thoughts) {
                thoughtPool.submit(thought);
            }
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
}
