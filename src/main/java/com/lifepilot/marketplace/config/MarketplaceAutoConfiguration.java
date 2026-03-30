package com.lifepilot.marketplace.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.repository.ChannelPluginRepository;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.marketplace.MarketplaceService;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.index.IndexRepository;
import com.lifepilot.marketplace.install.*;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.security.SecurityScanner;
import com.lifepilot.marketplace.version.VersionResolver;
import com.lifepilot.multiagent.loader.AgentMarkdownLoader;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.Map;

/**
 * 扩展市场 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.marketplace.enabled=true}（默认）激活，
 * 注册扩展市场所有核心 Bean：数据访问层、版本解析、安全扫描、索引管理、
 * 三种安装策略、扩展安装协调器和服务门面。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.marketplace.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MarketplaceProperties.class)
public class MarketplaceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceAutoConfiguration.class);

    // ─────────────────────────────────────────────
    //  数据访问层
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public IndexRepository indexRepository(JdbcTemplate jdbcTemplate) {
        log.info("扩展市场: 注册 IndexRepository");
        return new IndexRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public InstalledExtensionRepository installedExtensionRepository(JdbcTemplate jdbcTemplate) {
        log.info("扩展市场: 注册 InstalledExtensionRepository");
        return new InstalledExtensionRepository(jdbcTemplate);
    }

    // ─────────────────────────────────────────────
    //  版本解析
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public VersionResolver versionResolver() {
        log.info("扩展市场: 注册 VersionResolver");
        return new VersionResolver();
    }

    // ─────────────────────────────────────────────
    //  安全扫描
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public SecurityScanner securityScanner(MarketplaceProperties properties,
                                           DynamicToolRegistry toolRegistry) {
        log.info("扩展市场: 注册 SecurityScanner");
        return new SecurityScanner(properties, toolRegistry);
    }

    // ─────────────────────────────────────────────
    //  索引管理
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public IndexManager indexManager(MarketplaceProperties properties,
                                     IndexRepository indexRepository,
                                     InstalledExtensionRepository installedExtensionRepository,
                                     VersionResolver versionResolver,
                                     RestClient.Builder restClientBuilder) {
        log.info("扩展市场: 注册 IndexManager");
        return new IndexManager(properties, indexRepository, installedExtensionRepository,
                versionResolver, restClientBuilder);
    }

    // ─────────────────────────────────────────────
    //  安装策略
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public SkillInstallStrategy skillInstallStrategy(MarkdownSkillLoader markdownSkillLoader,
                                                     SkillRegistry skillRegistry,
                                                     MarketplaceProperties properties) {
        Path installDir = Path.of(properties.getInstallDirs().getSkills()
                .replace("${user.home}", System.getProperty("user.home")));
        log.info("扩展市场: 注册 SkillInstallStrategy, installDir={}", installDir);
        return new SkillInstallStrategy(markdownSkillLoader, skillRegistry, installDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentInstallStrategy agentInstallStrategy(AgentMarkdownLoader agentMarkdownLoader,
                                                     AgentRegistry agentRegistry,
                                                     MarketplaceProperties properties) {
        Path installDir = Path.of(properties.getInstallDirs().getAgents()
                .replace("${user.home}", System.getProperty("user.home")));
        log.info("扩展市场: 注册 AgentInstallStrategy, installDir={}", installDir);
        return new AgentInstallStrategy(agentMarkdownLoader, agentRegistry, installDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowInstallStrategy workflowInstallStrategy(WorkflowYamlParser workflowYamlParser,
                                                           WorkflowRepository workflowRepository,
                                                           WorkflowRegistry workflowRegistry,
                                                           MarketplaceProperties properties) {
        Path installDir = Path.of(properties.getInstallDirs().getWorkflows()
                .replace("${user.home}", System.getProperty("user.home")));
        log.info("扩展市场: 注册 WorkflowInstallStrategy, installDir={}", installDir);
        return new WorkflowInstallStrategy(workflowYamlParser, workflowRepository,
                workflowRegistry, installDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelInstallStrategy channelInstallStrategy(ChannelRegistry channelRegistry,
                                                         ChannelPluginRepository channelPluginRepository,
                                                         ChannelInstanceService channelInstanceService,
                                                         ObjectMapper objectMapper,
                                                         MarketplaceProperties properties) {
        Path installDir = Path.of(properties.getInstallDirs().getChannels()
                .replace("${user.home}", System.getProperty("user.home")));
        log.info("扩展市场: 注册 ChannelInstallStrategy, installDir={}", installDir);
        return new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                objectMapper,
                installDir,
                properties
        );
    }

    // ─────────────────────────────────────────────
    //  扩展安装协调器
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public ExtensionInstaller extensionInstaller(IndexManager indexManager,
                                                 VersionResolver versionResolver,
                                                 SecurityScanner securityScanner,
                                                 InstalledExtensionRepository installedExtensionRepository,
                                                 MarketplaceProperties properties,
                                                 SkillInstallStrategy skillInstallStrategy,
                                                 AgentInstallStrategy agentInstallStrategy,
                                                 WorkflowInstallStrategy workflowInstallStrategy,
                                                 ChannelInstallStrategy channelInstallStrategy,
                                                 RestClient.Builder restClientBuilder) {
        Map<ExtensionType, InstallStrategy> strategies = Map.of(
                ExtensionType.SKILL, skillInstallStrategy,
                ExtensionType.AGENT, agentInstallStrategy,
                ExtensionType.WORKFLOW, workflowInstallStrategy,
                ExtensionType.CHANNEL, channelInstallStrategy
        );
        log.info("扩展市场: 注册 ExtensionInstaller, strategies={}", strategies.keySet());
        return new ExtensionInstaller(indexManager, versionResolver, securityScanner,
                installedExtensionRepository, properties, strategies, restClientBuilder);
    }

    // ─────────────────────────────────────────────
    //  服务门面
    // ─────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public MarketplaceService marketplaceService(IndexManager indexManager,
                                                  ExtensionInstaller extensionInstaller,
                                                  VersionResolver versionResolver,
                                                  InstalledExtensionRepository installedExtensionRepository,
                                                  ObjectMapper objectMapper) {
        log.info("扩展市场: 注册 MarketplaceService");
        return new MarketplaceService(indexManager, extensionInstaller, versionResolver,
                installedExtensionRepository, objectMapper);
    }
}
