package com.lifepilot.tool.semantics;

import org.springframework.lang.Nullable;

/**
 * 调度资源编码工具。
 *
 * <p>统一生成调度资源 token，并提供冲突判定逻辑，避免各处散落字符串前缀判断。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
public final class ToolSchedulingResources {

    private static final String PREFIX_PATH = "path";
    private static final String PREFIX_TREE = "tree";
    private static final String PREFIX_ORIGIN = "origin";
    private static final String PREFIX_WORKSPACE = "workspace";

    private ToolSchedulingResources() {
    }

    public static String path(String normalizedPath) {
        return encode(PREFIX_PATH, normalizedPath);
    }

    public static String tree(String normalizedPath) {
        return encode(PREFIX_TREE, normalizedPath);
    }

    public static String origin(String normalizedOrigin) {
        return encode(PREFIX_ORIGIN, normalizedOrigin);
    }

    public static String workspace(String normalizedPath) {
        return encode(PREFIX_WORKSPACE, normalizedPath);
    }

    public static String exactValue(String scopeKey, String value) {
        return encode("value[" + scopeKey + "]", value);
    }

    public static boolean conflicts(@Nullable String leftToken, @Nullable String rightToken) {
        if (leftToken == null || leftToken.isBlank() || rightToken == null || rightToken.isBlank()) {
            return false;
        }
        if (leftToken.equals(rightToken)) {
            return true;
        }

        ParsedToken left = parse(leftToken);
        ParsedToken right = parse(rightToken);
        if (left == null || right == null) {
            return false;
        }
        if (!isPathLike(left.type()) || !isPathLike(right.type())) {
            return false;
        }
        return pathLikeConflicts(left, right);
    }

    private static boolean pathLikeConflicts(ParsedToken left, ParsedToken right) {
        return switch (left.type()) {
            case PREFIX_PATH -> switch (right.type()) {
                case PREFIX_PATH -> left.value().equals(right.value());
                case PREFIX_TREE, PREFIX_WORKSPACE -> ToolScopeNormalizer.pathStartsWith(left.value(), right.value());
                default -> false;
            };
            case PREFIX_TREE, PREFIX_WORKSPACE -> switch (right.type()) {
                case PREFIX_PATH -> ToolScopeNormalizer.pathStartsWith(right.value(), left.value());
                case PREFIX_TREE, PREFIX_WORKSPACE ->
                        ToolScopeNormalizer.pathStartsWith(left.value(), right.value())
                                || ToolScopeNormalizer.pathStartsWith(right.value(), left.value());
                default -> false;
            };
            default -> false;
        };
    }

    private static boolean isPathLike(String type) {
        return PREFIX_PATH.equals(type)
                || PREFIX_TREE.equals(type)
                || PREFIX_WORKSPACE.equals(type);
    }

    @Nullable
    private static ParsedToken parse(String token) {
        int separator = token.indexOf(':');
        if (separator <= 0 || separator == token.length() - 1) {
            return null;
        }
        return new ParsedToken(token.substring(0, separator), token.substring(separator + 1));
    }

    private static String encode(String type, String value) {
        return type + ":" + value;
    }

    private record ParsedToken(String type, String value) {}
}
