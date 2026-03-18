package com.lifepilot.meta.convenience;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 缓存反复失效 Bug Condition 探索测试。
 *
 * <p>验证 Bug 4：CapabilityAggregator 在短时间内收到多个事件时，
 * invalidateCache() 应只被调用 1 次（防抖合并）。
 * 在未修复代码上，每个事件都直接调用 invalidateCache()，
 * 10 个事件 → 10 次调用 → 测试断言失败 → 确认 bug 存在。</p>
 *
 * <p><b>Validates: Requirements 1.4, 2.4</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class CacheFlapping_BugCondition_探索测试 {

    @Test
    void 连续10个ToolRegistryEvent_防抖后invalidateCache只调用1次() throws InterruptedException {
        // 准备：Mock 四个注册中心
        var skillRegistry = mock(SkillRegistry.class);
        var agentRegistry = mock(AgentRegistry.class);
        var toolRegistry = mock(DynamicToolRegistry.class);
        var workflowRegistry = mock(WorkflowRegistry.class);

        // 准备：创建默认 MetaProperties
        var properties = new MetaProperties();

        // 准备：Mock SharedScheduler，debounce() 返回真实调度器
        var sharedScheduler = mock(SharedScheduler.class);
        when(sharedScheduler.debounce()).thenReturn(
                java.util.concurrent.Executors.newScheduledThreadPool(1));

        // 创建 CapabilityAggregator 并用 spy 包装以计数 invalidateCache() 调用
        var aggregator = spy(new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties, sharedScheduler));

        // 构造一个 ToolRegistryEvent
        var event = new ToolRegistryEvent.ToolsRegistered(
                List.of("test-tool"), ToolLayer.JAVA_NATIVE, "test");

        // 快速连续发布 10 个事件（模拟启动阶段批量注册）
        for (int i = 0; i < 10; i++) {
            aggregator.onToolRegistryEvent(event);
        }

        // 等待 600ms，让防抖窗口（500ms）过期
        Thread.sleep(600);

        // 断言：防抖后 invalidateCache() 应只被调用 1 次
        // 未修复代码：每个事件直接调用 → 10 次 → 断言失败 → 确认 bug
        verify(aggregator, times(1)).invalidateCache();
    }
}
