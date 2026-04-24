package com.lifepilot.meta.convenience;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentRegistryEvent;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * 单次事件正常触发缓存失效 — 保持测试。
 *
 * <p>验证 Property 8（Preservation）：单独到达的注册中心事件
 * 在合理时间内触发恰好 1 次 {@code invalidateCache()} 调用。
 * 此行为在修复前后均应保持不变。</p>
 *
 * <p><b>Validates: Requirements 3.5, 3.6</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class CacheFlapping_Preservation_保持测试 {

    private SkillRegistry skillRegistry;
    private AgentRegistry agentRegistry;
    private DynamicToolRegistry toolRegistry;
    private WorkflowRegistry workflowRegistry;
    private MetaProperties properties;
    private SharedScheduler sharedScheduler;

    @BeforeEach
    void setUp() {
        skillRegistry = mock(SkillRegistry.class);
        agentRegistry = mock(AgentRegistry.class);
        toolRegistry = mock(DynamicToolRegistry.class);
        workflowRegistry = mock(WorkflowRegistry.class);
        properties = new MetaProperties();
        sharedScheduler = mock(SharedScheduler.class);
        when(sharedScheduler.debounce()).thenReturn(
                java.util.concurrent.Executors.newScheduledThreadPool(1));
    }

    @Test
    void 单个ToolRegistryEvent_invalidateCache被调用1次() throws InterruptedException {
        // 创建 spy 以计数 invalidateCache() 调用
        var aggregator = spy(new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties, sharedScheduler));

        // 构造单个 ToolRegistryEvent
        var event = new ToolRegistryEvent.ToolsRegistered(
                List.of("test-tool"), ToolLayer.JAVA_NATIVE, "test");

        // 发布单个事件
        aggregator.onToolRegistryEvent(event);

        // 等待 600ms（超过防抖窗口 500ms）
        Thread.sleep(600);

        // 断言：invalidateCache() 恰好被调用 1 次
        verify(aggregator, times(1)).invalidateCache();
    }

    @Test
    void 单个SkillRegistryEvent_invalidateCache被调用1次() throws InterruptedException {
        var aggregator = spy(new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties, sharedScheduler));

        // 构造单个 SkillRegistryEvent（使用 SkillRegistered）
        var skillDef = new SkillDefinition(
                "test-skill", "测试技能", "测试用技能描述", "1.0.0",
                new SkillSource.UserDefined("/test", null), "测试指令内容",
                List.of(), Map.of(), SkillZhiweiMeta.empty());
        var event = new SkillRegistryEvent.SkillRegistered(skillDef);

        // 发布单个事件
        aggregator.onSkillRegistryEvent(event);

        // 等待 600ms
        Thread.sleep(600);

        // 断言：invalidateCache() 恰好被调用 1 次
        verify(aggregator, times(1)).invalidateCache();
    }

    @Test
    void 单个AgentRegistryEvent_invalidateCache被调用1次() throws InterruptedException {
        var aggregator = spy(new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties, sharedScheduler));

        // 构造单个 AgentRegistryEvent（使用 AgentRegistered）
        var agentDef = new AgentDefinition(
                "test-agent", "测试Agent", "测试用Agent描述",
                "你是一个测试Agent", List.of(),
                AgentBudget.DEFAULT, null,
                new AgentSource.Builtin(), Map.of());
        var event = new AgentRegistryEvent.AgentRegistered(agentDef);

        // 发布单个事件
        aggregator.onAgentRegistryEvent(event);

        // 等待 600ms
        Thread.sleep(600);

        // 断言：invalidateCache() 恰好被调用 1 次
        verify(aggregator, times(1)).invalidateCache();
    }
}
