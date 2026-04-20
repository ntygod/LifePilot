package com.lifepilot.document.generator;

import java.util.List;

/**
 * Excel（xlsx）生成器契约。
 *
 * <p>和 {@code DocumentGenerator}（markdown → docx）并列 —— xlsx 输入是结构化数据,
 * 不是 markdown，不共享 DocumentGenerator 接口。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface ExcelGenerator {

    /**
     * 从工作表数据生成 xlsx 字节。
     *
     * @param sheets 工作表列表（不能为空，至少含 1 个 SheetData）
     * @return 生成的 xlsx 文件字节
     */
    byte[] generate(List<SheetData> sheets);

    /** 返回 MIME 类型。 */
    String mimeType();
}
