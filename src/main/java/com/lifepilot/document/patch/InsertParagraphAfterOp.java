package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 在锚点段落之后插入新段 —— 锚点段落文本必须在文档中唯一匹配。
 *
 * @param anchorParagraphText 锚点段落全文（精确匹配）
 * @param newParagraphs       要插入的段落列表（非空）
 * @param reason              可选
 * @author zsg
 * @since 2026-04-21
 */
public record InsertParagraphAfterOp(
        String anchorParagraphText,
        List<NewParagraph> newParagraphs,
        @Nullable String reason
) implements DocumentPatchOperation {

    public InsertParagraphAfterOp {
        if (anchorParagraphText == null || anchorParagraphText.isBlank()) {
            throw new IllegalArgumentException("anchorParagraphText 不能为空");
        }
        if (newParagraphs == null || newParagraphs.isEmpty()) {
            throw new IllegalArgumentException("newParagraphs 至少一项");
        }
        newParagraphs = List.copyOf(newParagraphs);
    }
}
