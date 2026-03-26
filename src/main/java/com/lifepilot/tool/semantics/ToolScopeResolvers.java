package com.lifepilot.tool.semantics;

import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.tool.model.ToolInput;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 常用工具资源解析器工厂。
 *
 * <p>提供文件、Origin、集合、任务等标准资源解析模板，避免每个工具重复拼装 scope。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
public final class ToolScopeResolvers {

    private static final ToolScopeResolver NONE = input -> ToolScopeResolution.EMPTY;

    private ToolScopeResolvers() {
    }

    public static ToolScopeResolver none() {
        return NONE;
    }

    public static ToolScopeResolver paths(String... parameterNames) {
        return input -> {
            List<String> paths = normalizeDistinctValues(input, ToolScopeNormalizer::normalizePath, parameterNames);
            if (paths.isEmpty()) {
                return ToolScopeResolution.EMPTY;
            }
            List<String> workspacePaths = distinctSorted(paths.stream()
                    .map(ToolScopeNormalizer::resolveWorkspacePath)
                    .filter(Objects::nonNull)
                    .toList());
            List<String> normalizedResources = distinctSorted(paths.stream()
                    .map(ToolSchedulingResources::path)
                    .toList());
            Map<String, Object> values = new LinkedHashMap<>();
            putDimension(values, "paths", paths);
            putDimension(values, "workspacePaths", workspacePaths);
            return new ToolScopeResolution(ExecutionGrantScope.of(values), normalizedResources);
        };
    }

    public static ToolScopeResolver workspacePaths(String... parameterNames) {
        return input -> {
            List<String> workspacePaths = normalizeDistinctValues(input, ToolScopeNormalizer::normalizePath, parameterNames);
            if (workspacePaths.isEmpty()) {
                return ToolScopeResolution.EMPTY;
            }
            List<String> normalizedResources = distinctSorted(workspacePaths.stream()
                    .map(ToolSchedulingResources::workspace)
                    .toList());
            Map<String, Object> values = new LinkedHashMap<>();
            putDimension(values, "workspacePaths", workspacePaths);
            return new ToolScopeResolution(ExecutionGrantScope.of(values), normalizedResources);
        };
    }

    public static ToolScopeResolver origins(String... parameterNames) {
        return input -> {
            List<String> origins = normalizeDistinctValues(input, ToolScopeNormalizer::normalizeOrigin, parameterNames);
            if (origins.isEmpty()) {
                return ToolScopeResolution.EMPTY;
            }
            List<String> hosts = distinctSorted(origins.stream()
                    .map(ToolScopeNormalizer::extractHost)
                    .filter(Objects::nonNull)
                    .toList());
            List<String> normalizedResources = distinctSorted(origins.stream()
                    .map(ToolSchedulingResources::origin)
                    .toList());
            Map<String, Object> values = new LinkedHashMap<>();
            putDimension(values, "origins", origins);
            putDimension(values, "hosts", hosts);
            return new ToolScopeResolution(ExecutionGrantScope.of(values), normalizedResources);
        };
    }

    public static ToolScopeResolver pathTrees(String... parameterNames) {
        return input -> {
            List<String> paths = normalizeDistinctValues(input, ToolScopeNormalizer::normalizePath, parameterNames);
            if (paths.isEmpty()) {
                return ToolScopeResolution.EMPTY;
            }
            List<String> workspacePaths = distinctSorted(paths.stream()
                    .map(ToolScopeNormalizer::resolveWorkspacePath)
                    .filter(Objects::nonNull)
                    .toList());
            List<String> normalizedResources = distinctSorted(paths.stream()
                    .map(ToolSchedulingResources::tree)
                    .toList());
            Map<String, Object> values = new LinkedHashMap<>();
            putDimension(values, "paths", paths);
            putDimension(values, "workspacePaths", workspacePaths);
            return new ToolScopeResolution(ExecutionGrantScope.of(values), normalizedResources);
        };
    }

    public static ToolScopeResolver exactValues(String scopeKey, String... parameterNames) {
        return exactValues(scopeKey, true, parameterNames);
    }

    public static ToolScopeResolver exactValues(String scopeKey, boolean useForScheduling, String... parameterNames) {
        return input -> {
            List<String> values = normalizeDistinctValues(input, ToolScopeResolvers::trimToNull, parameterNames);
            if (values.isEmpty()) {
                return ToolScopeResolution.EMPTY;
            }
            Map<String, Object> scope = new LinkedHashMap<>();
            putDimension(scope, scopeKey, values);
            List<String> normalizedResources = useForScheduling
                    ? distinctSorted(values.stream()
                    .map(value -> ToolSchedulingResources.exactValue(scopeKey, value))
                    .toList())
                    : List.of();
            return new ToolScopeResolution(
                    ExecutionGrantScope.of(scope),
                    normalizedResources
            );
        };
    }

    public static ToolScopeResolver composite(ToolScopeResolver... resolvers) {
        List<ToolScopeResolver> delegates = resolvers != null ? List.of(resolvers) : List.of();
        return input -> {
            Map<String, Object> mergedScope = new LinkedHashMap<>();
            List<String> normalizedResources = new ArrayList<>();
            for (ToolScopeResolver resolver : delegates) {
                if (resolver == null) {
                    continue;
                }
                ToolScopeResolution resolution = resolver.resolve(input);
                if (resolution.scope() != null && !resolution.scope().isEmpty()) {
                    resolution.scope().values().forEach((key, value) ->
                            mergeScopeValue(mergedScope, key, value));
                }
                normalizedResources.addAll(resolution.normalizedResources());
            }
            if (mergedScope.isEmpty() && normalizedResources.isEmpty()) {
                return ToolScopeResolution.EMPTY;
            }
            return new ToolScopeResolution(
                    ExecutionGrantScope.of(mergedScope),
                    distinctSorted(normalizedResources)
            );
        };
    }

    private static void mergeScopeValue(Map<String, Object> target, String key, Object value) {
        List<String> merged = new ArrayList<>(ExecutionGrantScope.toStringList(target.get(key)));
        merged.addAll(ExecutionGrantScope.toStringList(value));
        putDimension(target, key, distinctSorted(merged));
    }

    private static List<String> normalizeDistinctValues(ToolInput input,
                                                        Function<String, String> normalizer,
                                                        String... parameterNames) {
        var normalized = new LinkedHashSet<String>();
        if (parameterNames == null) {
            return List.of();
        }
        for (String name : parameterNames) {
            String value = input.getOptionalParam(name, String.class).orElse(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalizedValue = normalizer.apply(value);
            if (normalizedValue != null && !normalizedValue.isBlank()) {
                normalized.add(normalizedValue);
            }
        }
        return distinctSorted(normalized);
    }

    private static List<String> distinctSorted(Iterable<String> values) {
        var normalized = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        var sorted = new ArrayList<>(normalized);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static void putDimension(Map<String, Object> values, String key, List<String> dimensionValues) {
        if (dimensionValues == null || dimensionValues.isEmpty()) {
            return;
        }
        values.put(key, dimensionValues.size() == 1 ? dimensionValues.get(0) : List.copyOf(dimensionValues));
    }

    @Nullable
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
