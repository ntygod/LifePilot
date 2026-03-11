﻿package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.A2uiSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A2UI 组件树结构校验器。
 *
 * @author zsg
 * @since 2026-03-11
 */
public final class A2uiComponentValidator {

    private static final Logger log = LoggerFactory.getLogger(A2uiComponentValidator.class);
    private static final int MAX_DEPTH = 12;
    private static final int MAX_SIGNAL_NAME_LENGTH = 120;
    private static final int MAX_SIGNAL_PAYLOAD_ENTRIES = 16;
    private static final int MAX_TEXT_LENGTH = 4_000;
    private static final int MAX_LABEL_LENGTH = 120;
    private static final int MAX_CODE_LENGTH = 12_000;
    private static final int MAX_TABLE_COLUMNS = 12;
    private static final int MAX_TABLE_ROWS = 200;
    private static final int MAX_TABLE_CELL_LENGTH = 500;

    public record ValidationResult(boolean valid, List<String> errors, @Nullable A2uiComponentTree truncatedTree) {
        public ValidationResult(boolean valid, List<String> errors) {
            this(valid, List.copyOf(errors), null);
        }

        public ValidationResult(boolean valid, List<String> errors, @Nullable A2uiComponentTree truncatedTree) {
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.truncatedTree = truncatedTree;
        }
    }

    private A2uiComponentValidator() {
    }

    public static ValidationResult validate(@Nullable A2uiComponentTree tree, int maxComponentsPerTree) {
        if (tree == null) {
            return new ValidationResult(false, List.of("组件树不能为空"));
        }

        var errors = new ArrayList<String>();
        var components = tree.components();
        A2uiComponentTree truncatedTree = null;

        if (components.isEmpty()) {
            return new ValidationResult(false, List.of("组件树不能为空"));
        }

        if (components.size() > maxComponentsPerTree) {
            log.warn("A2UI component tree exceeds limit: count={}, max={}", components.size(), maxComponentsPerTree);
            errors.add("组件数量超过限制 %d，已截断到 %d 个".formatted(components.size(), maxComponentsPerTree));
            components = components.subList(0, maxComponentsPerTree);
            truncatedTree = new A2uiComponentTree(components);
        }

        var idSet = new HashSet<String>();
        var duplicateIds = new HashSet<String>();
        var componentById = new HashMap<String, A2uiComponent>();

        for (var component : components) {
            if (component == null) {
                errors.add("组件节点不能为空");
                continue;
            }
            if (component.id() == null || component.id().isBlank()) {
                errors.add("组件 id 不能为空");
                continue;
            }
            if (!idSet.add(component.id())) {
                duplicateIds.add(component.id());
                continue;
            }

            componentById.put(component.id(), component);

            if (component.type() == null || component.type().isBlank()) {
                errors.add("组件 '%s' 的 type 不能为空".formatted(component.id()));
            } else if (!A2uiComponentCatalog.supportedTypes().contains(component.type())) {
                errors.add("组件 '%s' 使用了未注册类型 '%s'".formatted(component.id(), component.type()));
            }
            if (component.properties().containsKey("signal")) {
                errors.add("组件 '%s' 的 signal 必须放在顶层字段，不能放在 properties 中".formatted(component.id()));
            }

            validateSignal(component, errors);
            validateProperties(component, errors);
        }

        if (!duplicateIds.isEmpty()) {
            errors.add("组件 id 重复: %s".formatted(duplicateIds));
        }

        for (var component : components) {
            if (component == null || component.id() == null || component.id().isBlank()) {
                continue;
            }
            for (var childId : component.children()) {
                if (!idSet.contains(childId)) {
                    errors.add("组件 '%s' 的 children 引用了不存在的 id '%s'".formatted(component.id(), childId));
                }
            }
        }

        boolean hasCycle = hasCycle(componentById);
        if (hasCycle) {
            errors.add("组件树存在循环引用");
        } else if (exceedsDepthLimit(componentById, MAX_DEPTH)) {
            errors.add("组件树层级超过限制 %d".formatted(MAX_DEPTH));
        }

        boolean hasStructuralErrors = errors.stream().anyMatch(error -> !error.contains("已截断到"));
        return new ValidationResult(!hasStructuralErrors, errors, truncatedTree);
    }

