package com.lifepilot.memory.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;

/**
 * 基于 Spring 事件系统的记忆域事件总线实现。
 *
 * @author zsg
 * @since 2026-03-23
 */
public class SpringMemoryEventBus implements MemoryEventBus {

    private static final Logger log = LoggerFactory.getLogger(SpringMemoryEventBus.class);

    private final ApplicationEventPublisher eventPublisher;

    public SpringMemoryEventBus(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    @Override
    public void publish(MemoryEvent event) {
        if (event == null) {
            return;
        }
        eventPublisher.publishEvent(event);
        log.debug("记忆域事件已发布: type={}, sessionId={}", event.eventType(), event.sessionId());
    }
}
