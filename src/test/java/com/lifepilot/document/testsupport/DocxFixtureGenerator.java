package com.lifepilot.document.testsupport;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Docx 测试 fixture 一次性生成器 —— 手动触发，产出文件提交进 resources/fixtures。
 *
 * <p>两个产出：</p>
 * <ul>
 *   <li>sample-contract.docx：小型合同样本（标题 + 段落 + 1 个简单表格），供 4 种 op 基础测试</li>
 *   <li>sample-with-styles.docx：含加粗 / 斜体 / 字号混合的段落，专测 replace_text 跨 run 样式保留</li>
 * </ul>
 *
 * <p>@Disabled 默认跳过，需要刷新 fixture 时人工解开并跑单个用例。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@Disabled("手动触发生成 fixture — 产出文件会写到 src/test/resources/fixtures/document/")
class DocxFixtureGenerator {

    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/fixtures/document");

    @Test
    void 生成sample_contract() throws Exception {
        Files.createDirectories(FIXTURE_DIR);
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(
                     FIXTURE_DIR.resolve("sample-contract.docx").toFile())) {

            addHeading(doc, "技术服务合同", 1);
            addParagraph(doc, "甲方：示例科技有限公司");
            addParagraph(doc, "乙方：个人开发者张三");
            addHeading(doc, "第一章 服务范围", 2);
            addParagraph(doc, "乙方负责完成甲方委托的软件开发任务，交付物包括源代码与文档。");
            addHeading(doc, "第二章 付款条款", 2);
            addParagraph(doc, "风险如下：付款期限 30 天，若超期按月息 1% 计违约金。");
            addHeading(doc, "第三章 违约责任", 2);
            addParagraph(doc, "任一方违约需承担实际损失的赔偿责任。");

            XWPFTable table = doc.createTable(3, 3);
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("产品名称");
            header.getCell(1).setText("交付日期");
            header.getCell(2).setText("金额");
            XWPFTableRow r1 = table.getRow(1);
            r1.getCell(0).setText("后端模块");
            r1.getCell(1).setText("2026-05-01");
            r1.getCell(2).setText("50000");
            XWPFTableRow r2 = table.getRow(2);
            r2.getCell(0).setText("前端模块");
            r2.getCell(1).setText("2026-06-01");
            r2.getCell(2).setText("30000");

            doc.write(out);
        }
    }

    @Test
    void 生成sample_with_styles() throws Exception {
        Files.createDirectories(FIXTURE_DIR);
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream out = new FileOutputStream(
                     FIXTURE_DIR.resolve("sample-with-styles.docx").toFile())) {

            // 段落：混合 3 个 run —— 普通 / 加粗 / 斜体 红色
            XWPFParagraph para = doc.createParagraph();
            para.setAlignment(ParagraphAlignment.LEFT);

            XWPFRun run1 = para.createRun();
            run1.setText("开头段 ");
            run1.setFontFamily("Calibri");

            XWPFRun run2 = para.createRun();
            run2.setText("重要内容");
            run2.setBold(true);
            run2.setFontFamily("Calibri");
            run2.setFontSize(14);

            XWPFRun run3 = para.createRun();
            run3.setText(" 结尾段");
            run3.setItalic(true);
            run3.setColor("C00000");
            run3.setFontFamily("Calibri");

            // 第二段：纯文本（供 replace_text 基础案例）
            XWPFParagraph p2 = doc.createParagraph();
            XWPFRun r = p2.createRun();
            r.setText("第二段纯文本内容，其中有付款期限 30 天这个短语。");
            r.setFontFamily("Calibri");

            doc.write(out);
        }
    }

    private static void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(level == 1 ? 18 : 14);
    }

    private static void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
    }
}
