package com.lifepilot.interaction.channel.feishu;

import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.ResponseContent;

/**
 * 飞书消息格式转换器。
 *
 * <p>将 {@link ResponseContent} 转换为飞书消息格式：
 * <ul>
 *   <li>{@code TextContent} → text 类型 JSON</li>
 *   <li>{@code MarkdownContent} → post 富文本 JSON</li>
 *   <li>{@code CardContent} → post 富文本 JSON</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class FeishuMessageConverter implements MessageConverter {

    @Override
    public ChannelType channelType() {
        return ChannelType.FEISHU;
    }

    @Override
    public String convert(ResponseContent content) {
        return switch (content) {
            case ResponseContent.TextContent text -> text.text();
            case ResponseContent.MarkdownContent md -> buildPostJson("", md.markdown());
            case ResponseContent.CardContent card -> buildPostJson(card.title(), formatCardBody(card));
            case ResponseContent.StreamingContent stream -> stream.toPlainText();
        };
    }

    /**
     * 判断响应内容是否应使用 post 富文本格式。
     *
     * @param content 响应内容
     * @return 是否使用 post 格式
     */
    public boolean shouldUsePost(ResponseContent content) {
        return switch (content) {
            case ResponseContent.TextContent __ -> false;
            case ResponseContent.StreamingContent __ -> false;
            default -> true;
        };
    }

    /**
     * 构建飞书 post 富文本 JSON。
     *
     * <p>格式：{@code {"zh_cn":{"title":"...","content":[[{"tag":"text","text":"..."}]]}}}
     */
    static String buildPostJson(String title, String text) {
        return "{\"zh_cn\":{\"title\":\"%s\",\"content\":[[{\"tag\":\"text\",\"text\":\"%s\"}]]}}"
                .formatted(escapeJson(title), escapeJson(text));
    }

    private static String formatCardBody(ResponseContent.CardContent card) {
        var sb = new StringBuilder();
        sb.append(card.body());
        if (!card.actions().isEmpty()) {
            sb.append("\n\n");
            for (var action : card.actions()) {
                sb.append("[").append(action.label()).append("](").append(action.url()).append(") ");
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
