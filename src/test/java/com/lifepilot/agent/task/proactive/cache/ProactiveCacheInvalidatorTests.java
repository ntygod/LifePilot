package com.lifepilot.agent.task.proactive.cache;

import com.lifepilot.agent.task.proactive.ProactiveMemoryBridge;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ProactiveCacheInvalidator} 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("ProactiveCacheInvalidator 单元测试")
class ProactiveCacheInvalidatorTests {

    @Test
    @DisplayName("STALE_CANDIDATE → bridge.invalidateCacheForEntity 被调用")
    void stale_触发失效() {
        ProactiveMemoryBridge bridge = mock(ProactiveMemoryBridge.class);
        ObjectProvider<ProactiveMemoryBridge> provider = stubProvider(() -> bridge);
        ProactiveCacheInvalidator invalidator = new ProactiveCacheInvalidator(provider);

        invalidator.on(new EntityLifecycleChanged(
                "e1", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.STALE_CANDIDATE,
                "stale-by:new-entity", ChangeSource.LLM_SEMANTIC));

        verify(bridge, times(1)).invalidateCacheForEntity(eq("e1"));
    }

    @Test
    @DisplayName("ARCHIVED → bridge 调用")
    void archived_触发失效() {
        ProactiveMemoryBridge bridge = mock(ProactiveMemoryBridge.class);
        ObjectProvider<ProactiveMemoryBridge> provider = stubProvider(() -> bridge);
        ProactiveCacheInvalidator invalidator = new ProactiveCacheInvalidator(provider);

        invalidator.on(new EntityLifecycleChanged(
                "e1", "GOAL",
                LifecycleState.ACTIVE, LifecycleState.ARCHIVED,
                null, ChangeSource.CRON_EXPIRE));

        verify(bridge, times(1)).invalidateCacheForEntity("e1");
    }

    @Test
    @DisplayName("ACTIVE → bridge 不被调用（仍活跃）")
    void active_不触发() {
        ProactiveMemoryBridge bridge = mock(ProactiveMemoryBridge.class);
        ObjectProvider<ProactiveMemoryBridge> provider = stubProvider(() -> bridge);
        ProactiveCacheInvalidator invalidator = new ProactiveCacheInvalidator(provider);

        invalidator.on(new EntityLifecycleChanged(
                "e1", "PREFERENCE",
                null, LifecycleState.ACTIVE,
                "created", ChangeSource.LLM_SEMANTIC));

        verify(bridge, never()).invalidateCacheForEntity(anyString());
    }

    @Test
    @DisplayName("bridge 不存在时不抛异常")
    void bridge不存在_安全启动() {
        ObjectProvider<ProactiveMemoryBridge> provider = stubProvider(() -> null);
        ProactiveCacheInvalidator invalidator = new ProactiveCacheInvalidator(provider);

        invalidator.on(new EntityLifecycleChanged(
                "e1", "GOAL",
                LifecycleState.ACTIVE, LifecycleState.STALE_CANDIDATE,
                null, ChangeSource.LLM_SEMANTIC));
        // 无异常即通过
    }

    @Test
    @DisplayName("bridge 抛异常不向上传播")
    void bridge异常_被捕获() {
        ProactiveMemoryBridge bridge = mock(ProactiveMemoryBridge.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(bridge).invalidateCacheForEntity(anyString());
        ObjectProvider<ProactiveMemoryBridge> provider = stubProvider(() -> bridge);
        ProactiveCacheInvalidator invalidator = new ProactiveCacheInvalidator(provider);

        invalidator.on(new EntityLifecycleChanged(
                "e1", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                null, ChangeSource.LLM_SEMANTIC));
        // 无异常即通过
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ProactiveMemoryBridge> stubProvider(Supplier<ProactiveMemoryBridge> supplier) {
        ObjectProvider<ProactiveMemoryBridge> p = mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenAnswer(inv -> supplier.get());
        return p;
    }
}
