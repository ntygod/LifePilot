package com.lifepilot.meta.infra.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无障碍树 YAML 按深度裁剪工具测试。
 *
 * <p>Playwright 的 {@code locator.ariaSnapshot()} 返回 YAML 文本，
 * 每级子节点使用 2 个空格作为缩进。{@link AccessibilityYamlTrimmer}
 * 按缩进层数裁剪超过 {@code maxDepth} 的行。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class AccessibilityYamlTrimmer_maxDepth裁剪测试 {

    private static final String SAMPLE = String.join("\n",
            "- document:",
            "  - main:",
            "    - heading \"标题\":",
            "      - text: 标题文本",
            "    - button \"按钮\":",
            "      - text: 点击我"
    );

    @Test
    void maxDepth为0或负数时保留全量YAML() {
        assertThat(AccessibilityYamlTrimmer.trim(SAMPLE, 0)).isEqualTo(SAMPLE);
        assertThat(AccessibilityYamlTrimmer.trim(SAMPLE, -1)).isEqualTo(SAMPLE);
    }

    @Test
    void maxDepth为1时只保留顶层行() {
        String trimmed = AccessibilityYamlTrimmer.trim(SAMPLE, 1);

        // 顶层 document 保留，后续嵌套行应被替换或剔除
        assertThat(trimmed).contains("- document:");
        assertThat(trimmed).doesNotContain("- main:");
        assertThat(trimmed).doesNotContain("heading");
        assertThat(trimmed).doesNotContain("button");
    }

    @Test
    void maxDepth为2时保留两级节点() {
        String trimmed = AccessibilityYamlTrimmer.trim(SAMPLE, 2);

        assertThat(trimmed).contains("- document:");
        assertThat(trimmed).contains("- main:");
        // 第 3 级起（heading/button）应被裁掉
        assertThat(trimmed).doesNotContain("heading \"标题\"");
        assertThat(trimmed).doesNotContain("button \"按钮\"");
    }

    @Test
    void maxDepth裁剪后行数应严格小于原文行数() {
        int originalLines = SAMPLE.split("\n").length;
        long trimmedNonFoldingLines = AccessibilityYamlTrimmer.trim(SAMPLE, 2)
                .lines()
                .filter(line -> !line.trim().equals("..."))
                .count();

        assertThat(trimmedNonFoldingLines).isLessThan(originalLines);
    }

    @Test
    void maxDepth足够大时输出与输入等价() {
        String trimmed = AccessibilityYamlTrimmer.trim(SAMPLE, 99);
        assertThat(trimmed).isEqualTo(SAMPLE);
    }

    @Test
    void 空字符串或null输入不抛异常() {
        assertThat(AccessibilityYamlTrimmer.trim("", 3)).isEmpty();
        assertThat(AccessibilityYamlTrimmer.trim(null, 3)).isNull();
    }

    @Test
    void 裁剪后插入折叠占位符提示信息() {
        String input = String.join("\n",
                "- document:",
                "  - child1:",
                "    - grandchild: 应被裁剪"
        );
        String trimmed = AccessibilityYamlTrimmer.trim(input, 2);

        // 裁剪后应给出 "..." 折叠标记，让 LLM 知道存在省略
        assertThat(trimmed).contains("...");
    }

    @Test
    void 不规范的奇数缩进按向下取整处理() {
        // 某些实现可能出现 1 个空格缩进，函数应容忍而不抛异常
        String input = String.join("\n",
                "- a:",
                " - b:",
                "   - c:"
        );
        // 只要不抛异常即可
        assertThat(AccessibilityYamlTrimmer.trim(input, 2)).isNotNull();
    }
}
