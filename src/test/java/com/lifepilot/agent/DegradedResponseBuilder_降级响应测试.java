package com.lifepilot.agent;

import com.lifepilot.agent.model.ReactStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DegradedResponseBuilder#extractRecentToolFailure} 单元测试。
 *
 * @author zsg
 * @since 2026-04-04
 */
class DegradedResponseBuilder_降级响应测试 {

    // ==================== extractRecentToolFailure ====================

    @Test
    void 空列表返回null() {
        // given
        List<ReactStep> steps = List.of();

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isNull();
    }

    @Test
    void 只有成功的Observation返回null() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("web-search", "网页搜索", true, "搜索结果", 100),
                new ReactStep.Observation("code-exec", "代码执行", true, "执行成功", 50)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isNull();
    }

    @Test
    void 只有Thought步骤返回null() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Thought("让我思考一下这个问题"),
                new ReactStep.Thought("需要调用工具来获取信息")
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isNull();
    }

    @Test
    void 有失败Observation时返回正确格式() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Thought("准备搜索"),
                new ReactStep.Observation("web-search", "网页搜索", false, "连接超时", 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isEqualTo("网页搜索: 连接超时");
    }

    @Test
    void toolId为llm的失败Observation被跳过() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("todo.create", "创建待办", false, "参数错误", 0),
                new ReactStep.Observation("llm", null, false, "模型调用失败", 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then — 跳过 llm，找到前一个失败的 todo.create
        assertThat(result).isEqualTo("创建待办: 参数错误");
    }

    @Test
    void 所有失败Observation均为llm时返回null() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("llm", null, false, "模型调用失败", 0),
                new ReactStep.Observation("llm", "大模型", false, "配额耗尽", 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isNull();
    }

    @Test
    void 输出超过200字符时截断() {
        // given
        String longOutput = "错".repeat(250);
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("code-exec", "代码执行", false, longOutput, 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isNotNull();
        // "代码执行: " 占 6 个字符（4 汉字 + 冒号 + 空格），加截断后的 200 字符输出
        assertThat(result).isEqualTo("代码执行: " + "错".repeat(200));
        assertThat(result).hasSize(6 + 200);
    }

    @Test
    void toolName为null时使用toolId() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("web-search", null, false, "DNS 解析失败", 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isEqualTo("web-search: DNS 解析失败");
    }

    @Test
    void 多个失败Observation取最后一个() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("web-search", "网页搜索", false, "第一次失败", 0),
                new ReactStep.Thought("重试一下"),
                new ReactStep.Observation("code-exec", "代码执行", false, "第二次失败", 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then — 从末尾往前找，第一个命中的是最后一个失败 Observation
        assertThat(result).isEqualTo("代码执行: 第二次失败");
    }

    @Test
    void 最后一个失败后有成功Observation时仍取最近的失败() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("web-search", "网页搜索", false, "超时", 0),
                new ReactStep.Observation("code-exec", "代码执行", true, "执行成功", 50)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then — 从末尾逆序找，跳过成功的，找到失败的 web-search
        assertThat(result).isEqualTo("网页搜索: 超时");
    }

    @Test
    void 输出为null时显示为空字符串() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("web-search", "网页搜索", false, null, 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isEqualTo("网页搜索: ");
    }

    @Test
    void 输出有前后空白时自动strip() {
        // given
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("web-search", "网页搜索", false, "  连接超时  \n", 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isEqualTo("网页搜索: 连接超时");
    }

    @Test
    void 混合多种步骤类型只匹配失败Observation() {
        // given
        List<ReactStep> steps = new ArrayList<>();
        steps.add(new ReactStep.Progress("正在处理..."));
        steps.add(new ReactStep.Thought("分析问题"));
        steps.add(new ReactStep.ToolCall("web-search", "网页搜索", "{}", 100));
        steps.add(new ReactStep.Observation("web-search", "网页搜索", true, "搜索结果", 200));
        steps.add(new ReactStep.Thought("需要执行代码"));
        steps.add(new ReactStep.ToolCall("code-exec", "代码执行", "{\"code\":\"print(1)\"}", 50));
        steps.add(new ReactStep.Observation("code-exec", "代码执行", false, "运行时错误", 0));
        steps.add(new ReactStep.Answer("抱歉，执行出错了"));

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isEqualTo("代码执行: 运行时错误");
    }

    @Test
    void 输出恰好200字符不截断() {
        // given
        String exactOutput = "X".repeat(200);
        List<ReactStep> steps = List.of(
                new ReactStep.Observation("tool-a", "工具A", false, exactOutput, 0)
        );

        // when
        String result = DegradedResponseBuilder.extractRecentToolFailure(steps);

        // then
        assertThat(result).isEqualTo("工具A: " + "X".repeat(200));
    }
}
