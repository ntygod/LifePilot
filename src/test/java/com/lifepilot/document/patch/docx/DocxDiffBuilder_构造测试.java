package com.lifepilot.document.patch.docx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocxDiffBuilder 构造测试 —— 覆盖 4 种 op 的 segments 生成 + summary 摘要。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocxDiffBuilder_构造测试 {

    private final DocxDiffBuilder builder = new DocxDiffBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("replace_text 生成 4 段 segments")
    void replace_text生成四段segments() throws Exception {
        var op = new ReplaceTextOp("前缀", "旧文", "后缀", "新文", "测试");
        var applied = new AppliedOp(op, new ParagraphRunRange(3, 0, 2, 0, 4));

        String json = builder.build("doc-1", 0, 1, List.of(applied));

        JsonNode root = mapper.readTree(json);
        assertThat(root.get("documentId").asText()).isEqualTo("doc-1");
        assertThat(root.get("toVersion").asInt()).isEqualTo(1);
        JsonNode change = root.get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("replace_text");
        assertThat(change.get("paragraph_index").asInt()).isEqualTo(3);
        JsonNode segs = change.get("segments");
        assertThat(segs).hasSize(4);
        assertThat(segs.get(0).get("type").asText()).isEqualTo("keep");
        assertThat(segs.get(1).get("type").asText()).isEqualTo("delete");
        assertThat(segs.get(1).get("text").asText()).isEqualTo("旧文");
        assertThat(segs.get(2).get("type").asText()).isEqualTo("insert");
        assertThat(segs.get(2).get("text").asText()).isEqualTo("新文");
    }

    @Test
    @DisplayName("insert_paragraph_after 生成 insert segment")
    void insert_paragraph_after生成insert() throws Exception {
        var op = new InsertParagraphAfterOp("锚点段",
                List.of(new NewParagraph("新段 A", "Normal"), new NewParagraph("新段 B", "Normal")),
                "补充");
        var applied = new AppliedOp(op, new ParagraphRunRange(2, -1, -1, -1, -1));

        String json = builder.build("doc-2", 0, 1, List.of(applied));

        JsonNode change = mapper.readTree(json).get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("insert_paragraph_after");
        JsonNode segs = change.get("segments");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).get("type").asText()).isEqualTo("insert");
        assertThat(segs.get(0).get("text").asText()).contains("新段 A").contains("新段 B");
    }

    @Test
    @DisplayName("delete_paragraph 生成 delete segment")
    void delete_paragraph生成delete() throws Exception {
        var op = new DeleteParagraphOp("要删的段", "冗余");
        var applied = new AppliedOp(op, new ParagraphRunRange(5, -1, -1, -1, -1));

        String json = builder.build("doc-3", 1, 2, List.of(applied));

        JsonNode change = mapper.readTree(json).get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("delete_paragraph");
        assertThat(change.get("segments").get(0).get("type").asText()).isEqualTo("delete");
        assertThat(change.get("segments").get(0).get("text").asText()).isEqualTo("要删的段");
    }

    @Test
    @DisplayName("add_table_row 生成拼接的 insert segment")
    void add_table_row生成insert() throws Exception {
        var op = new AddTableRowOp("产品名称", "end", List.of("a", "b", "c"), null);
        var applied = new AppliedOp(op, null);

        String json = builder.build("doc-4", 0, 1, List.of(applied));

        JsonNode change = mapper.readTree(json).get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("add_table_row");
        assertThat(change.get("segments").get(0).get("text").asText()).isEqualTo("a | b | c");
    }

    @Test
    @DisplayName("summary 汇总数量与类型")
    void summary汇总数量与类型() throws Exception {
        var a = new AppliedOp(new ReplaceTextOp("", "t1", "", "n1", null), new ParagraphRunRange(0, 0, 0, 0, 2));
        var b = new AppliedOp(new ReplaceTextOp("", "t2", "", "n2", null), new ParagraphRunRange(0, 0, 0, 0, 2));
        var c = new AppliedOp(new DeleteParagraphOp("x", null), new ParagraphRunRange(1, -1, -1, -1, -1));

        String summary = builder.summarize(List.of(a, b, c));

        assertThat(summary).contains("共 3 处修改").contains("replace_text x2").contains("delete_paragraph x1");
    }
}
