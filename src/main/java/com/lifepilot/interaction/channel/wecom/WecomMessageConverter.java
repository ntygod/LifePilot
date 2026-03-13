package com.lifepilot.interaction.channel.wecom;

import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.ResponseContent;

/**
 * 企业微信消息格式转换器。
 *
 * <p>将 {@link ResponseContent} 转换为企微消息格式：
 * <ul>
 *   <li>{@code TextContent} → 纯文本</li>
 *   <li>{@code MarkdownContent} → 企微 Markdown</li>
 *   <li>{@code CardContent} → 企微 Markdown 卡片格式</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WecomMessageConverter implements MessageConverter {

    @Override
    public ChannelType channelType() {
        return ChannelType.WECOM;
    }

    @Override
    public String convert(ResponseContent content) {
        return switch (content) {
            case ResponseContent.TextContent text -> text.text();
            case ResponseContent.MarkdownContent md -> md.markdown();
            case ResponseContent.CardContent card -> formatCard(card);
            case ResponseContent.ImageContent img -> formatImageAsLink(img);
            case ResponseContent.StreamingContent stream -> stream.toPlainText();
        };
    }

    private static String formatCard(ResponseContent.CardContent card) {
        var sb = new StringBuilder();
        sb.append("**").append(card.title()).append("**\n\n");
        sb.append(card.body());
        if (!card.actions().isEmpty()) {
            sb.append("\n\n---\n");
            for (var action : card.actions()) {
                sb.append("[").append(action.label()).append("](").append(action.url()).append(") ");
            }
        }
        return sb.toString();
    }

    /**
     * 将图片内容降级为图文链接格式。
     */
    private static String formatImageAsLink(ResponseContent.ImageContent img) {
        var sb = new StringBuilder();
        if (img.caption() != null && !img.caption().isBlank()) {
            sb.append(img.caption()).append("\n\n");
        }
        sb.append("[").append(img.altText()).append("](").append(img.imageUrl()).append(")");
        return sb.toString();
    }

    /**
     * 判断响应内容是否应使用图文消息格式。
     *
     * @param content 响应内容
     * @return 是否使用图文消息格式
     */
    public boolean shouldUseNews(ResponseContent content) {
        return content instanceof ResponseContent.ImageContent;
    }
}
