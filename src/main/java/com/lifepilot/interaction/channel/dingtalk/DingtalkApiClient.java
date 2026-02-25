package com.lifepilot.interaction.channel.dingtalk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.lifepilot.interaction.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 钉钉 API 客户端，负责 access_token 管理和消息主动推送。
 *
 * <p>使用钉钉新版 API（api.dingtalk.com），access_token 缓存在内存中，过期前自动刷新。
 * 使用 Spring {@link RestClient} 发起 HTTP 请求。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class DingtalkApiClient {

    private static final Logger log = LoggerFactory.getLogger(DingtalkApiClient.class);
    private static final String NEW_BASE_URL = "https://api.dingtalk.com";
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final String appKey;
    private final String appSecret;
    private final String robotCode;
    private final RestClient restClient;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private volatile String accessToken;
    private volatile Instant tokenExpireAt = Instant.EPOCH;

    public DingtalkApiClient(GatewayProperties.ChannelsProperties.DingtalkChannelProperties config,
                             RestClient restClient) {
        this.appKey = config.appKey();
        this.appSecret = config.appSecret();
        this.robotCode = config.robotCode();
        this.restClient = restClient;
    }

    /**
     * 发送文本消息。
     *
     * @param userId 目标用户 ID
     * @param text   文本内容
     */
    public void sendText(String userId, String text) {
        var token = getAccessToken();
        Map<String, Object> body = Map.of(
                "robotCode", robotCode,
                "userIds", List.of(userId),
                "msgKey", "sampleText",
                "msgParam", "{\"content\":\"%s\"}".formatted(escapeJson(text))
        );
        doSend(token, body);
    }

    /**
     * 发送 ActionCard 消息。
     *
     * @param userId 目标用户 ID
     * @param title  卡片标题
     * @param text   卡片正文（Markdown 格式）
     */
    public void sendActionCard(String userId, String title, String text) {
        var token = getAccessToken();
        Map<String, Object> body = Map.of(
                "robotCode", robotCode,
                "userIds", List.of(userId),
                "msgKey", "sampleActionCard",
                "msgParam", "{\"title\":\"%s\",\"text\":\"%s\"}".formatted(
                        escapeJson(title), escapeJson(text))
        );
        doSend(token, body);
    }

    private void doSend(String token, Map<String, Object> body) {
        try {
            restClient.post()
                    .uri(NEW_BASE_URL + "/v1.0/robot/oToMessages/batchSend")
                    .header("x-acs-dingtalk-access-token", token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("钉钉消息发送成功");
        } catch (Exception e) {
            log.error("钉钉消息发送失败", e);
            throw new RuntimeException("钉钉消息发送失败", e);
        }
    }

    /**
     * 获取 access_token，过期时自动刷新。
     *
     * @return 有效的 access_token
     */
    String getAccessToken() {
        if (accessToken != null && Instant.now().isBefore(tokenExpireAt)) {
            return accessToken;
        }
        tokenLock.lock();
        try {
            // 双重检查
            if (accessToken != null && Instant.now().isBefore(tokenExpireAt)) {
                return accessToken;
            }
            refreshToken();
            return accessToken;
        } finally {
            tokenLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshToken() {
        try {
            var body = Map.of("appKey", appKey, "appSecret", appSecret);
            var response = restClient.post()
                    .uri(NEW_BASE_URL + "/v1.0/oauth2/accessToken")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("accessToken") == null) {
                throw new RuntimeException("钉钉 access_token 获取失败: 响应为空");
            }
            this.accessToken = (String) response.get("accessToken");
            int expiresIn = (int) response.getOrDefault("expireIn", 7200);
            this.tokenExpireAt = Instant.now().plusSeconds(expiresIn - TOKEN_REFRESH_MARGIN_SECONDS);
            log.info("钉钉 access_token 刷新成功, expiresIn={}s", expiresIn);
        } catch (Exception e) {
            log.error("钉钉 access_token 刷新失败", e);
            throw new RuntimeException("钉钉 access_token 刷新失败", e);
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
