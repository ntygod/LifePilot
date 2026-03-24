package com.lifepilot.embedding.client;

import com.lifepilot.llm.adapter.SpringAiProviderAdapter;

import java.util.List;

/**
 * 基于 Spring AI 适配器的向量服务客户端。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class SpringAiEmbeddingClient implements EmbeddingServiceClient {

    private final SpringAiProviderAdapter adapter;

    public SpringAiEmbeddingClient(SpringAiProviderAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public float[] embed(String text) {
        return adapter.embed(text);
    }

    @Override
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        float[][] results = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                results[i] = null;
                continue;
            }
            results[i] = embed(text);
        }
        return results;
    }
}
