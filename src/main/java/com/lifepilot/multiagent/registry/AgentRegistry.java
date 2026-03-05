package com.lifepilot.multiagent.registry;

import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentRegistryEvent;
import com.lifepilot.multiagent.model.AgentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 定义注册中心。
 *
 * <p>使用 ConcurrentHashMap 存储，支持运行时动态注册、注销和查找。
 * 通过 Spring ApplicationEventPublisher 发布注册/注销事件。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final ConcurrentHashMap<String, AgentDefinition> agents = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;

    public AgentRegistry(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 注册 Agent 定义（受保护的注册）。
     *
     * <p>规则：Builtin 不允许被 Builtin 覆盖，MarkdownDefined 可覆盖 Builtin。</p>
     *
     * <p>用于系统启动时的批量加载、Markdown 等“来源定义式”注册场景。</p>
     *
     * @param definition Agent 定义
     * @return 是否注册成功
     */
    public boolean register(AgentDefinition definition) {
        if (definition.id() == null || definition.id().isBlank()) {
            log.warn("Agent 注册失败: ID 为空");
            return false;
        }

        String id = definition.id();
        AgentDefinition existing = agents.get(id);

        // Builtin 不允许被 Builtin 覆盖
        if (existing != null
                && existing.source() instanceof AgentSource.Builtin
                && definition.source() instanceof AgentSource.Builtin) {
            log.warn("Agent 注册失败: Builtin 不允许覆盖已有 Builtin, id={}", id);
            return false;
        }

        agents.put(id, definition);
        eventPublisher.publishEvent(new AgentRegistryEvent.AgentRegistered(definition));
        log.info("Agent 注册成功: id={}, source={}", id, definition.source().getClass().getSimpleName());
        return true;
    }

    /**
     * 强制注册 Agent 定义。
     *
     * <p>跳过 Builtin 覆盖校验，主要用于运行时通过管理 API
     * 对已有 Agent（包括 Builtin）进行在线更新的场景。</p>
     *
     * @param definition Agent 定义
     * @return 是否注册成功
     */
    public boolean forceRegister(AgentDefinition definition) {
        if (definition.id() == null || definition.id().isBlank()) {
            log.warn("Agent 强制注册失败: ID 为空");
            return false;
        }

        String id = definition.id();
        agents.put(id, definition);
        eventPublisher.publishEvent(new AgentRegistryEvent.AgentRegistered(definition));
        log.info("Agent 强制注册成功: id={}, source={}", id, definition.source().getClass().getSimpleName());
        return true;
    }

    /**
     * 注销 Agent 定义。
     *
     * @param agentId Agent ID
     * @return 是否注销成功
     */
    public boolean unregister(String agentId) {
        AgentDefinition removed = agents.remove(agentId);
        if (removed != null) {
            eventPublisher.publishEvent(new AgentRegistryEvent.AgentUnregistered(agentId));
            log.info("Agent 注销成功: id={}", agentId);
            return true;
        }
        return false;
    }

    /** 按 ID 查找 Agent 定义。 */
    public Optional<AgentDefinition> find(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** 列出所有已注册 Agent（不可变列表）。 */
    public List<AgentDefinition> listAll() {
        return List.copyOf(agents.values());
    }

    /**
     * 批量注销指定来源类型的所有 Agent。
     *
     * @param sourceType 来源类型 Class
     * @return 注销数量
     */
    public int unregisterBySource(Class<? extends AgentSource> sourceType) {
        var toRemove = agents.values().stream()
                .filter(def -> sourceType.isInstance(def.source()))
                .map(AgentDefinition::id)
                .toList();
        toRemove.forEach(this::unregister);
        return toRemove.size();
    }
}
