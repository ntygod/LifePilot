package com.lifepilot.interaction.web.a2ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser.Segment;
import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser.Segment.A2uiSegment;
import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser.Segment.TextSegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamingA2uiParser 单元测试。
 *
 * @author zsg
 * @since 2026-03-11
 */
class StreamingA2uiParserTest {

    private StreamingA2uiParser parser;

    @BeforeEach
    void setUp() {
        parser = new StreamingA2uiParser();
    }

    // ---- 辅助方法 ----

    /**
     * 将所有 token 逐个 feed 并收集所有段，最后 flush。
     */
    private List<Segment> feedAllAndFlush(String... tokens) {
        var segments = new ArrayList<Segment>();
        for (String token : tokens) {
            segments.addAll(parser.feed(token));
        }
        segments.addAll(parser.flush());
        return segments;
    }

    private String collectText(List<Segment> segments) {
        var sb = new StringBuilder();
        for (var seg : segments) {
            if (seg instanceof TextSegment(var text)) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private List<String> collectA2uiJsons(List<Segment> segments) {
        return segments.stream()
                .filter(s -> s instanceof A2uiSegment)
                .map(s -> ((A2uiSegment) s).json())
                .toList();
    }

    // ---- 测试用例 ----

    @Test
    void 纯文本输入_全部输出为TextSegment() {
        var segments = feedAllAndFlush("Hello, world!");
        assertEquals("Hello, world!", collectText(segments));
        assertTrue(collectA2uiJsons(segments).isEmpty());
    }

    @Test
    void 单个完整A2UI块_输出一个A2uiSegment() {
        String json = "{\"components\":[]}";
        var segments = feedAllAndFlush("<a2ui>" + json + "</a2ui>");
        assertEquals(1, collectA2uiJsons(segments).size());
        assertEquals(json, collectA2uiJsons(segments).getFirst());
        assertTrue(collectText(segments).isEmpty());
    }

    @Test
    void 文本加A2UI块加文本_正确分离() {
        String input = "前面的文本<a2ui>{\"key\":\"value\"}</a2ui>后面的文本";
        var segments = feedAllAndFlush(input);
        assertEquals("前面的文本后面的文本", collectText(segments));
        assertEquals(List.of("{\"key\":\"value\"}"), collectA2uiJsons(segments));
    }

    @Test
    void 标记在token边界断开_正确拼接() {
        // <a2ui> 在 "<a2u" + "i>" 处断开
        var segments = feedAllAndFlush("text<a2u", "i>{\"ok\":true}</a2ui>end");
        assertEquals("textend", collectText(segments));
        assertEquals(List.of("{\"ok\":true}"), collectA2uiJsons(segments));
    }

    @Test
    void 闭标记在token边界断开_正确拼接() {
        // </a2ui> 在 "</a2u" + "i>" 处断开
        var segments = feedAllAndFlush("<a2ui>content</a2u", "i>after");
        assertEquals("after", collectText(segments));
        assertEquals(List.of("content"), collectA2uiJsons(segments));
    }

    @Test
    void 未闭合标记_flush时作为文本输出() {
        var segments = feedAllAndFlush("before<a2ui>unclosed content");
        assertEquals("before<a2ui>unclosed content", collectText(segments));
        assertTrue(collectA2uiJsons(segments).isEmpty());
    }

    @Test
    void 多个A2UI块_多个A2uiSegment() {
        String input = "text1<a2ui>json1</a2ui>middle<a2ui>json2</a2ui>text2";
        var segments = feedAllAndFlush(input);
        assertEquals("text1middletext2", collectText(segments));
        assertEquals(List.of("json1", "json2"), collectA2uiJsons(segments));
    }

    @Test
    void 不匹配的开标签_回退为文本() {
        // "<a2x" 不匹配 "<a2ui>"，应回退为文本
        var segments = feedAllAndFlush("before<a2x>after");
        assertEquals("before<a2x>after", collectText(segments));
        assertTrue(collectA2uiJsons(segments).isEmpty());
    }

    @Test
    void 内容中包含小于号_不影响解析() {
        // 内容中有 '<' 但不是 </a2ui> 的开始
        var segments = feedAllAndFlush("<a2ui>{\"a\":1<2}</a2ui>");
        // '<' 后面是 '2'，不匹配 </a2ui>，回退到 content
        assertEquals(List.of("{\"a\":1<2}"), collectA2uiJsons(segments));
    }

    @Test
    void 逐字符feed_正确解析() {
        String input = "hi<a2ui>data</a2ui>bye";
        var segments = new ArrayList<Segment>();
        for (char c : input.toCharArray()) {
            segments.addAll(parser.feed(String.valueOf(c)));
        }
        segments.addAll(parser.flush());
        assertEquals("hibye", collectText(segments));
        assertEquals(List.of("data"), collectA2uiJsons(segments));
    }

    @Test
    void reset后可复用() {
        feedAllAndFlush("<a2ui>first</a2ui>");
        parser.reset();
        var segments = feedAllAndFlush("<a2ui>second</a2ui>");
        assertEquals(List.of("second"), collectA2uiJsons(segments));
    }

    @Test
    void null和空token_返回空列表() {
        assertEquals(List.of(), parser.feed(null));
        assertEquals(List.of(), parser.feed(""));
    }

    @Test
    void 部分开标签在流末尾_flush回退为文本() {
        var segments = feedAllAndFlush("text<a2u");
        assertEquals("text<a2u", collectText(segments));
        assertTrue(collectA2uiJsons(segments).isEmpty());
    }

    @Test
    void 部分闭标签在流末尾_flush回退为文本() {
        var segments = feedAllAndFlush("<a2ui>content</a2u");
        // 未闭合的块，整体回退为文本
        assertEquals("<a2ui>content</a2u", collectText(segments));
        assertTrue(collectA2uiJsons(segments).isEmpty());
    }

    @Test
    void 内容中包含不匹配的闭标签前缀_回退到内容() {
        // 内容中有 "</a2x" 不匹配 "</a2ui>"
        var segments = feedAllAndFlush("<a2ui>before</a2x>after</a2ui>");
        assertEquals(List.of("before</a2x>after"), collectA2uiJsons(segments));
    }
}
