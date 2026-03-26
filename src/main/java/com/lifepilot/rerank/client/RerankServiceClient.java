package com.lifepilot.rerank.client;

import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * 原生精排客户端。
 *
 * @author zsg
 * @since 2026-03-24
 */
public interface RerankServiceClient {

    /**
     * 对候选文本执行精排。
     *
     * @param query 查询
     * @param documents 候选文本
     * @param topK 返回数量
     * @param timeoutOverride 超时覆盖
     * @return 排序结果
     */
    List<RerankScore> rerank(String query, List<String> documents, int topK, @Nullable Duration timeoutOverride);

    /**
     * 原生精排得分项。
     *
     * @param index 候选下标
     * @param score 相关性得分
     */
    record RerankScore(int index, double score) {
    }
}
