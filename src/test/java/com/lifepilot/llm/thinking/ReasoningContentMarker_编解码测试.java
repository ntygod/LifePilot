package com.lifepilot.llm.thinking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReasoningContentMarker} 编解码契约测试。
 *
 * @author zsg
 * @since 2026-04-28
 */
@DisplayName("ReasoningContentMarker 编解码")
class ReasoningContentMarker_编解码测试 {

    @Test
    void 编码后能抽出原始_reasoning() {
        String reasoning = "我需要先调用搜索工具确认参数，再决定下一步";
        String encoded = ReasoningContentMarker.encode(null, reasoning);

        assertThat(ReasoningContentMarker.hasMarker(encoded)).isTrue();
        assertThat(ReasoningContentMarker.extract(encoded)).isEqualTo(reasoning);
    }

    @Test
    void 剥离_marker_后保留原_content() {
        String encoded = ReasoningContentMarker.encode("正文", "推理过程");

        String stripped = ReasoningContentMarker.stripMarker(encoded);
        assertThat(stripped).isEqualTo("正文");
    }

    @Test
    void reasoning_为_null_时不编码_marker() {
        // null = 非 thinking 模式（如 GPT-4o 普通响应），不需要 reasoning_content 字段
        String result = ReasoningContentMarker.encode("正文", null);
        assertThat(result).isEqualTo("正文");
        assertThat(ReasoningContentMarker.hasMarker(result)).isFalse();
    }

    @Test
    void reasoning_为空字符串_仍编码_marker_保留_thinking_语义() {
        // "" = thinking 模式但本次思考为空（DeepSeek 短响应），仍需编码 marker，
        // 下游 rewriter 抽出空 reasoning 注入 reasoning_content 字段满足多轮契约
        String result = ReasoningContentMarker.encode("正文", "");
        assertThat(ReasoningContentMarker.hasMarker(result)).isTrue();
        assertThat(ReasoningContentMarker.extract(result)).isEmpty();
        assertThat(ReasoningContentMarker.stripMarker(result)).isEqualTo("正文");
    }

    @Test
    void 原_content_为_null_时编码不抛异常() {
        String encoded = ReasoningContentMarker.encode(null, "推理");
        assertThat(ReasoningContentMarker.extract(encoded)).isEqualTo("推理");
        assertThat(ReasoningContentMarker.stripMarker(encoded)).isEmpty();
    }

    @Test
    void 跨行_reasoning_应能正确抽出() {
        String reasoning = "第一步：分析\n第二步：决策\n第三步：执行";
        String encoded = ReasoningContentMarker.encode("正文", reasoning);

        assertThat(ReasoningContentMarker.extract(encoded)).isEqualTo(reasoning);
    }

    @Test
    void 不含_marker_的_content_不被改动() {
        String plain = "纯文本无任何 marker";
        assertThat(ReasoningContentMarker.hasMarker(plain)).isFalse();
        assertThat(ReasoningContentMarker.extract(plain)).isNull();
        assertThat(ReasoningContentMarker.stripMarker(plain)).isEqualTo(plain);
    }

    @Test
    void marker_使用控制字符不与自然文本碰撞() {
        // 模型生成的自然文本绝大多数不会含 SOH 控制字符；本测试确保 marker 不会被普通文本误识别
        String plainWithSpecialPunct = "正文含特殊符号 ★ ※ ☆ — • 等";
        assertThat(ReasoningContentMarker.hasMarker(plainWithSpecialPunct)).isFalse();
    }
}
