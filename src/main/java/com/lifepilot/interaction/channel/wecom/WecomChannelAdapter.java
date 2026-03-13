package com.lifepilot.interaction.channel.wecom;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import com.lifepilot.interaction.channel.AbstractChannelAdapter;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.ResponseContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

/**
 * 企业微信通道适配器，处理企微 Webhook 回调消息。
 *
 * <p>处理流程：签名验证 → AES 解密 → XML 解析 → normalize → 异步提交 Gateway。
 * 响应通过 {@link WecomApiClient} 主动推送。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WecomChannelAdapter extends AbstractChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WecomChannelAdapter.class);

    private final WecomCrypto crypto;
    private final WecomSignatureVerifier signatureVerifier;
    private final WecomApiClient apiClient;
    private final WecomMessageConverter converter;
    private final String corpId;

    public WecomChannelAdapter(MessageGateway gateway, GatewayProperties properties,
                               WecomCrypto crypto, WecomSignatureVerifier signatureVerifier,
                               WecomApiClient apiClient, WecomMessageConverter converter) {
        super(gateway, properties);
        this.crypto = crypto;
        this.signatureVerifier = signatureVerifier;
        this.apiClient = apiClient;
        this.converter = converter;
        this.corpId = properties.channels().wecom().corpId();
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WECOM;
    }

    @Override
    public GatewayMessage normalize(Object rawMessage) {
        // 由 handleMessage 内部调用，rawMessage 为解析后的 Map
        @SuppressWarnings("unchecked")
        var fields = (Map<String, String>) rawMessage;
        String fromUser = fields.getOrDefault("FromUserName", "unknown");
        String content = fields.getOrDefault("Content", "");
        String msgId = fields.getOrDefault("MsgId", "");

        // 文本以 / 开头转为 CommandMessage，否则转为 TextMessage
        MessageContent messageContent = content.startsWith("/")
                ? MessageContent.CommandMessage.parse(content)
                : new MessageContent.TextMessage(content.isBlank() ? "[空消息]" : content);

        String agentId = properties.channels().wecom().agentId();
        return GatewayMessage.builder()
                .messageId(msgId.isEmpty() ? null : msgId)
                .channelType(ChannelType.WECOM)
                .userId(fromUser)
                .sessionId("wecom:" + corpId + ":" + fromUser)
                .content(messageContent)
                .channelMetadata(new ChannelMetadata.WecomMetadata(
                        corpId, agentId != null ? agentId : "", "", "", "", null))
                .timestamp(Instant.now())
                .attachments(List.of())
                .traceHeaders(Map.of())
                .build();
    }

    /**
     * 处理企微 URL 验证请求（GET）。
     *
     * @param params 请求参数（msg_signature, timestamp, nonce, echostr）
     * @return 解密后的 echostr 明文
     */
    public String handleVerification(Map<String, String> params) {
        String msgSignature = params.get("msg_signature");
        String timestamp = params.get("timestamp");
        String nonce = params.get("nonce");
        String echostr = params.get("echostr");

        if (!signatureVerifier.verify(msgSignature, timestamp, nonce, echostr)) {
            log.warn("企微 URL 验证签名失败");
            return "success";
        }
        return crypto.decrypt(echostr);
    }

    /**
     * 处理企微消息接收请求（POST）。
     *
     * @param params  请求参数（msg_signature, timestamp, nonce）
     * @param xmlBody 加密 XML 消息体
     * @return 固定返回 "success"
     */
    public String handleMessage(Map<String, String> params, String xmlBody) {
        try {
            String msgSignature = params.get("msg_signature");
            String timestamp = params.get("timestamp");
            String nonce = params.get("nonce");

            // 从 XML 中提取 Encrypt 字段
            String encrypt = extractXmlField(xmlBody, "Encrypt");
            if (encrypt == null) {
                log.warn("企微消息缺少 Encrypt 字段");
                return "success";
            }

            // 签名验证
            if (!signatureVerifier.verify(msgSignature, timestamp, nonce, encrypt)) {
                log.warn("企微消息签名验证失败");
                return "success";
            }

            // AES 解密
            String decryptedXml = crypto.decrypt(encrypt);

            // 解析 XML 字段
            var fields = parseXml(decryptedXml);
            var message = normalize(fields);

            // 异步提交到 Gateway
            submitAsync(message);
        } catch (Exception e) {
            log.error("企微消息处理异常", e);
        }
        return "success";
    }

    @Override
    protected void doStart() {
        log.info("企微通道适配器启动");
    }

    @Override
    protected void doStop() {
        log.info("企微通道适配器停止");
    }

    @Override
    protected void doSendResponse(String userId, GatewayResponse response) {
        ResponseContent content = response.content();
        String text = converter.convert(content);

        if (converter.shouldUseNews(content)) {
            // ImageContent → 图文消息
            if (content instanceof ResponseContent.ImageContent img) {
                apiClient.sendNews(userId, img.altText(),
                        img.caption() != null ? img.caption() : "",
                        img.imageUrl(), img.imageUrl());
            } else {
                apiClient.sendText(userId, text);
            }
        } else if (content instanceof ResponseContent.MarkdownContent) {
            apiClient.sendMarkdown(userId, text);
        } else {
            apiClient.sendText(userId, text);
        }
    }

    // ── XML 解析工具 ──────────────────────────────────────────

    /**
     * 从 XML 字符串中提取指定字段值。
     */
    static String extractXmlField(String xml, String fieldName) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            var nodes = doc.getElementsByTagName(fieldName);
            if (nodes.getLength() > 0) {
                return nodes.item(0).getTextContent();
            }
        } catch (Exception e) {
            log.warn("XML 字段提取失败: field={}", fieldName, e);
        }
        return null;
    }

    /**
     * 解析企微 XML 消息为字段 Map。
     */
    private static Map<String, String> parseXml(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            var root = doc.getDocumentElement();
            var children = root.getChildNodes();
            var map = new java.util.HashMap<String, String>();
            for (int i = 0; i < children.getLength(); i++) {
                var node = children.item(i);
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    map.put(node.getNodeName(), node.getTextContent());
                }
            }
            return map;
        } catch (Exception e) {
            log.error("企微 XML 解析失败", e);
            return Map.of();
        }
    }
}
