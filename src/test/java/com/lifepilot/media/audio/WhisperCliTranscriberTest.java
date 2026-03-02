package com.lifepilot.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WhisperCliTranscriber 单元测试。
 *
 * 由于集成真实 CLI 依赖环境，这里仅覆盖可用性检测和不可用时的失败分支。
 *
 * @author zsg
 * @since 2026-07-01
 */
class WhisperCliTranscriberTest {

    @Test
    void 指定不存在的cli路径时isAvailable返回false() {
        WhisperCliTranscriber transcriber = new WhisperCliTranscriber(
                "D:/path/to/non-existent/whisper-cli.exe",
                "tiny"
        );

        assertFalse(transcriber.isAvailable());
    }

    @Test
    void cli不可用时转录抛出AudioTranscriptionException() {
        WhisperCliTranscriber transcriber = new WhisperCliTranscriber(
                "D:/path/to/non-existent/whisper-cli.exe",
                "tiny"
        );

        AudioTranscriptionException ex = assertThrows(
                AudioTranscriptionException.class,
                () -> transcriber.transcribe(new byte[] {1, 2, 3}, "audio/wav")
        );

        assertTrue(ex.getMessage().contains("Whisper CLI 不可用"));
    }
}

