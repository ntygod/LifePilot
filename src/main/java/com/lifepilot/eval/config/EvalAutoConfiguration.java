package com.lifepilot.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.evaluator.DimensionEvaluator;
import com.lifepilot.eval.evaluator.ParameterValidityEvaluator;
import com.lifepilot.eval.evaluator.PolicyComplianceEvaluator;
import com.lifepilot.eval.evaluator.StepEfficiencyEvaluator;
import com.lifepilot.eval.evaluator.TokenEfficiencyEvaluator;
import com.lifepilot.eval.evaluator.ToolSelectionEvaluator;
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

import java.util.List;

/**
 * Agentic Evals 框架 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.eval.enabled=true}（需显式开启）激活，
 * 注册评估框架全部 Bean：场景加载器、场景序列化器、五个维度评估器、
 * 轨迹评估器、LLM 评判器、持久化存储、报告生成器和评估引擎。</p>
 *
 * <p>依赖已有模块：DynamicToolRegistry、LlmRouter、JdbcTemplate、
 * ObjectMapper、AgentLoop、TraceRecorder。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
@AutoConfiguration
@EnableConfigurationProperties(EvalConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.eval", name = "enabled", havingValue = "true", matchIfMissing = false)
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

    // ==================== 五维评估器 ====================

    @Bean
    @ConditionalOnMissingBean
    public ToolSelectionEvaluator toolSelectionEvaluator() {
        return new ToolSelectionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ParameterValidityEvaluator parameterValidityEvaluator(DynamicToolRegistry toolRegistry) {
        return new ParameterValidityEvaluator(toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public StepEfficiencyEvaluator stepEfficiencyEvaluator() {
        return new StepEfficiencyEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyComplianceEvaluator policyComplianceEvaluator() {
        return new PolicyComplianceEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenEfficiencyEvaluator tokenEfficiencyEvaluator() {
        return new TokenEfficiencyEvaluator();
    }

    // ==================== 轨迹评估器 ====================

    @Bean
    @ConditionalOnMissingBean
    public TrajectoryEvaluator trajectoryEvaluator(List<DimensionEvaluator> evaluators,
                                                    DynamicToolRegistry toolRegistry) {
        log.info("注册 TrajectoryEvaluator Bean: 维度评估器数量={}", evaluators.size());
        return new TrajectoryEvaluator(evaluators, toolRegistry);
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
                                  TraceRecorder traceRecorder,
                                  TrajectoryEvaluator trajectoryEvaluator,
                                  LlmJudge llmJudge,
                                  EvalStore evalStore,
                                  EvalReport evalReport,
                                  EvalConfigProperties config) {
        return new EvalEngine(scenarioLoader, agentLoop, traceRecorder,
                trajectoryEvaluator, llmJudge, evalStore, evalReport, config);
    }
}
