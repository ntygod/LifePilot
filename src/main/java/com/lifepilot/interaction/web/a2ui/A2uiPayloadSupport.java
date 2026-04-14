package com.lifepilot.interaction.web.a2ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * A2UI 载荷工具类，供流式响应、同步响应和历史恢复共用。
 *
 * @author zsg
 * @since 2026-03-11
 */
public final class A2uiPayloadSupport {

    private static final Logger log = LoggerFactory.getLogger(A2uiPayloadSupport.class);

    private A2uiPayloadSupport() {
    }

    @Nullable
    public static A2uiComponentTree deserializeStoredTree(@Nullable String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return normalizeTree(objectMapper.readValue(json, A2uiComponentTree.class));
        } catch (Exception e) {
            log.warn("A2UI 存储载荷反序列化失败: error={}", e.getMessage());
        }
        return null;
    }

    public static A2uiComponentTree normalizeTree(A2uiComponentTree tree) {
        if (tree == null) {
            return new A2uiComponentTree(List.of());
        }
        return tree;
    }
}
