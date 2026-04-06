package com.lifepilot.media.audio;

import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.audio.transcription.TranscriptionModel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * AudioTranscriber 可用性检测测试。
 *
 * <p>覆盖 {@link AudioTranscriber#isAvailable()} 的三种组合：
 * 仅 whisperCli 可用、仅 transcriptionModel 可用、都不可用。</p>
 *
 * @author zsg
 * @since 2026-04-06
 */
@ExtendWith(MockitoExtension.class)
class AudioTranscriber_可用性检测测试 {

    @Mock
    private WhisperCliTranscriber whisperCli;

    @Mock
    private TranscriptionModel transcriptionModel;

    private final MediaProperties properties = new MediaProperties();

    @Test
    void whisperCli可用时返回true() {
        when(whisperCli.isAvailable()).thenReturn(true);
        var transcriber = new AudioTranscriber(whisperCli, null, properties);

        assertTrue(transcriber.isAvailable());
    }

    @Test
    void whisperCli不可用但transcriptionModel非null时返回true() {
        when(whisperCli.isAvailable()).thenReturn(false);
        var transcriber = new AudioTranscriber(whisperCli, transcriptionModel, properties);

        assertTrue(transcriber.isAvailable());
    }

    @Test
    void transcriptionModel非null且无whisperCli时返回true() {
        var transcriber = new AudioTranscriber(null, transcriptionModel, properties);

        assertTrue(transcriber.isAvailable());
    }

    @Test
    void whisperCli和transcriptionModel都为null时返回false() {
        var transcriber = new AudioTranscriber(null, null, properties);

        assertFalse(transcriber.isAvailable());
    }

    @Test
    void whisperCli不可用且transcriptionModel为null时返回false() {
        when(whisperCli.isAvailable()).thenReturn(false);
        var transcriber = new AudioTranscriber(whisperCli, null, properties);

        assertFalse(transcriber.isAvailable());
    }
}
