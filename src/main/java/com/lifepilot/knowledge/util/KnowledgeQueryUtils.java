package com.lifepilot.knowledge.util;

import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库查询工具类 — 提供 SQL 构建、类型解析、JSON 解析等共享方法。
 *
 * <p>统一替代 FtsIndexer / VectorIndexer / Repository 中重复的工具方法。
 *
 * @author zsg
 * @since 2026-04-07
 */
public final class KnowledgeQueryUtils {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryUtils.class);

    private KnowledgeQueryUtils() {}

    /**
     * 解析文档来源类型字符串，无法识别时回退到 {@link DocumentSourceType#FILE}。
     *
     * @param rawValue 原始字符串值
     * @return 解析后的来源类型
     */
    public static DocumentSourceType parseSourceType(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DocumentSourceType.FILE;
        }
        try {
            return DocumentSourceType.valueOf(rawValue);
        } catch (IllegalArgumentException e) {
            log.warn("未知文档来源类型，回退 FILE: value={}", rawValue);
            return DocumentSourceType.FILE;
        }
    }

    /**
     * 构建按知识域范围过滤的 SQL WHERE 子句。
     *
     * @param kbColumn        知识库 ID 列名
     * @param scopes          检索范围列表
     * @return SQL 片段和参数
     */
    public static ScopeSql buildScopeSql(String kbColumn, List<KnowledgeSearchScope> scopes) {
        var sqlParts = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (KnowledgeSearchScope scope : scopes) {
            if (scope == null || scope.knowledgeBaseId() == null || scope.knowledgeBaseId().isBlank()) {
                continue;
            }
            sqlParts.add(kbColumn + " = ?");
            params.add(scope.knowledgeBaseId());
        }
        return new ScopeSql(String.join(" OR ", sqlParts), params);
    }

    /**
     * 简单 JSON 数组解析 — 提取 JSON 字符串数组中的元素。
     *
     * <p>用于解析 heading_hierarchy_json 等简单 JSON 数组列。
     *
     * @param json JSON 数组字符串
     * @return 解析后的字符串列表
     */
    public static List<String> parseJsonList(String json) {
        if (json == null || json.equals("[]")) {
            return List.of();
        }
        var content = json.substring(1, json.length() - 1);
        if (content.isBlank()) {
            return List.of();
        }
        return Arrays.stream(content.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .toList();
    }

    /**
     * 范围过滤 SQL 片段及其参数。
     */
    public record ScopeSql(String sql, List<Object> params) {}
}
