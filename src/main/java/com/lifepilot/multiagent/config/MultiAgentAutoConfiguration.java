package com.lifepilot.multiagent.config;

import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.config.AgentAutoConfiguration;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.multiagent.bridge.AgentToToolBridge;
import com.lifepilot.multiagent.discovery.ToolDiscoveryService;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.execution.HandoffToolFactory;
import com.lifepilot.multiagent.loader.AgentMarkdownLoader;
import com.lifepilot.multiagent.loader.AgentMarkdownParser;
import com.lifepilot.multiagent.loader.AgentMarkdownSerializer;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.config.SkillAutoConfiguration;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 多 Agent 协作 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.agent.multi-agent.enabled=true}（默认）激活，
 * 注册 AgentRegistry、AgentExecutor、HandoffToolFactory、AgentMarkdownParser、
 * AgentMarkdownLoader、AgentToToolBridge、ToolDiscoveryService 等核心 Bean。</p>
 *
 * <p>应用启动后自动加载预设 Agent（classpath preset-agents/）和用户自定义 Agent，
 * 并根据配置启动热加载。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@AutoConfiguration(after = {AgentAutoConfiguration.class, SkillAutoConfiguration.class})
@EnableConfigurationProperties(MultiAgentProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.agent.multi-agent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MultiAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry(ApplicationEventPublisher eventPublisher) {
        log.info("多 Agent 协作: 注册 AgentRegistry");
        return new AgentRegistry(eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentExecutor agentExecutor(AgentOrchestrator agentOrchestrator,
                                       DynamicToolRegistry toolRegistry,
                                       MultiAgentProperties config) {
        log.info("多 Agent 协作: 注册 AgentExecutor, maxDelegationDepth={}",
                config.getMaxDelegationDepth());
        return new AgentExecutor(agentOrchestrator, toolRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandoffToolFactory handoffToolFactory(AgentExecutor agentExecutor) {
        log.info("多 Agent 协作: 注册 HandoffToolFactory");
        return new HandoffToolFactory(agentExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentMarkdownParser agentMarkdownParser(MultiAgentProperties config) {
        log.info("多 Agent 协作: 注册 AgentMarkdownParser");
        return new AgentMarkdownParser(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentMarkdownSerializer agentMarkdownSerializer() {
        log.info("多 Agent 协作: 注册 AgentMarkdownSerializer");
        return new AgentMarkdownSerializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentMarkdownLoader agentMarkdownLoader(AgentRegistry agentRegistry,
                                                    AgentMarkdownParser parser,
                                                    MultiAgentProperties config,
                                                    SharedScheduler sharedScheduler) {
        log.info("多 Agent 协作: 注册 AgentMarkdownLoader, path={}",
                config.getAgentDefinitionsPath());
        return new AgentMarkdownLoader(agentRegistry, parser, config, sharedScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToToolBridge agentToToolBridge(DynamicToolRegistry toolRegistry,
                                               HandoffToolFactory handoffToolFactory,
                                               MultiAgentProperties config) {
        log.info("多 Agent 协作: 注册 AgentToToolBridge, registerHandoffTools={}",
                config.isRegisterHandoffTools());
        return new AgentToToolBridge(toolRegistry, handoffToolFactory, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolDiscoveryService toolDiscoveryService(DynamicToolRegistry toolRegistry) {
        log.info("多 Agent 协作: 注册 ToolDiscoveryService");
        return new ToolDiscoveryService(toolRegistry);
    }

    // ==================== 启动后初始化 ====================

    /**
     * 应用启动完成后加载预设 Agent 和用户自定义 Agent，启动热加载。
     *
     * <p>加载顺序：先从 classpath preset-agents/ 加载预设 Agent（Builtin 来源），
     * 再从 agentDefinitionsPath 加载用户自定义 Markdown Agent（MarkdownDefined 来源）。
     * MarkdownDefined 可覆盖 Builtin，实现用户自定义优先。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();

        if (!ctx.containsBean("agentRegistry")) {
            return;
        }

        var registry = ctx.getBean(AgentRegistry.class);
        var parser = ctx.getBean(AgentMarkdownParser.class);
        var loader = ctx.getBean(AgentMarkdownLoader.class);
        var config = ctx.getBean(MultiAgentProperties.class);

        // 1. 加载预设 Agent（Builtin 来源）
        loadPresetAgents(registry, parser);

        // 2. 加载用户自定义 Agent（MarkdownDefined 来源）
        String agentPath = config.getAgentDefinitionsPath();
        if (agentPath.startsWith("~")) {
            agentPath = System.getProperty("user.home") + agentPath.substring(1);
        }
        loader.loadFromDirectory(Path.of(agentPath));

        // 3. 启动热加载
        if (config.getHotReload().isEnabled()) {
            loader.startHotReload();
        }

        log.info("多 Agent 协作初始化完成: 已注册 {} 个 Agent", registry.listAll().size());
    }

    /**
     * 从 classpath preset-agents/ 目录加载预设 Agent 定义。
     *
     * <p>预设 Agent 以 Builtin 来源注册，用户自定义 Agent（MarkdownDefined）可覆盖。</p>
     */
    private void loadPresetAgents(AgentRegistry registry, AgentMarkdownParser parser) {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:preset-agents/*.md");

            for (Resource resource : resources) {
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    String filename = resource.getFilename();
                    // 使用相对路径作为标识，避免 Windows 上 classpath: 前缀的非法字符
                    Path virtualPath = Path.of("preset-agents", filename);

                    Optional<AgentDefinition> parsed = parser.parse(content, virtualPath);
                    parsed.ifPresent(def -> {
                        // 将来源替换为 Builtin
                        var builtinDef = def.toBuilder()
                                .source(new AgentSource.Builtin())
                                .build();
                        registry.register(builtinDef);
                        log.info("预设 Agent 加载成功: id={}, name={}", builtinDef.id(), builtinDef.name());
                    });
                } catch (IOException e) {
                    log.warn("预设 Agent 文件读取失败: resource={}, error={}",
                            resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("预设 Agent 目录扫描失败: error={}", e.getMessage());
        }
    }
}
