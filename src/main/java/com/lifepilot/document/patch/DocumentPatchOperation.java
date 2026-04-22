package com.lifepilot.document.patch;

/**
 * 文档 patch 操作 —— 面向 LLM 的顶层 sealed 协议。
 *
 * <p>P3B 起分层：本接口 permits 限定为格式子 sealed 接口（docx / xlsx），
 * 具体 op record 改挂在对应子接口下。</p>
 * <ul>
 *   <li>{@link DocxPatchOperation} — 4 个 docx op record</li>
 *   <li>{@link XlsxPatchOperation} — 4 个 xlsx op record</li>
 * </ul>
 *
 * <p>Engine 在自己那一侧按子接口 pattern matching 保持穷尽；Service 层按 MIME
 * 决定投喂哪个子接口列表给哪个 engine，不在同一批混用。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface DocumentPatchOperation
        permits DocxPatchOperation, XlsxPatchOperation {

    /** LLM 可选的解释，会透传到 diff JSON 给用户看。 */
    String reason();
}
