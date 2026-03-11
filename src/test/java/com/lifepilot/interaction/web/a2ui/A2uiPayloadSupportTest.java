package com.lifepilot.interaction.web.a2ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class A2uiPayloadSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractContent_stripsA2uiTagsAndKeepsTopLevelSignal() {
        String raw = """
                先看这个面板：
                <a2ui>{"components":[{"id":"btn-1","type":"Button","properties":{"label":"继续"},"children":[],"signal":{"name":"next.step","payload":{"step":1}}}]}</a2ui>
                好了
                """;

        var parsed = A2uiPayloadSupport.extractContent(raw, objectMapper, 20);

        assertFalse(parsed.visibleText().contains("<a2ui>"));
        assertEquals("next.step", parsed.tree().components().getFirst().signal().name());
        assertEquals("继续", parsed.tree().components().getFirst().properties().get("label"));
    }

    @Test
    void extractContent_keepsOnlyTheFirstValidA2uiBlock() {
        String raw = """
                先看第一个面板
                <a2ui>{"components":[{"id":"card-1","type":"Card","properties":{"title":"第一块"},"children":[],"signal":null}]}</a2ui>
                中间说明
                <a2ui>{"components":[{"id":"card-2","type":"Card","properties":{"title":"第二块"},"children":[],"signal":null}]}</a2ui>
                结束
                """;

        var parsed = A2uiPayloadSupport.extractContent(raw, objectMapper, 20);

        assertNotNull(parsed.tree());
        assertEquals("card-1", parsed.tree().components().getFirst().id());
        assertFalse(parsed.visibleText().contains("<a2ui>"));
        assertTrue(parsed.visibleText().contains("先看第一个面板"));
        assertTrue(parsed.visibleText().contains("中间说明"));
        assertTrue(parsed.visibleText().contains("结束"));
    }

    @Test
    void deserializeStoredTree_acceptsOnlyStandardTreeShape() throws Exception {
        String json = """
                {"components":[{"id":"card-1","type":"Card","properties":{},"children":[],"signal":null}]}
                """;

        var tree = A2uiPayloadSupport.deserializeStoredTree(json, objectMapper);

        assertNotNull(tree);
        assertEquals("card-1", tree.components().getFirst().id());
    }

    @Test
    void deserializeStoredTree_rejectsLegacyArrayShape() throws Exception {
        String json = objectMapper.writeValueAsString(new Object[] {
                new A2uiComponentTree(java.util.List.of())
        });

        assertNull(A2uiPayloadSupport.deserializeStoredTree(json, objectMapper));
    }

    @Test
    void validator_rejectsLegacySignalPlacementUnknownTypesAndCycles() throws Exception {
        var tree = objectMapper.readValue("""
                {
                  "components": [
                    {"id":"a","type":"Card","properties":{"signal":{"name":"next.step","payload":{"step":1}}},"children":["b"],"signal":null},
                    {"id":"b","type":"Unknown","properties":{},"children":["a"],"signal":null}
                  ]
                }
                """, A2uiComponentTree.class);

        var result = A2uiComponentValidator.validate(tree, 10);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("顶层字段")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("未注册类型")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("循环引用")));
    }

    @Test
    void validator_rejectsTooDeepTrees() throws Exception {
        var tree = objectMapper.readValue("""
                {
                  "components": [
                    {"id":"n1","type":"Card","properties":{},"children":["n2"],"signal":null},
                    {"id":"n2","type":"Card","properties":{},"children":["n3"],"signal":null},
                    {"id":"n3","type":"Card","properties":{},"children":["n4"],"signal":null},
                    {"id":"n4","type":"Card","properties":{},"children":["n5"],"signal":null},
                    {"id":"n5","type":"Card","properties":{},"children":["n6"],"signal":null},
                    {"id":"n6","type":"Card","properties":{},"children":["n7"],"signal":null},
                    {"id":"n7","type":"Card","properties":{},"children":["n8"],"signal":null},
                    {"id":"n8","type":"Card","properties":{},"children":["n9"],"signal":null},
                    {"id":"n9","type":"Card","properties":{},"children":["n10"],"signal":null},
                    {"id":"n10","type":"Card","properties":{},"children":["n11"],"signal":null},
                    {"id":"n11","type":"Card","properties":{},"children":["n12"],"signal":null},
                    {"id":"n12","type":"Card","properties":{},"children":["n13"],"signal":null},
                    {"id":"n13","type":"Card","properties":{},"children":[],"signal":null}
                  ]
                }
                """, A2uiComponentTree.class);

        var result = A2uiComponentValidator.validate(tree, 20);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("层级超过限制")));
    }

    @Test
    void validator_rejectsDangerousImageUrlsAndOversizedTables() throws Exception {
        var tree = objectMapper.readValue("""
                {
                  "components": [
                    {
                      "id":"img-1",
                      "type":"Image",
                      "properties":{"src":"javascript:alert(1)","alt":"风险图片"},
                      "children":[],
                      "signal":null
                    },
                    {
                      "id":"table-1",
                      "type":"Table",
                      "properties":{
                        "columns":[
                          {"key":"c1","label":"列1"},{"key":"c2","label":"列2"},{"key":"c3","label":"列3"},
                          {"key":"c4","label":"列4"},{"key":"c5","label":"列5"},{"key":"c6","label":"列6"},
                          {"key":"c7","label":"列7"},{"key":"c8","label":"列8"},{"key":"c9","label":"列9"},
                          {"key":"c10","label":"列10"},{"key":"c11","label":"列11"},{"key":"c12","label":"列12"},
                          {"key":"c13","label":"列13"}
                        ],
                        "rows":[]
                      },
                      "children":[],
                      "signal":null
                    }
                  ]
                }
                """, A2uiComponentTree.class);

        var result = A2uiComponentValidator.validate(tree, 20);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Image.src")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("Table.columns 超过上限")));
    }

    @Test
    void validator_rejectsOutOfRangeProgress() throws Exception {
        var tree = objectMapper.readValue("""
                {
                  "components": [
                    {
                      "id":"progress-1",
                      "type":"Progress",
                      "properties":{"value":120,"label":"处理进度"},
                      "children":[],
                      "signal":null
                    }
                  ]
                }
                """, A2uiComponentTree.class);

        var result = A2uiComponentValidator.validate(tree, 10);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("0 到 100")));
    }

    @Test
    void validator_rejectsInvalidEnumValuesAndUnsupportedSignalHosts() throws Exception {
        var tree = objectMapper.readValue("""
                {
                  "components": [
                    {
                      "id":"text-1",
                      "type":"Text",
                      "properties":{"text":"标题","variant":"hero"},
                      "children":[],
                      "signal":null
                    },
                    {
                      "id":"card-1",
                      "type":"Card",
                      "properties":{"title":"卡片"},
                      "children":[],
                      "signal":{"name":"card.open","payload":{"id":"1"}}
                    }
                  ]
                }
                """, A2uiComponentTree.class);

        var result = A2uiComponentValidator.validate(tree, 10);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("variant")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("不支持 signal")));
    }
}