    private static boolean hasCycle(Map<String, A2uiComponent> componentById) {
        var visiting = new HashSet<String>();
        var visited = new HashSet<String>();
        for (String id : componentById.keySet()) {
            if (detectCycle(id, componentById, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectCycle(String id,
                                       Map<String, A2uiComponent> componentById,
                                       Set<String> visiting,
                                       Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }

        var component = componentById.get(id);
        if (component != null) {
            for (String childId : component.children()) {
                if (componentById.containsKey(childId)
                        && detectCycle(childId, componentById, visiting, visited)) {
                    return true;
                }
            }
        }

        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private static boolean exceedsDepthLimit(Map<String, A2uiComponent> componentById, int maxDepth) {
        var depthCache = new HashMap<String, Integer>();
        for (String id : componentById.keySet()) {
            if (computeDepth(id, componentById, depthCache) > maxDepth) {
                return true;
            }
        }
        return false;
    }

    private static int computeDepth(String id,
                                    Map<String, A2uiComponent> componentById,
                                    Map<String, Integer> depthCache) {
        Integer cached = depthCache.get(id);
        if (cached != null) {
            return cached;
        }

        var component = componentById.get(id);
        if (component == null || component.children().isEmpty()) {
            depthCache.put(id, 1);
            return 1;
        }

        int depth = 1;
        for (String childId : component.children()) {
            if (componentById.containsKey(childId)) {
                depth = Math.max(depth, 1 + computeDepth(childId, componentById, depthCache));
            }
        }
        depthCache.put(id, depth);
        return depth;
    }

    private static void validateProperties(A2uiComponent component, List<String> errors) {
        if (component.type() == null || component.type().isBlank()) {
            return;
        }

        switch (component.type()) {
            case "Text" -> {
                validateStringProperty(component, errors, "text", true, MAX_TEXT_LENGTH);
                validateEnumProperty(component, errors, "variant");
            }
            case "Card" -> {
                validateStringProperty(component, errors, "title", false, MAX_LABEL_LENGTH);
                validateStringProperty(component, errors, "subtitle", false, MAX_TEXT_LENGTH);
                validateBooleanProperty(component, errors, "elevated");
            }
            case "Button" -> {
                validateStringProperty(component, errors, "label", true, MAX_LABEL_LENGTH);
                validateEnumProperty(component, errors, "variant");
                validateBooleanProperty(component, errors, "disabled");
            }
            case "TextField" -> {
                validateStringProperty(component, errors, "label", false, MAX_LABEL_LENGTH);
                validateStringProperty(component, errors, "placeholder", false, MAX_LABEL_LENGTH);
                validateStringProperty(component, errors, "value", false, MAX_TEXT_LENGTH);
            }
            case "List" -> validateBooleanProperty(component, errors, "ordered");
            case "ListItem" -> validateStringProperty(component, errors, "text", true, MAX_TEXT_LENGTH);
            case "DatePicker" -> {
                validateStringProperty(component, errors, "label", false, MAX_LABEL_LENGTH);
                validateStringProperty(component, errors, "value", false, 64);
            }
            case "Chip" -> {
                validateStringProperty(component, errors, "label", true, MAX_LABEL_LENGTH);
                validateBooleanProperty(component, errors, "selected");
            }
            case "Divider" -> validateEnumProperty(component, errors, "orientation");
            case "Image" -> validateImage(component, errors);
            case "Table" -> validateTable(component, errors);
            case "CodeBlock" -> {
                validateStringProperty(component, errors, "code", true, MAX_CODE_LENGTH);
                validateStringProperty(component, errors, "language", false, 32);
            }
            case "Progress" -> validateProgress(component, errors);
            default -> {
                // 未注册类型已在前面校验过
            }
        }
    }

    private static void validateSignal(A2uiComponent component, List<String> errors) {
        A2uiSignal signal = component.signal();
        if (signal == null) {
            return;
        }
        if (!A2uiComponentCatalog.supportsSignal(component.type())) {
            errors.add("组件 '%s' 的类型 '%s' 不支持 signal".formatted(component.id(), component.type()));
        }
        if (signal.name() == null || signal.name().isBlank()) {
            errors.add("组件 '%s' 的 signal.name 不能为空".formatted(component.id()));
        } else if (signal.name().length() > MAX_SIGNAL_NAME_LENGTH) {
            errors.add("组件 '%s' 的 signal.name 超过长度限制 %d".formatted(component.id(), MAX_SIGNAL_NAME_LENGTH));
        }
        if (signal.payload().size() > MAX_SIGNAL_PAYLOAD_ENTRIES) {
            errors.add("组件 '%s' 的 signal.payload 参数过多，上限 %d".formatted(component.id(), MAX_SIGNAL_PAYLOAD_ENTRIES));
        }
        for (String key : signal.payload().keySet()) {
            if (key == null || key.isBlank()) {
                errors.add("组件 '%s' 的 signal.payload key 不能为空".formatted(component.id()));
                break;
            }
        }
    }

    private static void validateStringProperty(A2uiComponent component,
                                               List<String> errors,
                                               String key,
                                               boolean required,
                                               int maxLength) {
        Object value = component.properties().get(key);
        if (value == null) {
            if (required) {
                errors.add("组件 '%s' 的 properties.%s 不能为空".formatted(component.id(), key));
            }
            return;
        }
        if (!(value instanceof String text)) {
            errors.add("组件 '%s' 的 properties.%s 必须是字符串".formatted(component.id(), key));
            return;
        }
        if (required && text.isBlank()) {
            errors.add("组件 '%s' 的 properties.%s 不能为空字符串".formatted(component.id(), key));
            return;
        }
        if (text.length() > maxLength) {
            errors.add("组件 '%s' 的 properties.%s 超过长度限制 %d".formatted(component.id(), key, maxLength));
        }
    }

    private static void validateBooleanProperty(A2uiComponent component, List<String> errors, String key) {
        Object value = component.properties().get(key);
        if (value != null && !(value instanceof Boolean)) {
            errors.add("组件 '%s' 的 properties.%s 必须是 boolean".formatted(component.id(), key));
        }
    }

    private static void validateEnumProperty(A2uiComponent component, List<String> errors, String key) {
        Object value = component.properties().get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof String text)) {
            errors.add("组件 '%s' 的 properties.%s 必须是字符串".formatted(component.id(), key));
            return;
        }

        var allowedValues = A2uiComponentCatalog.allowedValues(component.type(), key);
        if (allowedValues != null && !allowedValues.contains(text)) {
            errors.add("组件 '%s' 的 properties.%s 不在允许值范围内 %s".formatted(component.id(), key, allowedValues));
        }
    }

    private static void validatePositiveNumberProperty(A2uiComponent component, List<String> errors, String key) {
        Object value = component.properties().get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof Number number) || number.doubleValue() <= 0) {
            errors.add("组件 '%s' 的 properties.%s 必须是大于 0 的数字".formatted(component.id(), key));
        }
    }

