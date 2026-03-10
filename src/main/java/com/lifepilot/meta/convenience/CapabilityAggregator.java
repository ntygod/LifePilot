package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentRegistryEvent;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 能力聚合器 — 从四个注册中心拉取信息，统一为 {@link CapabilitySummary}。
 *
 * <p>聚合策略：
 * <ul>
 *   <li>首次调用时聚合，结果缓存到 {@link ConcurrentHashMap}</li>
 *   <li>缓存 TTL 由 {@link MetaProperties.Introspection#getCacheTtlSeconds()} 控制（默认 60s）</li>
 *   <li>注册中心的注册/注销事件触发缓存失效</li>
 *   <li>支持按类型过滤（skill/agent/tool/workflow/mcp）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class CapabilityAggregator {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAggregator.class);
    private static final String CACHE_KEY = "summary";

    private final SkillRegistry skillRegistry;
    private final AgentRegistry agentRegistry;
    private final DynamicToolRegistry toolRegistry;
    private final WorkflowRegistry workflowRegistry;
    private final int cacheTtlSeconds;

    /** 防抖调度器（单线程虚拟线程）。 */
    private final ScheduledExecutorService debounceExecutor;

    /** 防抖窗口（毫秒）。 */
    private final int debounceMillis;

    /** 待执行的防抖任务。 */
    private volatile ScheduledFuture<?> pendingInvalidation;

    /** 缓存条目：聚合结果 + 过期时间。 */
    private record CachedSummary(CapabilitySummary summary, Instant expireAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expireAt);
        }
    }

    private final ConcurrentHashMap<String, CachedSummary> cache = new ConcurrentHashMap<>();

    public CapabilityAggregator(SkillRegistry skillRegistry,
                                AgentRegistry agentRegistry,
                                DynamicToolRegistry toolRegistry,
                                WorkflowRegistry workflowRegistry,
                                MetaProperties properties) {
        this.skillRegistry = skillRegistry;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.workflowRegistry = workflowRegistry;
        this.cacheTtlSeconds = properties.getIntrospection().getCacheTtlSeconds();
        this.debounceMillis = properties.getIntrospection().getDebounceMillis();
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("capability-debounce").factory());
    }

    /**
     * 聚合所有注册中心的能力信息。
     *
     * <p>优先返回缓存结果，缓存过期时重新聚合。</p>
     *
     * @return 能力摘要
     */
    public CapabilitySummary aggregate() {
        var cached = cache.get(CACHE_KEY);
        if (cached != null && !cached.isExpired()) {
            log.debug("能力聚合缓存命中");
            return cached.summary();
        }

        log.debug("能力聚合缓存未命中，重新聚合");
        var summary = doAggregate();
        cache.put(CACHE_KEY, new CachedSummary(summary,
                Instant.now().plusSeconds(cacheTtlSeconds)));
        return summary;
    }

    /**
     * 按类型过滤能力信息。
     *
     * @param type 类型（skill/agent/tool/workflow/mcp）
     * @return 过滤后的能力列表
     */
    public List<CapabilityInfo> filterByType(String type) {
        var summary = aggregate();
        return switch (type.toLowerCase()) {
            case "skill" -> summary.skills();
            case "agent" -> summary.agents();
            case "tool" -> summary.tools();
            case "workflow" -> summary.workflows();
            case "mcp" -> summary.mcpServers();
            default -> List.of();
        };
    }

    /** 使缓存失效。 */
    public void invalidateCache() {
        cache.clear();
        log.debug("能力聚合缓存已失效");
    }

    // ─────────────────────────────────────────────
    //  事件监听 — 注册中心变更时触发缓存失效
    // ─────────────────────────────────────────────

    /** 监听 Skill 注册中心事件。 */
    @EventListener
    public void onSkillRegistryEvent(SkillRegistryEvent event) {
        scheduleInvalidation();
    }

    /** 监听工具注册中心事件。 */
    @EventListener
    public void onToolRegistryEvent(ToolRegistryEvent event) {
        scheduleInvalidation();
    }

    /** 监听 Agent 注册中心事件。 */
    @EventListener
    public void onAgentRegistryEvent(AgentRegistryEvent event) {
        scheduleInvalidation();
    }

    /**
     * 调度防抖缓存失效。
     *
     * <p>取消上一个待执行的失效任务，重新调度延迟 {@code debounceMillis} 后执行。
     * 短时间内的多次事件只会触发一次实际的 {@link #invalidateCache()} 调用。</p>
     */
    private void scheduleInvalidation() {
        var pending = this.pendingInvalidation;
        if (pending != null) {
            pending.cancel(false);
        }
        this.pendingInvalidation = debounceExecutor.schedule(
                this::invalidateCache, debounceMillis, TimeUnit.MILLISECONDS);
    }

    /** 关闭防抖调度器。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        debounceExecutor.shutdownNow();
    }

    // ─────────────────────────────────────────────
    //  内部聚合逻辑
    // ─────────────────────────────────────────────

    /** 执行实际聚合。 */
    private CapabilitySummary doAggregate() {
        var skills = skillRegistry.listAll().stream()
                .map(this::toCapabilityInfo)
                .toList();

        var agents = agentRegistry.listAll().stream()
                .map(this::toCapabilityInfo)
                .toList();

        var tools = toolRegistry.getToolSnapshot().stream()
                .map(this::toCapabilityInfo)
                .toList();

        var workflows = workflowRegistry.listAll().stream()
                .map(this::toCapabilityInfo)
                .toList();

        // MCP Server 信息从工具注册中心的 MCP 层工具推断
        var mcpServers = toolRegistry.getToolSnapshot().stream()
                .filter(t -> t.layer() == com.lifepilot.tool.model.ToolLayer.MCP_EXTERNAL)
                .map(t -> extractMcpServerName(t.id()))
                .distinct()
                .map(serverName -> new CapabilityInfo(
                        serverName, serverName, "MCP Server: " + serverName,
                        "mcp", "active", "mcp"))
                .toList();

        log.info("能力聚合完成: skills={}, agents={}, tools={}, workflows={}, mcpServers={}",
                skills.size(), agents.size(), tools.size(), workflows.size(), mcpServers.size());

        return new CapabilitySummary(skills, agents, tools, workflows, mcpServers);
    }

    /** 将 SkillDefinition 映射为 CapabilityInfo。 */
    private CapabilityInfo toCapabilityInfo(SkillDefinition skill) {
        return new CapabilityInfo(
                skill.id(),
                skill.name(),
                skill.description(),
                mapSkillSource(skill.source()),
                "active",
                "skill"
        );
    }

    /** 将 AgentDefinition 映射为 CapabilityInfo。 */
    private CapabilityInfo toCapabilityInfo(AgentDefinition agent) {
        return new CapabilityInfo(
                agent.id(),
                agent.name(),
                agent.description(),
                mapAgentSource(agent.source()),
                "active",
                "agent"
        );
    }

    /** 将 ToolContract 映射为 CapabilityInfo。 */
    private CapabilityInfo toCapabilityInfo(ToolContract tool) {
        String source = switch (tool.layer()) {
            case JAVA_NATIVE -> "builtin";
            case SKILL_DECLARATIVE -> "skill";
            case MCP_EXTERNAL -> "mcp";
        };
        return new CapabilityInfo(
                tool.id(),
                tool.name(),
                tool.description(),
                source,
                "active",
                "tool"
        );
    }

    /** 将 WorkflowDefinition 映射为 CapabilityInfo。 */
    private CapabilityInfo toCapabilityInfo(WorkflowDefinition workflow) {
        return new CapabilityInfo(
                workflow.id(),
                workflow.name(),
                workflow.description() != null ? workflow.description() : "",
                "yaml",
                workflow.enabled() ? "active" : "inactive",
                "workflow"
        );
    }

    /** 映射 SkillSource 到字符串。 */
    private String mapSkillSource(SkillSource source) {
        return switch (source) {
            case SkillSource.Builtin _ -> "builtin";
            case SkillSource.UserDefined _ -> "yaml";
            case SkillSource.AutoGenerated _ -> "auto-generated";
            case SkillSource.Marketplace _ -> "marketplace";
        };
    }

    /** 映射 AgentSource 到字符串。 */
    private String mapAgentSource(AgentSource source) {
        return switch (source) {
            case AgentSource.Builtin _ -> "builtin";
            case AgentSource.MarkdownDefined _ -> "yaml";
            case AgentSource.Marketplace _ -> "marketplace";
        };
    }


    /** 从 MCP 工具 ID 提取 Server 名称（约定：{serverName}.{toolName}）。 */
    private String extractMcpServerName(String toolId) {
        int dotIndex = toolId.indexOf('.');
        return dotIndex > 0 ? toolId.substring(0, dotIndex) : toolId;
    }
}
