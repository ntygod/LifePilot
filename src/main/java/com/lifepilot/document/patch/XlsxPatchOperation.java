package com.lifepilot.document.patch;

/**
 * xlsx 独占的 patch 操作 —— P3B 新增 4 个 op record 挂在此接口下。
 *
 * <p>占位版本（Task 1）—— Task 2 会填充 permits + 4 个 xlsx op record。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface XlsxPatchOperation extends DocumentPatchOperation permits XlsxPatchOperation.Placeholder {

    /** 占位 record，Task 2 会移除；此处仅为保持 sealed 接口能编译通过。 */
    record Placeholder() implements XlsxPatchOperation {
        @Override
        public String reason() { return null; }
    }
}
