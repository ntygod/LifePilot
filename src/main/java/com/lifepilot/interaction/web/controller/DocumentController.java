package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.interaction.web.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档产物访问控制器 —— Phase 3A 扩展：元数据 / 版本列表 / diff / commit / rollback / 丢弃工作副本。
 *
 * <p>Phase 2A 仅提供 {@code /{id}/download}，本次扩展新增 5 个端点并让 download 支持
 * {@code ?version=} 指向历史版本文件。所有新端点使用结构化 JSON 响应；错误语义对齐：</p>
 * <ul>
 *   <li>业务校验失败（{@link IllegalArgumentException}/{@link IllegalStateException}） → 400</li>
 *   <li>资源缺失 → 404</li>
 *   <li>I/O 故障 → 500</li>
 * </ul>
 *
 * <p>仅在 Web 通道启用时装载（参照 AttachmentController 的 @ConditionalOnProperty）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@RestController
@RequestMapping("/api/documents")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final SessionDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentVersionService versionService;

    public DocumentController(SessionDocumentRepository documentRepository,
                              DocumentVersionRepository versionRepository,
                              DocumentVersionService versionService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.versionService = versionService;
    }

    // ===== Phase 2A 下载（P3 扩展 version 参数）=====

    /**
     * 按文档 ID 返回文件二进制流；可选 {@code version} 指向历史版本，缺省读取最新工作副本。
     *
     * @param id      文档 UUID
     * @param version 可选版本号；为 null 则下载 {@code session_documents.file_path}
     * @return 200 + 文件字节；记录/版本/物理文件缺失返回 404；I/O 异常返回 500
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id,
                                                      @RequestParam(required = false) Integer version) {
        SessionDocumentRecord record = documentRepository.findById(id);
        if (record == null) {
            log.warn("文档不存在：id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path path;
        if (version != null) {
            DocumentVersionRecord ver = versionRepository.findByDocumentIdAndVersion(id, version);
            if (ver == null) {
                log.warn("文档版本不存在：id={}, version={}", id, version);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            path = Paths.get(ver.filePath());
        } else {
            path = Paths.get(record.filePath());
        }

        if (!Files.exists(path)) {
            log.warn("文档文件丢失：id={}, path={}", id, path);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        try {
            byte[] data = Files.readAllBytes(path);
            // 文件名按 RFC 5987 编码，+ 还原为 %20 以兼容空格展示
            String encoded = URLEncoder.encode(record.fileName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(record.mimeType());
            } catch (IllegalArgumentException e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .contentLength(data.length)
                    .body(new ByteArrayResource(data));
        } catch (IOException e) {
            log.error("读取文档字节失败：id={}, path={}", id, path, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===== P3 新增端点 =====

    /**
     * 列出会话下的文档工作副本。
     *
     * <p>{@code status=working} 仅返回已开始编辑（latestVersion > 0）的文档 ——
     * 这是前端"文档工作区"面板的主要场景，用户想回到之前改过的文档。</p>
     * <p>{@code status=all} 返回该 session 下所有 session_documents 行。</p>
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listBySession(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "working") String status) {
        List<SessionDocumentRecord> all = documentRepository.findBySessionId(sessionId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionDocumentRecord r : all) {
            if ("working".equals(status) && r.latestVersion() <= 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("fileName", r.fileName());
            m.put("mimeType", r.mimeType());
            m.put("fileSize", r.fileSize());
            m.put("origin", r.origin());
            m.put("sourcePath", r.sourcePath());
            m.put("latestVersion", r.latestVersion());
            m.put("createdAt", r.createdAt().toString());
            items.add(m);
        }
        return ApiResponse.ok(items);
    }

    /**
     * 获取文档元数据（供前端 diff 卡片渲染）。
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getMetadata(@PathVariable String id) {
        SessionDocumentRecord record = documentRepository.findById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.id());
        map.put("fileName", record.fileName());
        map.put("mimeType", record.mimeType());
        map.put("fileSize", record.fileSize());
        map.put("origin", record.origin());
        map.put("sourcePath", record.sourcePath());
        map.put("latestVersion", record.latestVersion());
        map.put("createdAt", record.createdAt().toString());
        return ApiResponse.ok(map);
    }

    /**
     * 列出文档版本（按 version_no 升序，分页）。
     * 响应结构 {@code { items, total, page, pageSize }} 对齐项目 API 分页规范。
     */
    @GetMapping("/{id}/versions")
    public ApiResponse<Map<String, Object>> listVersions(@PathVariable String id,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int pageSize) {
        if (documentRepository.findById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var pageResult = versionService.listVersions(id, page, pageSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocumentVersionRecord v : pageResult.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("versionNo", v.versionNo());
            m.put("source", v.source());
            m.put("patchSummary", v.patchSummary());
            m.put("createdAt", v.createdAt().toString());
            items.add(m);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("total", pageResult.total());
        response.put("page", pageResult.page());
        response.put("pageSize", pageResult.pageSize());
        return ApiResponse.ok(response);
    }

    /**
     * 返回指定区间（仅实现取 to 版本缓存 diff 的简化语义）。
     */
    @GetMapping("/{id}/diff")
    public ApiResponse<Map<String, Object>> diff(@PathVariable String id,
                                                  @RequestParam int from,
                                                  @RequestParam int to) {
        if (documentRepository.findById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        DocumentVersionRecord ver = versionRepository.findByDocumentIdAndVersion(id, to);
        if (ver == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", id);
        m.put("from", from);
        m.put("to", to);
        m.put("diffJson", ver.diffJson() == null ? "" : ver.diffJson());
        return ApiResponse.ok(m);
    }

    /** commit 请求体 —— target = overwrite / saveAs；saveAs 时 saveAsPath 不能为空。 */
    public record CommitRequest(String target, String saveAsPath) {}

    /**
     * 提交工作副本 —— 覆盖原 sourcePath 或另存到新路径。
     */
    @PostMapping("/{id}/commit")
    public ApiResponse<Map<String, Object>> commit(@PathVariable String id, @RequestBody CommitRequest body) {
        try {
            DocumentVersionService.CommitResult result;
            if ("overwrite".equals(body.target())) {
                result = versionService.commitOverwrite(id);
            } else if ("saveAs".equals(body.target())) {
                if (body.saveAsPath() == null || body.saveAsPath().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "saveAsPath 不能为空");
                }
                result = versionService.commitSaveAs(id, body.saveAsPath());
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target 必须是 overwrite/saveAs");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("committedPath", result.committedPath());
            if (result.backupPath() != null) {
                m.put("backupPath", result.backupPath());
            }
            return ApiResponse.ok(m);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.error("commit 失败：id={}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** rollback 请求体 —— 目标版本号。 */
    public record RollbackRequest(int version) {}

    /**
     * 回滚到指定版本（生成新版本快照；不抹掉历史链）。
     */
    @PostMapping("/{id}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable String id, @RequestBody RollbackRequest body) {
        try {
            var result = versionService.rollback(id, body.version());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("newVersion", result.newVersion());
            m.put("summary", result.patchSummary());
            return ApiResponse.ok(m);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.error("rollback 失败：id={}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * 丢弃工作副本（删除 working 目录 + 版本链 + session_documents 行）。
     */
    @DeleteMapping("/{id}/working-copy")
    public ResponseEntity<Void> discardWorkingCopy(@PathVariable String id) {
        try {
            versionService.discard(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            log.error("discard 失败：id={}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
