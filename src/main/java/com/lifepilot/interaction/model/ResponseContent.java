package com.lifepilot.interaction.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * 响应内容 sealed interface，穷举所有响应类型。
 *
 * <p>通过 sealed interface + record 模式确保类型安全和不可变性，
 * 通道适配器可根据响应类型选择最佳渲染方式。
 *
 * @author zsg
 * @since 2026-02-25
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ResponseContent.TextContent.class, name = "TEXT"),
        @JsonSubTypes.Type(value = ResponseContent.MarkdownContent.class, name = "MARKDOWN"),
        @JsonSubTypes.Type(value = ResponseContent.CardContent.class, name = "CARD"),
        @JsonSubTypes.Type(value = ResponseContent.ImageContent.class, name = "IMAGE"),
        @JsonSubTypes.Type(value = ResponseContent.FileContent.class, name = "FILE"),
        @JsonSubTypes.Type(value = ResponseContent.StreamingContent.class, name = "STREAMING"),
})
public sealed interface ResponseContent
        permits ResponseContent.TextContent,
                ResponseContent.MarkdownContent,
                ResponseContent.CardContent,
                ResponseContent.ImageContent,
                ResponseContent.FileContent,
                ResponseContent.StreamingContent {

    /**
     * 返回响应内容的纯文本表示。
     *
     * @return 非 null 的纯文本字符串
     */
    String toPlainText();

    /**
     * 纯文本响应。
     *
     * @param text 文本内容
     * @author zsg
     * @since 2026-02-25
     */
    record TextContent(String text) implements ResponseContent {

        @Override
        public String toPlainText() {
            return text;
        }
    }

    /**
     * Markdown 格式响应。
     *
     * @param markdown Markdown 内容
     * @author zsg
     * @since 2026-02-25
     */
    record MarkdownContent(String markdown) implements ResponseContent {

        @Override
        public String toPlainText() {
            return markdown;
        }
    }

    /**
     * 卡片响应。
     *
     * @param title   卡片标题
     * @param body    卡片正文
     * @param actions 卡片操作列表（不可变）
     * @author zsg
     * @since 2026-02-25
     */
    record CardContent(String title, String body, List<CardAction> actions) implements ResponseContent {

        /**
         * 紧凑构造器：防御性拷贝操作列表。
         */
        public CardContent {
            actions = List.copyOf(actions);
        }

        @Override
        public String toPlainText() {
            return "[卡片: %s] %s".formatted(title, body);
        }

        /**
         * 卡片操作。
         *
         * @param label 操作标签
         * @param value 操作值（URL 或回调标识）
         * @param type  操作类型：{@code "url"}（链接跳转）或 {@code "callback"}（平台回调）
         * @author zsg
         * @since 2026-02-25
         */
        public record CardAction(String label, String value, String type) {

            /** URL 操作 — 点击后打开链接。 */
            public static CardAction url(String label, String url) {
                return new CardAction(label, url, "url");
            }

            /** 回调操作 — 点击后通过平台回调通知服务端。 */
            public static CardAction callback(String label, String callbackValue) {
                return new CardAction(label, callbackValue, "callback");
            }
        }
    }

    /**
     * 图片响应。
     *
     * @param imageUrl 图片 URL
     * @param altText  替代文本
     * @param caption  图片说明（可选）
     * @author zsg
     * @since 2026-03-13
     */
    record ImageContent(String imageUrl, String altText, @Nullable String caption) implements ResponseContent {
        @Override
        public String toPlainText() {
            return caption != null
                ? "[图片: %s] %s".formatted(altText, caption)
                : "[图片: %s]".formatted(altText);
        }
    }

    /**
     * 文件响应 —— 引用已落盘的 document/attachment，让 channel 层发成"文件消息"
     * （飞书 msg_type=file、企微 file 等）。仅含引用不含字节，delivery 层按需读物理文件。
     *
     * @param documentId 关联的 document id（来自 session_documents）
     * @param fileName   文件名（含扩展名）
     * @param mimeType   MIME
     * @param caption    可选的附随文字说明
     * @author zsg
     * @since 2026-04-22
     */
    record FileContent(String documentId, String fileName, String mimeType, @Nullable String caption)
            implements ResponseContent {

        @Override
        public String toPlainText() {
            return caption != null
                    ? "[文件: %s] %s".formatted(fileName, caption)
                    : "[文件: %s]".formatted(fileName);
        }
    }

    /**
     * 流式响应，通过 streamId 标识流式传输通道。
     *
     * @param streamId 流式传输标识
     * @author zsg
     * @since 2026-02-25
     */
    record StreamingContent(String streamId) implements ResponseContent {

        @Override
        public String toPlainText() {
            return "[流式响应: %s]".formatted(streamId);
        }
    }
}
