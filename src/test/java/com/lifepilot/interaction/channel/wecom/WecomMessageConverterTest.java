package com.lifepilot.interaction.channel.wecom;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.ResponseContent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WecomMessageConverter} 单元测试。
 *
 * @author zsg
 * @since 2026-03-13
 */
class WecomMessageConverterTest {

    private WecomMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new WecomMessageConverter();
    }

    @Test
    void channelType_返回WECOM() {
        assertEquals(ChannelType.WECOM, converter.channelType());
    }

    // ── ImageContent ─────────────────────────────────────

    @Nested
    class 图片内容 {

        @Test
        void ImageContent_降级为图文链接格式() {
            var img = new ImageContent("https://img.example.com/pic.png", "示例图片", "这是一张图片");

            var result = converter.convert(img);

            assertTrue(result.contains("这是一张图片"));
            assertTrue(result.contains("[示例图片](https://img.example.com/pic.png)"));
        }

        @Test
        void ImageContent_无caption时只有链接() {
            var img = new ImageContent("https://img.example.com/pic.png", "示例图片", null);

            var result = converter.convert(img);

            assertFalse(result.contains("\n\n["));
            assertTrue(result.contains("[示例图片](https://img.example.com/pic.png)"));
        }
    }

    // ── CardContent ──────────────────────────────────────

    @Nested
    class 卡片内容 {

        @Test
        void CardContent_转换为Markdown格式() {
            var card = new CardContent("任务提醒", "你有一个待办任务",
                    List.of(new CardContent.CardAction("查看", "https://example.com/task")));

            var result = converter.convert(card);

            assertTrue(result.contains("**任务提醒**"));
            assertTrue(result.contains("你有一个待办任务"));
            assertTrue(result.contains("[查看](https://example.com/task)"));
        }
    }

    // ── TextContent ──────────────────────────────────────

    @Nested
    class 文本内容 {

        @Test
        void TextContent_纯文本直接透传() {
            var text = new TextContent("你好世界");

            var result = converter.convert(text);

            assertEquals("你好世界", result);
        }
    }

    // ── 路由方法 ─────────────────────────────────────────

    @Nested
    class 路由方法 {

        @Test
        void shouldUseNews_ImageContent返回true() {
            assertTrue(converter.shouldUseNews(new ImageContent("url", "alt", null)));
        }

        @Test
        void shouldUseNews_TextContent返回false() {
            assertFalse(converter.shouldUseNews(new TextContent("纯文本")));
        }
    }
}
