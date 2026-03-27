package com.lifepilot.knowledge.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatastoreDocumentProjector 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class DatastoreDocumentProjectorTest {

    private final DatastoreDocumentProjector projector = new DatastoreDocumentProjector(new ObjectMapper());

    @Test
    void 默认投影_递归展开复杂结构并保留关键语义() {
        var projected = projector.project(collection(null), document("""
                {
                  "title": "星港回声",
                  "summary": "在坠落的环城里，林霁必须在记忆消散前找回母亲留下的导航图。",
                  "tags": ["科幻", "成长"],
                  "characters": [
                    {
                      "name": "林霁",
                      "role": "主角",
                      "traits": ["冷静", "固执"],
                      "goal": "找回母亲"
                    },
                    {
                      "name": "岑野",
                      "role": "向导",
                      "traits": ["敏锐", "多疑"],
                      "goal": "离开环城"
                    }
                  ],
                  "world": {
                    "rule": "潮汐能会吞噬记忆",
                    "factions": [
                      {"name": "港务署", "goal": "封锁环城"},
                      {"name": "潮汐会", "goal": "唤醒星门"}
                    ]
                  },
                  "chapters": [
                    {
                      "title": "第一章",
                      "hook": "林霁在坠落区发现残缺导航图",
                      "conflict": "她必须在失忆前逃离封锁线"
                    }
                  ]
                }
                """));

        assertThat(projected.fileName()).isEqualTo("小说素材 - 星港回声");
        assertThat(projected.filePath()).isEqualTo("datastore://ds-novel/doc-001");
        assertThat(projected.sourceRef())
                .containsEntry("collectionId", "ds-novel")
                .containsEntry("collectionName", "小说素材")
                .containsEntry("documentId", "doc-001");
        assertThat(projected.content())
                .contains("Collection: 小说素材")
                .contains("Title: 星港回声")
                .contains("Fields:")
                .contains("Summary: 在坠落的环城里")
                .contains("科幻, 成长")
                .contains("Characters:")
                .contains("林霁")
                .contains("潮汐会")
                .contains("第一章")
                .contains("导航图");
    }

    @Test
    void 投影配置_支持路径规则分段渲染和排除噪声字段() {
        String projectionConfigJson = """
                {
                  "titlePaths": ["book.title"],
                  "bodyPaths": ["book.pitch"],
                  "tagPaths": ["book.tags"],
                  "timePaths": ["book.updatedAt"],
                  "scalarPaths": ["stats.wordCount", "stats.status"],
                  "excludePaths": ["characters[*].embedding", "rawNotes"],
                  "includeRawJson": false,
                  "sections": [
                    {
                      "label": "角色卡",
                      "paths": ["characters[*]"],
                      "mode": "OBJECT_SUMMARY",
                      "maxItems": 3,
                      "includeFieldName": false
                    },
                    {
                      "label": "章节钩子",
                      "paths": ["chapters[*].hook"],
                      "mode": "LINES",
                      "maxItems": 4,
                      "includeFieldName": false
                    }
                  ]
                }
                """;

        var projected = projector.project(collection(projectionConfigJson), document("""
                {
                  "book": {
                    "title": "深海编年",
                    "pitch": "一支失联的潜航队在海底遗迹中寻找能够改写潮汐的装置。",
                    "tags": ["科幻", "悬疑"],
                    "updatedAt": "2026-03-01T08:30:00Z"
                  },
                  "characters": [
                    {
                      "name": "林霁",
                      "role": "队长",
                      "skill": "声呐定位",
                      "embedding": "noise-vector-1"
                    },
                    {
                      "name": "周沉",
                      "role": "工程师",
                      "skill": "机械修复",
                      "embedding": "noise-vector-2"
                    }
                  ],
                  "chapters": [
                    {"hook": "潜航器在废墟中收到求救信号", "draft": "内部草稿"},
                    {"hook": "队伍发现能篡改记忆的潮汐核心", "draft": "第二份草稿"}
                  ],
                  "stats": {
                    "wordCount": 12000,
                    "status": "draft"
                  },
                  "rawNotes": "这些噪声字段不应该进入检索文本"
                }
                """));

        assertThat(projected.fileName()).isEqualTo("小说素材 - 深海编年");
        assertThat(projected.content())
                .contains("Title: 深海编年")
                .contains("Body:\n一支失联的潜航队")
                .contains("12000")
                .contains("draft")
                .contains("角色卡:")
                .contains("章节钩子:")
                .contains("潜航器在废墟中收到求救信号")
                .contains("队伍发现能篡改记忆的潮汐核心")
                .doesNotContain("noise-vector")
                .doesNotContain("rawNotes")
                .doesNotContain("Raw JSON:");
    }

    @Test
    void 原始Json回退_按长度截断输出() {
        var projected = projector.project(collection("""
                {
                  "titlePaths": ["name"],
                  "bodyPaths": [],
                  "tagPaths": [],
                  "timePaths": [],
                  "rawJsonMaxLength": 80
                }
                """), document("""
                {
                  "name": "超长素材",
                  "notes": "0123456789012345678901234567890123456789012345678901234567890123456789",
                  "appendix": "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
                }
                """));

        assertThat(projected.content())
                .contains("Title: 超长素材")
                .contains("Raw JSON:")
                .contains("[truncated]");
    }

    private Collection collection(String projectionConfigJson) {
        return new Collection(
                "ds-novel",
                "小说素材",
                "用于测试的小说素材库",
                CollectionType.DOCUMENT,
                null,
                projectionConfigJson,
                null,
                "tester",
                "2026-03-27T10:00:00Z",
                "2026-03-27T10:00:00Z"
        );
    }

    private Document document(String dataJson) {
        return new Document(
                "doc-001",
                "ds-novel",
                dataJson,
                "2026-03-27T11:00:00Z",
                "2026-03-27T11:00:00Z",
                "2026-03-27T11:00:00Z"
        );
    }
}
