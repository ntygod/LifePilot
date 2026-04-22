package com.lifepilot.document.generator;

import java.util.List;

/**
 * PowerPoint（pptx）生成器契约。
 *
 * <p>和 {@code DocumentGenerator}（markdown → docx）、{@code ExcelGenerator}（xlsx）并列
 * —— pptx 输入是幻灯片大纲（title + bullets + notes 的结构化列表）,不是 markdown,
 * 不共享 DocumentGenerator 接口。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface PowerpointGenerator {

    /**
     * 从幻灯片大纲生成 pptx 字节。
     *
     * @param slides 幻灯片列表。null 或空列表时生成零幻灯片的合法 pptx
     *               （XMLSlideShow 允许无幻灯片的空演示文稿,和 XSSFWorkbook 的规则不同）
     * @return 生成的 pptx 文件字节
     */
    byte[] generate(List<SlideData> slides);

    /** 返回 MIME 类型。 */
    String mimeType();
}
