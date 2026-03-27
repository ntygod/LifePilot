package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebExceptionHandler 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class WebExceptionHandlerTest {

    @Test
    void handleMaxUploadSizeExceeded_返回413和明确提示() {
        var handler = new WebExceptionHandler();

        ResponseEntity<ErrorResponse> response =
                handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(1048576L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(413);
        assertThat(response.getBody().message()).isEqualTo("文件大小超过限制（最大 1MB）");
    }
}
