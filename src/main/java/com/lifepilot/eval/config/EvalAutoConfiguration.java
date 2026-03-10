package com.lifepilot.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.evaluator.TrajectoryEvaluator;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.scenario.ScenarioSerializer;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;


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

    // ==================== 轨迹评估器 ====================

    @Bean
    @ConditionalOnMissingBean
    public TrajectoryEvaluator evalTrajectoryEvaluator(EvaluationCore evaluationCore) {
        log.info("注册 TrajectoryEvaluator Bean（委托 EvaluationCore）");
        return new TrajectoryEvaluator(evaluationCore);
    }

    // ==================== LLM 评判器 ====================

    @Bean
    @ConditionalOnMissingBean
    public LlmJudge llmJudge(LlmRouter llmRouter, EvalConfigProperties config) {
        return new LlmJudge(llmRouter, config);
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

    // ==================== 评估引擎 ====================

    @Bean
    @ConditionalOnMissingBean
    public EvalEngine evalEngine(ScenarioLoader scenarioLoader,
                                  AgentLoop agentLoop,
                                  TraceQuery traceQuery,
                                  EvaluationCore evaluationCore,
                                  LlmJudge llmJudge,
                                  EvalStore evalStore,
                                  EvalReport evalReport,
                                  DynamicToolRegistry toolRegistry,
                                  EvalConfigProperties config) {
        return new EvalEngine(scenarioLoader, agentLoop, traceQuery,
                evaluationCore, llmJudge, evalStore, evalReport, toolRegistry, config);
    }
}
