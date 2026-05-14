package com.lifepilot.agent.task.proactive;

import java.util.Set;

/**
 * 主动引擎偏好命名卫士 — 文档级强化 {@code proactive-*} 前缀约定。
 *
 * <p>知微的 L4 PreferenceRule 同时承载主动引擎专属偏好（{@code proactive-domain} /
 * {@code proactive-timing} / {@code proactive-style}）和记忆模块通用偏好
 * ({@code user-preference} / 饮食 / 购物 等)。本类提供静态判定，便于调用方在日志、
 * 审计和未来扩展时显式区分两类偏好。</p>
 *
 * <p>为什么不做运行期强制拦截：现有代码已经在读写侧显式指定了 category，
 * 强制拦截会带来破坏性变更风险，收益低。该卫士作为文档级强化 + 单元测试
 * 约束存在。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class ProactivePreferenceGuard {

    /** 主动引擎偏好 category 前缀。 */
    public static final String PROACTIVE_PREFIX = "proactive-";

    /** 已知但非主动引擎专属的 category 白名单。 */
    public static final Set<String> KNOWN_NON_PROACTIVE_CATEGORIES = Set.of(
            "user-preference",
            "schedule",
            "output"
    );

    private ProactivePreferenceGuard() {
        // 工具类禁用实例化
    }

    /** 判定 category 是否为主动引擎专属（{@code proactive-*} 前缀）。 */
    public static boolean isProactiveCategory(String category) {
        if (category == null || category.isBlank()) return false;
        return category.startsWith(PROACTIVE_PREFIX);
    }

    /** 判定 category 是否为已知的合法类别（主动引擎专属 或 已知白名单）。 */
    public static boolean isRecognizedCategory(String category) {
        if (category == null || category.isBlank()) return false;
        return isProactiveCategory(category)
                || KNOWN_NON_PROACTIVE_CATEGORIES.contains(category);
    }
}
