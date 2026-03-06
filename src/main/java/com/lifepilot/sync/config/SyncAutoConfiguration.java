package com.lifepilot.sync.config;

import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.sync.connector.SyncConnector;
import com.lifepilot.sync.connector.caldav.CalDavConnector;
import com.lifepilot.sync.connector.dida.DidaConnector;
import com.lifepilot.sync.connector.obsidian.ObsidianConnector;
import com.lifepilot.sync.connector.todoist.TodoistConnector;
import com.lifepilot.sync.credential.CredentialStore;
import com.lifepilot.sync.engine.ChangeDetector;
import com.lifepilot.sync.engine.ConflictResolver;
import com.lifepilot.sync.engine.SyncEngine;
import com.lifepilot.sync.repository.SyncConflictRepository;
import com.lifepilot.sync.repository.SyncProfileRepository;
import com.lifepilot.sync.repository.SyncRecordRepository;
import com.lifepilot.sync.repository.SyncStateRepository;
import com.lifepilot.sync.scheduler.SyncScheduler;
import com.lifepilot.sync.skill.SyncSkillProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 外部数据源同步模块 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.sync.enabled=true}（默认）激活，
 * 注册同步模块所有核心组件：Repository、CredentialStore、Connector、
 * ChangeDetector、ConflictResolver、SyncEngine、SyncScheduler、SyncSkillProvider。
 * 每个 Bean 使用 {@link ConditionalOnMissingBean} 允许用户覆盖。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@AutoConfiguration
@EnableConfigurationProperties(SyncProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.sync", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SyncAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SyncAutoConfiguration.class);

    // ==================== Repository 层 ====================

    @Bean
    @ConditionalOnMissingBean
    public SyncProfileRepository syncProfileRepository(JdbcTemplate jdbcTemplate) {
        log.info("同步模块: 注册 SyncProfileRepository");
        return new SyncProfileRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SyncRecordRepository syncRecordRepository(JdbcTemplate jdbcTemplate) {
        log.info("同步模块: 注册 SyncRecordRepository");
        return new SyncRecordRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SyncStateRepository syncStateRepository(JdbcTemplate jdbcTemplate) {
        log.info("同步模块: 注册 SyncStateRepository");
        return new SyncStateRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SyncConflictRepository syncConflictRepository(JdbcTemplate jdbcTemplate) {
        log.info("同步模块: 注册 SyncConflictRepository");
        return new SyncConflictRepository(jdbcTemplate);
    }

    // ==================== 凭证存储 ====================

    @Bean
    @ConditionalOnMissingBean
    public CredentialStore credentialStore(JdbcTemplate jdbcTemplate, SyncProperties properties) {
        log.info("同步模块: 注册 CredentialStore");
        return new CredentialStore(jdbcTemplate, properties);
    }

    // ==================== 连接器 ====================

    @Bean
    @ConditionalOnMissingBean
    public CalDavConnector calDavConnector(CredentialStore credentialStore, SyncProperties properties) {
        log.info("同步模块: 注册 CalDavConnector");
        return new CalDavConnector(credentialStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TodoistConnector todoistConnector(CredentialStore credentialStore, SyncProperties properties) {
        log.info("同步模块: 注册 TodoistConnector");
        return new TodoistConnector(credentialStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public DidaConnector didaConnector(CredentialStore credentialStore, SyncProperties properties) {
        log.info("同步模块: 注册 DidaConnector");
        return new DidaConnector(credentialStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObsidianConnector obsidianConnector(SyncProperties properties) {
        log.info("同步模块: 注册 ObsidianConnector");
        return new ObsidianConnector(properties);
    }

    /**
     * 连接器类型映射表，将 connector type 字符串映射到对应的 SyncConnector 实例。
     */
    @Bean
    @ConditionalOnMissingBean(name = "syncConnectors")
    public Map<String, SyncConnector> syncConnectors(CalDavConnector calDavConnector,
                                                      TodoistConnector todoistConnector,
                                                      DidaConnector didaConnector,
                                                      ObsidianConnector obsidianConnector) {
        log.info("同步模块: 注册 syncConnectors 映射表");
        return Map.of(
                calDavConnector.type(), calDavConnector,
                todoistConnector.type(), todoistConnector,
                didaConnector.type(), didaConnector,
                obsidianConnector.type(), obsidianConnector
        );
    }

    // ==================== 核心引擎 ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TodoRepository.class, ScheduleRepository.class, HabitRepository.class})
    public ChangeDetector changeDetector(TodoRepository todoRepository,
                                         ScheduleRepository scheduleRepository,
                                         HabitRepository habitRepository,
                                         SyncRecordRepository syncRecordRepository) {
        log.info("同步模块: 注册 ChangeDetector");
        return new ChangeDetector(todoRepository, scheduleRepository, habitRepository, syncRecordRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConflictResolver conflictResolver(SyncRecordRepository syncRecordRepository,
                                              SyncConflictRepository syncConflictRepository) {
        log.info("同步模块: 注册 ConflictResolver");
        return new ConflictResolver(syncRecordRepository, syncConflictRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ChangeDetector.class, EpisodicMemory.class})
    public SyncEngine syncEngine(Map<String, SyncConnector> syncConnectors,
                                  ChangeDetector changeDetector,
                                  ConflictResolver conflictResolver,
                                  SyncRecordRepository syncRecordRepository,
                                  SyncStateRepository syncStateRepository,
                                  SyncConflictRepository syncConflictRepository,
                                  TodoRepository todoRepository,
                                  ScheduleRepository scheduleRepository,
                                  HabitRepository habitRepository,
                                  EpisodicMemory episodicMemory,
                                  SyncProperties properties) {
        log.info("同步模块: 注册 SyncEngine");
        return new SyncEngine(syncConnectors, changeDetector, conflictResolver,
                syncRecordRepository, syncStateRepository, syncConflictRepository,
                todoRepository, scheduleRepository, habitRepository,
                episodicMemory, properties);
    }

    // ==================== 调度器 ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SyncEngine.class)
    public SyncScheduler syncScheduler(SyncEngine syncEngine,
                                        SyncProfileRepository syncProfileRepository,
                                        SyncProperties properties) {
        log.info("同步模块: 注册 SyncScheduler");
        return new SyncScheduler(syncEngine, syncProfileRepository, properties);
    }

    // ==================== Agent 集成 ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SyncEngine.class)
    public SyncSkillProvider syncSkillProvider(SyncEngine syncEngine,
                                               SyncScheduler syncScheduler,
                                               SyncProfileRepository syncProfileRepository,
                                               SyncStateRepository syncStateRepository,
                                               SyncConflictRepository syncConflictRepository,
                                               SyncRecordRepository syncRecordRepository,
                                               CredentialStore credentialStore,
                                               Map<String, SyncConnector> syncConnectors,
                                               SyncProperties properties,
                                               PromptRegistry promptRegistry) {
        log.info("同步模块: 注册 SyncSkillProvider");
        return new SyncSkillProvider(syncEngine, syncScheduler, syncProfileRepository,
                syncStateRepository, syncConflictRepository, syncRecordRepository,
                credentialStore, syncConnectors, properties, promptRegistry);
    }

    // ==================== 启动后初始化 ====================

    /**
     * 应用启动完成后启动同步调度器。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        if (ctx.containsBean("syncScheduler")) {
            var scheduler = ctx.getBean(SyncScheduler.class);
            scheduler.start();
            log.info("ApplicationReady: SyncScheduler 已启动");
        }
    }
}
