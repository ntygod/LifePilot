package com.lifepilot.interaction.web.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.tool.artifact.ArtifactFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 会话产物访问控制器 — 提供 {@code /api/artifacts/{id}/download} 文件下载与
 * {@code /api/artifacts/{id}} 元数据查询两个端点。
 *
 * <p>用于 Web / Tauri 桌面端在收到 SSE {@code artifact-ref} 事件后，按需下载
 * 文件字节或拉取产物元信息渲染产物卡片。</p>
 *
 * <p>仅在 Web 通道启用时装载（参照 {@code lifepilot.gateway.channels.web.enabled}）；
 * 路径越界 / 物理文件丢失 → 404，I/O 异常 → 500。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
@RestController
@RequestMapping("/api/artifacts")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);

    private final SessionArtifactRepository artifactRepository;
    private final Path workspaceRoot;

    public ArtifactController(SessionArtifactRepository artifactRepository,
                              ZhiweiPaths zhiweiPaths) {
        this.artifactRepository = artifactRepository;
        this.workspaceRoot = zhiweiPaths.workspace();
    }

    /**
     * 按 artifactId 下载产物字节流。
     *
     * <p>路径越界 / 物理文件丢失 → 404；I/O 异常 → 500。</p>
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) {
        Optional<SessionArtifactRepository.SessionArtifactRow> rowOpt = artifactRepository.findById(id);
        if (rowOpt.isEmpty()) {
            log.warn("artifact 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Map<String, Object> payload = artifactRepository.readPayload(id);
        Object pathObj = payload.get("path");
        if (!(pathObj instanceof String pathStr) || pathStr.isBlank()) {
            log.warn("artifact 缺少 path 字段: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path path = Paths.get(pathStr);

        if (!ArtifactFilter.isInWorkspaceRoot(path, workspaceRoot)) {
            log.warn("artifact 路径越界: id={}, path={}", id, path);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!Files.isRegularFile(path)) {
            log.warn("artifact 物理文件丢失: id={}, path={}", id, path);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        try {
            byte[] data = Files.readAllBytes(path);
            String fileName = stringValue(payload.get("fileName"), rowOpt.get().title());
            String mimeType = stringValue(payload.get("mimeType"), MediaType.APPLICATION_OCTET_STREAM_VALUE);
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            MediaType contentType;
            try {
                contentType = MediaType.parseMediaType(mimeType);
            } catch (IllegalArgumentException e) {
                contentType = MediaType.APPLICATION_OCTET_STREAM;
            }

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .contentLength(data.length)
                    .body(new ByteArrayResource(data));
        } catch (IOException e) {
            log.error("读取 artifact 字节失败: id={}, path={}", id, path, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 按 artifactId 返回产物元数据 JSON：
     * {@code {id, fileName, mimeType, size, kind, summary, createdAt}}。
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> metadata(@PathVariable String id) {
        Optional<SessionArtifactRepository.SessionArtifactRow> rowOpt = artifactRepository.findById(id);
        if (rowOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        SessionArtifactRepository.SessionArtifactRow row = rowOpt.get();
        Map<String, Object> payload = artifactRepository.readPayload(id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("fileName", stringValue(payload.get("fileName"), row.title()));
        body.put("mimeType", stringValue(payload.get("mimeType"), MediaType.APPLICATION_OCTET_STREAM_VALUE));
        body.put("size", payload.getOrDefault("size", 0L));
        body.put("kind", stringValue(payload.get("kind"), row.artifactType()));
        body.put("summary", row.summary());
        body.put("createdAt", row.createdAt() != null ? row.createdAt().toString() : null);
        return ApiResponse.ok(body);
    }

    private static String stringValue(Object value, String fallback) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
