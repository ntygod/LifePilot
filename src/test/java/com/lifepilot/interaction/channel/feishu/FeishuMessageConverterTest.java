package com.lifepilot.interaction.channel.feishu;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.ResponseContent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FeishuMessageConverter} 单元测试。
 *
 * @author zsg
 * @since 2026-03-13
 */
class FeishuMessageConverterTest {

    private FeishuMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new FeishuMessageConverter();
    }

    @Test
    void channelType_返回FEISHU() {
        assertEquals(ChannelType.FEISHU, converter.channelType());
    }

    // ── ImageContent ─────────────────────────────────────

    @Nested
    class 图片内容 {

        @Test
        void ImageContent_生成imageJson包含imageUrl和altText() {
            var img = new ImageContent("https://img.example.com/pic.png", "示例图片", null);

            var result = converter.convert(img);

            assertTrue(result.contains("image_key"));
            assertTrue(result.contains("https://img.example.com/pic.png"));
            assertTrue(result.contains("示例图片"));
        }
    }

    // ── CardContent ──────────────────────────────────────

    @Nested
    class 卡片内容 {

        @Test
        void CardContent_生成交互式卡片JSON() {
            var card = new CardContent("任务提醒", "你有一个待办任务",
                    List.of(new CardContent.CardAction("查看", "https://example.com/task")));

            var result = converter.convert(card);

            assertTrue(result.contains("wide_screen_mode"));
            assertTrue(result.contains("任务提醒"));
            assertTrue(result.contains("你有一个待办任务"));
            assertTrue(result.contains("markdown"));
            assertTrue(result.contains("button"));
            assertTrue(result.contains("https://example.com/task"));
        }
    }

    // ── MarkdownContent ──────────────────────────────────

    @Nested
    class Markdown内容 {

        @Test
        void MarkdownContent_包含链接时转换为tag_a标签() {
            var md = new MarkdownContent("请查看[文档](https://docs.example.com)了解详情");

            var result = converter.convert(md);

            assertTrue(result.contains("\"tag\":\"a\""));
            assertTrue(result.contains("文档"));
            assertTrue(result.contains("https://docs.example.com"));
        }

        @Test
        void MarkdownContent_无链接时使用text标签() {
            var md = new MarkdownContent("纯文本内容，无链接");

            var result = converter.convert(md);

            assertTrue(result.contains("\"tag\":\"text\""));
            assertTrue(result.contains("纯文本内容，无链接"));
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
        void shouldUsePost_MarkdownContent返回true() {
            assertTrue(converter.shouldUsePost(new MarkdownContent("# 标题")));
        }

        @Test
        void shouldUsePost_TextContent返回false() {
            assertFalse(converter.shouldUsePost(new TextContent("纯文本")));
        }

        @Test
        void shouldUseInteractiveCard_CardContent返回true() {
            assertTrue(converter.shouldUseInteractiveCard(
                    new CardContent("标题", "正文", List.of())));
        }

        @Test
        void shouldUseInteractiveCard_TextContent返回false() {
            assertFalse(converter.shouldUseInteractiveCard(new TextContent("纯文本")));
        }

        @Test
        void shouldUseImage_ImageContent返回true() {
            assertTrue(converter.shouldUseImage(new ImageContent("url", "alt", null)));
        }

        @Test
        void shouldUseImage_TextContent返回false() {
            assertFalse(converter.shouldUseImage(new TextContent("纯文本")));
        }
    }
}
