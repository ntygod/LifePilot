package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 删除整段 —— paragraphText 必须在文档中唯一匹配一个段落。
 *
 * @author zsg
 * @since 2026-04-21
 */
public record DeleteParagraphOp(
        String paragraphText,
        @Nullable String reason
) implements DocumentPatchOperation {

    public DeleteParagraphOp {
        if (paragraphText == null || paragraphText.isBlank()) {
            throw new IllegalArgumentException("paragraphText 不能为空");
        }
    }
}
