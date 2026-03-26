package com.lifepilot.media.config;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.DocumentExtractor;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaType;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.audio.SpeechSynthesizer;
import com.lifepilot.media.audio.WhisperCliTranscriber;
import com.lifepilot.media.video.AudioTrackExtractor;
import com.lifepilot.media.video.KeyFrameExtractor;
import com.lifepilot.media.video.VideoProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MediaAutoConfiguration 集成测试。
 *
 * 验证 Spring Context 加载成功，多模态相关 Bean 注册与注入链完整。
 *
 * @author zsg
 * @since 2026-07-01
 */
@SpringBootTest
class MediaAutoConfigurationIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 多模态相关Bean均已注册且可注入() {
        assertNotNull(applicationContext.getBean(MediaProperties.class));
        assertNotNull(applicationContext.getBean(MediaType.class));
        assertNotNull(applicationContext.getBean(MediaValidator.class));
        assertNotNull(applicationContext.getBean(MediaProcessor.class));
        assertNotNull(applicationContext.getBean(DocumentExtractor.class));

        assertNotNull(applicationContext.getBean(WhisperCliTranscriber.class));
        assertNotNull(applicationContext.getBean(AudioTranscriber.class));

        // SpeechSynthesizer 受 @ConditionalOnBean(TextToSpeechModel) 约束，可能不存在，这里不强制校验

        assertNotNull(applicationContext.getBean(KeyFrameExtractor.class));
        assertNotNull(applicationContext.getBean(AudioTrackExtractor.class));
        assertNotNull(applicationContext.getBean(VideoProcessor.class));
    }

    @Test
    void 多模态路由器及依赖链可用() {
        // ProviderRegistry / CircuitBreakerManager / GenerationRouter 在主配置中已提供
        assertNotNull(applicationContext.getBean(ProviderRegistry.class));
        assertNotNull(applicationContext.getBean(CircuitBreakerManager.class));
        assertNotNull(applicationContext.getBean(GenerationRouter.class));

        MultimodalRouter multimodalRouter = applicationContext.getBean(MultimodalRouter.class);
        assertNotNull(multimodalRouter);
    }
}

