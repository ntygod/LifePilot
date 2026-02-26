package com.lifepilot.sync.connector.caldav;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * CalDAV 连接器，使用 Java HttpClient 与 CalDAV 服务器通信。
 *
 * <p>支持 sync-collection REPORT（RFC 6578）增量同步和 ETag 条件 PUT 推送。
 * 认证方式支持 Basic Auth 和 OAuth2 Bearer Token。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class CalDavConnector implements SyncConnector {

    private static final Logger log = LoggerFactory.getLogger(CalDavConnector.class);

    private static final String CONNECTOR_TYPE = "caldav";

    private final CredentialStore credentialStore;
    private final SyncProperties properties;
    private final HttpClient httpClient;
    private final FieldMapping<com.lifepilot.skill.builtin.schedule.ScheduleItem, String> eventMapping;
    private final FieldMapping<com.lifepilot.skill.builtin.todo.TodoItem, String> todoMapping;

    public CalDavConnector(CredentialStore credentialStore, SyncProperties properties) {
        this.credentialStore = credentialStore;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeout()))
                .build();
        this.eventMapping = CalDavFieldMapping.eventMapping();
        this.todoMapping = CalDavFieldMapping.todoMapping();
    }

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public ConnectionTestResult testConnection(SyncProfile profile) {
        long start = System.currentTimeMillis();
        try {
            var params = parseConnectionParams(profile);
            var calendarUrl = buildCalendarUrl(params);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(calendarUrl))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .headers(buildAuthHeaders(profile, params))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("CalDAV 连接测试成功: url={}, 耗时={}ms", calendarUrl, elapsed);
                return new ConnectionTestResult(true, elapsed, null);
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new ConnectionTestResult(false, elapsed, "认证失败: HTTP " + response.statusCode());
            } else {
                return new ConnectionTestResult(false, elapsed,
                        "CalDAV 服务器返回错误: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("CalDAV 连接测试失败: {}", e.getMessage());
            return new ConnectionTestResult(false, elapsed, "连接失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteChangeSet fetchChanges(SyncProfile profile, @Nullable String syncToken) {
        var params = parseConnectionParams(profile);
        var calendarUrl = buildCalendarUrl(params);

        try {
            // 构建 sync-collection REPORT XML 请求体
            var xmlBody = buildSyncCollectionReport(syncToken);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(calendarUrl))
                    .method("REPORT", HttpRequest.BodyPublishers.ofString(xmlBody))
                    .timeout(Duration.ofSeconds(properties.getTimeout()))
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .header("Depth", "1")
                    .headers(buildAuthHeaders(profile, params))
                    .build();

            var response = executeWithRetry(request);

            // sync-token 过期时服务器返回 410 Gone，回退全量同步
            if (response.statusCode() == 410) {
                log.info("CalDAV sync-token 已过期，回退全量同步: profileId={}", profile.id());
                return fetchChanges(profile, null);
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SyncException.RemoteApiException(
                        "CalDAV REPORT 请求失败: HTTP " + response.statusCode());
            }

            return parseMultistatusResponse(response.body(), syncToken == null);
        } catch (SyncException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException.ConnectionException("CalDAV 拉取变更失败: " + e.getMessage(), e);
        }
    }

    @Override
    public PushResult pushChanges(SyncProfile profile, List<SyncOperation> operations) {
        var params = parseConnectionParams(profile);
        var calendarUrl = buildCalendarUrl(params);
        int successCount = 0;
        var errors = new ArrayList<PushResult.PushError>();

        for (var op : operations) {
            try {
                switch (op) {
                    case SyncOperation.Create create -> {
                        var resourceUrl = calendarUrl + "/" + create.localEntityId() + ".ics";
                        var icalContent = convertToICal(create.localEntityType(), create.remotePayload());
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(resourceUrl))
                                .PUT(HttpRequest.BodyPublishers.ofString(icalContent))
                                .timeout(Duration.ofSeconds(properties.getTimeout()))
                                .header("Content-Type", "text/calendar; charset=utf-8")
                                .header("If-None-Match", "*")
                                .headers(buildAuthHeaders(profile, params))
                                .build();
                        var response = executeWithRetry(request);
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            successCount++;
                        } else {
                            errors.add(new PushResult.PushError(create.localEntityId(),
                                    "PUT 创建失败: HTTP " + response.statusCode()));
                        }
                    }
                    case SyncOperation.Update update -> {
                        var resourceUrl = calendarUrl + "/" + update.remoteEntityId() + ".ics";
                        var icalContent = convertToICal(update.localEntityType(), update.remotePayload());
                        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(resourceUrl))
                                .PUT(HttpRequest.BodyPublishers.ofString(icalContent))
                                .timeout(Duration.ofSeconds(properties.getTimeout()))
                                .header("Content-Type", "text/calendar; charset=utf-8")
                                .headers(buildAuthHeaders(profile, params));
                        // ETag 条件 PUT
                        var request = builder.build();
                        var response = executeWithRetry(request);
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            successCount++;
                        } else {
                            errors.add(new PushResult.PushError(update.localEntityId(),
                                    "PUT 更新失败: HTTP " + response.statusCode()));
                        }
                    }
                    case SyncOperation.Delete delete -> {
                        var resourceUrl = calendarUrl + "/" + delete.remoteEntityId() + ".ics";
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(resourceUrl))
                                .DELETE()
                                .timeout(Duration.ofSeconds(properties.getTimeout()))
                                .headers(buildAuthHeaders(profile, params))
                                .build();
                        var response = executeWithRetry(request);
                        if (response.statusCode() >= 200 && response.statusCode() < 300
                                || response.statusCode() == 404) {
                            successCount++;
                        } else {
                            errors.add(new PushResult.PushError(delete.localEntityId(),
                                    "DELETE 失败: HTTP " + response.statusCode()));
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

        log.info("CalDAV 推送完成: 成功={}, 失败={}", successCount, errors.size());
        return new PushResult(successCount, errors.size(), errors);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 解析 connectionParamsJson 中的连接参数。
     */
    private Map<String, String> parseConnectionParams(SyncProfile profile) {
        var json = profile.connectionParamsJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JsonHelper.parseSimpleJsonToMap(json);
    }

    /**
     * 构建日历 URL。
     */
    private String buildCalendarUrl(Map<String, String> params) {
        var serverUrl = params.getOrDefault("serverUrl",
                properties.getConnectors().getCaldav().getBaseUrl());
        var calendarPath = params.getOrDefault("calendarPath", "");
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        if (!calendarPath.isEmpty() && !calendarPath.startsWith("/")) {
            calendarPath = "/" + calendarPath;
        }
        return serverUrl + calendarPath;
    }

    /**
     * 构建认证 Header 数组。
     */
    private String[] buildAuthHeaders(SyncProfile profile, Map<String, String> params) {
        var authType = params.getOrDefault("authType",
                properties.getConnectors().getCaldav().getAuthType());

        if ("oauth2".equalsIgnoreCase(authType)) {
            var token = credentialStore.retrieve(profile.id(), "access_token")
                    .orElseThrow(() -> new SyncException.CredentialException(
                            "CalDAV OAuth2 access_token 不存在: profileId=" + profile.id()));
            return new String[]{"Authorization", "Bearer " + token};
        } else {
            // Basic Auth
            var username = params.getOrDefault("username", "");
            var password = credentialStore.retrieve(profile.id(), "password").orElse("");
            var credentials = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes());
            return new String[]{"Authorization", "Basic " + credentials};
        }
    }

    /**
     * 构建 sync-collection REPORT XML 请求体。
     */
    private String buildSyncCollectionReport(@Nullable String syncToken) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
        sb.append("<D:sync-collection xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        if (syncToken != null) {
            sb.append("  <D:sync-token>").append(escapeXml(syncToken)).append("</D:sync-token>\n");
        } else {
            sb.append("  <D:sync-token/>\n");
        }
        sb.append("  <D:sync-level>1</D:sync-level>\n");
        sb.append("  <D:prop>\n");
        sb.append("    <D:getetag/>\n");
        sb.append("    <C:calendar-data/>\n");
        sb.append("  </D:prop>\n");
        sb.append("</D:sync-collection>\n");
        return sb.toString();
    }

    /**
     * 解析 multistatus XML 响应，提取远程变更集。
     */
    private RemoteChangeSet parseMultistatusResponse(String xmlBody, boolean isFullSync) {
        var created = new ArrayList<RemoteChangeSet.RemoteEntity>();
        var updated = new ArrayList<RemoteChangeSet.RemoteEntity>();
        var deletedIds = new ArrayList<String>();
        String newSyncToken = null;

        // 简易 XML 解析：提取 sync-token 和 response 元素
        newSyncToken = extractXmlValue(xmlBody, "sync-token");
        if (newSyncToken == null) {
            newSyncToken = extractXmlValue(xmlBody, "D:sync-token");
        }

        // 提取每个 response 元素
        var responses = extractXmlBlocks(xmlBody, "response", "D:response");
        for (var responseBlock : responses) {
            var href = extractXmlValue(responseBlock, "href");
            if (href == null) {
                href = extractXmlValue(responseBlock, "D:href");
            }
            if (href == null) continue;

            // 检查是否为 404（已删除）
            var status = extractXmlValue(responseBlock, "status");
            if (status == null) {
                status = extractXmlValue(responseBlock, "D:status");
            }
            if (status != null && status.contains("404")) {
                var remoteId = extractResourceId(href);
                if (remoteId != null) {
                    deletedIds.add(remoteId);
                }
                continue;
            }

            // 提取 ETag 和 calendar-data
            var etag = extractXmlValue(responseBlock, "getetag");
            if (etag == null) {
                etag = extractXmlValue(responseBlock, "D:getetag");
            }
            var calendarData = extractXmlValue(responseBlock, "calendar-data");
            if (calendarData == null) {
                calendarData = extractXmlValue(responseBlock, "C:calendar-data");
            }
            if (calendarData == null || calendarData.isBlank()) continue;

            // 解析 iCalendar 数据
            var props = ICalendarParser.parseComponent(calendarData);
            if (props.isEmpty()) continue;

            var componentType = props.get("X-COMPONENT-TYPE");
            var uid = props.getOrDefault("UID", extractResourceId(href));
            if (uid == null) continue;

            var entityType = "VEVENT".equals(componentType) ? "ScheduleItem" : "TodoItem";
            var fields = new LinkedHashMap<String, Object>(props);
            fields.put("_raw_ical", calendarData);

            var entity = new RemoteChangeSet.RemoteEntity(
                    uid, entityType, fields, etag, null);

            if (isFullSync) {
                created.add(entity);
            } else {
                updated.add(entity);
            }
        }

        return new RemoteChangeSet(created, updated, deletedIds, newSyncToken);
    }

    /**
     * 将本地实体转换为 iCalendar 文本。
     *
     * <p>根据实体类型使用对应的 FieldMapping 进行转换：
     * ScheduleItem → VEVENT，TodoItem → VTODO。
     * 如果 payload 已经是 String（iCalendar 文本），直接返回。</p>
     */
    private String convertToICal(String entityType, Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        if (payload instanceof com.lifepilot.skill.builtin.schedule.ScheduleItem schedule) {
            return eventMapping.toRemote(schedule);
        }
        if (payload instanceof com.lifepilot.skill.builtin.todo.TodoItem todo) {
            return todoMapping.toRemote(todo);
        }
        throw new SyncException.MappingException(
                "不支持的实体类型: " + entityType + ", payload=" + payload.getClass().getName());
    }

    /**
     * 带指数退避重试的 HTTP 请求执行。
     */
    private HttpResponse<String> executeWithRetry(HttpRequest request) {
        int maxRetries = properties.getMaxRetries();
        long[] delayHolder = {500}; // 使用数组避免 lambda 捕获限制

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                // 429 Rate Limited 时读取 Retry-After
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
                throw new SyncException.ConnectionException("CalDAV 请求被中断", e);
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    throw new SyncException.ConnectionException(
                            "CalDAV 请求失败（已重试 " + maxRetries + " 次）: " + e.getMessage(), e);
                }
                log.debug("CalDAV 请求失败，第 {} 次重试: {}", attempt + 1, e.getMessage());
                try {
                    Thread.sleep(delayHolder[0]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SyncException.ConnectionException("CalDAV 重试等待被中断", ie);
                }
                delayHolder[0] = Math.min(delayHolder[0] * 2, 5000); // 指数退避，上限 5s
            }
        }
        throw new SyncException.ConnectionException("CalDAV 请求失败：超过最大重试次数");
    }

    // ==================== 简易 XML/JSON 解析工具 ====================

    /**
     * 从 XML 中提取指定标签的文本值（取第一个匹配）。
     */
    private static String extractXmlValue(String xml, String tagName) {
        var openTag = "<" + tagName;
        int start = xml.indexOf(openTag);
        if (start < 0) return null;

        // 跳过属性部分，找到 '>'
        int gtIndex = xml.indexOf('>', start + openTag.length());
        if (gtIndex < 0) return null;

        // 自闭合标签
        if (xml.charAt(gtIndex - 1) == '/') return null;

        var closeTag = "</" + tagName + ">";
        int end = xml.indexOf(closeTag, gtIndex + 1);
        if (end < 0) return null;

        return xml.substring(gtIndex + 1, end).trim();
    }

    /**
     * 从 XML 中提取所有匹配标签的块（支持两个候选标签名）。
     */
    private static List<String> extractXmlBlocks(String xml, String tagName1, String tagName2) {
        var blocks = new ArrayList<String>();
        extractBlocksForTag(xml, tagName1, blocks);
        if (blocks.isEmpty()) {
            extractBlocksForTag(xml, tagName2, blocks);
        }
        return blocks;
    }

    private static void extractBlocksForTag(String xml, String tagName, List<String> blocks) {
        var openTag = "<" + tagName;
        var closeTag = "</" + tagName + ">";
        int pos = 0;
        while (pos < xml.length()) {
            int start = xml.indexOf(openTag, pos);
            if (start < 0) break;
            int end = xml.indexOf(closeTag, start);
            if (end < 0) break;
            blocks.add(xml.substring(start, end + closeTag.length()));
            pos = end + closeTag.length();
        }
    }

    /**
     * 从 href 中提取资源 ID（去掉路径和 .ics 后缀）。
     */
    private static String extractResourceId(String href) {
        if (href == null) return null;
        var name = href;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        if (name.endsWith(".ics")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * XML 特殊字符转义。
     */
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
