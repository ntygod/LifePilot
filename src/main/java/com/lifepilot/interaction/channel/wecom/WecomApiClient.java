package com.lifepilot.interaction.channel.wecom;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.lifepilot.interaction.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 企业微信 API 客户端，负责 access_token 管理和消息主动推送。
 *
 * <p>access_token 缓存在内存中，过期前自动刷新。
 * 使用 Spring {@link RestClient} 发起 HTTP 请求。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WecomApiClient {

    private static final Logger log = LoggerFactory.getLogger(WecomApiClient.class);
    private static final String BASE_URL = "https://qyapi.weixin.qq.com/cgi-bin";
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    private final String corpId;
    private final String secret;
    private final RestClient restClient;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private volatile String accessToken;
    private volatile Instant tokenExpireAt = Instant.EPOCH;

    public WecomApiClient(GatewayProperties.ChannelsProperties.WecomChannelProperties config, RestClient restClient) {
        this.corpId = config.corpId();
        this.secret = config.secret();
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
        var body = Map.of(
                "touser", userId,
                "msgtype", "text",
                "agentid", corpId,
                "text", Map.of("content", text)
        );
        doSend(token, body);
    }

    /**
     * 发送 Markdown 消息。
     *
     * @param userId   目标用户 ID
     * @param markdown Markdown 内容
     */
    public void sendMarkdown(String userId, String markdown) {
        var token = getAccessToken();
        var body = Map.of(
                "touser", userId,
                "msgtype", "markdown",
                "agentid", corpId,
                "markdown", Map.of("content", markdown)
        );
        doSend(token, body);
    }

    private void doSend(String token, Map<String, Object> body) {
        try {
            restClient.post()
                    .uri(BASE_URL + "/message/send?access_token={token}", token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("企微消息发送成功");
        } catch (Exception e) {
            log.error("企微消息发送失败", e);
            throw new RuntimeException("企微消息发送失败", e);
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
            var response = restClient.get()
                    .uri(BASE_URL + "/gettoken?corpid={corpId}&corpsecret={secret}", corpId, secret)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw new RuntimeException("企微 access_token 获取失败: 响应为空");
            }
            this.accessToken = (String) response.get("access_token");
            int expiresIn = (int) response.getOrDefault("expires_in", 7200);
            this.tokenExpireAt = Instant.now().plusSeconds(expiresIn - TOKEN_REFRESH_MARGIN_SECONDS);
            log.info("企微 access_token 刷新成功, expiresIn={}s", expiresIn);
        } catch (Exception e) {
            log.error("企微 access_token 刷新失败", e);
            throw new RuntimeException("企微 access_token 刷新失败", e);
        }
    }
}