    private static void validateImage(A2uiComponent component, List<String> errors) {
        Object src = component.properties().get("src");
        if (!(src instanceof String srcValue) || srcValue.isBlank()) {
            errors.add("组件 '%s' 的 Image.src 必须是非空字符串".formatted(component.id()));
            return;
        }
        if (!(srcValue.startsWith("https://")
                || srcValue.startsWith("http://")
                || srcValue.startsWith("/")
                || srcValue.startsWith("data:image/"))) {
            errors.add("组件 '%s' 的 Image.src 仅支持 http(s)、站内相对路径或 data:image".formatted(component.id()));
        }
        validateStringProperty(component, errors, "alt", false, MAX_LABEL_LENGTH);
        validatePositiveNumberProperty(component, errors, "width");
        validatePositiveNumberProperty(component, errors, "height");
    }

    @SuppressWarnings("unchecked")
    private static void validateTable(A2uiComponent component, List<String> errors) {
        Object columns = component.properties().get("columns");
        Object rows = component.properties().get("rows");
        if (!(columns instanceof List<?> columnList) || columnList.isEmpty()) {
            errors.add("组件 '%s' 的 Table.columns 必须是非空数组".formatted(component.id()));
            return;
        }
        if (columnList.size() > MAX_TABLE_COLUMNS) {
            errors.add("组件 '%s' 的 Table.columns 超过上限 %d".formatted(component.id(), MAX_TABLE_COLUMNS));
        }
        for (Object column : columnList) {
            if (!(column instanceof Map<?, ?> columnMap)) {
                errors.add("组件 '%s' 的 Table.columns 元素必须是对象".formatted(component.id()));
                continue;
            }
            Object key = columnMap.get("key");
            Object label = columnMap.get("label");
            if (!(key instanceof String keyText) || keyText.isBlank()) {
                errors.add("组件 '%s' 的 Table.columns.key 必须是非空字符串".formatted(component.id()));
            }
            if (!(label instanceof String labelText) || labelText.isBlank()) {
                errors.add("组件 '%s' 的 Table.columns.label 必须是非空字符串".formatted(component.id()));
            } else if (labelText.length() > MAX_LABEL_LENGTH) {
                errors.add("组件 '%s' 的 Table.columns.label 超过长度限制 %d".formatted(component.id(), MAX_LABEL_LENGTH));
            }
        }

        if (!(rows instanceof List<?> rowList)) {
            errors.add("组件 '%s' 的 Table.rows 必须是数组".formatted(component.id()));
            return;
        }
        if (rowList.size() > MAX_TABLE_ROWS) {
            errors.add("组件 '%s' 的 Table.rows 超过上限 %d".formatted(component.id(), MAX_TABLE_ROWS));
        }
        for (Object row : rowList) {
            if (!(row instanceof Map<?, ?> rowMap)) {
                errors.add("组件 '%s' 的 Table.rows 元素必须是对象".formatted(component.id()));
                continue;
            }
            for (Object value : ((Map<Object, Object>) rowMap).values()) {
                if (value != null && String.valueOf(value).length() > MAX_TABLE_CELL_LENGTH) {
                    errors.add("组件 '%s' 的 Table 单元格内容超过长度限制 %d".formatted(component.id(), MAX_TABLE_CELL_LENGTH));
                    break;
                }
            }
        }
    }

    private static void validateProgress(A2uiComponent component, List<String> errors) {
        Object value = component.properties().get("value");
        if (!(value instanceof Number number)) {
            errors.add("组件 '%s' 的 Progress.value 必须是数字".formatted(component.id()));
            return;
        }
        double progress = number.doubleValue();
        if (progress < 0 || progress > 100) {
            errors.add("组件 '%s' 的 Progress.value 必须在 0 到 100 之间".formatted(component.id()));
        }
        validateStringProperty(component, errors, "label", false, MAX_LABEL_LENGTH);
    }
}
