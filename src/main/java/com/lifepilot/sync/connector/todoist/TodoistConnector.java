package com.lifepilot.sync.connector.todoist;

import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.connector.JsonHelper;
import com.lifepilot.sync.connector.SyncConnector;
import com.lifepilot.sync.credential.CredentialStore;
import com.lifepilot.sync.mapping.FieldMapping;
import com.lifepilot.sync.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Todoist 连接器，使用 Java HttpClient 调用 Todoist API v1 Sync endpoint。
 *
 * <p>支持 sync_token 增量同步、batch commands 推送、OAuth2 Bearer Token 认证
 * 和 401 自动 Token 刷新。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class TodoistConnector implements SyncConnector {

    private static final Logger log = LoggerFactory.getLogger(TodoistConnector.class);

    private static final String CONNECTOR_TYPE = "todoist";

    private final CredentialStore credentialStore;
    private final SyncProperties properties;
    private final HttpClient httpClient;
    private final FieldMapping<com.lifepilot.skill.builtin.todo.TodoItem, Map<String, Object>> taskMapping;

    public TodoistConnector(CredentialStore credentialStore, SyncProperties properties) {
        this.credentialStore = credentialStore;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeout()))
                .build();
        this.taskMapping = TodoistFieldMapping.taskMapping();
    }

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public ConnectionTestResult testConnection(SyncProfile profile) {
        long start = System.currentTimeMillis();
        try {
            var baseUrl = getBaseUrl(profile);
            var token = getAccessToken(profile);

            // GET /sync/v1/sync with sync_token="*" and resource_types=["user"]
            var formBody = "sync_token=" + encode("*")
                    + "&resource_types=" + encode("[\"user\"]");

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/sync/v1/sync"))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Bearer " + token)
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Todoist 连接测试成功: 耗时={}ms", elapsed);
                return new ConnectionTestResult(true, elapsed, null);
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new ConnectionTestResult(false, elapsed, "认证失败: HTTP " + response.statusCode());
            } else {
                return new ConnectionTestResult(false, elapsed,
                        "Todoist API 返回错误: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Todoist 连接测试失败: {}", e.getMessage());
            return new ConnectionTestResult(false, elapsed, "连接失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteChangeSet fetchChanges(SyncProfile profile, @Nullable String syncToken) {
        var baseUrl = getBaseUrl(profile);
        var token = getAccessToken(profile);

        // sync_token="*" 时执行全量同步
        var effectiveSyncToken = (syncToken == null || syncToken.isBlank()) ? "*" : syncToken;

        try {
            var formBody = "sync_token=" + encode(effectiveSyncToken)
                    + "&resource_types=" + encode("[\"items\"]");

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/sync/v1/sync"))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Bearer " + token)
                    .build();

            var response = executeWithRetryAndRefresh(request, profile);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SyncException.RemoteApiException(
                        "Todoist Sync API 请求失败: HTTP " + response.statusCode());
            }

            return parseSyncResponse(response.body(), "*".equals(effectiveSyncToken));
        } catch (SyncException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException.ConnectionException("Todoist 拉取变更失败: " + e.getMessage(), e);
        }
    }

    @Override
    public PushResult pushChanges(SyncProfile profile, List<SyncOperation> operations) {
        if (operations.isEmpty()) {
            return new PushResult(0, 0, List.of());
        }

        var baseUrl = getBaseUrl(profile);
        var token = getAccessToken(profile);

        try {
            // 构建 commands JSON 数组
            var commandsJson = buildCommandsJson(operations);

            var formBody = "commands=" + encode(commandsJson);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/sync/v1/sync"))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Bearer " + token)
                    .build();

            var response = executeWithRetryAndRefresh(request, profile);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SyncException.RemoteApiException(
                        "Todoist 推送失败: HTTP " + response.statusCode());
            }

            // 解析推送结果
            return parsePushResponse(response.body(), operations);
        } catch (SyncException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException.ConnectionException("Todoist 推送变更失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取 Todoist API 基础 URL。
     */
    private String getBaseUrl(SyncProfile profile) {
        var params = parseConnectionParams(profile);
        return params.getOrDefault("baseUrl",
                properties.getConnectors().getTodoist().getBaseUrl());
    }

    /**
     * 获取 OAuth2 Access Token。
     */
    private String getAccessToken(SyncProfile profile) {
        return credentialStore.retrieve(profile.id(), "access_token")
                .orElseThrow(() -> new SyncException.CredentialException(
                        "Todoist access_token 不存在: profileId=" + profile.id()));
    }

    /**
     * 解析 Todoist Sync API 响应，提取 items 和 sync_token。
     */
    private RemoteChangeSet parseSyncResponse(String responseBody, boolean isFullSync) {
        var created = new ArrayList<RemoteChangeSet.RemoteEntity>();
        var updated = new ArrayList<RemoteChangeSet.RemoteEntity>();
        var deletedIds = new ArrayList<String>();

        // 提取 sync_token
        var newSyncToken = JsonHelper.extractJsonStringValue(responseBody, "sync_token");

        // 提取 items 数组
        var itemsArray = JsonHelper.extractJsonArray(responseBody, "items");
        for (var itemJson : itemsArray) {
            var fields = JsonHelper.parseSimpleJsonObject(itemJson);
            if (fields.isEmpty()) continue;

            var remoteId = fields.getOrDefault("id", "").toString();
            if (remoteId.isEmpty()) continue;

            // 检查是否已删除
            var isDeleted = fields.get("is_deleted");
            if (isDeleted != null && ("true".equals(isDeleted.toString()) || "1".equals(isDeleted.toString()))) {
                deletedIds.add(remoteId);
                continue;
            }

            var entity = new RemoteChangeSet.RemoteEntity(
                    remoteId, "TodoItem", fields, null, null);

            if (isFullSync) {
                created.add(entity);
            } else {
                updated.add(entity);
            }
        }

        return new RemoteChangeSet(created, updated, deletedIds, newSyncToken);
    }

    /**
     * 构建 Todoist Sync API commands JSON 数组。
     */
    private String buildCommandsJson(List<SyncOperation> operations) {
        var sb = new StringBuilder("[");
        boolean first = true;

        for (var op : operations) {
            if (!first) sb.append(",");
            first = false;

            var tempId = UUID.randomUUID().toString();
            var uuid = UUID.randomUUID().toString();

            switch (op) {
                case SyncOperation.Create create -> {
                    sb.append("{\"type\":\"item_add\",\"temp_id\":\"").append(tempId)
                            .append("\",\"uuid\":\"").append(uuid)
                            .append("\",\"args\":")
                            .append(JsonHelper.payloadToJson(create.remotePayload()))
                            .append("}");
                }
                case SyncOperation.Update update -> {
                    var argsJson = JsonHelper.payloadToJson(update.remotePayload());
                    // 确保 args 中包含 id
                    if (!argsJson.contains("\"id\"")) {
                        argsJson = argsJson.substring(0, argsJson.length() - 1)
                                + ",\"id\":\"" + update.remoteEntityId() + "\"}";
                    }
                    sb.append("{\"type\":\"item_update\",\"uuid\":\"").append(uuid)
                            .append("\",\"args\":").append(argsJson).append("}");
                }
                case SyncOperation.Delete delete -> {
                    sb.append("{\"type\":\"item_delete\",\"uuid\":\"").append(uuid)
                            .append("\",\"args\":{\"id\":\"")
                            .append(delete.remoteEntityId()).append("\"}}");
                }
            }
        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * 解析推送响应，统计成功/失败数。
     */
    private PushResult parsePushResponse(String responseBody, List<SyncOperation> operations) {
        // Todoist Sync API 返回 sync_status 对象，key 为 command uuid
        // 简化处理：如果整体响应成功，视为全部成功
        int total = operations.size();
        var errors = new ArrayList<PushResult.PushError>();

        // 检查是否有错误
        if (responseBody.contains("\"error\"")) {
            // 有错误时尝试解析
            log.warn("Todoist 推送响应包含错误: {}", responseBody);
            errors.add(new PushResult.PushError("batch", "推送响应包含错误"));
            return new PushResult(total - 1, 1, errors);
        }

        log.info("Todoist 推送完成: 成功={}", total);
        return new PushResult(total, 0, errors);
    }

    /**
     * 带 401 自动 Token 刷新的 HTTP 请求执行。
     */
    private HttpResponse<String> executeWithRetryAndRefresh(HttpRequest request, SyncProfile profile) {
        var response = executeWithRetry(request);

        // 401 时尝试刷新 Token
        if (response.statusCode() == 401) {
            log.info("Todoist 返回 401，尝试刷新 Token: profileId={}", profile.id());
            if (refreshToken(profile)) {
                // 用新 Token 重建请求
                var newToken = getAccessToken(profile);
                var newRequest = HttpRequest.newBuilder(request, (k, v) -> true)
                        .header("Authorization", "Bearer " + newToken)
                        .build();
                return executeWithRetry(newRequest);
            }
        }

        return response;
    }

    /**
     * 尝试刷新 OAuth2 Token。
     *
     * @return 刷新是否成功
     */
    private boolean refreshToken(SyncProfile profile) {
        try {
            var params = parseConnectionParams(profile);
            var baseUrl = getBaseUrl(profile);
            var refreshToken = credentialStore.retrieve(profile.id(), "refresh_token").orElse(null);
            if (refreshToken == null) {
                log.warn("Todoist refresh_token 不存在，无法刷新: profileId={}", profile.id());
                return false;
            }

            var clientId = params.getOrDefault("clientId", "");
            var clientSecret = params.getOrDefault("clientSecret", "");

            var formBody = "client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret)
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + encode(refreshToken);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/oauth/access_token"))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                var newAccessToken = JsonHelper.extractJsonStringValue(response.body(), "access_token");
                var newRefreshToken = JsonHelper.extractJsonStringValue(response.body(), "refresh_token");

                if (newAccessToken != null) {
                    credentialStore.store(profile.id(), "access_token", newAccessToken);
                }
                if (newRefreshToken != null) {
                    credentialStore.store(profile.id(), "refresh_token", newRefreshToken);
                }
                log.info("Todoist Token 刷新成功: profileId={}", profile.id());
                return true;
            } else {
                log.warn("Todoist Token 刷新失败: HTTP {}", response.statusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("Todoist Token 刷新异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 带指数退避重试的 HTTP 请求执行。
     */
    private HttpResponse<String> executeWithRetry(HttpRequest request) {
        int maxRetries = properties.getMaxRetries();
        long[] delayHolder = {500};

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 && attempt < maxRetries) {
                    long currentDelay = delayHolder[0];
                    var retryAfter = response.headers().firstValue("Retry-After")
                            .map(s -> {
                                try { return Long.parseLong(s) * 1000; }
                                catch (NumberFormatException e) { return currentDelay; }
                            }).orElse(currentDelay);
                    Thread.sleep(retryAfter);
                    continue;
                }
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SyncException.ConnectionException("Todoist 请求被中断", e);
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    throw new SyncException.ConnectionException(
                            "Todoist 请求失败（已重试 " + maxRetries + " 次）: " + e.getMessage(), e);
                }
                log.debug("Todoist 请求失败，第 {} 次重试: {}", attempt + 1, e.getMessage());
                try {
                    Thread.sleep(delayHolder[0]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SyncException.ConnectionException("Todoist 重试等待被中断", ie);
                }
                delayHolder[0] = Math.min(delayHolder[0] * 2, 5000);
            }
        }
        throw new SyncException.ConnectionException("Todoist 请求失败：超过最大重试次数");
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析 connectionParamsJson。
     */
    private Map<String, String> parseConnectionParams(SyncProfile profile) {
        var json = profile.connectionParamsJson();
        if (json == null || json.isBlank()) return Map.of();
        return JsonHelper.parseSimpleJsonToMap(json);
    }

    /**
     * URL 编码。
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
