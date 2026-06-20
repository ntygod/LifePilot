package com.lifepilot.agent.initiative.signal;

import com.lifepilot.agent.initiative.InitiativeEngine;
import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.model.Signal;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Initiative Engine 事件监听器 — 将 Spring Event 转化为 Signal 并驱动主动引擎。
 *
 * <p>监听的事件：
 * <ul>
 *   <li>{@link ConversationCompletedEvent} → 对话结束信号 → 触发 Thinker + 尝试表达</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class InitiativeEventListener {

    private static final Logger log = LoggerFactory.getLogger(InitiativeEventListener.class);

    private final InitiativeEngine engine;
    private volatile Instant lastExpressedAt;
    private volatile int todayExpressedCount;
    private volatile LocalDate countedDate;

    public InitiativeEventListener(InitiativeEngine engine) {
        this.engine = engine;
    }

    /**
     * 对话结束后触发主动引擎。
     *
     * <p>异步执行，不阻塞对话结束流程。</p>
     */
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        Thread.startVirtualThread(() -> {
            try {
                // 1. 将事件转化为 Signal 并交给 Thinker 处理
                var signal = new Signal.ConversationEnded(
                        event.getSessionId(),
                        event.getSummary(),
                        Instant.now()
                );
                engine.processSignal(signal);

                // 2. 尝试表达就绪的想法
                var context = buildGatekeeperContext();
                var expressed = engine.tryExpress(context);
                if (expressed != null) {
                    lastExpressedAt = Instant.now();
                    incrementTodayCount();
                    log.info("主动引擎: 对话结束后表达想法, intentKey={}",
                            expressed.intentKey());
                }
            } catch (Exception e) {
                log.warn("主动引擎: 事件处理失败, sessionId={}, error={}",
                        event.getSessionId(), e.getMessage());
            }
        });
    }

    /**
     * 构建 Gatekeeper 上下文。
     */
    private Gatekeeper.GatekeeperContext buildGatekeeperContext() {
        Duration timeSinceLastExpress = lastExpressedAt != null
                ? Duration.between(lastExpressedAt, Instant.now())
                : null;
        return new Gatekeeper.GatekeeperContext(
                false, // 对话刚结束，用户不在对话中
                getTodayCount(),
                timeSinceLastExpress
        );
    }

    private int getTodayCount() {
        LocalDate today = LocalDate.now();
        if (!today.equals(countedDate)) {
            countedDate = today;
            todayExpressedCount = 0;
        }
        return todayExpressedCount;
    }

    private void incrementTodayCount() {
        LocalDate today = LocalDate.now();
        if (!today.equals(countedDate)) {
            countedDate = today;
            todayExpressedCount = 0;
        }
        todayExpressedCount++;
    }
}
