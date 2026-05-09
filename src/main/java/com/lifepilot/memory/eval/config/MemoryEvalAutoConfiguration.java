package com.lifepilot.memory.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.eval.baseline.BaselineStore;
import com.lifepilot.memory.eval.baseline.RegressionDetector;
import com.lifepilot.memory.eval.judge.ExactMatchJudge;
import com.lifepilot.memory.eval.judge.F1Judge;
import com.lifepilot.memory.eval.judge.LlmAsJudge;
import com.lifepilot.memory.eval.loader.BenchmarkLoader;
import com.lifepilot.memory.eval.loader.LocomoLoader;
import com.lifepilot.memory.eval.loader.LongMemEvalLoader;
import com.lifepilot.memory.eval.report.JsonReporter;
import com.lifepilot.memory.eval.report.MarkdownReporter;
import com.lifepilot.memory.eval.runner.BenchmarkRunner;
import com.lifepilot.memory.eval.runner.MemoryQueryAdapter;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 记忆评估 Harness 自动装配。
 *
 * <p>只有在 {@code lifepilot.memory.eval.enabled=true} 时激活。默认关闭，
 * 防止生产环境误注册评估相关 Bean。</p>
 *
 * <p>装配职责：</p>
 * <ul>
 *     <li>LoCoMo / LongMemEval Loader</li>
 *     <li>ExactMatch / F1 / LlmAsJudge</li>
 *     <li>MarkdownReporter / JsonReporter / BaselineStore / RegressionDetector</li>
 *     <li>BenchmarkRunner 主入口（需要 {@code MemoryQueryAdapter} 和
 *         {@code BenchmarkRunner.RunContextFactory}，默认未注册时 harness 无法跑）</li>
 * </ul>
 *
 * <p>{@link com.lifepilot.memory.eval.runner.IsolatedMemoryContext} 不作为 Bean 注册，
 * 只在 {@code RunContextFactory} 实现内部按需创建。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryEvalProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.memory.eval", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class MemoryEvalAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryEvalAutoConfiguration.class);

    public MemoryEvalAutoConfiguration(MemoryEvalProperties properties) {
        log.info("记忆评估 Harness 已启用，模式={}，基线路径={}",
                properties.getMode(), properties.getRegression().getBaselinePath());
    }

    @Bean
    @ConditionalOnMissingBean(LocomoLoader.class)
    public LocomoLoader locomoLoader(MemoryEvalProperties properties, ObjectMapper objectMapper) {
        return new LocomoLoader(properties.getDatasetCacheDir(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(LongMemEvalLoader.class)
    public LongMemEvalLoader longMemEvalLoader(MemoryEvalProperties properties, ObjectMapper objectMapper) {
        return new LongMemEvalLoader(properties.getDatasetCacheDir(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ExactMatchJudge.class)
    public ExactMatchJudge exactMatchJudge() {
        return new ExactMatchJudge();
    }

    @Bean
    @ConditionalOnMissingBean(F1Judge.class)
    public F1Judge f1Judge() {
        return new F1Judge();
    }

    @Bean
    @ConditionalOnMissingBean(LlmAsJudge.class)
    public LlmAsJudge llmAsJudge(MemoryEvalProperties properties,
                                  @Nullable GenerationRouter generationRouter) {
        BiFunction<String, String, String> caller = null;
        if (generationRouter != null) {
            caller = (scene, prompt) -> {
                // 调用文本生成；scene 由配置侧映射到具体模型，其他参数使用默认。
                var response = generationRouter.call(
                        scene, prompt, /* outputSchema */ null,
                        /* serviceId */ null, /* modelName */ null,
                        com.lifepilot.modelservice.model.GenerationCapability.CHAT,
                        /* timeoutOverride */ null);
                return response == null ? "" : response.content();
            };
        }
        return new LlmAsJudge(caller, properties.getJudge());
    }

    @Bean
    @ConditionalOnMissingBean(MarkdownReporter.class)
    public MarkdownReporter markdownReporter(MemoryEvalProperties properties) {
        return new MarkdownReporter(properties.getReporting().getOutputDir());
    }

    @Bean
    @ConditionalOnMissingBean(JsonReporter.class)
    public JsonReporter jsonReporter(MemoryEvalProperties properties) {
        return new JsonReporter(properties.getReporting().getOutputDir());
    }

    @Bean
    @ConditionalOnMissingBean(BaselineStore.class)
    public BaselineStore baselineStore(MemoryEvalProperties properties) {
        return new BaselineStore(properties.getRegression().getBaselinePath());
    }

    @Bean
    @ConditionalOnMissingBean(RegressionDetector.class)
    public RegressionDetector regressionDetector(MemoryEvalProperties properties) {
        return new RegressionDetector(properties.getRegression());
    }

    /**
     * 默认 MemoryQueryAdapter 返回空预测。真实评估需要项目方在 `eval` profile 下
     * 显式注册一个访问 memory.search / memory.recall 的实现（Runner 主流程在
     * 真实集成任务里落地）。本 Bean 作为安全占位，保证 harness 不因缺 Adapter 启动失败。
     */
    @Bean
    @ConditionalOnMissingBean(MemoryQueryAdapter.class)
    public MemoryQueryAdapter noopMemoryQueryAdapter() {
        log.warn("MemoryQueryAdapter 使用空实现：评估将只验证 harness 流程，不度量真实召回质量。"
                + " 请注册真实 MemoryQueryAdapter Bean 以进行完整评估。");
        return (sessions, question) -> new MemoryQueryAdapter.QueryResult("", List.of(), "");
    }

    /**
     * 默认 RunContextFactory 仅重放占位，不启动隔离 SQLite。
     * 真实评估需要在 `eval` profile 下注册基于 {@link com.lifepilot.memory.eval.runner.IsolatedMemoryContext}
     * 的实现。
     */
    @Bean
    @ConditionalOnMissingBean(BenchmarkRunner.RunContextFactory.class)
    public BenchmarkRunner.RunContextFactory noopRunContextFactory() {
        log.warn("RunContextFactory 使用空实现：评估将不隔离 SQLite。"
                + " 请注册 IsolatedMemoryContext 版本以获得真实评估。");
        return benchmarkCase -> new BenchmarkRunner.RunContext() {
            @Override public void replay(com.lifepilot.memory.eval.loader.BenchmarkCase bc) { /* no-op */ }
            @Override public void close() { /* no-op */ }
        };
    }

    @Bean
    @ConditionalOnMissingBean(BenchmarkRunner.class)
    public BenchmarkRunner benchmarkRunner(List<BenchmarkLoader> loaders,
                                           MemoryEvalProperties properties,
                                           BaselineStore baselineStore,
                                           RegressionDetector regressionDetector,
                                           MarkdownReporter markdownReporter,
                                           JsonReporter jsonReporter,
                                           BenchmarkRunner.RunContextFactory runContextFactory,
                                           MemoryQueryAdapter queryAdapter,
                                           ExactMatchJudge exactMatchJudge,
                                           F1Judge f1Judge,
                                           LlmAsJudge llmAsJudge) {
        return new BenchmarkRunner(
                loaders, properties, baselineStore, regressionDetector,
                markdownReporter, jsonReporter, runContextFactory, queryAdapter,
                exactMatchJudge, f1Judge, llmAsJudge);
    }
}
