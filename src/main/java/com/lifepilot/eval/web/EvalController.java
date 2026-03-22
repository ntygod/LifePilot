package com.lifepilot.eval.web;

import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.feedback.EvalFeedback;
import com.lifepilot.eval.feedback.EvalFeedback.FeedbackType;
import com.lifepilot.eval.feedback.FeedbackStore;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.ComparisonReport;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 评估模块 REST 控制器。
 *
 * <p>暴露评估场景查询、评估运行触发、结果查询、报告获取、
 * A/B 对比、基线管理、人工反馈等 HTTP 端点。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final ScenarioLoader scenarioLoader;
    private final EvalEngine evalEngine;
    private final EvalStore evalStore;
    private final EvalReport evalReport;
    private final FeedbackStore feedbackStore;

    public EvalController(ScenarioLoader scenarioLoader,
                          EvalEngine evalEngine,
                          EvalStore evalStore,
                          EvalReport evalReport,
                          FeedbackStore feedbackStore) {
        this.scenarioLoader = scenarioLoader;
        this.evalEngine = evalEngine;
        this.evalStore = evalStore;
        this.evalReport = evalReport;
        this.feedbackStore = feedbackStore;
    }

    // ==================== 场景 ====================

    /**
     * 列出所有场景，可选按标签过滤。
     */
    @GetMapping("/scenarios")
    public ResponseEntity<ApiResponse<List<BenchmarkScenario>>> listScenarios(
            @RequestParam(required = false) String tag) {
        log.info("查询评估场景: tag={}", tag);
        List<BenchmarkScenario> scenarios = (tag != null && !tag.isBlank())
                ? scenarioLoader.loadByTags(List.of(tag))
                : scenarioLoader.loadAll();
        return ResponseEntity.ok(ApiResponse.ok(scenarios));
    }

    /**
     * 获取单个场景。
     */
    @GetMapping("/scenarios/{id}")
    public ResponseEntity<ApiResponse<BenchmarkScenario>> getScenario(@PathVariable String id) {
        log.info("查询评估场景详情: id={}", id);
        BenchmarkScenario scenario = scenarioLoader.loadById(id);
        return ResponseEntity.ok(ApiResponse.ok(scenario));
    }

    /**
     * 获取指定场景的标注答案。
     */
    @GetMapping("/scenarios/{scenarioId}/golden-answers")
    public ResponseEntity<ApiResponse<List<EvalFeedback>>> getGoldenAnswers(
            @PathVariable String scenarioId) {
        log.info("查询标注答案: scenarioId={}", scenarioId);
        List<EvalFeedback> answers = feedbackStore.findGoldenAnswers(scenarioId);
        return ResponseEntity.ok(ApiResponse.ok(answers));
    }

    // ==================== 运行 ====================

    /**
     * 触发批量评估运行。
     */
    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<ReportSummary>> triggerRun(@RequestBody EvalRunRequest request) {
        log.info("触发评估运行: scenarioIds={}, tag={}, smokeTestOnly={}, offlineReeval={}",
                request.scenarioIds(), request.tag(), request.smokeTestOnly(), request.offlineReeval());
        List<BenchmarkScenario> scenarios = resolveScenarios(request);
        ReportSummary summary = evalEngine.evaluateBatch(scenarios);
        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    /**
     * 查询指定运行的评估结果列表。
     */
    @GetMapping("/runs/{evalRunId}/results")
    public ResponseEntity<ApiResponse<List<EvalResult>>> getRunResults(@PathVariable String evalRunId) {
        log.info("查询运行结果: evalRunId={}", evalRunId);
        List<EvalResult> results = evalStore.findByRunId(evalRunId);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    /**
     * 获取指定运行的报告汇总。
     */
    @GetMapping("/runs/{evalRunId}/report")
    public ResponseEntity<ApiResponse<ReportSummary>> getRunReport(@PathVariable String evalRunId) {
        log.info("查询运行报告: evalRunId={}", evalRunId);
        List<EvalResult> results = evalStore.findByRunId(evalRunId);
        ReportSummary summary = evalReport.generateSummary(results, evalRunId);
        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    /**
     * A/B 对比两次运行。
     */
    @GetMapping("/runs/{currentRunId}/compare/{baselineRunId}")
    public ResponseEntity<ApiResponse<ComparisonReport>> compareRuns(
            @PathVariable String currentRunId,
            @PathVariable String baselineRunId) {
        log.info("A/B 对比: current={}, baseline={}", currentRunId, baselineRunId);
        ComparisonReport report = evalReport.compareRuns(currentRunId, baselineRunId);
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    /**
     * 标记指定运行为基线。
     */
    @PostMapping("/runs/{evalRunId}/baseline")
    public ResponseEntity<ApiResponse<Void>> markAsBaseline(@PathVariable String evalRunId) {
        log.info("标记基线运行: evalRunId={}", evalRunId);
        evalStore.markAsBaseline(evalRunId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // ==================== 结果 ====================

    /**
     * 按场景查询历史评估结果。
     */
    @GetMapping("/results")
    public ResponseEntity<ApiResponse<List<EvalResult>>> getResultsByScenario(
            @RequestParam String scenarioId,
            @RequestParam(defaultValue = "10") int limit) {
        log.info("按场景查询历史结果: scenarioId={}, limit={}", scenarioId, limit);
        List<EvalResult> results = evalStore.findByScenarioId(scenarioId, limit);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ==================== 反馈 ====================

    /**
     * 提交评估结果反馈。
     */
    @PostMapping("/results/{evalId}/feedback")
    public ResponseEntity<ApiResponse<EvalFeedback>> submitFeedback(
            @PathVariable String evalId,
            @RequestParam String scenarioId,
            @RequestBody FeedbackRequest request) {
        log.info("提交反馈: evalId={}, scenarioId={}, type={}", evalId, scenarioId, request.feedbackType());

        var feedback = new EvalFeedback(
                UUID.randomUUID().toString(),
                evalId,
                scenarioId,
                FeedbackType.valueOf(request.feedbackType().toUpperCase()),
                request.comment(),
                request.goldenAnswer(),
                null,
                Instant.now()
        );
        feedbackStore.persist(feedback);
        return ResponseEntity.ok(ApiResponse.ok(feedback));
    }

    /**
     * 查询评估结果的反馈列表。
     */
    @GetMapping("/results/{evalId}/feedback")
    public ResponseEntity<ApiResponse<List<EvalFeedback>>> getFeedback(@PathVariable String evalId) {
        log.info("查询反馈: evalId={}", evalId);
        List<EvalFeedback> feedbacks = feedbackStore.findByEvalId(evalId);
        return ResponseEntity.ok(ApiResponse.ok(feedbacks));
    }

    // ==================== 内部方法 ====================

    /**
     * 根据请求参数解析待评估的场景列表。
     */
    private List<BenchmarkScenario> resolveScenarios(EvalRunRequest request) {
        // 冒烟测试模式
        if (Boolean.TRUE.equals(request.smokeTestOnly())) {
            return scenarioLoader.loadByTags(List.of("smoke"));
        }
        if (request.scenarioIds() != null && !request.scenarioIds().isEmpty()) {
            return request.scenarioIds().stream()
                    .map(scenarioLoader::loadById)
                    .toList();
        }
        if (request.tag() != null && !request.tag().isBlank()) {
            return scenarioLoader.loadByTags(List.of(request.tag()));
        }
        return scenarioLoader.loadAll();
    }
}
