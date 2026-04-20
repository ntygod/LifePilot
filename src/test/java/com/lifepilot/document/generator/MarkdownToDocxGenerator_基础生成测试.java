package com.lifepilot.document.generator;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownToDocxGenerator_基础生成测试 {

    private final MarkdownToDocxGenerator generator = new MarkdownToDocxGenerator();

    @Test
    void mimeType_返回_wordprocessingml() {
        assertThat(generator.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void 一级标题与段落能被回读到() throws Exception {
        String md = "# Phase 2A 验证报告\n\n这是一段正文,含关键词 海豚登月计划。";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String allText = doc.getParagraphs().stream()
                    .map(p -> p.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(allText).contains("Phase 2A 验证报告");
            assertThat(allText).contains("海豚登月计划");
        }
    }

    @Test
    void 二级三级标题字号比正文大() throws Exception {
        // POI 新建 XWPFDocument 没有 Heading 样式定义,用字号判定标题
        String md = "# 一级\n\n## 二级\n\n### 三级\n\n正文";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 收集所有段落的最大 runFontSize,标题段落字号应 >= 14(实现里一级 20 / 二级 16 / 三级 14 / 正文 11)
            var largeParagraphs = doc.getParagraphs().stream()
                    .filter(p -> p.getRuns().stream()
                            .anyMatch(r -> r.getFontSize() >= 14))
                    .count();
            assertThat(largeParagraphs).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void 无序列表条目保留文本() throws Exception {
        String md = "# 清单\n\n- 第一项\n- 第二项\n- 第三项\n";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String all = doc.getParagraphs().stream().map(p -> p.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(all).contains("第一项");
            assertThat(all).contains("第二项");
            assertThat(all).contains("第三项");
        }
    }

    @Test
    void 有序列表条目保留文本() throws Exception {
        String md = "# 步骤\n\n1. 初始化\n2. 运行\n3. 收尾\n";

        byte[] bytes = generator.generate(md);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String all = doc.getParagraphs().stream().map(p -> p.getText())
                    .collect(Collectors.joining("\n"));
            assertThat(all).contains("初始化");
            assertThat(all).contains("运行");
            assertThat(all).contains("收尾");
        }
    }

    @Test
    void 空_markdown_生成最小可读的_docx() throws Exception {
        byte[] bytes = generator.generate("");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // 不抛异常就算通过;POI 读得出 XWPFDocument 说明是合法 docx
            assertThat(doc.getParagraphs()).isNotNull();
        }
    }
}
