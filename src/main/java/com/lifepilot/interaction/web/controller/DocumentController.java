package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.SessionDocumentRepository;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文档产物访问控制器。
 *
 * <p>按 {@code documentId} 从 {@link SessionDocumentRepository} 查找元数据后回读文件字节，
 * 以 {@code attachment} 形式响应下载。Content-Disposition 采用 RFC 5987
 * {@code filename*=UTF-8''} 格式，确保中文文件名不乱码。</p>
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

    private final SessionDocumentRepository sessionDocumentRepository;

    public DocumentController(SessionDocumentRepository sessionDocumentRepository) {
        this.sessionDocumentRepository = sessionDocumentRepository;
    }

    /**
     * 按文档 ID 返回文件二进制流。
     *
     * @param id 文档 UUID
     * @return 200 + 文件字节；记录或物理文件缺失返回 404；I/O 异常返回 500
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) {
        SessionDocumentRecord record = sessionDocumentRepository.findById(id);
        if (record == null) {
            log.warn("文档不存在：id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path path = Paths.get(record.filePath());
        if (!Files.exists(path)) {
            log.warn("文档文件丢失：id={}, path={}", id, record.filePath());
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
                    .contentLength(record.fileSize())
                    .body(new ByteArrayResource(data));
        } catch (IOException e) {
            log.error("读取文档字节失败：id={}, path={}", id, record.filePath(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
