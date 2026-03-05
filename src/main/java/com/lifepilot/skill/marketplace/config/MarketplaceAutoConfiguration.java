package com.lifepilot.skill.marketplace.config;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.marketplace.MarketplaceService;
import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.index.IndexRepository;
import com.lifepilot.skill.marketplace.install.InstalledSkillRepository;
import com.lifepilot.skill.marketplace.install.SkillInstaller;
import com.lifepilot.skill.marketplace.security.SkillSecurityScanner;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.yaml.YamlSchemaValidator;
import com.lifepilot.skill.yaml.YamlSkillLoader;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Skill 市场 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.marketplace.enabled=true}（默认）激活，
 * 注册市场模块所有核心 Bean：数据访问层、版本解析、安全扫描、索引管理、安装器和服务门面。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.marketplace.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MarketplaceProperties.class)
public class MarketplaceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceAutoConfiguration.class);

    // --- 数据访问层 ---

    @Bean
    @ConditionalOnMissingBean
    public IndexRepository indexRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 市场: 注册 IndexRepository");
        return new IndexRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public InstalledSkillRepository installedSkillRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 市场: 注册 InstalledSkillRepository");
        return new InstalledSkillRepository(jdbcTemplate);
    }

    // --- 版本解析 ---

    @Bean
    @ConditionalOnMissingBean
    public VersionResolver versionResolver() {
        log.info("Skill 市场: 注册 VersionResolver");
        return new VersionResolver();
    }

    // --- 安全扫描 ---

    @Bean
    @ConditionalOnMissingBean
    public SkillSecurityScanner skillSecurityScanner(MarketplaceProperties properties,
                                                     DynamicToolRegistry toolRegistry) {
        log.info("Skill 市场: 注册 SkillSecurityScanner");
        return new SkillSecurityScanner(properties, toolRegistry);
    }

    // --- 索引管理 ---

    @Bean
    @ConditionalOnMissingBean
    public IndexManager indexManager(MarketplaceProperties properties,
                                     IndexRepository indexRepository,
                                     InstalledSkillRepository installedSkillRepository,
                                     VersionResolver versionResolver,
                                     RestClient.Builder restClientBuilder) {
        log.info("Skill 市场: 注册 IndexManager");
        return new IndexManager(properties, indexRepository, installedSkillRepository,
                versionResolver, restClientBuilder);
    }

    // --- 安装器 ---

    @Bean
    @ConditionalOnMissingBean
    public SkillInstaller skillInstaller(IndexManager indexManager,
                                         VersionResolver versionResolver,
                                         SkillSecurityScanner securityScanner,
                                         YamlSchemaValidator schemaValidator,
                                         YamlSkillLoader yamlSkillLoader,
                                         SkillRegistry skillRegistry,
                                         InstalledSkillRepository installedSkillRepository,
                                         MarketplaceProperties marketplaceProperties,
                                         SkillConfigProperties skillConfigProperties,
                                         RestClient.Builder restClientBuilder) {
        log.info("Skill 市场: 注册 SkillInstaller");
        return new SkillInstaller(indexManager, versionResolver, securityScanner,
                schemaValidator, yamlSkillLoader, skillRegistry, installedSkillRepository,
                marketplaceProperties, skillConfigProperties, restClientBuilder);
    }

    // --- 服务门面 ---

    @Bean
    @ConditionalOnMissingBean
    public MarketplaceService marketplaceService(IndexManager indexManager,
                                                  SkillInstaller skillInstaller,
                                                  VersionResolver versionResolver,
                                                  InstalledSkillRepository installedSkillRepository) {
        log.info("Skill 市场: 注册 MarketplaceService");
        return new MarketplaceService(indexManager, skillInstaller, versionResolver,
                installedSkillRepository);
    }
}
