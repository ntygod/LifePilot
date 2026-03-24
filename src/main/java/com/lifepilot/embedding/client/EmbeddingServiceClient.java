package com.lifepilot.embedding.client;

import java.util.List;

/**
 * 向量服务客户端。
 *
 * @author zsg
 * @since 2026-03-24
 */
public interface EmbeddingServiceClient {

    /**
     * 计算单条文本向量。
     *
     * @param text 文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 批量计算文本向量。
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    float[][] embedBatch(List<String> texts);
}
