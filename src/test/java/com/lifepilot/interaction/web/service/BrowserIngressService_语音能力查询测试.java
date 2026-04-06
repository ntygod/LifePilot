package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * BrowserIngressService 语音能力查询测试。
 *
 * <p>覆盖 {@link BrowserIngressService#voiceCapability()} 的各种组合：
 * 原生音频可用、STT 可用、都不可用。</p>
 *
 * @author zsg
 * @since 2026-04-06
 */
@ExtendWith(MockitoExtension.class)
class BrowserIngressService_语音能力查询测试 {

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private ChatTurnService chatTurnService;

    @Mock
    private AudioTranscriber audioTranscriber;

    private final MediaProperties mediaProperties = new MediaProperties();

    /**
     * 构建 BrowserIngressService 实例，指定 nativeAudioProbe 和 audioTranscriber。
     */
    private BrowserIngressService 创建服务(boolean nativeAudioResult, AudioTranscriber transcriber) {
        return new BrowserIngressService(
                attachmentRepository,
                chatTurnService,
                null,  // sseSessionManager 不影响 voiceCapability
                transcriber,
                mediaProperties,
                () -> nativeAudioResult
        );
    }

    @Test
    void 原生音频可用时nativeAudio为true且supported为true() {
        var service = 创建服务(true, null);

        var capability = service.voiceCapability();

        assertTrue(capability.nativeAudio());
        assertFalse(capability.stt());
        assertTrue(capability.supported());
    }

    @Test
    void STT可用时stt为true且supported为true() {
        when(audioTranscriber.isAvailable()).thenReturn(true);
        var service = 创建服务(false, audioTranscriber);

        var capability = service.voiceCapability();

        assertFalse(capability.nativeAudio());
        assertTrue(capability.stt());
        assertTrue(capability.supported());
    }

    @Test
    void 原生音频和STT都可用时两者均为true() {
        when(audioTranscriber.isAvailable()).thenReturn(true);
        var service = 创建服务(true, audioTranscriber);

        var capability = service.voiceCapability();

        assertTrue(capability.nativeAudio());
        assertTrue(capability.stt());
        assertTrue(capability.supported());
    }

    @Test
    void 都不可用时supported为false() {
        var service = 创建服务(false, null);

        var capability = service.voiceCapability();

        assertFalse(capability.nativeAudio());
        assertFalse(capability.stt());
        assertFalse(capability.supported());
    }

    @Test
    void audioTranscriber非null但不可用时stt为false() {
        when(audioTranscriber.isAvailable()).thenReturn(false);
        var service = 创建服务(false, audioTranscriber);

        var capability = service.voiceCapability();

        assertFalse(capability.stt());
        assertFalse(capability.supported());
    }

    @Test
    void voiceCapability返回record字段与构造参数一致() {
        // 验证 VoiceCapability record 的字段正确映射
        var capability = new BrowserIngressService.VoiceCapability(true, false, true);

        assertEquals(true, capability.nativeAudio());
        assertEquals(false, capability.stt());
        assertEquals(true, capability.supported());
    }
}
