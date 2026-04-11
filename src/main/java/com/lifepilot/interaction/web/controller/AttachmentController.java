package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 附件访问控制器。
 *
 * <p>根据附件 ID 从数据库中查询文件路径并返回二进制内容，用于前端展示图片/文件。</p>
 *
 * @author zsg
 * @since 2026-03-02
 */
@RestController
@RequestMapping("/api/attachments")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    private final AttachmentRepository attachmentRepository;

    public AttachmentController(AttachmentRepository attachmentRepository) {
        this.attachmentRepository = attachmentRepository;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getAttachment(@PathVariable String id) {
        AttachmentRecord record = attachmentRepository.findById(id);
        if (record == null) {
            log.warn("附件不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path path = Paths.get(record.filePath());
        if (!Files.exists(path)) {
            log.warn("附件文件不存在: id={}, path={}", id, record.filePath());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        try {
            byte[] data = Files.readAllBytes(path);
            ByteArrayResource resource = new ByteArrayResource(data);

            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(record.mimeType());
            } catch (IllegalArgumentException e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + record.fileName() + "\"")
                    .contentLength(record.fileSize())
                    .body(resource);
        } catch (IOException e) {
            log.error("读取附件文件失败: id={}, path={}", id, record.filePath(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
