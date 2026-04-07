package com.lifepilot.knowledge.eval;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索质量评估器 — 基于 golden test set 计算 Recall@k / MRR / NDCG 指标。
 *
 * <p>接收一组测试用例（查询 + 期望命中的 chunkId 列表），逐条执行混合检索，
 * 将实际检索结果与期望结果对比，计算三项检索质量指标并汇总。</p>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class RetrievalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluator.class);

    private final DocumentRetriever retriever;

    public RetrievalEvaluator(DocumentRetriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 单条测试用例。
     *
     * @param query            查询文本
     * @param expectedChunkIds 期望命中的 chunkId 列表（golden answer）
     * @param knowledgeBaseId  目标知识库 ID
     */
    public record TestCase(String query, List<String> expectedChunkIds, String knowledgeBaseId) {}

    /**
     * 评估指标汇总。
     *
     * @param recallAtK              平均 Recall@k
     * @param mrr                    Mean Reciprocal Rank
     * @param ndcg                   平均 Normalized Discounted Cumulative Gain
     * @param totalCases             测试用例总数
     * @param caseResults            每条用例的详细结果
     */
    public record EvalMetrics(
            double recallAtK,
            double mrr,
            double ndcg,
            int totalCases,
            List<CaseResult> caseResults
    ) {}

    /**
     * 单条用例的评估结果。
     *
     * @param query                  查询文本
     * @param recall                 该用例的 Recall@k
     * @param reciprocalRank         该用例的 Reciprocal Rank
     * @param ndcg                   该用例的 NDCG@k
     * @param expectedCount          期望命中数
     * @param retrievedRelevantCount 实际命中的相关结果数
     * @param retrievedChunkIds      实际检索到的 chunkId 列表
     */
    public record CaseResult(
            String query,
            double recall,
            double reciprocalRank,
            double ndcg,
            int expectedCount,
            int retrievedRelevantCount,
            List<String> retrievedChunkIds
    ) {}

    /**
     * 批量评估检索质量。
     *
     * <p>对每条测试用例执行检索，计算 Recall@k、MRR、NDCG，最后取所有用例的平均值。</p>
     *
     * @param testCases 测试用例列表
     * @param k         Top-K 截断数
     * @return 评估指标汇总
     */
    public EvalMetrics evaluate(List<TestCase> testCases, int k) {
        if (testCases == null || testCases.isEmpty()) {
            return new EvalMetrics(0.0, 0.0, 0.0, 0, List.of());
        }

        int effectiveK = Math.max(k, 1);
        var caseResults = new ArrayList<CaseResult>(testCases.size());
        double totalRecall = 0.0;
        double totalMrr = 0.0;
        double totalNdcg = 0.0;

        for (var testCase : testCases) {
            try {
                // 执行检索
                List<DocumentSearchResult> results = retriever.retrieve(
                        testCase.query(), List.of(testCase.knowledgeBaseId()), effectiveK);

                // 提取检索到的 chunkId 列表
                List<String> retrievedIds = results.stream()
                        .map(DocumentSearchResult::chunkId)
                        .toList();

                // 计算单条用例指标
                double recall = recallAtK(retrievedIds, testCase.expectedChunkIds(), effectiveK);
                double rr = reciprocalRank(retrievedIds, testCase.expectedChunkIds());
                double casNdcg = ndcg(retrievedIds, testCase.expectedChunkIds(), effectiveK);

                long relevantCount = retrievedIds.subList(0, Math.min(effectiveK, retrievedIds.size()))
                        .stream()
                        .filter(testCase.expectedChunkIds()::contains)
                        .count();

                var caseResult = new CaseResult(
                        testCase.query(),
                        recall,
                        rr,
                        casNdcg,
                        testCase.expectedChunkIds().size(),
                        (int) relevantCount,
                        retrievedIds
                );
                caseResults.add(caseResult);

                totalRecall += recall;
                totalMrr += rr;
                totalNdcg += casNdcg;

                log.debug("评估用例完成: query='{}', recall={}, rr={}, ndcg={}, 命中={}/{}",
                        testCase.query(), recall, rr, casNdcg, relevantCount, testCase.expectedChunkIds().size());

            } catch (Exception e) {
                log.warn("评估用例执行失败，跳过: query='{}', error={}", testCase.query(), e.getMessage());
                caseResults.add(new CaseResult(
                        testCase.query(), 0.0, 0.0, 0.0,
                        testCase.expectedChunkIds().size(), 0, List.of()));
            }
        }

        int total = testCases.size();
        double avgRecall = totalRecall / total;
        double avgMrr = totalMrr / total;
        double avgNdcg = totalNdcg / total;

        log.info("检索评估完成: cases={}, avgRecall@{}={}, avgMRR={}, avgNDCG={}",
                total, effectiveK, avgRecall, avgMrr, avgNdcg);

        return new EvalMetrics(avgRecall, avgMrr, avgNdcg, total, List.copyOf(caseResults));
    }

    /**
     * 计算 Recall@k — top-k 结果中召回了多少期望结果。
     */
    static double recallAtK(List<String> retrieved, List<String> expected, int k) {
        var topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        long relevant = topK.stream().filter(expected::contains).count();
        return expected.isEmpty() ? 1.0 : (double) relevant / expected.size();
    }

    /**
     * 计算 Reciprocal Rank — 第一个相关结果的倒数排名。
     */
    static double reciprocalRank(List<String> retrieved, List<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 计算 NDCG@k — 归一化折损累计增益。
     *
     * <p>DCG: 对 top-k 中每个相关结果，累加 1/log2(rank+1)。
     * IDCG: 假设所有相关结果都排在最前面的理想 DCG。
     * NDCG = DCG / IDCG。</p>
     */
    static double ndcg(List<String> retrieved, List<String> expected, int k) {
        var topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        double dcg = 0.0;
        for (int i = 0; i < topK.size(); i++) {
            if (expected.contains(topK.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // log2(rank+1), rank 从 1 开始
            }
        }
        // 理想 DCG: 所有相关结果排在最前面
        double idcg = 0.0;
        for (int i = 0; i < Math.min(expected.size(), k); i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }
}
