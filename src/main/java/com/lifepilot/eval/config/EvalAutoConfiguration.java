package com.lifepilot.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.config.threadpool.VirtualThreadExecutorFactory;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.evaluator.DiagnosticEnricher;
import com.lifepilot.eval.feedback.FeedbackStore;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.scenario.ScenarioSerializer;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.eval.web.EvalController;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.experience.ExperienceSummarizer;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;


/**
 * Agentic Evals 框架 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.eval.enabled=true}（默认开启，matchIfMissing=true）激活，
 * 注册评估框架全部 Bean。</p>
 *
 * <p>依赖已有模块：DynamicToolRegistry、LlmRouter、JdbcTemplate、
 * ObjectMapper、AgentLoop、TraceQuery、EvaluationCore。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
@AutoConfiguration
@EnableConfigurationProperties(EvalConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.eval", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(AgentOrchestrator.class)
public class EvalAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EvalAutoConfiguration.class);

    // ==================== 场景层 ====================

    @Bean
    @ConditionalOnMissingBean
    public ScenarioLoader scenarioLoader(EvalConfigProperties config) {
        log.info("注册 ScenarioLoader Bean");
        return new ScenarioLoader(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScenarioSerializer scenarioSerializer() {
        return new ScenarioSerializer();
    }

    // ==================== LLM 评判器 ====================

    @Bean
    @ConditionalOnMissingBean
    public LlmJudge llmJudge(LlmRouter llmRouter, EvalConfigProperties config,
                             PromptRegistry promptRegistry) {
        return new LlmJudge(llmRouter, config, promptRegistry);
    }

    // ==================== 持久化与报告 ====================

    @Bean
    @ConditionalOnMissingBean
    public EvalStore evalStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new EvalStore(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvalReport evalReport(EvalStore evalStore, EvalConfigProperties config) {
        return new EvalReport(evalStore, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeedbackStore feedbackStore(JdbcTemplate jdbcTemplate) {
        return new FeedbackStore(jdbcTemplate);
    }

    // ==================== REST 控制器 ====================

    @Bean
    @ConditionalOnMissingBean
    public EvalController evalController(ScenarioLoader scenarioLoader,
                                          EvalEngine evalEngine,
                                          EvalStore evalStore,
                                          EvalReport evalReport,
                                          FeedbackStore feedbackStore) {
        log.info("注册 EvalController Bean");
        return new EvalController(scenarioLoader, evalEngine, evalStore, evalReport, feedbackStore);
    }

    // ==================== 评估引擎 ====================

    @Bean
    @ConditionalOnMissingBean
    public DiagnosticEnricher diagnosticEnricher() {
        return new DiagnosticEnricher();
    }

    @Bean
    @ConditionalOnMissingBean(name = "evalExecutor")
    public ExecutorService evalExecutor(VirtualThreadExecutorFactory executorFactory) {
        log.info("注册 eval 专用 ExecutorService Bean");
        return executorFactory.create("eval-engine");
    }

    @Bean
    @ConditionalOnMissingBean
    public EvalEngine evalEngine(ScenarioLoader scenarioLoader,
                                  AgentOrchestrator agentOrchestrator,
                                  TraceQuery traceQuery,
                                  EvaluationCore evaluationCore,
                                  LlmJudge llmJudge,
                                  EvalStore evalStore,
                                  EvalReport evalReport,
                                  DynamicToolRegistry toolRegistry,
                                  EvalConfigProperties config,
                                  ExecutorService evalExecutor,
                                  DiagnosticEnricher diagnosticEnricher,
                                  ObjectMapper objectMapper,
                                  @Autowired(required = false) ExperienceSummarizer experienceSummarizer) {
        return new EvalEngine(scenarioLoader, agentOrchestrator, traceQuery,
                evaluationCore, llmJudge, evalStore, evalReport, toolRegistry, config,
                evalExecutor, diagnosticEnricher, objectMapper, experienceSummarizer);
    }

    // ==================== 内置场景同步 ====================

    /**
     * 应用启动完成后，将 classpath:eval-scenarios/ 下的内置场景 YAML 同步到用户目录。
     * 本地已存在同名文件时跳过，不覆盖用户自定义修改。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncBuiltinScenarios(ApplicationReadyEvent event) {
        var config = event.getApplicationContext().getBean(EvalConfigProperties.class);
        Path scenarioDir = resolveScenarioDir(config.getScenarioDirectory());
        int synced = 0;
        int skipped = 0;

        try {
            if (!Files.exists(scenarioDir)) {
                Files.createDirectories(scenarioDir);
            }

            Set<String> localFiles = listLocalYamlStems(scenarioDir);

            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:eval-scenarios/*.yml");

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String stem = fileStem(filename);

                if (localFiles.contains(stem)) {
                    skipped++;
                    log.debug("内置场景检测到本地同名文件，跳过: file={}", filename);
                    continue;
                }

                try {
                    String yaml = resource.getContentAsString(StandardCharsets.UTF_8);
                    Path target = scenarioDir.resolve(filename);
                    Files.writeString(target, yaml, StandardOpenOption.CREATE_NEW);
                    synced++;
                    log.info("内置场景已同步到用户目录: file={}", filename);
                } catch (IOException e) {
                    log.warn("同步内置场景失败: file={}, error={}", filename, e.getMessage());
                }
            }

            log.info("内置场景同步完成: synced={}, skipped={}", synced, skipped);
        } catch (IOException e) {
            log.warn("内置场景同步异常: error={}", e.getMessage());
        }
    }

    /** 解析场景目录路径，支持 ~ 前缀替换为用户主目录。 */
    private static Path resolveScenarioDir(String dir) {
        if (dir.startsWith("~")) {
            return Path.of(System.getProperty("user.home") + dir.substring(1));
        }
        return Path.of(dir);
    }

    /** 列出本地场景目录中已有的 YAML 文件名（不含扩展名）。 */
    private Set<String> listLocalYamlStems(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        var stems = new HashSet<String>();
        try (var files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".yml") || name.endsWith(".yaml"))
                    .map(this::fileStem)
                    .forEach(stems::add);
        } catch (IOException e) {
            log.warn("读取场景目录失败: dir={}, error={}", dir, e.getMessage());
            return Set.of();
        }
        return Set.copyOf(stems);
    }

    /** 提取文件名主干（去掉扩展名）。 */
    private String fileStem(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
