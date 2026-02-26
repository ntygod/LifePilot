package com.lifepilot.sync.connector.dida;

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
 * 滴答清单连接器，使用 Java HttpClient 调用滴答清单 Open API。
 *
 * <p>支持任务（TodoItem）双向同步和习惯（HabitItem）仅拉取同步。
 * 使用 updated 时间戳进行增量变更检测，OAuth2 Authorization Code flow 认证。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class DidaConnector implements SyncConnector {

    private static final Logger log = LoggerFactory.getLogger(DidaConnector.class);

    private static final String CONNECTOR_TYPE = "dida";

    private final CredentialStore credentialStore;
    private final SyncProperties properties;
    private final HttpClient httpClient;
    private final FieldMapping<com.lifepilot.skill.builtin.todo.TodoItem, Map<String, Object>> taskMapping;
    private final FieldMapping<com.lifepilot.skill.builtin.habit.HabitItem, Map<String, Object>> habitMapping;

    public DidaConnector(CredentialStore credentialStore, SyncProperties properties) {
        this.credentialStore = credentialStore;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeout()))
                .build();
        this.taskMapping = DidaFieldMapping.taskMapping();
        this.habitMapping = DidaFieldMapping.habitMapping();
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

            // GET /open/v1/task with limit=1
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/open/v1/task?limit=1"))
                    .GET()
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Authorization", "Bearer " + token)
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("滴答清单连接测试成功: 耗时={}ms", elapsed);
                return new ConnectionTestResult(true, elapsed, null);
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new ConnectionTestResult(false, elapsed, "认证失败: HTTP " + response.statusCode());
            } else {
                return new ConnectionTestResult(false, elapsed,
                        "滴答清单 API 返回错误: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("滴答清单连接测试失败: {}", e.getMessage());
            return new ConnectionTestResult(false, elapsed, "连接失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteChangeSet fetchChanges(SyncProfile profile, @Nullable String syncToken) {
        var baseUrl = getBaseUrl(profile);
        var token = getAccessToken(profile);

        try {
            var created = new ArrayList<RemoteChangeSet.RemoteEntity>();
            var updated = new ArrayList<RemoteChangeSet.RemoteEntity>();
            boolean isFullSync = (syncToken == null || syncToken.isBlank());

            // 拉取任务
            var taskUrl = baseUrl + "/open/v1/task";
            var taskRequest = HttpRequest.newBuilder()
                    .uri(URI.create(taskUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Authorization", "Bearer " + token)
                    .build();

            var taskResponse = executeWithRetryAndRefresh(taskRequest, profile);
            if (taskResponse.statusCode() >= 200 && taskResponse.statusCode() < 300) {
                parseTasksResponse(taskResponse.body(), syncToken, isFullSync, created, updated);
            } else {
                throw new SyncException.RemoteApiException(
                        "滴答清单任务拉取失败: HTTP " + taskResponse.statusCode());
            }

            // 拉取习惯
            var habitUrl = baseUrl + "/open/v1/habit";
            var habitRequest = HttpRequest.newBuilder()
                    .uri(URI.create(habitUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Authorization", "Bearer " + token)
                    .build();

            var habitResponse = executeWithRetryAndRefresh(habitRequest, profile);
            if (habitResponse.statusCode() >= 200 && habitResponse.statusCode() < 300) {
                parseHabitsResponse(habitResponse.body(), syncToken, isFullSync, created, updated);
            } else {
                log.warn("滴答清单习惯拉取失败: HTTP {}，跳过习惯同步", habitResponse.statusCode());
            }

            // 新的 syncToken 使用当前时间戳
            var newSyncToken = java.time.Instant.now().toString();

            return new RemoteChangeSet(created, updated, List.of(), newSyncToken);
        } catch (SyncException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException.ConnectionException("滴答清单拉取变更失败: " + e.getMessage(), e);
        }
    }

    @Override
    public PushResult pushChanges(SyncProfile profile, List<SyncOperation> operations) {
        var baseUrl = getBaseUrl(profile);
        var token = getAccessToken(profile);
        int successCount = 0;
        var errors = new ArrayList<PushResult.PushError>();

        for (var op : operations) {
            // HabitItem 仅支持 PULL_ONLY，跳过推送
            if (isHabitOperation(op)) {
                log.debug("滴答清单习惯仅支持拉取，跳过推送: op={}", op);
                continue;
            }

            try {
                switch (op) {
                    case SyncOperation.Create create -> {
                        var jsonBody = JsonHelper.payloadToJson(create.remotePayload());
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/open/v1/task"))
                                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                .timeout(Duration.ofSeconds(properties.getTimeout()))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + token)
                                .build();
                        var response = executeWithRetryAndRefresh(request, profile);
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            successCount++;
                        } else {
                            errors.add(new PushResult.PushError(create.localEntityId(),
                                    "创建失败: HTTP " + response.statusCode()));
                        }
                    }
                    case SyncOperation.Update update -> {
                        var jsonBody = JsonHelper.payloadToJson(update.remotePayload());
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/open/v1/task/" + update.remoteEntityId()))
                                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                                .timeout(Duration.ofSeconds(properties.getTimeout()))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + token)
                                .build();
                        var response = executeWithRetryAndRefresh(request, profile);
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            successCount++;
                        } else {
                            errors.add(new PushResult.PushError(update.localEntityId(),
                                    "更新失败: HTTP " + response.statusCode()));
                        }
                    }
                    case SyncOperation.Delete delete -> {
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/open/v1/task/" + delete.remoteEntityId()))
                                .DELETE()
                                .timeout(Duration.ofSeconds(properties.getTimeout()))
                                .header("Authorization", "Bearer " + token)
                                .build();
                        var response = executeWithRetryAndRefresh(request, profile);
                        if (response.statusCode() >= 200 && response.statusCode() < 300
                                || response.statusCode() == 404) {
                            successCount++;
                        } else {
                            errors.add(new PushResult.PushError(delete.localEntityId(),
                                    "删除失败: HTTP " + response.statusCode()));
                        }
                    }
                }
            } catch (Exception e) {
                var entityId = switch (op) {
                    case SyncOperation.Create c -> c.localEntityId();
                    case SyncOperation.Update u -> u.localEntityId();
                    case SyncOperation.Delete d -> d.localEntityId();
                };
                errors.add(new PushResult.PushError(entityId, "推送异常: " + e.getMessage()));
            }
        }

        log.info("滴答清单推送完成: 成功={}, 失败={}", successCount, errors.size());
        return new PushResult(successCount, errors.size(), errors);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取滴答清单 API 基础 URL。
     */
    private String getBaseUrl(SyncProfile profile) {
        var params = parseConnectionParams(profile);
        return params.getOrDefault("baseUrl",
                properties.getConnectors().getDida().getBaseUrl());
    }

    /**
     * 获取 OAuth2 Access Token。
     */
    private String getAccessToken(SyncProfile profile) {
        return credentialStore.retrieve(profile.id(), "access_token")
                .orElseThrow(() -> new SyncException.CredentialException(
                        "滴答清单 access_token 不存在: profileId=" + profile.id()));
    }

    /**
     * 解析任务列表响应。
     */
    private void parseTasksResponse(String responseBody, @Nullable String syncToken,
                                    boolean isFullSync,
                                    List<RemoteChangeSet.RemoteEntity> created,
                                    List<RemoteChangeSet.RemoteEntity> updated) {
        // 响应可能是 JSON 数组或包含数组的对象
        var items = extractItemsFromResponse(responseBody);
        for (var itemJson : items) {
            var fields = JsonHelper.parseSimpleJsonObject(itemJson);
            if (fields.isEmpty()) continue;

            var remoteId = fields.getOrDefault("id", "").toString();
            if (remoteId.isEmpty()) continue;

            // 增量过滤：比较 updated 时间戳
            if (!isFullSync && syncToken != null) {
                var updatedAt = fields.get("modifiedTime");
                if (updatedAt == null) updatedAt = fields.get("updated");
                if (updatedAt != null && updatedAt.toString().compareTo(syncToken) <= 0) {
                    continue;
                }
            }

            var entity = new RemoteChangeSet.RemoteEntity(
                    remoteId, "TodoItem", fields, null,
                    fields.containsKey("modifiedTime") ? fields.get("modifiedTime").toString() : null);

            if (isFullSync) {
                created.add(entity);
            } else {
                updated.add(entity);
            }
        }
    }

    /**
     * 解析习惯列表响应。
     */
    private void parseHabitsResponse(String responseBody, @Nullable String syncToken,
                                     boolean isFullSync,
                                     List<RemoteChangeSet.RemoteEntity> created,
                                     List<RemoteChangeSet.RemoteEntity> updated) {
        var items = extractItemsFromResponse(responseBody);
        for (var itemJson : items) {
            var fields = JsonHelper.parseSimpleJsonObject(itemJson);
            if (fields.isEmpty()) continue;

            var remoteId = fields.getOrDefault("id", "").toString();
            if (remoteId.isEmpty()) continue;

            // 增量过滤
            if (!isFullSync && syncToken != null) {
                var updatedAt = fields.get("modifiedTime");
                if (updatedAt == null) updatedAt = fields.get("updated");
                if (updatedAt != null && updatedAt.toString().compareTo(syncToken) <= 0) {
                    continue;
                }
            }

            var entity = new RemoteChangeSet.RemoteEntity(
                    remoteId, "HabitItem", fields, null,
                    fields.containsKey("modifiedTime") ? fields.get("modifiedTime").toString() : null);

            if (isFullSync) {
                created.add(entity);
            } else {
                updated.add(entity);
            }
        }
    }

    /**
     * 判断操作是否涉及 HabitItem。
     */
    private boolean isHabitOperation(SyncOperation op) {
        return switch (op) {
            case SyncOperation.Create c -> "HabitItem".equals(c.localEntityType());
            case SyncOperation.Update u -> "HabitItem".equals(u.localEntityType());
            case SyncOperation.Delete d -> "HabitItem".equals(d.localEntityType());
        };
    }

    /**
     * 从响应体中提取 JSON 对象列表（支持数组或包含数组的对象）。
     */
    private List<String> extractItemsFromResponse(String responseBody) {
        var trimmed = responseBody.strip();
        if (trimmed.startsWith("[")) {
            // 直接是数组
            return JsonHelper.extractJsonArray("[" + trimmed.substring(1), "");
        }
        // 尝试从对象中提取
        // 简化：直接按顶层对象分割
        return splitTopLevelObjects(trimmed);
    }

    /**
     * 从 JSON 数组字符串中分割顶层对象。
     */
    private List<String> splitTopLevelObjects(String content) {
        var objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(content.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    /**
     * 带 401 自动 Token 刷新的 HTTP 请求执行。
     */
    private HttpResponse<String> executeWithRetryAndRefresh(HttpRequest request, SyncProfile profile) {
        var response = executeWithRetry(request);

        if (response.statusCode() == 401) {
            log.info("滴答清单返回 401，尝试刷新 Token: profileId={}", profile.id());
            if (refreshToken(profile)) {
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
     */
    private boolean refreshToken(SyncProfile profile) {
        try {
            var params = parseConnectionParams(profile);
            var baseUrl = getBaseUrl(profile);
            var refreshToken = credentialStore.retrieve(profile.id(), "refresh_token").orElse(null);
            if (refreshToken == null) {
                log.warn("滴答清单 refresh_token 不存在，无法刷新: profileId={}", profile.id());
                return false;
            }

            var clientId = params.getOrDefault("clientId", "");
            var clientSecret = params.getOrDefault("clientSecret", "");

            var formBody = "client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret)
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + encode(refreshToken);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/oauth/token"))
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
                log.info("滴答清单 Token 刷新成功: profileId={}", profile.id());
                return true;
            } else {
                log.warn("滴答清单 Token 刷新失败: HTTP {}", response.statusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("滴答清单 Token 刷新异常: {}", e.getMessage());
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
                throw new SyncException.ConnectionException("滴答清单请求被中断", e);
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    throw new SyncException.ConnectionException(
                            "滴答清单请求失败（已重试 " + maxRetries + " 次）: " + e.getMessage(), e);
                }
                log.debug("滴答清单请求失败，第 {} 次重试: {}", attempt + 1, e.getMessage());
                try {
                    Thread.sleep(delayHolder[0]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SyncException.ConnectionException("滴答清单重试等待被中断", ie);
                }
                delayHolder[0] = Math.min(delayHolder[0] * 2, 5000);
            }
        }
        throw new SyncException.ConnectionException("滴答清单请求失败：超过最大重试次数");
    }

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
