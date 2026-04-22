package com.lifepilot.document.patch;

/**
 * 修改段落样式 —— P1-8。
 *
 * <p>按锚点文本定位段落，把它的 style 设成目标（如 {@code Heading1} / {@code Heading2} /
 * {@code Normal} / {@code Title}）。保留原 run 的字符级格式。</p>
 *
 * @param anchorText   段落的锚点文本（整段或子串，文档内必须唯一）
 * @param newStyle     目标 style 名（docx 里 styles.xml 已定义的 style id）
 * @param reason       可选原因说明
 * @author zsg
 * @since 2026-04-22
 */
public record SetParagraphStyleOp(
        String anchorText,
        String newStyle,
        String reason
) implements DocxPatchOperation {

    public SetParagraphStyleOp {
        if (anchorText == null || anchorText.isBlank()) {
            throw new IllegalArgumentException("anchorText 不能为空");
        }
        if (newStyle == null || newStyle.isBlank()) {
            throw new IllegalArgumentException("newStyle 不能为空");
        }
    }
}
