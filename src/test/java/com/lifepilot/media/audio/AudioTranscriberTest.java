package com.lifepilot.media.audio;

import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AudioTranscriber 单元测试。
 *
 * 覆盖级联降级（本地优先→云端回退→全部不可用异常）和音频大小校验相关逻辑。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class AudioTranscriberTest {

    @Mock
    private WhisperCliTranscriber whisperCli;

    @Mock
    private TranscriptionModel transcriptionModel;

    private MediaProperties properties;

    @InjectMocks
    private AudioTranscriber transcriber;

    @BeforeEach
    void setUp() {
        properties = new MediaProperties();
        transcriber = new AudioTranscriber(whisperCli, transcriptionModel, properties);
    }

    @Test
    void 本地whisper可用且转录成功时优先使用本地不触发云端回退() {
        byte[] audio = new byte[] {1, 2, 3};

        when(whisperCli.isAvailable()).thenReturn(true);
        when(whisperCli.transcribe(audio, "audio/wav")).thenReturn("本地转录结果");

        String text = transcriber.transcribe(audio, "audio/wav");

        assertEquals("本地转录结果", text);
        verify(whisperCli).isAvailable();
        verify(whisperCli).transcribe(audio, "audio/wav");
        verifyNoInteractions(transcriptionModel);
    }

    @Test
    void 本地whisper失败且存在云端模型时回退到云端转录() {
        byte[] audio = new byte[] {4, 5, 6};

        when(whisperCli.isAvailable()).thenReturn(true);
        when(whisperCli.transcribe(audio, "audio/wav"))
                .thenThrow(new AudioTranscriptionException("本地失败"));

        // 使用 Mockito 深度 stub，避免依赖具体 Result 类型
        AudioTranscriptionResponse response = mock(AudioTranscriptionResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput()).thenReturn("云端转录结果");
        when(transcriptionModel.call(any(AudioTranscriptionPrompt.class))).thenReturn(response);

        String text = transcriber.transcribe(audio, "audio/wav");

        assertEquals("云端转录结果", text);
        verify(whisperCli).isAvailable();
        verify(whisperCli).transcribe(audio, "audio/wav");
        verify(transcriptionModel).call(any(AudioTranscriptionPrompt.class));
    }

    @Test
    void 本地和云端都不可用时抛出AudioTranscriptionException() {
        byte[] audio = new byte[] {7, 8, 9};

        // 本地不可用
        when(whisperCli.isAvailable()).thenReturn(false);
        // 云端模型为 null
        transcriber = new AudioTranscriber(whisperCli, null, properties);

        AudioTranscriptionException ex = assertThrows(
                AudioTranscriptionException.class,
                () -> transcriber.transcribe(audio, "audio/wav")
        );

        assertEquals("所有语音转录方式均不可用", ex.getMessage());
    }
}

