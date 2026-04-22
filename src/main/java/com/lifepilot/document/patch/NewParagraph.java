package com.lifepilot.document.patch;

/**
 * 新增段落描述 —— InsertParagraphAfterOp 的子元素。
 *
 * @param text  段落文本
 * @param style 段落样式枚举：Normal / Heading1 / Heading2 / Heading3 / ListBullet；缺失默认 Normal
 * @author zsg
 * @since 2026-04-21
 */
public record NewParagraph(String text, String style) {

    public static final String STYLE_NORMAL = "Normal";
    public static final String STYLE_HEADING_1 = "Heading1";
    public static final String STYLE_HEADING_2 = "Heading2";
    public static final String STYLE_HEADING_3 = "Heading3";
    public static final String STYLE_LIST_BULLET = "ListBullet";

    public NewParagraph {
        if (text == null) text = "";
        if (style == null || style.isBlank()) style = STYLE_NORMAL;
    }
}
