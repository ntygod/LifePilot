package com.lifepilot.agent.model;

import com.lifepilot.agent.suspend.model.ResumePayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReactStepSerializer 单元测试。
 *
 * @author zsg
 * @since 2026-03-19
 */
class ReactStepSerializerTest {

    @Test
    void serialize_空列表_返回空() {
        assertEquals(List.of(), ReactStepSerializer.serialize(List.of()));
        assertEquals(List.of(), ReactStepSerializer.serialize(null));
    }

    @Test
    void serialize_Thought步骤_包含type和content() {
        var steps = List.<ReactStep>of(new ReactStep.Thought("这是一段思考"));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals(1, result.size());
        assertEquals("THOUGHT", result.getFirst().get("type"));
        assertEquals(0, result.getFirst().get("index"));
        assertEquals("这是一段思考", result.getFirst().get("content"));
    }

    @Test
    void serialize_ToolCall步骤_inputSummary为自然语言() {
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall("web.search", "网页搜索", "{\"query\":\"test\"}", 150));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("TOOL_CALL", result.getFirst().get("type"));
        assertEquals("web.search", result.getFirst().get("toolId"));
        assertEquals("网页搜索", result.getFirst().get("toolName"));
        assertEquals("搜索「test」", result.getFirst().get("inputSummary"));
        assertEquals(150L, result.getFirst().get("latencyMs"));
    }

    @Test
    void serialize_ToolCall步骤_有callId时应输出callId() {
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall("web.search", "网页搜索", "{\"query\":\"test\"}", 150, "call-123"));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("call-123", result.getFirst().get("callId"));
    }

    @Test
    void serialize_ToolCall步骤_toolName为null时不包含该字段() {
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall("unknown-tool", null, "{}", 50));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("unknown-tool", result.getFirst().get("toolId"));
        assertFalse(result.getFirst().containsKey("toolName"));
    }

    @Test
    void serialize_Observation步骤_outputSummary为自然语言() {
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("web.search", "网页搜索", true,
                        "{\"results\":[{\"title\":\"A\",\"url\":\"https://a.com\"}],\"count\":1}", 42));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("OBSERVATION", result.getFirst().get("type"));
        assertEquals("web.search", result.getFirst().get("toolId"));
        assertEquals(true, result.getFirst().get("success"));
        assertEquals("找到 1 条结果", result.getFirst().get("outputSummary"));
        assertNotNull(result.getFirst().get("outputDetail"));
    }

    @Test
    void serialize_Observation步骤_有callId时应输出callId() {
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("web.search", "网页搜索", true, "搜索结果内容", 42, "call-456"));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("call-456", result.getFirst().get("callId"));
    }

    @Test
    void serialize_Answer步骤_包含content() {
        var steps = List.<ReactStep>of(new ReactStep.Answer("最终回答"));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("ANSWER", result.getFirst().get("type"));
        assertEquals("最终回答", result.getFirst().get("content"));
    }

    @Test
    void serialize_Suspend步骤_包含reason和suspendedAt() {
        var now = Instant.now();
        var steps = List.<ReactStep>of(
                new ReactStep.Suspend(
                        new SuspendReason.UserConfirmation("tool-1", "{}", "HIGH", "confirm-1"),
                        now, 3));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("SUSPEND", result.getFirst().get("type"));
        assertNotNull(result.getFirst().get("reason"));
        assertEquals(now.toString(), result.getFirst().get("suspendedAt"));
    }

    @Test
    void serialize_Resume步骤_包含resumedAt和suspendDurationMs() {
        var now = Instant.now();
        var steps = List.<ReactStep>of(
                new ReactStep.Resume(
                        new ResumePayload.UserDecision("confirm-1", true, "已确认"),
                        now, Duration.ofSeconds(30)));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("RESUME", result.getFirst().get("type"));
        assertEquals(now.toString(), result.getFirst().get("resumedAt"));
        assertEquals(30000L, result.getFirst().get("suspendDurationMs"));
    }

    @Test
    void truncate_超长Thought_截断到500字符() {
        String longContent = "A".repeat(600);
        var steps = List.<ReactStep>of(new ReactStep.Thought(longContent));
        var result = ReactStepSerializer.serialize(steps);

        String content = (String) result.getFirst().get("content");
        assertEquals(501, content.length()); // 500 + "…"
        assertTrue(content.endsWith("…"));
    }

    @Test
    void truncate_null文本_返回空字符串() {
        assertEquals("", ReactStepSerializer.truncate(null, 100));
    }

    @Test
    void serialize_完整ReAct循环_索引递增() {
        var steps = List.<ReactStep>of(
                new ReactStep.Thought("思考问题"),
                new ReactStep.ToolCall("web.search", "搜索", "{\"query\":\"test\"}", 100),
                new ReactStep.Observation("web.search", "搜索", true,
                        "{\"results\":[{\"title\":\"R\",\"url\":\"https://r.com\"}]}", 20),
                new ReactStep.Answer("最终回答")
        );
        var result = ReactStepSerializer.serialize(steps);

        assertEquals(4, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(i, result.get(i).get("index"));
        }
        assertEquals("THOUGHT", result.get(0).get("type"));
        assertEquals("TOOL_CALL", result.get(1).get("type"));
        assertEquals("OBSERVATION", result.get(2).get("type"));
        assertEquals("ANSWER", result.get(3).get("type"));
    }

    // ─── summarizeInput 测试 ───

    @Test
    void summarizeInput_webSearch_提取query() {
        assertEquals("搜索「Java 并发编程」",
                ReactStepSerializer.summarizeInput("web.search", "{\"query\":\"Java 并发编程\",\"maxResults\":10}"));
    }

    @Test
    void summarizeInput_fileRead_提取path() {
        assertEquals("读取 /src/main/java/App.java",
                ReactStepSerializer.summarizeInput("file.read", "{\"path\":\"/src/main/java/App.java\"}"));
    }

    @Test
    void summarizeInput_fileEdit_提取path和操作数() {
        assertEquals("编辑 /src/App.vue（2 处修改）",
                ReactStepSerializer.summarizeInput("file.edit",
                        "{\"path\":\"/src/App.vue\",\"operations\":[{\"type\":\"replace\"},{\"type\":\"insert\"}]}"));
    }

    @Test
    void summarizeInput_shellExec_提取command() {
        assertEquals("执行 `npm install`",
                ReactStepSerializer.summarizeInput("shell.exec", "{\"command\":\"npm install\"}"));
    }

    @Test
    void summarizeInput_codeExecute_提取language() {
        assertEquals("执行 python 代码",
                ReactStepSerializer.summarizeInput("code.execute", "{\"code\":\"print(1)\",\"language\":\"python\"}"));
    }

    @Test
    void summarizeInput_datastore_按action分派() {
        assertEquals("创建集合「文案库」",
                ReactStepSerializer.summarizeInput("datastore",
                        "{\"action\":\"create-collection\",\"name\":\"文案库\",\"type\":\"DOCUMENT\"}"));
        assertEquals("查询「文案库」",
                ReactStepSerializer.summarizeInput("datastore",
                        "{\"action\":\"query\",\"collectionName\":\"文案库\"}"));
    }

    @Test
    void summarizeInput_memory_按action分派() {
        assertEquals("搜索记忆「用户偏好」",
                ReactStepSerializer.summarizeInput("memory",
                        "{\"action\":\"search\",\"query\":\"用户偏好\"}"));
        assertEquals("创建记忆「小明」",
                ReactStepSerializer.summarizeInput("memory",
                        "{\"action\":\"create\",\"name\":\"小明\",\"entityType\":\"PERSON\"}"));
    }

    @Test
    void summarizeInput_gitQuery_按action分派() {
        assertEquals("查看 Git 状态",
                ReactStepSerializer.summarizeInput("git.query", "{\"action\":\"status\"}"));
        assertEquals("查看 Git 日志",
                ReactStepSerializer.summarizeInput("git.query", "{\"action\":\"log\",\"count\":5}"));
    }

    @Test
    void summarizeInput_browser_按action分派() {
        assertEquals("截取页面截图",
                ReactStepSerializer.summarizeInput("browser", "{\"action\":\"screenshot\"}"));
    }

    @Test
    void summarizeInput_未知工具_通用提取() {
        assertEquals("搜索「hello」",
                ReactStepSerializer.summarizeInput("mcp.custom", "{\"query\":\"hello\"}"));
    }

    @Test
    void summarizeInput_畸形JSON_降级截断() {
        String broken = "not json at all, just a plain string input for some tool";
        String result = ReactStepSerializer.summarizeInput("unknown", broken);
        assertNotNull(result);
        assertTrue(result.length() <= 121); // 120 + "…"
    }

    // ─── summarizeOutput 测试 ───

    @Test
    void summarizeOutput_失败_提取错误信息() {
        assertEquals("连接超时",
                ReactStepSerializer.summarizeOutput("web.search",
                        "{\"error\":\"连接超时\"}", false));
    }

    @Test
    void summarizeOutput_失败_纯文本错误() {
        assertEquals("Tool execution failed: timeout",
                ReactStepSerializer.summarizeOutput("unknown",
                        "Tool execution failed: timeout", false));
    }

    @Test
    void summarizeOutput_fileRead_显示行数() {
        assertEquals("读取了 150 行",
                ReactStepSerializer.summarizeOutput("file.read",
                        "{\"content\":\"...\",\"lineCount\":150,\"path\":\"/a.java\"}", true));
    }

    @Test
    void summarizeOutput_shellExec_显示退出码() {
        assertEquals("退出码 0",
                ReactStepSerializer.summarizeOutput("shell.exec",
                        "{\"exitCode\":0,\"stdout\":\"OK\"}", true));
    }

    @Test
    void summarizeOutput_未知工具_返回操作成功() {
        assertEquals("操作成功",
                ReactStepSerializer.summarizeOutput("mcp.custom", "{\"some\":\"data\"}", true));
    }

    // ─── extractOutputDetail 测试 ───

    @Test
    void extractOutputDetail_webSearch_格式化结果列表() {
        String output = "{\"results\":[{\"title\":\"标题A\",\"url\":\"https://a.com\",\"snippet\":\"摘要A\"}," +
                "{\"title\":\"标题B\",\"url\":\"https://b.com\"}]}";
        String detail = ReactStepSerializer.extractOutputDetail("web.search", output, true);
        assertNotNull(detail);
        assertTrue(detail.contains("1. 标题A"));
        assertTrue(detail.contains("https://a.com"));
        assertTrue(detail.contains("2. 标题B"));
    }

    @Test
    void extractOutputDetail_shellExec_截断stdout() {
        String output = "{\"exitCode\":0,\"stdout\":\"" + "x".repeat(100) + "\"}";
        String detail = ReactStepSerializer.extractOutputDetail("shell.exec", output, true);
        assertNotNull(detail);
        assertTrue(detail.contains("x"));
    }

    @Test
    void extractOutputDetail_失败_完整错误信息() {
        String detail = ReactStepSerializer.extractOutputDetail("datastore",
                "{\"error\":\"创建集合失败: 非法字段名\"}", false);
        assertNotNull(detail);
        assertTrue(detail.contains("非法字段名"));
    }

    @Test
    void extractOutputDetail_无有效内容_返回null() {
        assertNull(ReactStepSerializer.extractOutputDetail("notify", "{\"success\":true}", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serialize_browser工具_observation含output原始字段() {
        String output = "{\"url\":\"https://example.com\",\"title\":\"示例\","
                + "\"screenshot\":\"AAAA\","
                + "\"elements\":[{\"index\":0,\"tag\":\"a\",\"text\":\"首页\"}],"
                + "\"total\":1,\"truncated\":false}";
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("browser", "浏览器", true, output, 42));
        var result = ReactStepSerializer.serialize(steps);

        // outputSummary / outputDetail 原路径保留
        assertEquals("操作成功", result.getFirst().get("outputSummary"));

        // browser 工具追加结构化 output
        assertTrue(result.getFirst().containsKey("output"), "browser observation 应包含 output 字段");
        var parsed = (java.util.Map<String, Object>) result.getFirst().get("output");
        assertEquals("https://example.com", parsed.get("url"));
        assertEquals("示例", parsed.get("title"));
        assertEquals("AAAA", parsed.get("screenshot"));
        assertEquals(false, parsed.get("truncated"));
        var elements = (List<java.util.Map<String, Object>>) parsed.get("elements");
        assertEquals(1, elements.size());
        assertEquals("a", elements.getFirst().get("tag"));
    }

    @Test
    void serialize_browser点分子工具_observation含output原始字段() {
        // P2 后如果 browser 工具拆分为 browser.navigate / browser.click 等 ID，
        // isBrowserTool 应继续覆盖 "browser." 前缀
        String output = "{\"url\":\"https://a.com\",\"title\":\"A\"}";
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("browser.navigate", "浏览器导航", true, output, 10));
        var result = ReactStepSerializer.serialize(steps);

        assertTrue(result.getFirst().containsKey("output"),
                "browser.* 子工具 observation 应包含 output 字段");
    }

    @Test
    void serialize_非browser工具_observation不含output字段() {
        // 避免给其它工具引入额外载荷，保持序列化精简
        String output = "{\"exitCode\":0,\"stdout\":\"hello\"}";
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("shell.exec", "Shell 执行", true, output, 50));
        var result = ReactStepSerializer.serialize(steps);

        assertFalse(result.getFirst().containsKey("output"),
                "非 browser 工具 observation 不应携带 output 字段");
    }

    @Test
    void serialize_browser工具_非法JSON输出不崩溃() {
        // 上游工具偶发返回纯文本时不应让 reactSteps 序列化抛异常
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("browser", "浏览器", false, "plain text error", 5));
        var result = ReactStepSerializer.serialize(steps);

        assertFalse(result.getFirst().containsKey("output"),
                "非 JSON 输出时应静默跳过 output 字段");
    }

    @Test
    void serialize_browser工具_空输出不崩溃() {
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("browser", "浏览器", true, "", 1));
        var result = ReactStepSerializer.serialize(steps);

        assertFalse(result.getFirst().containsKey("output"));
    }
}
