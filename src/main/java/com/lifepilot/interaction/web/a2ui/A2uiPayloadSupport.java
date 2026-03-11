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

    public record ParsedA2uiContent(String visibleText, @Nullable A2uiComponentTree tree) {
    }

    public static ParsedA2uiContent extractContent(@Nullable String rawContent,
                                                   ObjectMapper objectMapper,
                                                   int maxComponentsPerTree) {
        if (rawContent == null || rawContent.isBlank()) {
            return new ParsedA2uiContent(rawContent != null ? rawContent : "", null);
        }

        var parser = new StreamingA2uiParser();
        var visible = new StringBuilder();
        A2uiComponentTree latestTree = null;

        for (var segment : parser.feed(rawContent)) {
            latestTree = appendSegment(segment, visible, latestTree, objectMapper, maxComponentsPerTree);
        }
        for (var segment : parser.flush()) {
            latestTree = appendSegment(segment, visible, latestTree, objectMapper, maxComponentsPerTree);
        }

        return new ParsedA2uiContent(visible.toString(), latestTree);
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

    @Nullable
    private static A2uiComponentTree appendSegment(StreamingA2uiParser.Segment segment,
                                                   StringBuilder visible,
                                                   @Nullable A2uiComponentTree latestTree,
                                                   ObjectMapper objectMapper,
                                                   int maxComponentsPerTree) {
        if (segment instanceof StreamingA2uiParser.Segment.TextSegment(var text)) {
            visible.append(text);
            return latestTree;
        }
        if (segment instanceof StreamingA2uiParser.Segment.A2uiSegment(var json)) {
            if (latestTree != null) {
                log.warn("单次响应中检测到多个 A2UI 块，保留第一个有效块");
                return latestTree;
            }
            var parsedTree = parseTree(json, objectMapper, maxComponentsPerTree);
            return parsedTree != null ? parsedTree : latestTree;
        }
        return latestTree;
    }

    @Nullable
    private static A2uiComponentTree parseTree(String json,
                                               ObjectMapper objectMapper,
                                               int maxComponentsPerTree) {
        try {
            var normalizedTree = normalizeTree(objectMapper.readValue(json, A2uiComponentTree.class));
            var validation = A2uiComponentValidator.validate(normalizedTree, maxComponentsPerTree);
            if (!validation.valid()) {
                log.warn("A2UI 载荷校验失败: errors={}", validation.errors());
                return null;
            }
            return validation.truncatedTree() != null ? validation.truncatedTree() : normalizedTree;
        } catch (Exception e) {
            log.warn("A2UI 载荷解析失败: error={}", e.getMessage());
            return null;
        }
    }
}
