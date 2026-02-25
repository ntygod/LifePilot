package com.lifepilot.interaction.channel.feishu;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.lifepilot.interaction.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 飞书 API 客户端，负责 tenant_access_token 管理和消息主动推送。
 *
 * <p>使用飞书开放平台 API，tenant_access_token 缓存在内存中，过期前自动刷新。
 * 使用 Spring {@link RestClient} 发起 HTTP 请求。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class FeishuApiClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuApiClient.class);
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final String appId;
    private final String appSecret;
    private final RestClient restClient;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private volatile String tenantAccessToken;
    private volatile Instant tokenExpireAt = Instant.EPOCH;

    public FeishuApiClient(GatewayProperties.ChannelsProperties.FeishuChannelProperties config,
                           RestClient restClient) {
        this.appId = config.appId();
        this.appSecret = config.appSecret();
        this.restClient = restClient;
    }

    /**
     * 发送文本消息。
     *
     * @param chatId 目标会话 ID（chat_id 或 open_id）
     * @param text   文本内容
     */
    public void sendText(String chatId, String text) {
        var token = getTenantAccessToken();
        Map<String, Object> body = Map.of(
                "receive_id", chatId,
                "msg_type", "text",
                "content", "{\"text\":\"%s\"}".formatted(escapeJson(text))
        );
        doSend(token, chatId, body);
    }

    /**
     * 发送富文本（post）消息。
     *
     * @param chatId   目标会话 ID
     * @param richText 富文本 JSON 内容
     */
    public void sendPost(String chatId, String richText) {
        var token = getTenantAccessToken();
        Map<String, Object> body = Map.of(
                "receive_id", chatId,
                "msg_type", "post",
                "content", richText
        );
        doSend(token, chatId, body);
    }

    private void doSend(String token, String receiveId, Map<String, Object> body) {
        try {
            restClient.post()
                    .uri(BASE_URL + "/im/v1/messages?receive_id_type=chat_id")
                    .header("Authorization", "Bearer " + token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("飞书消息发送成功: receiveId={}", receiveId);
        } catch (Exception e) {
            log.error("飞书消息发送失败: receiveId={}", receiveId, e);
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
            var body = Map.of("app_id", appId, "app_secret", appSecret);
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
