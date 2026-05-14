package com.lifepilot.agent.task.proactive.cache;

import com.lifepilot.agent.task.proactive.ProactiveMemoryBridge;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;

import java.util.Set;

/**
 * 监听 {@link EntityLifecycleChanged} 事件，当 L3 实体进入非活跃态时
 * 通知 {@link ProactiveMemoryBridge} 失效相关缓存。
 *
 * <p>为 memory-staleness spec C-P0-1 问题的落地：记忆侧事件已存在，但之前没有
 * 任何主动引擎监听器。本组件填补这条链路。</p>
 *
 * <p>当 {@code ProactiveMemoryBridge} Bean 不存在（主动引擎关闭）时，监听器
 * 仍可正常启动但不触发任何行为。</p>
 *
 * <p>由 {@code ProactiveAutoConfiguration} 以 @Bean 方式装配。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class ProactiveCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(ProactiveCacheInvalidator.class);

    /** 触发失效的非活跃生命周期态。 */
    private static final Set<LifecycleState> INVALIDATE_STATES = Set.of(
            LifecycleState.STALE_CANDIDATE,
            LifecycleState.ARCHIVED,
            LifecycleState.SUPERSEDED,
            LifecycleState.CANCELLED,
            LifecycleState.EXPIRED);

    private final ObjectProvider<ProactiveMemoryBridge> bridgeProvider;

    public ProactiveCacheInvalidator(ObjectProvider<ProactiveMemoryBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    @EventListener
    public void on(EntityLifecycleChanged event) {
        if (event == null || event.newState() == null) return;
        if (!INVALIDATE_STATES.contains(event.newState())) return;
        ProactiveMemoryBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) {
            log.debug("ProactiveCacheInvalidator: Bridge 不存在（主动引擎可能未启用）");
            return;
        }
        try {
            bridge.invalidateCacheForEntity(event.entityId());
        } catch (RuntimeException e) {
            log.warn("ProactiveCacheInvalidator: 通知 Bridge 失效失败 entity={}, err={}",
                    event.entityId(), e.getMessage());
        }
    }
}
