package com.lifepilot.knowledge.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Datastore 文档向量投影器。
 *
 * <p>使用通用规则将结构化 JSON 投影为适合检索的文本。
 * 除了默认启发式规则外，还支持通过 projection_config_json
 * 声明字段路径和 section 渲染策略，提升复杂嵌套数据的可检索性。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
public class DatastoreDocumentProjector {

    private static final Logger log = LoggerFactory.getLogger(DatastoreDocumentProjector.class);

    private static final int DEFAULT_RAW_JSON_MAX_LENGTH = 1500;
    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int DEFAULT_MAX_ARRAY_ITEMS = 6;
    private static final int DEFAULT_MAX_OBJECT_FIELDS = 8;
    private static final int DEFAULT_MAX_SCALAR_VALUES = 40;
    private static final int DEFAULT_MAX_SECTION_CHARS = 1500;

    private final ObjectMapper objectMapper;

    public DatastoreDocumentProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将结构化 datastore 文档投影为知识库可检索文本。
     */
    public ProjectedDatastoreDocument project(Collection collection, Document document) {
        ProjectionOptions options = parseOptions(collection.projectionConfigJson());
        JsonNode root = readJson(document.dataJson());

        Set<String> referencedRootFields = collectReferencedRootFields(options);
        Set<String> autoSectionRootFields = determineAutoSectionRootFields(root, options, referencedRootFields);

        var sections = new ArrayList<String>();
        sections.add("Collection: " + collection.name());
        sections.add("Document ID: " + document.id());
        if (document.recordedAt() != null && !document.recordedAt().isBlank()) {
            sections.add("Recorded At: " + document.recordedAt());
        }

        String title = options.titlePaths().isEmpty()
                ? inferTitle(root, options)
                : firstNonBlank(resolveValues(root, options.titlePaths(), options), options);
        if (title != null) {
            sections.add("Title: " + title);
        }

        if (!options.bodyPaths().isEmpty()) {
            addIfPresent(sections, renderResolvedSection(
                    "Body",
                    resolveValues(root, options.bodyPaths(), options),
                    SectionMode.TEXT,
                    options,
                    true,
                    options.maxArrayItems(),
                    options.maxSectionChars()
            ));
        }
        if (!options.tagPaths().isEmpty()) {
            addIfPresent(sections, renderResolvedSection(
                    "Tags",
                    resolveValues(root, options.tagPaths(), options),
                    SectionMode.LINES,
                    options,
                    true,
                    options.maxArrayItems(),
                    options.maxSectionChars()
            ));
        }
        if (!options.timePaths().isEmpty()) {
            addIfPresent(sections, renderResolvedSection(
                    "Timeline",
                    resolveValues(root, options.timePaths(), options),
                    SectionMode.LINES,
                    options,
                    true,
                    options.maxArrayItems(),
                    options.maxSectionChars()
            ));
        }

        var scalarLines = options.scalarPaths().isEmpty()
                ? collectRemainingScalarLines(root, options, referencedRootFields, autoSectionRootFields)
                : collectExplicitScalarLines(root, options);
        if (!scalarLines.isEmpty()) {
            sections.add("Fields:\n" + String.join("\n", scalarLines));
        }

        for (ProjectionSection section : options.sections()) {
            addIfPresent(sections, renderConfiguredSection(root, section, options));
        }
        for (String rootField : autoSectionRootFields) {
            JsonNode node = root.get(rootField);
            if (node != null && !node.isNull()) {
                addIfPresent(sections, renderAutoSection(prettyLabel(rootField), node, rootField, options));
            }
        }
        if (options.includeRawJson()) {
            sections.add("Raw JSON:\n" + truncate(document.dataJson(), options.rawJsonMaxLength()));
        }

        var sourceRef = new LinkedHashMap<String, Object>();
        sourceRef.put("collectionId", collection.id());
        sourceRef.put("collectionName", collection.name());
        sourceRef.put("documentId", document.id());
        if (document.recordedAt() != null) {
            sourceRef.put("recordedAt", document.recordedAt());
        }

        String fileName = (title != null && !title.isBlank())
                ? "%s - %s".formatted(collection.name(), title)
                : "%s - %s".formatted(collection.name(), document.id());
        String filePath = "datastore://" + collection.id() + "/" + document.id();

        return new ProjectedDatastoreDocument(
                fileName,
                filePath,
                String.join("\n\n", sections.stream()
                        .filter(text -> text != null && !text.isBlank())
                        .map(String::strip)
                        .toList()),
                Map.copyOf(sourceRef)
        );
    }

