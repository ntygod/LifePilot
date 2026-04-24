package com.lifepilot.meta.infra.browser;

/**
 * 无障碍树 YAML 按深度裁剪工具。
 *
 * <p>Playwright 的 {@code locator.ariaSnapshot()} 返回形如下列的 YAML：</p>
 * <pre>
 * - document:
 *   - main:
 *     - heading "标题":
 *       - text: 标题文本
 * </pre>
 *
 * <p>每级节点使用 2 个空格缩进。本工具按行扫描，计算前导空格数 / 2 得到当前深度，
 * 丢弃所有超过 {@code maxDepth} 的行，并在裁剪位置插入 {@code "..."} 折叠提示，
 * 让 LLM 明白存在被省略的子树。</p>
 *
 * <p>纯函数，无副作用，可被单元测试直接验证。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
final class AccessibilityYamlTrimmer {

    private AccessibilityYamlTrimmer() {
        // 工具类禁止实例化
    }

    /**
     * 按深度裁剪 YAML 文本。
     *
     * @param yaml     输入 YAML，允许 null
     * @param maxDepth 最大深度（从 1 开始）。{@code <= 0} 表示不裁剪
     * @return 裁剪后的 YAML；输入为 null 时原样返回
     */
    static String trim(String yaml, int maxDepth) {
        if (yaml == null || yaml.isEmpty() || maxDepth <= 0) {
            return yaml;
        }

        String[] lines = yaml.split("\n", -1);
        StringBuilder sb = new StringBuilder(yaml.length());
        boolean folded = false;
        String pendingIndent = null;

        for (String line : lines) {
            int indent = leadingSpaces(line);
            // 层级从 1 开始：0 空格 = 第 1 层，2 空格 = 第 2 层
            int depth = indent / 2 + 1;

            if (depth <= maxDepth) {
                if (folded && pendingIndent != null) {
                    // 先吐出折叠占位符
                    sb.append(pendingIndent).append("...").append('\n');
                    folded = false;
                    pendingIndent = null;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            } else {
                if (!folded) {
                    folded = true;
                    // 折叠占位符需保持与被裁剪行同缩进，便于 YAML 语义可读
                    pendingIndent = " ".repeat(indent);
                }
            }
        }
        // 行尾的折叠占位符
        if (folded && pendingIndent != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(pendingIndent).append("...");
        }
        return sb.toString();
    }

    /** 计算字符串前导空格数。 */
    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
