package com.lifepilot.sync.connector.obsidian;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YamlFrontmatterParser 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class YamlFrontmatterParserTest {

    // ==================== parse 方法测试 ====================

    @Test
    void 解析基本frontmatter() {
        var content = """
                ---
                type: todo
                title: 买牛奶
                priority: HIGH
                ---
                任务描述正文
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("todo", result.frontmatter().get("type"));
        assertEquals("买牛奶", result.frontmatter().get("title"));
        assertEquals("HIGH", result.frontmatter().get("priority"));
        assertEquals("任务描述正文", result.body());
    }

    @Test
    void 解析双引号包裹的值() {
        var content = """
                ---
                title: "带引号的标题"
                dueDate: "2026-03-01"
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("带引号的标题", result.frontmatter().get("title"));
        assertEquals("2026-03-01", result.frontmatter().get("dueDate"));
    }

    @Test
    void 解析单引号包裹的值() {
        var content = """
                ---
                title: '单引号标题'
                note: 'hello world'
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("单引号标题", result.frontmatter().get("title"));
        assertEquals("hello world", result.frontmatter().get("note"));
    }

    @Test
    void 解析列表值() {
        var content = """
                ---
                tags: [购物, 日常, 重要]
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        var tags = result.frontmatter().get("tags");
        assertInstanceOf(List.class, tags);
        assertEquals(List.of("购物", "日常", "重要"), tags);
    }

    @Test
    void 解析空frontmatter() {
        var content = """
                ---
                ---
                正文内容
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertTrue(result.frontmatter().isEmpty());
        assertEquals("正文内容", result.body());
    }

    @Test
    void 无frontmatter分隔符_返回完整内容作为正文() {
        var content = "这是一段普通文本\n没有frontmatter";

        var result = YamlFrontmatterParser.parse(content);

        assertTrue(result.frontmatter().isEmpty());
        assertEquals("这是一段普通文本\n没有frontmatter", result.body());
    }

    @Test
    void 仅有frontmatter_无正文() {
        var content = """
                ---
                type: schedule
                title: 开会
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("schedule", result.frontmatter().get("type"));
        assertEquals("开会", result.frontmatter().get("title"));
        assertEquals("", result.body());
    }

    @Test
    void 值包含冒号() {
        var content = """
                ---
                title: "时间: 下午3点"
                url: "https://example.com"
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("时间: 下午3点", result.frontmatter().get("title"));
        assertEquals("https://example.com", result.frontmatter().get("url"));
    }

    @Test
    void 去除行内注释() {
        var content = """
                ---
                type: todo # 待办类型
                title: 买牛奶 # 重要
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("todo", result.frontmatter().get("type"));
        assertEquals("买牛奶", result.frontmatter().get("title"));
    }

    @Test
    void 空值跳过() {
        var content = """
                ---
                type: todo
                description:
                title: 测试
                ---
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("todo", result.frontmatter().get("type"));
        assertEquals("测试", result.frontmatter().get("title"));
        assertFalse(result.frontmatter().containsKey("description"));
    }

    @Test
    void null输入_返回空结果() {
        var result = YamlFrontmatterParser.parse(null);

        assertTrue(result.frontmatter().isEmpty());
        assertEquals("", result.body());
    }

    @Test
    void 空字符串输入_返回空结果() {
        var result = YamlFrontmatterParser.parse("");

        assertTrue(result.frontmatter().isEmpty());
        assertEquals("", result.body());
    }

    @Test
    void 多行正文内容() {
        var content = """
                ---
                type: todo
                ---
                第一行正文
                第二行正文
                第三行正文
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertEquals("todo", result.frontmatter().get("type"));
        assertTrue(result.body().contains("第一行正文"));
        assertTrue(result.body().contains("第二行正文"));
        assertTrue(result.body().contains("第三行正文"));
    }

    @Test
    void 无结束分隔符_视为无frontmatter() {
        var content = """
                ---
                type: todo
                title: 测试
                """;

        var result = YamlFrontmatterParser.parse(content);

        assertTrue(result.frontmatter().isEmpty());
    }

    // ==================== format 方法测试 ====================

    @Test
    void 格式化基本键值对() {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("type", "todo");
        frontmatter.put("title", "买牛奶");

        var formatted = YamlFrontmatterParser.format(frontmatter, "正文内容");

        assertTrue(formatted.startsWith("---\n"));
        assertTrue(formatted.contains("type: todo\n"));
        assertTrue(formatted.contains("title: 买牛奶\n"));
        assertTrue(formatted.contains("---\n"));
        assertTrue(formatted.contains("正文内容"));
    }

    @Test
    void 格式化列表值() {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("tags", List.of("购物", "日常"));

        var formatted = YamlFrontmatterParser.format(frontmatter, "");

        assertTrue(formatted.contains("tags: [购物, 日常]\n"));
    }

    @Test
    void 格式化包含特殊字符的值_自动加引号() {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("title", "时间: 下午3点");

        var formatted = YamlFrontmatterParser.format(frontmatter, "");

        assertTrue(formatted.contains("title: \"时间: 下午3点\"\n"));
    }

    @Test
    void 格式化空正文() {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("type", "todo");

        var formatted = YamlFrontmatterParser.format(frontmatter, "");

        assertTrue(formatted.endsWith("---\n"));
        assertFalse(formatted.contains("\n\n"));
    }

    @Test
    void 格式化null正文() {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("type", "todo");

        var formatted = YamlFrontmatterParser.format(frontmatter, null);

        assertTrue(formatted.endsWith("---\n"));
    }

    // ==================== 往返测试 ====================

    @Test
    void format后parse_往返一致性() {
        var originalFrontmatter = new LinkedHashMap<String, Object>();
        originalFrontmatter.put("type", "todo");
        originalFrontmatter.put("title", "买牛奶");
        originalFrontmatter.put("priority", "HIGH");
        originalFrontmatter.put("tags", List.of("购物", "日常"));
        var originalBody = "任务描述正文";

        var formatted = YamlFrontmatterParser.format(originalFrontmatter, originalBody);
        var parsed = YamlFrontmatterParser.parse(formatted);

        assertEquals("todo", parsed.frontmatter().get("type"));
        assertEquals("买牛奶", parsed.frontmatter().get("title"));
        assertEquals("HIGH", parsed.frontmatter().get("priority"));
        assertEquals(List.of("购物", "日常"), parsed.frontmatter().get("tags"));
        assertEquals(originalBody, parsed.body());
    }

    @Test
    void 完整Obsidian格式_往返一致性() {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("type", "todo");
        frontmatter.put("title", "买牛奶");
        frontmatter.put("priority", "HIGH");
        frontmatter.put("status", "PENDING");
        frontmatter.put("dueDate", "2026-03-01");
        frontmatter.put("tags", List.of("购物", "日常"));
        frontmatter.put("zhiwei_id", "uuid-xxx");
        var body = "任务描述正文";

        var formatted = YamlFrontmatterParser.format(frontmatter, body);
        var parsed = YamlFrontmatterParser.parse(formatted);

        assertEquals(frontmatter.size(), parsed.frontmatter().size());
        for (var entry : frontmatter.entrySet()) {
            assertEquals(entry.getValue(), parsed.frontmatter().get(entry.getKey()),
                    "字段 " + entry.getKey() + " 不一致");
        }
        assertEquals(body, parsed.body());
    }
}