    private ProjectionOptions parseOptions(String projectionConfigJson) {
        if (projectionConfigJson == null || projectionConfigJson.isBlank() || "{}".equals(projectionConfigJson)) {
            return ProjectionOptions.defaults();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(projectionConfigJson, new TypeReference<>() {});
            return new ProjectionOptions(
                    projectionPaths(raw, "titlePaths", List.of()),
                    projectionPaths(raw, "bodyPaths", List.of()),
                    projectionPaths(raw, "tagPaths", List.of()),
                    projectionPaths(raw, "timePaths", List.of()),
                    projectionPaths(raw, "scalarPaths", List.of()),
                    stringList(raw.get("excludePaths"), List.of()),
                    parseSections(raw.get("sections")),
                    booleanValue(raw.get("includeRawJson"), true),
                    intValue(raw.get("rawJsonMaxLength"), DEFAULT_RAW_JSON_MAX_LENGTH),
                    intValue(raw.get("maxDepth"), DEFAULT_MAX_DEPTH),
                    intValue(raw.get("maxArrayItems"), DEFAULT_MAX_ARRAY_ITEMS),
                    intValue(raw.get("maxObjectFields"), DEFAULT_MAX_OBJECT_FIELDS),
                    intValue(raw.get("maxScalarValues"), DEFAULT_MAX_SCALAR_VALUES),
                    intValue(raw.get("maxSectionChars"), DEFAULT_MAX_SECTION_CHARS)
            );
        } catch (Exception e) {
            log.warn("投影配置解析失败，回退默认配置: collectionProjection={}, error={}",
                    projectionConfigJson, e.getMessage());
            return ProjectionOptions.defaults();
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Datastore 文档 JSON 非法: " + e.getMessage(), e);
        }
    }

