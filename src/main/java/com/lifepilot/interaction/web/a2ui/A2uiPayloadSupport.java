package com.lifepilot.interaction.web.a2ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * A2UI payload helpers shared by streaming, sync responses, and history restore.
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
            log.warn("A2UI stored payload deserialize failed: error={}", e.getMessage());
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
                log.warn("Multiple A2UI blocks detected in one response; keeping the first valid block");
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
                log.warn("A2UI payload validation failed: errors={}", validation.errors());
                return null;
            }
            return validation.truncatedTree() != null ? validation.truncatedTree() : normalizedTree;
        } catch (Exception e) {
            log.warn("A2UI payload parse failed: error={}", e.getMessage());
            return null;
        }
    }
}
