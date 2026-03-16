package com.lifepilot.eval.web;

import com.lifepilot.eval.engine.EvalEngine;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评估模块 REST 控制器。
 *
 * <p>暴露评估场景查询、评估运行触发、结果查询、报告获取等 HTTP 端点，
 * 复用已有的 {@link ScenarioLoader}、{@link EvalEngine}、
 * {@link EvalStore}、{@link EvalReport}。</p>
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

    public EvalController(ScenarioLoader scenarioLoader,
                          EvalEngine evalEngine,
                          EvalStore evalStore,
                          EvalReport evalReport) {
        this.scenarioLoader = scenarioLoader;
        this.evalEngine = evalEngine;
        this.evalStore = evalStore;
        this.evalReport = evalReport;
    }

    /**
     * 列出所有场景，可选按标签过滤。
     *
     * @param tag 标签过滤（可选）
     * @return 场景列表
     */
    @GetMapping("/scenarios")
    public ResponseEntity<List<BenchmarkScenario>> listScenarios(
            @RequestParam(required = false) String tag) {
        log.info("查询评估场景: tag={}", tag);
        List<BenchmarkScenario> scenarios = (tag != null && !tag.isBlank())
                ? scenarioLoader.loadByTags(List.of(tag))
                : scenarioLoader.loadAll();
        return ResponseEntity.ok(scenarios);
    }

    /**
     * 获取单个场景。
     *
     * @param id 场景 ID
     * @return 场景定义
     */
    @GetMapping("/scenarios/{id}")
    public ResponseEntity<BenchmarkScenario> getScenario(@PathVariable String id) {
        log.info("查询评估场景详情: id={}", id);
        BenchmarkScenario scenario = scenarioLoader.loadById(id);
        return ResponseEntity.ok(scenario);
    }

    /**
     * 触发批量评估运行。
     *
     * <p>根据请求参数决定评估范围：指定 scenarioIds 时按 ID 过滤，
     * 指定 tag 时按标签过滤，两者都为空时执行全部场景。</p>
     *
     * @param request 评估运行请求
     * @return 报告汇总
     */
    @PostMapping("/runs")
    public ResponseEntity<ReportSummary> triggerRun(@RequestBody EvalRunRequest request) {
        log.info("触发评估运行: scenarioIds={}, tag={}", request.scenarioIds(), request.tag());
        List<BenchmarkScenario> scenarios = resolveScenarios(request);
        ReportSummary summary = evalEngine.evaluateBatch(scenarios);
        return ResponseEntity.ok(summary);
    }

    /**
     * 查询指定运行的评估结果列表。
     *
     * @param evalRunId 评估运行 ID
     * @return 评估结果列表
     */
    @GetMapping("/runs/{evalRunId}/results")
    public ResponseEntity<List<EvalResult>> getRunResults(@PathVariable String evalRunId) {
        log.info("查询运行结果: evalRunId={}", evalRunId);
        List<EvalResult> results = evalStore.findByRunId(evalRunId);
        return ResponseEntity.ok(results);
    }

    /**
     * 获取指定运行的报告汇总。
     *
     * @param evalRunId 评估运行 ID
     * @return 报告汇总
     */
    @GetMapping("/runs/{evalRunId}/report")
    public ResponseEntity<ReportSummary> getRunReport(@PathVariable String evalRunId) {
        log.info("查询运行报告: evalRunId={}", evalRunId);
        List<EvalResult> results = evalStore.findByRunId(evalRunId);
        ReportSummary summary = evalReport.generateSummary(results, evalRunId);
        return ResponseEntity.ok(summary);
    }

    /**
     * 按场景查询历史评估结果。
     *
     * @param scenarioId 场景 ID
     * @param limit      最大返回条数（默认 10）
     * @return 评估结果列表
     */
    @GetMapping("/results")
    public ResponseEntity<List<EvalResult>> getResultsByScenario(
            @RequestParam String scenarioId,
            @RequestParam(defaultValue = "10") int limit) {
        log.info("按场景查询历史结果: scenarioId={}, limit={}", scenarioId, limit);
        List<EvalResult> results = evalStore.findByScenarioId(scenarioId, limit);
        return ResponseEntity.ok(results);
    }

    /**
     * 根据请求参数解析待评估的场景列表。
     */
    private List<BenchmarkScenario> resolveScenarios(EvalRunRequest request) {
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
