package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.DocxPatchOperation;
import org.springframework.lang.Nullable;

/**
 * 成功应用的 op + 定位结果 —— 供 DocxDiffBuilder 构造 diff 用。
 *
 * @param op    原 op 对象
 * @param range 定位范围（replace_text 必填；其他 op 段落级时填 paragraph-only range，cell 场景填 null）
 * @author zsg
 * @since 2026-04-21
 */
public record AppliedOp(DocxPatchOperation op, @Nullable ParagraphRunRange range) {}
