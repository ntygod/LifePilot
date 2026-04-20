package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.SessionDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * DocumentController 下载端点测试。
 *
 * <p>覆盖：正常下载 + 中文文件名 URL 编码；记录缺失 404；物理文件缺失 404。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentController_下载端点测试 {

    @Mock
    SessionDocumentRepository sessionDocumentRepository;

    @Test
    void 成功返回文档字节含正确_Content_Disposition(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("report.docx");
        byte[] payload = "fake docx bytes".getBytes();
        Files.write(file, payload);

        when(sessionDocumentRepository.findById("doc-1")).thenReturn(new SessionDocumentRecord(
                "doc-1", "sess-1", "entry-1", "Q3 报表.docx", file.toString(),
                payload.length,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now()));

        var controller = new DocumentController(sessionDocumentRepository);
        ResponseEntity<ByteArrayResource> response = controller.download("doc-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getByteArray()).isEqualTo(payload);
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("Q3%20%E6%8A%A5%E8%A1%A8.docx");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);
    }

    @Test
    void 文档不存在返回_404() {
        when(sessionDocumentRepository.findById("missing")).thenReturn(null);

        var controller = new DocumentController(sessionDocumentRepository);

        assertThatThrownBy(() -> controller.download("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 记录存在但物理文件丢失返回_404(@TempDir Path tmp) {
        Path deleted = tmp.resolve("gone.docx");
        // 文件故意不创建
        when(sessionDocumentRepository.findById("doc-x")).thenReturn(new SessionDocumentRecord(
                "doc-x", "sess-1", null, "gone.docx", deleted.toString(), 100L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now()));

        var controller = new DocumentController(sessionDocumentRepository);

        assertThatThrownBy(() -> controller.download("doc-x"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
