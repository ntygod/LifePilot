package com.lifepilot.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReactStepSerializer#extractGeneratedFilePath 单元测试。
 *
 * <p>覆盖文件工具路径提取的全部分支：成功提取、toolId 过滤、失败观察、
 * 空输出、非 JSON 输出、缺失 path 字段等。</p>
 *
 * @author zsg
 * @since 2026-04-04
 */
class ReactStepSerializer_文件路径提取测试 {

    // ==================== 成功提取路径 ====================

    @Test
    void 成功提取_file_write工具的Unix路径() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "{\"path\":\"/home/user/docs/report.md\",\"bytes\":1024}", 10);
        assertEquals("/home/user/docs/report.md",
                ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void 成功提取_file_edit工具的路径() {
        var obs = new ReactStep.Observation(
                "file.edit", "编辑文件", true,
                "{\"path\":\"/tmp/config.yml\"}", 5);
        assertEquals("/tmp/config.yml",
                ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void 成功提取_file_manage工具的路径() {
        var obs = new ReactStep.Observation(
                "file.manage", "文件管理", true,
                "{\"path\":\"/var/data/output.csv\",\"action\":\"create\"}", 8);
        assertEquals("/var/data/output.csv",
                ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void 成功提取_Windows路径_含反斜杠转义() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "{\"path\":\"D:\\\\Users\\\\test\\\\file.md\"}", 10);
        assertEquals("D:\\Users\\test\\file.md",
                ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void 成功提取_路径中含中文() {
        var obs = new ReactStep.Observation(
                "file.write", null, true,
                "{\"path\":\"/home/用户/文档/报告.md\"}", 12);
        assertEquals("/home/用户/文档/报告.md",
                ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    // ==================== toolId 不匹配 → null ====================

    @Test
    void 非文件工具_web_search_返回null() {
        var obs = new ReactStep.Observation(
                "web.search", "网页搜索", true,
                "{\"path\":\"/some/path\"}", 20);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void 非文件工具_todo_create_返回null() {
        var obs = new ReactStep.Observation(
                "todo.create", "创建待办", true,
                "{\"path\":\"/some/path\"}", 15);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    // ==================== 失败的观察 → null ====================

    @Test
    void 失败的observation_即使是文件工具也返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", false,
                "{\"path\":\"/home/user/file.txt\"}", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    // ==================== output 为 null 或空 → null ====================

    @Test
    void output为null_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true, null, 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void output为空字符串_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true, "", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void output为纯空白_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true, "   \t\n  ", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    // ==================== output 无 path 字段 → null ====================

    @Test
    void JSON中无path字段_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "{\"bytes\":1024,\"status\":\"ok\"}", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void path字段为非文本类型_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "{\"path\":12345}", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void path字段为null值_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "{\"path\":null}", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    // ==================== output 非 JSON → null ====================

    @Test
    void output为纯文本_非JSON_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "文件写入成功", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }

    @Test
    void output为残缺JSON_返回null() {
        var obs = new ReactStep.Observation(
                "file.write", "写入文件", true,
                "{\"path\":\"/broken", 10);
        assertNull(ReactStepSerializer.extractGeneratedFilePath(obs));
    }
}
