package com.lifepilot.memory.store.event;

/**
 * 记忆域事件总线抽象。
 *
 * <p>阶段 1 先作为统一发布入口，后续可演进为具备异步调度、失败重试、
 * 幂等回放能力的正式事件总线。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public interface MemoryEventBus {

    void publish(MemoryEvent event);

    default void publishAll(Iterable<? extends MemoryEvent> events) {
        if (events == null) {
            return;
        }
        for (MemoryEvent event : events) {
            if (event != null) {
                publish(event);
            }
        }
    }
}
