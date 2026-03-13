package com.lifepilot.interaction.channel.dingtalk;

import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.ResponseContent;

/**
 * 钉钉消息格式转换器。
 *
 * <p>将 {@link ResponseContent} 转换为钉钉消息 JSON 格式：
 * <ul>
 *   <li>{@code TextContent} → text 类型 JSON</li>
 *   <li>{@code MarkdownContent} → markdown 类型 JSON</li>
 *   <li>{@code CardContent} → ActionCard JSON</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class DingtalkMessageConverter implements MessageConverter {

    @Override
    public ChannelType channelType() {
        return ChannelType.DINGTALK;
    }

    @Override
    public String convert(ResponseContent content) {
        return switch (content) {
            case ResponseContent.TextContent text -> text.text();
            case ResponseContent.MarkdownContent md -> md.markdown();
            case ResponseContent.CardContent card -> formatActionCard(card);
            case ResponseContent.ImageContent img -> img.toPlainText();
            case ResponseContent.StreamingContent stream -> stream.toPlainText();
        };
    }

    /**
     * 判断响应内容是否应使用 ActionCard 格式。
     *
     * <p>超过 500 字符或含 Markdown 格式时使用 ActionCard。
     *
     * @param content 响应内容
     * @return 是否使用 ActionCard
     */
    public boolean shouldUseActionCard(ResponseContent content) {
        return switch (content) {
            case ResponseContent.MarkdownContent __ -> true;
            case ResponseContent.CardContent __ -> true;
            case ResponseContent.TextContent text -> text.text().length() > 500;
            case ResponseContent.ImageContent __ -> false;
            case ResponseContent.StreamingContent __ -> false;
        };
    }

    private static String formatActionCard(ResponseContent.CardContent card) {
        var sb = new StringBuilder();
        sb.append("## ").append(card.title()).append("\n\n");
        sb.append(card.body());
        if (!card.actions().isEmpty()) {
            sb.append("\n\n---\n");
            for (var action : card.actions()) {
                sb.append("[").append(action.label()).append("](").append(action.url()).append(") ");
            }
        }
        return sb.toString();
    }
}