    private List<String> collectExplicitScalarLines(JsonNode root, ProjectionOptions options) {
        var lines = new ArrayList<String>();
        for (ResolvedValue value : resolveValues(root, options.scalarPaths(), options)) {
            for (String line : renderKeyValueLines(value.node(), value.path(), 0, options, true)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines.stream().distinct().limit(options.maxScalarValues()).toList();
    }

    private List<String> collectRemainingScalarLines(JsonNode root,
                                                     ProjectionOptions options,
                                                     Set<String> referencedRootFields,
                                                     Set<String> autoSectionRootFields) {
        var lines = new LinkedHashSet<String>();
        var excludedRoots = new LinkedHashSet<String>();
        excludedRoots.addAll(referencedRootFields);
        excludedRoots.addAll(autoSectionRootFields);
        collectRemainingScalarLines(root, "", 0, options, excludedRoots, lines);
        return lines.stream().limit(options.maxScalarValues()).toList();
    }

    private void collectRemainingScalarLines(JsonNode node,
                                             String currentPath,
                                             int depth,
                                             ProjectionOptions options,
                                             Set<String> excludedRootFields,
                                             Set<String> lines) {
        if (node == null || node.isNull() || depth > options.maxDepth()) {
            return;
        }
        if (!currentPath.isBlank()) {
            String rootField = rootField(currentPath);
            if (excludedRootFields.contains(rootField) || isExcluded(currentPath, options.excludePaths())) {
                return;
            }
        }

        if (node.isValueNode()) {
            if (!currentPath.isBlank()) {
                lines.add(formatPathLabel(currentPath) + ": " + sanitizeText(node.asText(), options.maxSectionChars()));
            }
            return;
        }
        if (allScalar(node)) {
            if (!currentPath.isBlank()) {
                lines.add(formatPathLabel(currentPath) + ": " + joinScalarArray(node, options));
            }
            return;
        }
        if (node.isArray()) {
            int count = Math.min(node.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                collectRemainingScalarLines(
                        node.get(i),
                        appendIndex(currentPath, i),
                        depth + 1,
                        options,
                        excludedRootFields,
                        lines
                );
            }
            return;
        }
        node.fields().forEachRemaining(entry -> collectRemainingScalarLines(
                entry.getValue(),
                appendPath(currentPath, entry.getKey()),
                depth + 1,
                options,
                excludedRootFields,
                lines
        ));
    }

    private Set<String> collectReferencedRootFields(ProjectionOptions options) {
        var rootFields = new LinkedHashSet<String>();
        collectRootFields(rootFields, options.titlePaths());
        collectRootFields(rootFields, options.bodyPaths());
        collectRootFields(rootFields, options.tagPaths());
        collectRootFields(rootFields, options.timePaths());
        collectRootFields(rootFields, options.scalarPaths());
        collectRootFields(rootFields, options.excludePaths());
        options.sections().forEach(section -> collectRootFields(rootFields, section.paths()));
        return rootFields;
    }

    private void collectRootFields(Set<String> target, List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            String rootField = rootField(normalizePathSpec(path));
            if (rootField != null && !rootField.isBlank()) {
                target.add(rootField);
            }
        }
    }

    private Set<String> determineAutoSectionRootFields(JsonNode root,
                                                       ProjectionOptions options,
                                                       Set<String> referencedRootFields) {
        if (root == null || !root.isObject()) {
            return Set.of();
        }
        var rootFields = new LinkedHashSet<String>();
        root.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode value = entry.getValue();
            if (referencedRootFields.contains(fieldName) || isExcluded(fieldName, options.excludePaths())) {
                return;
            }
            if (value != null && !value.isNull() && shouldRenderAutoSection(value)) {
                rootFields.add(fieldName);
            }
        });
        return rootFields;
    }

    private boolean shouldRenderAutoSection(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return !node.isEmpty() && !allScalar(node);
        }
        return node.isObject();
    }

    private String renderConfiguredSection(JsonNode root, ProjectionSection section, ProjectionOptions options) {
        var values = resolveValues(root, section.paths(), options);
        return renderResolvedSection(
                section.label(),
                values,
                section.mode(),
                options,
                section.includeFieldName(),
                section.maxItems(),
                section.maxChars()
        );
    }

    private String renderAutoSection(String label, JsonNode node, String basePath, ProjectionOptions options) {
        List<String> lines = renderStructuredLines(node, basePath, options, 0);
        if (lines.isEmpty()) {
            return null;
        }
        return label + ":\n" + truncate(String.join("\n", lines), options.maxSectionChars());
    }

    private String renderResolvedSection(String label,
                                         List<ResolvedValue> values,
                                         SectionMode mode,
                                         ProjectionOptions options,
                                         boolean includeFieldName,
                                         int maxItems,
                                         int maxChars) {
        if (values.isEmpty()) {
            return null;
        }
        String body = switch (mode) {
            case TEXT -> renderTextSection(values, options, includeFieldName, maxChars);
            case LINES -> renderLineSection(values, options, includeFieldName, maxItems, maxChars);
            case OBJECT_SUMMARY -> renderObjectSummarySection(values, options, includeFieldName, maxItems, maxChars);
            case KEY_VALUE -> renderKeyValueSection(values, options, includeFieldName, maxItems, maxChars);
            case JSON -> renderJsonSection(values, includeFieldName, maxChars);
        };
        if (body == null || body.isBlank()) {
            return null;
        }
        return label + ":\n" + body;
    }

    private String renderTextSection(List<ResolvedValue> values,
                                     ProjectionOptions options,
                                     boolean includeFieldName,
                                     int maxChars) {
        var parts = new ArrayList<String>();
        for (ResolvedValue value : values) {
            String text = renderNodeAsText(value.node(), options);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (includeFieldName && values.size() > 1) {
                parts.add(formatPathLabel(value.path()) + ":\n" + text);
            } else {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? null : truncate(String.join("\n\n", parts), maxChars);
    }

    private String renderLineSection(List<ResolvedValue> values,
                                     ProjectionOptions options,
                                     boolean includeFieldName,
                                     int maxItems,
                                     int maxChars) {
        var lines = new ArrayList<String>();
        for (ResolvedValue value : values) {
            if (lines.size() >= maxItems) {
                break;
            }
            if (value.node().isValueNode() || allScalar(value.node())) {
                String rendered = renderSimpleValue(value.node(), options);
                if (rendered != null && !rendered.isBlank()) {
                    lines.add((includeFieldName ? formatPathLabel(value.path()) + ": " : "") + rendered);
                }
                continue;
            }
            for (String line : renderStructuredLines(value.node(), value.path(), options, 0)) {
                if (lines.size() >= maxItems) {
                    break;
                }
                lines.add(line);
            }
        }
        return lines.isEmpty() ? null : truncate(String.join("\n", lines), maxChars);
    }

    private String renderObjectSummarySection(List<ResolvedValue> values,
                                              ProjectionOptions options,
                                              boolean includeFieldName,
                                              int maxItems,
                                              int maxChars) {
        var lines = new ArrayList<String>();
        for (ResolvedValue value : values) {
            if (lines.size() >= maxItems) {
                break;
            }
            JsonNode node = value.node();
            if (node.isArray()) {
                int count = Math.min(node.size(), maxItems - lines.size());
                for (int i = 0; i < count; i++) {
                    String summary = summarizeNode(node.get(i), appendIndex(value.path(), i), options);
                    if (summary != null && !summary.isBlank()) {
                        lines.add("- " + summary);
                    }
                }
            } else {
                String summary = summarizeNode(node, value.path(), options);
                if (summary != null && !summary.isBlank()) {
                    lines.add(includeFieldName ? formatPathLabel(value.path()) + ": " + summary : "- " + summary);
                }
            }
        }
        return lines.isEmpty() ? null : truncate(String.join("\n", lines), maxChars);
    }

    private String renderKeyValueSection(List<ResolvedValue> values,
                                         ProjectionOptions options,
                                         boolean includeFieldName,
                                         int maxItems,
                                         int maxChars) {
        var lines = new ArrayList<String>();
        for (ResolvedValue value : values) {
            if (lines.size() >= maxItems) {
                break;
            }
            for (String line : renderKeyValueLines(value.node(), value.path(), 0, options, includeFieldName)) {
                if (lines.size() >= maxItems) {
                    break;
                }
                lines.add(line);
            }
        }
        return lines.isEmpty() ? null : truncate(String.join("\n", lines), maxChars);
    }

    private String renderJsonSection(List<ResolvedValue> values, boolean includeFieldName, int maxChars) {
        var parts = new ArrayList<String>();
        for (ResolvedValue value : values) {
            String json = serializeNode(value.node());
            if (json == null || json.isBlank()) {
                continue;
            }
            if (includeFieldName && values.size() > 1) {
                parts.add(formatPathLabel(value.path()) + ":\n" + truncate(json, maxChars));
            } else {
                parts.add(truncate(json, maxChars));
            }
        }
        return parts.isEmpty() ? null : truncate(String.join("\n\n", parts), maxChars);
    }

    private List<String> renderStructuredLines(JsonNode node,
                                               String basePath,
                                               ProjectionOptions options,
                                               int depth) {
        if (node == null || node.isNull() || depth > options.maxDepth()) {
            return List.of();
        }
        if (node.isValueNode() || allScalar(node)) {
            String text = renderSimpleValue(node, options);
            return text == null || text.isBlank()
                    ? List.of()
                    : List.of(formatPathLabel(basePath) + ": " + text);
        }
        if (node.isArray()) {
            var lines = new ArrayList<String>();
            int count = Math.min(node.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                JsonNode item = node.get(i);
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isValueNode() || allScalar(item)) {
                    String rendered = renderSimpleValue(item, options);
                    if (rendered != null && !rendered.isBlank()) {
                        lines.add("- " + rendered);
                    }
                } else {
                    String summary = summarizeNode(item, appendIndex(basePath, i), options);
                    if (summary != null && !summary.isBlank()) {
                        lines.add("- " + summary);
                    }
                }
            }
            if (node.size() > count) {
                lines.add("- ... +" + (node.size() - count) + " more");
            }
            return lines;
        }

        var lines = new ArrayList<String>();
        node.fields().forEachRemaining(entry -> {
            if (lines.size() >= options.maxObjectFields()) {
                return;
            }
            String childPath = appendPath(basePath, entry.getKey());
            if (isExcluded(childPath, options.excludePaths())) {
                return;
            }
            JsonNode child = entry.getValue();
            if (child == null || child.isNull()) {
                return;
            }
            if (child.isValueNode() || allScalar(child)) {
                String rendered = renderSimpleValue(child, options);
                if (rendered != null && !rendered.isBlank()) {
                    lines.add(entry.getKey() + ": " + rendered);
                }
            } else {
                String summary = summarizeNode(child, childPath, options);
                if (summary != null && !summary.isBlank()) {
                    lines.add(entry.getKey() + ": " + summary);
                }
            }
        });
        return lines;
    }

    private List<String> renderKeyValueLines(JsonNode node,
                                             String currentPath,
                                             int depth,
                                             ProjectionOptions options,
                                             boolean includeFieldName) {
        var lines = new ArrayList<String>();
        collectKeyValueLines(node, currentPath, depth, options, includeFieldName, lines);
        return lines.stream().distinct().limit(options.maxScalarValues()).toList();
    }

    private void collectKeyValueLines(JsonNode node,
                                      String currentPath,
                                      int depth,
                                      ProjectionOptions options,
                                      boolean includeFieldName,
                                      List<String> lines) {
        if (node == null || node.isNull() || depth > options.maxDepth() || lines.size() >= options.maxScalarValues()) {
            return;
        }
        if (node.isValueNode() || allScalar(node)) {
            String text = renderSimpleValue(node, options);
            if (text != null && !text.isBlank()) {
                lines.add((includeFieldName ? formatPathLabel(currentPath) + ": " : "") + text);
            }
            return;
        }
        if (node.isArray()) {
            int count = Math.min(node.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                collectKeyValueLines(
                        node.get(i),
                        appendIndex(currentPath, i),
                        depth + 1,
                        options,
                        includeFieldName,
                        lines
                );
            }
            return;
        }
        node.fields().forEachRemaining(entry -> collectKeyValueLines(
                entry.getValue(),
                appendPath(currentPath, entry.getKey()),
                depth + 1,
                options,
                includeFieldName,
                lines
        ));
    }

    private List<ResolvedValue> resolveValues(JsonNode root, List<String> pathSpecs, ProjectionOptions options) {
        if (pathSpecs == null || pathSpecs.isEmpty()) {
            return List.of();
        }
        var values = new ArrayList<ResolvedValue>();
        var seenPaths = new LinkedHashSet<String>();
        for (String rawSpec : pathSpecs) {
            String pathSpec = normalizePathSpec(rawSpec);
            if (pathSpec.isBlank()) {
                continue;
            }
            if (isExplicitPath(pathSpec)) {
                resolveExplicitPath(root, parsePath(pathSpec), 0, "", 0, options, values, seenPaths);
            } else {
                resolveFieldName(root, pathSpec, "", 0, options, values, seenPaths);
            }
        }
        return values.stream()
                .filter(value -> !isExcluded(value.path(), options.excludePaths()))
                .toList();
    }

    private void resolveExplicitPath(JsonNode current,
                                     List<PathSegment> segments,
                                     int index,
                                     String currentPath,
                                     int depth,
                                     ProjectionOptions options,
                                     List<ResolvedValue> values,
                                     Set<String> seenPaths) {
        if (current == null || current.isNull() || depth > options.maxDepth()) {
            return;
        }
        if (index >= segments.size()) {
            if (!currentPath.isBlank() && seenPaths.add(currentPath)) {
                values.add(new ResolvedValue(currentPath, current));
            }
            return;
        }

        PathSegment segment = segments.get(index);
        JsonNode next = segment.name().isBlank()
                ? current
                : current.isObject() ? current.get(segment.name()) : null;
        if (next == null || next.isNull()) {
            return;
        }

        if (segment.arrayWildcard()) {
            if (!next.isArray()) {
                return;
            }
            int count = Math.min(next.size(), options.maxArrayItems());
            String arrayBasePath = segment.name().isBlank() ? currentPath : appendPath(currentPath, segment.name());
            for (int i = 0; i < count; i++) {
                resolveExplicitPath(
                        next.get(i),
                        segments,
                        index + 1,
                        appendIndex(arrayBasePath, i),
                        depth + 1,
                        options,
                        values,
                        seenPaths
                );
            }
            return;
        }

        String nextPath = segment.name().isBlank() ? currentPath : appendPath(currentPath, segment.name());
        resolveExplicitPath(next, segments, index + 1, nextPath, depth + 1, options, values, seenPaths);
    }

    private void resolveFieldName(JsonNode current,
                                  String fieldName,
                                  String currentPath,
                                  int depth,
                                  ProjectionOptions options,
                                  List<ResolvedValue> values,
                                  Set<String> seenPaths) {
        if (current == null || current.isNull() || depth > options.maxDepth()) {
            return;
        }
        if (current.isObject()) {
            current.fields().forEachRemaining(entry -> {
                String childPath = appendPath(currentPath, entry.getKey());
                if (entry.getKey().equals(fieldName) && seenPaths.add(childPath)) {
                    values.add(new ResolvedValue(childPath, entry.getValue()));
                }
                resolveFieldName(entry.getValue(), fieldName, childPath, depth + 1, options, values, seenPaths);
            });
            return;
        }
        if (current.isArray()) {
            int count = Math.min(current.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                resolveFieldName(current.get(i), fieldName, appendIndex(currentPath, i), depth + 1,
                        options, values, seenPaths);
            }
        }
    }

    private String firstNonBlank(List<ResolvedValue> values, ProjectionOptions options) {
        for (ResolvedValue value : values) {
            String rendered = renderSimpleValue(value.node(), options);
            if (rendered != null && !rendered.isBlank()) {
                return rendered.strip();
            }
            String summary = summarizeNode(value.node(), value.path(), options);
            if (summary != null && !summary.isBlank()) {
                return summary.strip();
            }
        }
        return null;
    }

    private String inferTitle(JsonNode root, ProjectionOptions options) {
        var candidates = new ArrayList<String>();
        collectTitleCandidates(root, 0, options, candidates);
        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            int score = titleScore(candidate);
            if (score > bestScore) {
                bestCandidate = candidate;
                bestScore = score;
            }
        }
        return bestCandidate;
    }

    private void collectTitleCandidates(JsonNode node,
                                        int depth,
                                        ProjectionOptions options,
                                        List<String> candidates) {
        if (node == null || node.isNull() || depth > Math.min(options.maxDepth(), 2) || candidates.size() >= 20) {
            return;
        }
        if (node.isValueNode()) {
            String text = sanitizeText(node.asText(), 120);
            if (text != null && !text.isBlank()) {
                candidates.add(text);
            }
            return;
        }
        if (allScalar(node)) {
            String text = joinScalarArray(node, options);
            if (text != null && !text.isBlank()) {
                candidates.add(text);
            }
            return;
        }
        if (node.isArray()) {
            int count = Math.min(node.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                collectTitleCandidates(node.get(i), depth + 1, options, candidates);
            }
            return;
        }
        node.fields().forEachRemaining(entry -> collectTitleCandidates(entry.getValue(), depth + 1, options, candidates));
    }

    private int titleScore(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        int length = candidate.length();
        if (length >= 4 && length <= 40) {
            score += 40;
        } else if (length <= 80) {
            score += 20;
        } else {
            score -= Math.min(60, length / 4);
        }
        if (candidate.indexOf('\n') >= 0) {
            score -= 30;
        }
        if (candidate.contains("，") || candidate.contains(",") || candidate.contains("。")) {
            score -= 10;
        }
        if (candidate.chars().anyMatch(Character::isUpperCase)) {
            score += 5;
        }
        if (candidate.chars().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
            score += 10;
        }
        if (candidate.contains(" ")) {
            score += 6;
        }
        if (candidate.chars().allMatch(ch -> Character.isLowerCase(ch) || Character.isDigit(ch) || ch == '-' || ch == '_')) {
            score -= 12;
        }
        return score;
    }

    private String renderNodeAsText(JsonNode node, ProjectionOptions options) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            return sanitizeText(node.asText(), options.maxSectionChars());
        }
        if (allScalar(node)) {
            return joinScalarArray(node, options);
        }
        if (node.isArray()) {
            var parts = new ArrayList<String>();
            int count = Math.min(node.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                String text = renderNodeAsText(node.get(i), options);
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            }
            return truncate(String.join("\n\n", parts), options.maxSectionChars());
        }
        return summarizeNode(node, "", options);
    }

    private String summarizeNode(JsonNode node, ProjectionOptions options) {
        return summarizeNode(node, "", options);
    }

    private String summarizeNode(JsonNode node, String basePath, ProjectionOptions options) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode() || allScalar(node)) {
            return renderSimpleValue(node, options);
        }
        var parts = new ArrayList<String>();
        collectSummaryParts(node, basePath, 0, options, parts);
        if (parts.isEmpty()) {
            return truncate(serializeNode(node), options.maxSectionChars());
        }
        return truncate(String.join("; ", parts), options.maxSectionChars());
    }

    private void collectSummaryParts(JsonNode node,
                                     String currentPath,
                                     int depth,
                                     ProjectionOptions options,
                                     List<String> parts) {
        if (node == null || node.isNull() || depth > options.maxDepth() || parts.size() >= options.maxObjectFields()) {
            return;
        }
        if (node.isValueNode() || allScalar(node)) {
            String rendered = renderSimpleValue(node, options);
            if (rendered != null && !rendered.isBlank()) {
                String label = currentPath.isBlank() ? "value" : shortPathLabel(currentPath);
                parts.add(label + ": " + rendered);
            }
            return;
        }
        if (node.isArray()) {
            int count = Math.min(node.size(), options.maxArrayItems());
            for (int i = 0; i < count; i++) {
                JsonNode item = node.get(i);
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isValueNode() || allScalar(item)) {
                    String rendered = renderSimpleValue(item, options);
                    if (rendered != null && !rendered.isBlank()) {
                        parts.add(rendered);
                    }
                } else {
                    String summary = summarizeNode(item, appendIndex(currentPath, i), options);
                    if (summary != null && !summary.isBlank()) {
                        parts.add(summary);
                    }
                }
                if (parts.size() >= options.maxObjectFields()) {
                    return;
                }
            }
            return;
        }
        node.fields().forEachRemaining(entry -> {
            if (parts.size() >= options.maxObjectFields()) {
                return;
            }
            String childPath = appendPath(currentPath, entry.getKey());
            if (isExcluded(childPath, options.excludePaths())) {
                return;
            }
            collectSummaryParts(entry.getValue(), childPath, depth + 1, options, parts);
        });
    }

    private String renderSimpleValue(JsonNode node, ProjectionOptions options) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            return sanitizeText(node.asText(), options.maxSectionChars());
        }
        if (allScalar(node)) {
            return joinScalarArray(node, options);
        }
        return sanitizeText(jsonNodeToText(node), options.maxSectionChars());
    }

    private String jsonNodeToText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        if (node.isArray()) {
            var items = new ArrayList<String>();
            node.forEach(item -> {
                String text = jsonNodeToText(item);
                if (text != null && !text.isBlank()) {
                    items.add(text);
                }
            });
            return String.join(", ", items);
        }
        return serializeNode(node);
    }

    private String joinScalarArray(JsonNode node, ProjectionOptions options) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            return sanitizeText(node.asText(), options.maxSectionChars());
        }
        var items = new ArrayList<String>();
        int count = Math.min(node.size(), options.maxArrayItems());
        for (int i = 0; i < count; i++) {
            JsonNode item = node.get(i);
            if (item == null || item.isNull()) {
                continue;
            }
            items.add(sanitizeText(item.asText(), options.maxSectionChars()));
        }
        if (node.size() > count) {
            items.add("... +" + (node.size() - count) + " more");
        }
        return String.join(", ", items);
    }

    private boolean allScalar(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (!item.isValueNode()) {
                return false;
            }
        }
        return true;
    }

    private String serializeNode(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private List<String> projectionPaths(Map<String, Object> raw,
                                         String key,
                                         List<String> defaultValue) {
        if (raw.containsKey(key)) {
            return stringList(raw.get(key), defaultValue);
        }
        return defaultValue;
    }

    private List<ProjectionSection> parseSections(Object rawValue) {
        if (!(rawValue instanceof List<?> rawSections)) {
            return List.of();
        }
        var sections = new ArrayList<ProjectionSection>();
        for (Object rawSection : rawSections) {
            if (!(rawSection instanceof Map<?, ?> rawMap)) {
                continue;
            }
            String label = stringValue(rawMap.get("label")).orElse(null);
            List<String> paths = stringList(rawMap.get("paths"), List.of());
            if (label == null || label.isBlank() || paths.isEmpty()) {
                continue;
            }
            sections.add(new ProjectionSection(
                    label.strip(),
                    paths,
                    SectionMode.parse(stringValue(rawMap.get("mode")).orElse(null)),
                    intValue(rawMap.get("maxItems"), DEFAULT_MAX_ARRAY_ITEMS),
                    intValue(rawMap.get("maxChars"), DEFAULT_MAX_SECTION_CHARS),
                    booleanValue(rawMap.get("includeFieldName"), true)
            ));
        }
        return sections;
    }

    private Optional<String> stringValue(Object rawValue) {
        if (rawValue instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }
        return Optional.empty();
    }

    private List<String> stringList(Object rawValue, List<String> defaultValue) {
        if (!(rawValue instanceof List<?> values)) {
            return defaultValue;
        }
        var result = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        return result.isEmpty() ? defaultValue : result;
    }

    private boolean booleanValue(Object rawValue, boolean defaultValue) {
        if (rawValue instanceof Boolean value) {
            return value;
        }
        return defaultValue;
    }

    private int intValue(Object rawValue, int defaultValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private String normalizePathSpec(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String normalized = rawPath.strip();
        if (normalized.startsWith("$.")) {
            return normalized.substring(2);
        }
        if ("$".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private boolean isExplicitPath(String pathSpec) {
        return pathSpec.contains(".") || pathSpec.contains("[") || pathSpec.contains("$");
    }

    private List<PathSegment> parsePath(String pathSpec) {
        var segments = new ArrayList<PathSegment>();
        for (String rawSegment : pathSpec.split("\\.")) {
            if (rawSegment.isBlank()) {
                continue;
            }
            if ("[*]".equals(rawSegment)) {
                segments.add(new PathSegment("", true));
                continue;
            }
            if (rawSegment.endsWith("[*]")) {
                segments.add(new PathSegment(rawSegment.substring(0, rawSegment.length() - 3), true));
            } else {
                segments.add(new PathSegment(rawSegment, false));
            }
        }
        return segments;
    }

    private boolean isExcluded(String currentPath, List<String> excludePaths) {
        if (currentPath == null || currentPath.isBlank() || excludePaths == null || excludePaths.isEmpty()) {
            return false;
        }
        String normalizedPath = normalizePathSpec(currentPath);
        for (String rawExcludePath : excludePaths) {
            String excludePath = normalizePathSpec(rawExcludePath);
            if (excludePath.isBlank()) {
                continue;
            }
            if (normalizedPath.matches("^" + toPathRegex(excludePath) + "(?:$|\\..*|\\[.*)")) {
                return true;
            }
        }
        return false;
    }

    private String toPathRegex(String pathSpec) {
        var pattern = new StringBuilder();
        for (int i = 0; i < pathSpec.length(); i++) {
            char current = pathSpec.charAt(i);
            if (current == '[' && i + 2 < pathSpec.length()
                    && pathSpec.charAt(i + 1) == '*'
                    && pathSpec.charAt(i + 2) == ']') {
                pattern.append("\\[\\d+\\]");
                i += 2;
                continue;
            }
            switch (current) {
                case '.' -> pattern.append("\\.");
                case '[' -> pattern.append("\\[");
                case ']' -> pattern.append("\\]");
                case '\\', '^', '$', '|', '?', '*', '+', '(', ')', '{', '}' -> pattern.append("\\").append(current);
                default -> pattern.append(current);
            }
        }
        return pattern.toString();
    }

    private String appendPath(String currentPath, String segment) {
        if (segment == null || segment.isBlank()) {
            return currentPath == null ? "" : currentPath;
        }
        if (currentPath == null || currentPath.isBlank()) {
            return segment;
        }
        return currentPath + "." + segment;
    }

    private String appendIndex(String currentPath, int index) {
        if (currentPath == null || currentPath.isBlank()) {
            return "[" + index + "]";
        }
        return currentPath + "[" + index + "]";
    }

    private String rootField(String path) {
        String normalizedPath = normalizePathSpec(path);
        if (normalizedPath.isBlank() || normalizedPath.startsWith("[")) {
            return "";
        }
        int dotIndex = normalizedPath.indexOf('.');
        int arrayIndex = normalizedPath.indexOf('[');
        int endIndex;
        if (dotIndex < 0) {
            endIndex = arrayIndex < 0 ? normalizedPath.length() : arrayIndex;
        } else if (arrayIndex < 0) {
            endIndex = dotIndex;
        } else {
            endIndex = Math.min(dotIndex, arrayIndex);
        }
        return normalizedPath.substring(0, endIndex);
    }

    private String prettyLabel(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        String normalized = rawValue.strip()
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ');
        var builder = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isWhitespace(current)) {
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != ' ') {
                    builder.append(' ');
                }
                previous = ' ';
                continue;
            }
            boolean separateCamelCase = Character.isUpperCase(current)
                    && !builder.isEmpty()
                    && previous != ' '
                    && Character.isLowerCase(previous);
            boolean separateDigit = Character.isDigit(current)
                    && !builder.isEmpty()
                    && previous != ' '
                    && !Character.isDigit(previous);
            boolean separateLetter = Character.isLetter(current)
                    && !builder.isEmpty()
                    && Character.isDigit(previous);
            if ((separateCamelCase || separateDigit || separateLetter)
                    && builder.charAt(builder.length() - 1) != ' ') {
                builder.append(' ');
            }
            builder.append(builder.isEmpty() ? Character.toUpperCase(current) : current);
            previous = current;
        }
        return builder.toString().trim();
    }

    private String formatPathLabel(String path) {
        String normalizedPath = normalizePathSpec(path);
        if (normalizedPath.isBlank()) {
            return "Value";
        }
        var labels = new ArrayList<String>();
        for (String rawSegment : normalizedPath.split("\\.")) {
            if (rawSegment.isBlank()) {
                continue;
            }
            labels.add(formatSegmentLabel(rawSegment));
        }
        return labels.isEmpty() ? prettyLabel(normalizedPath) : String.join(" > ", labels);
    }

    private String formatSegmentLabel(String rawSegment) {
        int arrayIndexStart = rawSegment.indexOf('[');
        String fieldName = arrayIndexStart >= 0 ? rawSegment.substring(0, arrayIndexStart) : rawSegment;
        var label = new StringBuilder();
        if (!fieldName.isBlank()) {
            label.append(prettyLabel(fieldName));
        }
        int cursor = arrayIndexStart;
        while (cursor >= 0 && cursor < rawSegment.length()) {
            int end = rawSegment.indexOf(']', cursor);
            if (end < 0) {
                break;
            }
            String rawIndex = rawSegment.substring(cursor + 1, end).trim();
            if (label.isEmpty()) {
                label.append("Item");
            }
            label.append(" #");
            if (rawIndex.chars().allMatch(Character::isDigit)) {
                label.append(Integer.parseInt(rawIndex) + 1);
            } else {
                label.append(rawIndex);
            }
            cursor = rawSegment.indexOf('[', end + 1);
        }
        return label.isEmpty() ? prettyLabel(rawSegment) : label.toString();
    }

    private String shortPathLabel(String path) {
        String normalizedPath = normalizePathSpec(path);
        if (normalizedPath.isBlank()) {
            return "Value";
        }
        int dotIndex = normalizedPath.lastIndexOf('.');
        String tail = dotIndex >= 0 ? normalizedPath.substring(dotIndex + 1) : normalizedPath;
        return formatSegmentLabel(tail);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String normalized = text.strip();
        if (maxLength <= 0 || normalized.length() <= maxLength) {
            return normalized;
        }
        String suffix = "... [truncated]";
        if (maxLength <= suffix.length()) {
            return normalized.substring(0, maxLength);
        }
        return normalized.substring(0, maxLength - suffix.length()).stripTrailing() + suffix;
    }

    private String sanitizeText(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ');
        var lines = new ArrayList<String>();
        boolean previousBlank = false;
        for (String line : normalized.split("\n")) {
            String compact = line.strip().replaceAll("\\s+", " ");
            if (compact.isBlank()) {
                if (!previousBlank) {
                    lines.add("");
                    previousBlank = true;
                }
            } else {
                lines.add(compact);
                previousBlank = false;
            }
        }
        String cleaned = String.join("\n", lines).strip();
        return truncate(cleaned, maxLength);
    }

    private void addIfPresent(List<String> sections, String text) {
        if (text != null && !text.isBlank()) {
            sections.add(text);
        }
    }

    private record ProjectionOptions(
            List<String> titlePaths,
            List<String> bodyPaths,
            List<String> tagPaths,
            List<String> timePaths,
            List<String> scalarPaths,
            List<String> excludePaths,
            List<ProjectionSection> sections,
            boolean includeRawJson,
            int rawJsonMaxLength,
            int maxDepth,
            int maxArrayItems,
            int maxObjectFields,
            int maxScalarValues,
            int maxSectionChars
    ) {
        static ProjectionOptions defaults() {
            return new ProjectionOptions(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    true,
                    DEFAULT_RAW_JSON_MAX_LENGTH,
                    DEFAULT_MAX_DEPTH,
                    DEFAULT_MAX_ARRAY_ITEMS,
                    DEFAULT_MAX_OBJECT_FIELDS,
                    DEFAULT_MAX_SCALAR_VALUES,
                    DEFAULT_MAX_SECTION_CHARS
            );
        }
    }

    public record ProjectedDatastoreDocument(
            String fileName,
            String filePath,
            String content,
            Map<String, Object> sourceRef
    ) {}

    private record ResolvedValue(String path, JsonNode node) {}

    private record PathSegment(String name, boolean arrayWildcard) {}

    private record ProjectionSection(
            String label,
            List<String> paths,
            SectionMode mode,
            int maxItems,
            int maxChars,
            boolean includeFieldName
    ) {}

    private enum SectionMode {
        TEXT,
        LINES,
        OBJECT_SUMMARY,
        KEY_VALUE,
        JSON;

        static SectionMode parse(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return TEXT;
            }
            try {
                return SectionMode.valueOf(rawValue.strip().replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return TEXT;
            }
        }
    }
}
