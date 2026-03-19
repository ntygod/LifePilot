package com.lifepilot.interaction.channel.feishu;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.lifepilot.interaction.config.ChannelConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 飞书 API 客户端，负责 tenant_access_token 管理和消息主动推送。
 *
 * <p>使用飞书开放平台 API，tenant_access_token 缓存在内存中，过期前自动刷新。
 * 凭证通过 {@link ChannelConfigProvider} 动态读取，支持运行时通过 Web UI 更新。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class FeishuApiClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuApiClient.class);
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final ChannelConfigProvider configProvider;
    private final RestClient restClient;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private volatile String tenantAccessToken;
    private volatile Instant tokenExpireAt = Instant.EPOCH;

    public FeishuApiClient(ChannelConfigProvider configProvider, RestClient restClient) {
        this.configProvider = configProvider;
        this.restClient = restClient;
    }

    /**
     * 发送文本消息。
     *
     * @param receiveId     目标 ID（chat_id 或 open_id）
     * @param receiveIdType ID 类型（"chat_id" 或 "open_id"）
     * @param text          文本内容
     */
    public void sendText(String receiveId, String receiveIdType, String text) {
        var token = getTenantAccessToken();
        Map<String, Object> body = Map.of(
                "receive_id", receiveId,
                "msg_type", "text",
                "content", "{\"text\":\"%s\"}".formatted(escapeJson(text))
        );
        doSend(token, receiveId, receiveIdType, body);
    }

    /**
     * 发送富文本（post）消息。
     *
     * @param receiveId     目标 ID
     * @param receiveIdType ID 类型
     * @param richText      富文本 JSON 内容
     */
    public void sendPost(String receiveId, String receiveIdType, String richText) {
        var token = getTenantAccessToken();
        Map<String, Object> body = Map.of(
                "receive_id", receiveId,
                "msg_type", "post",
                "content", richText
        );
        doSend(token, receiveId, receiveIdType, body);
    }

    /**
     * 发送交互式消息卡片。
     *
     * @param receiveId     目标 ID
     * @param receiveIdType ID 类型
     * @param cardJson      卡片 JSON 内容
     */
    public void sendInteractiveCard(String receiveId, String receiveIdType, String cardJson) {
        var token = getTenantAccessToken();
        Map<String, Object> body = Map.of(
                "receive_id", receiveId,
                "msg_type", "interactive",
                "content", cardJson
        );
        doSend(token, receiveId, receiveIdType, body);
    }

    /**
     * 发送图片消息。
     *
     * @param receiveId     目标 ID
     * @param receiveIdType ID 类型
     * @param imageKey      飞书图片 key
     */
    public void sendImage(String receiveId, String receiveIdType, String imageKey) {
        var token = getTenantAccessToken();
        Map<String, Object> body = Map.of(
                "receive_id", receiveId,
                "msg_type", "image",
                "content", "{\"image_key\":\"%s\"}".formatted(escapeJson(imageKey))
        );
        doSend(token, receiveId, receiveIdType, body);
    }

    private void doSend(String token, String receiveId, String receiveIdType, Map<String, Object> body) {
        try {
            restClient.post()
                    .uri(BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType)
                    .header("Authorization", "Bearer " + token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("飞书消息发送成功: receiveId={}, receiveIdType={}", receiveId, receiveIdType);
        } catch (Exception e) {
            log.error("飞书消息发送失败: receiveId={}, receiveIdType={}", receiveId, receiveIdType, e);
            throw new RuntimeException("飞书消息发送失败", e);
        }
    }

    /**
     * 获取 tenant_access_token，过期时自动刷新。
     *
     * @return 有效的 tenant_access_token
     */
    String getTenantAccessToken() {
        if (tenantAccessToken != null && Instant.now().isBefore(tokenExpireAt)) {
            return tenantAccessToken;
        }
        tokenLock.lock();
        try {
            // 双重检查
            if (tenantAccessToken != null && Instant.now().isBefore(tokenExpireAt)) {
                return tenantAccessToken;
            }
            refreshToken();
            return tenantAccessToken;
        } finally {
            tokenLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshToken() {
        try {
            // 动态读取凭证，支持运行时通过 Web UI 更新
            var config = configProvider.getFeishuConfig();
            var currentAppId = config.appId();
            var currentAppSecret = config.appSecret();
            if (currentAppId == null || currentAppId.isBlank() || currentAppSecret == null || currentAppSecret.isBlank()) {
                throw new RuntimeException("飞书 appId 或 appSecret 未配置");
            }
            var body = Map.of("app_id", currentAppId, "app_secret", currentAppSecret);
            var response = restClient.post()
                    .uri(BASE_URL + "/auth/v3/tenant_access_token/internal")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("tenant_access_token") == null) {
                throw new RuntimeException("飞书 tenant_access_token 获取失败: 响应为空");
            }
            this.tenantAccessToken = (String) response.get("tenant_access_token");
            int expire = (int) response.getOrDefault("expire", 7200);
            this.tokenExpireAt = Instant.now().plusSeconds(expire - TOKEN_REFRESH_MARGIN_SECONDS);
            log.info("飞书 tenant_access_token 刷新成功, expire={}s", expire);
        } catch (Exception e) {
            log.error("飞书 tenant_access_token 刷新失败", e);
            throw new RuntimeException("飞书 tenant_access_token 刷新失败", e);
        }
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
