package com.lifepilot.document.patch;

/**
 * 文档 patch 操作 —— 面向 LLM 的 sealed 协议。
 *
 * <p>4 种 op 共享一个父接口供 Engine 按 pattern matching 分派；每个 op 自带定位器字段与新内容。
 * 协议形状见 {@code docs/superpowers/specs/2026-04-21-document-workspace-phase3-design.md} §2.3。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface DocumentPatchOperation
        permits ReplaceTextOp, InsertParagraphAfterOp, DeleteParagraphOp, AddTableRowOp {

    /** LLM 可选的解释，会透传到 diff JSON 给用户看。 */
    String reason();
}
