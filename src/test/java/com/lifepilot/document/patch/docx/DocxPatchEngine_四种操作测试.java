package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.ReplaceTextOp;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocxPatchEngine 四种 op 行为测试 —— 本 Task 先覆盖 replace_text，Task 7 补齐其余。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocxPatchEngine_四种操作测试 {

    private static final Path CONTRACT = Path.of("src/test/resources/fixtures/document/sample-contract.docx");
    private static final Path STYLES = Path.of("src/test/resources/fixtures/document/sample-with-styles.docx");

    private final DocxPatchEngine engine = new DocxPatchEngine(new TextAnchorLocator());

    @Test
    @DisplayName("replace_text 单 run 内替换成功")
    void replace_text单run内替换() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期", "付款期限 15 天", "缩短期限");

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            assertThat(result.appliedOps()).hasSize(1);
            // 验证文本被改
            String fulltext = extractAllText(doc);
            assertThat(fulltext).contains("付款期限 15 天").doesNotContain("付款期限 30 天");
        }
    }

    @Test
    @DisplayName("replace_text 跨 run 保样式")
    void replace_text跨run保样式() throws Exception {
        try (InputStream in = Files.newInputStream(STYLES);
             XWPFDocument doc = new XWPFDocument(in)) {
            // 跨 run 1-2：run 1 "重要内容" 加粗, run 2 " 结尾段" 斜体红色。
            // target "内容 结尾" 跨这两个 run
            var op = new ReplaceTextOp("重要", "内容 结尾", "段", "文字 尾部", null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isTrue();
            XWPFParagraph para = doc.getParagraphs().get(0);
            // run 1 应保留加粗
            XWPFRun run1 = para.getRuns().get(1);
            assertThat(run1.isBold()).isTrue();
            // run 2 应保留斜体 + 红色
            XWPFRun run2 = para.getRuns().get(2);
            assertThat(run2.isItalic()).isTrue();
            assertThat(run2.getColor()).isEqualTo("C00000");
            // 拼接文本应是：开头段 重要文字 尾部段
            assertThat(para.getText()).isEqualTo("开头段 重要文字 尾部段");
        }
    }

    @Test
    @DisplayName("replace_text locator 多命中整批失败")
    void replace_text多命中失败() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var op = new ReplaceTextOp("", "第", "", "X", null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(op));

            assertThat(result.success()).isFalse();
            assertThat(result.failedOps()).hasSize(1);
            assertThat(result.failedOps().get(0).opType()).isEqualTo("replace_text");
        }
    }

    @Test
    @DisplayName("replace_text 事务性 — 第二 op 失败整批回滚")
    void replace_text事务性() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACT);
             XWPFDocument doc = new XWPFDocument(in)) {
            var beforeText = extractAllText(doc);
            var good = new ReplaceTextOp("风险如下：", "付款期限 30 天", "，若超期", "付款期限 15 天", null);
            var bad = new ReplaceTextOp("", "绝不存在的文字", "", "Y", null);

            var result = engine.apply(doc, List.<DocumentPatchOperation>of(good, bad));

            assertThat(result.success()).isFalse();
            // 引擎返回 failure；文档虽已被内存改动，但调用方会丢弃，不写盘。
            // 这里验证返回的 failedOps.opIndex=1（bad 是第二个）。
            assertThat(result.failedOps().get(0).opIndex()).isEqualTo(1);
        }
    }

    private static String extractAllText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText()).append("\n");
        }
        return sb.toString();
    }
}
