package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 文本锚点替换 —— P3A 主力 op。
 *
 * <p>服务端在工作副本全文中查找 {@code beforeContext + target + afterContext} 拼接串，
 * 必须恰好出现 1 次，否则定位失败。找到后只替换 {@code target} 对应的文字，
 * 保留横跨 run 的样式（首 run 样式继承到替换文本）。</p>
 *
 * @param beforeContext 前置锚点（可为空串），建议 ≥ 10 字提高精度
 * @param target        要替换的文本（非空）
 * @param afterContext  后置锚点（可为空串）
 * @param newText       新文本（可为空串，空串 = 删除 target）
 * @param reason        可选，LLM 给用户看的解释
 * @author zsg
 * @since 2026-04-21
 */
public record ReplaceTextOp(
        String beforeContext,
        String target,
        String afterContext,
        String newText,
        @Nullable String reason
) implements DocxPatchOperation {

    public ReplaceTextOp {
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("ReplaceTextOp.target 不能为空");
        }
        if (beforeContext == null) beforeContext = "";
        if (afterContext == null) afterContext = "";
        if (newText == null) newText = "";
    }
}
