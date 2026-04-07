package com.lifepilot.knowledge.eval;

import com.lifepilot.interaction.web.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索评估 REST API。
 *
 * <ul>
 *   <li>POST /api/knowledge/eval/run — 执行评估，返回 Recall@k / MRR / NDCG 指标</li>
 *   <li>GET /api/knowledge/eval/last — 获取最近一次评估结果</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-07
 */
@RestController
@RequestMapping("/api/knowledge/eval")
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(RetrievalEvaluator.class)
public class RetrievalEvalController {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalController.class);

    private final RetrievalEvaluator evaluator;

    /** 最近一次评估结果缓存（volatile 保证跨线程可见性） */
    private volatile RetrievalEvaluator.EvalMetrics lastResult;

    public RetrievalEvalController(RetrievalEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * 评估请求体。
     *
     * @param testCases 测试用例列表
     * @param k         Top-K 截断数（默认 10）
     */
    public record EvalRequest(List<RetrievalEvaluator.TestCase> testCases, int k) {}

    /**
     * 执行检索质量评估。
     *
     * @param request 评估请求（包含测试用例和 k 值）
     * @return 评估指标汇总
     */
    @PostMapping("/run")
    public ApiResponse<RetrievalEvaluator.EvalMetrics> runEval(@RequestBody EvalRequest request) {
        log.info("触发检索评估: cases={}, k={}", request.testCases().size(), request.k());
        var metrics = evaluator.evaluate(request.testCases(), request.k() > 0 ? request.k() : 10);
        lastResult = metrics;
        log.info("检索评估完成: recall@k={}, mrr={}, ndcg={}",
                metrics.recallAtK(), metrics.mrr(), metrics.ndcg());
        return ApiResponse.ok(metrics);
    }

    /**
     * 获取最近一次评估结果。
     *
     * @return 最近一次评估指标，若尚未执行过则返回 404
     */
    @GetMapping("/last")
    public ApiResponse<RetrievalEvaluator.EvalMetrics> getLastResult() {
        if (lastResult == null) {
            return ApiResponse.error(404, "尚未执行过评估");
        }
        return ApiResponse.ok(lastResult);
    }
}
