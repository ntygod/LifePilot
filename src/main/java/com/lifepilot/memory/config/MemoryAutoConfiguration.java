package com.lifepilot.memory.config;

import com.lifepilot.agent.learning.config.AgentLearningAutoConfiguration;
import com.lifepilot.memory.consumption.config.MemoryConsumptionAutoConfiguration;
import com.lifepilot.memory.governance.config.MemoryGovernanceAutoConfiguration;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalAutoConfiguration;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * 记忆系统自动装配 — 聚合入口，导入五个子模块配置。
 *
 * <p>Bean 定义已迁移到各子模块：
 * <ul>
 *   <li>{@link MemoryStoreAutoConfiguration} — 存储层（实体 CRUD、向量 DB、投影、工作区）</li>
 *   <li>{@link MemoryRetrievalAutoConfiguration} — 检索层（FTS、图遍历、混合检索、编排）</li>
 *   <li>{@link MemoryConsumptionAutoConfiguration} — 消费层（热摘要、压缩、清理）</li>
 *   <li>{@link MemoryGovernanceAutoConfiguration} — 治理层（访问策略、安全、审计、MCP）</li>
 *   <li>{@link AgentLearningAutoConfiguration} — 学习层（提取、巩固、遗忘、经验）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import({
        MemoryStoreAutoConfiguration.class,
        MemoryRetrievalAutoConfiguration.class,
        MemoryConsumptionAutoConfiguration.class,
        MemoryGovernanceAutoConfiguration.class,
        AgentLearningAutoConfiguration.class
})
public class MemoryAutoConfiguration {
}
