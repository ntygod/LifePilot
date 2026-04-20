package com.lifepilot.document.generator;

/**
 * 文档生成器契约 —— Phase 2A 单 docx，Phase 2B 扩展 xlsx / pptx。
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface DocumentGenerator {

    /**
     * 从 markdown 生成文档字节。
     *
     * @param markdown 源 markdown 文本
     * @return 生成的文件字节
     */
    byte[] generate(String markdown);

    /** 返回生成器支持的 MIME 类型。 */
    String mimeType();
}
