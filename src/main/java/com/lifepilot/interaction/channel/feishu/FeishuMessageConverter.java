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
            case ResponseContent.MarkdownContent md -> buildPostJsonWithLinks(md.markdown());
            case ResponseContent.CardContent card -> buildInteractiveCardJson(card);
            case ResponseContent.ImageContent img -> buildImageJson(img);
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
            case ResponseContent.ImageContent __ -> false;
            case ResponseContent.CardContent __ -> false;
            default -> true;
        };
    }

    /**
     * 判断响应内容是否应使用交互式卡片格式。
     *
     * @param content 响应内容
     * @return 是否使用交互式卡片
     */
    public boolean shouldUseInteractiveCard(ResponseContent content) {
        return content instanceof ResponseContent.CardContent;
    }

    /**
     * 判断响应内容是否应使用图片格式。
     *
     * @param content 响应内容
     * @return 是否使用图片格式
     */
    public boolean shouldUseImage(ResponseContent content) {
        return content instanceof ResponseContent.ImageContent;
    }

    /**
     * 构建飞书 post 富文本 JSON。
     *
     * <p>格式：{@code {"zh_cn":{"title":"...","content":[[{"tag":"text","text":"..."}]]}}}
     */
    /**
     * 构建飞书交互式消息卡片 JSON。
     */
    static String buildInteractiveCardJson(ResponseContent.CardContent card) {
        var sb = new StringBuilder();
        sb.append("{\"config\":{\"wide_screen_mode\":true},");
        sb.append("\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"")
          .append(escapeJson(card.title())).append("\"}},");
        sb.append("\"elements\":[{\"tag\":\"markdown\",\"content\":\"")
          .append(escapeJson(card.body())).append("\"}");
        for (var action : card.actions()) {
            sb.append(",{\"tag\":\"action\",\"actions\":[{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"")
              .append(escapeJson(action.label()))
              .append("\"},\"url\":\"").append(escapeJson(action.url()))
              .append("\",\"type\":\"primary\"}]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 构建飞书图片消息 JSON。
     */
    static String buildImageJson(ResponseContent.ImageContent img) {
        var alt = img.altText() != null ? img.altText() : "";
        return "{\"image_key\":\"%s\",\"alt\":\"%s\"}".formatted(escapeJson(img.imageUrl()), escapeJson(alt));
    }

    /**
     * 构建包含链接标签的飞书 post 富文本 JSON。
     * 将 Markdown 链接 [text](url) 转换为飞书 {tag:"a"} 标签。
     */
    static String buildPostJsonWithLinks(String markdown) {
        var linkPattern = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
        var matcher = linkPattern.matcher(markdown);
        var elements = new StringBuilder();
        int lastEnd = 0;
        boolean first = true;
        while (matcher.find()) {
            var before = markdown.substring(lastEnd, matcher.start());
            if (!before.isEmpty()) {
                if (!first) elements.append(",");
                elements.append("{\"tag\":\"text\",\"text\":\"").append(escapeJson(before)).append("\"}");
                first = false;
            }
            if (!first) elements.append(",");
            elements.append("{\"tag\":\"a\",\"text\":\"").append(escapeJson(matcher.group(1)))
                    .append("\",\"href\":\"").append(escapeJson(matcher.group(2))).append("\"}");
            first = false;
            lastEnd = matcher.end();
        }
        var remaining = markdown.substring(lastEnd);
        if (!remaining.isEmpty()) {
            if (!first) elements.append(",");
            elements.append("{\"tag\":\"text\",\"text\":\"").append(escapeJson(remaining)).append("\"}");
        }
        if (elements.isEmpty()) {
            elements.append("{\"tag\":\"text\",\"text\":\"").append(escapeJson(markdown)).append("\"}");
        }
        return "{\"zh_cn\":{\"title\":\"\",\"content\":[[" + elements + "]]}}";
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
