package com.lifepilot.media.config;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.lang.Nullable;

import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.llm.LlmRouter;
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

/**
 * 多模态能力自动配置。
 *
 * <p>注册媒体处理、音频处理、视频处理和多模态路由相关的 Spring Bean。
 * 所有 Bean 使用 {@code @ConditionalOnMissingBean} 允许用户自定义覆盖。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
@AutoConfiguration
@EnableConfigurationProperties(MediaProperties.class)
@ConditionalOnProperty(name = "lifepilot.media.enabled", havingValue = "true", matchIfMissing = true)
public class MediaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MediaType mediaType() {
        return new MediaType();
    }

    @Bean
    @ConditionalOnMissingBean
    public MediaValidator mediaValidator(MediaProperties properties, MediaType mediaType) {
        return new MediaValidator(properties, mediaType);
    }

    @Bean
    @ConditionalOnMissingBean
    public MediaProcessor mediaProcessor(MediaProperties properties, MediaType mediaType) {
        return new MediaProcessor(properties, mediaType);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentExtractor documentExtractor(List<DocumentParser> parsers) {
        return new DocumentExtractor(parsers);
    }

    @Bean
    @ConditionalOnMissingBean
    public WhisperCliTranscriber whisperCliTranscriber(MediaProperties properties) {
        return new WhisperCliTranscriber(
                properties.getAudio().getWhisperCliPath(),
                properties.getAudio().getWhisperModel());
    }

    @Bean
    @ConditionalOnMissingBean
    public AudioTranscriber audioTranscriber(
            @Nullable WhisperCliTranscriber whisperCli,
            @Nullable TranscriptionModel transcriptionModel,
            MediaProperties properties) {
        return new AudioTranscriber(whisperCli, transcriptionModel, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TextToSpeechModel.class)
    public SpeechSynthesizer speechSynthesizer(TextToSpeechModel textToSpeechModel, MediaProperties properties) {
        return new SpeechSynthesizer(textToSpeechModel, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public KeyFrameExtractor keyFrameExtractor(MediaProcessor mediaProcessor, MediaProperties properties) {
        return new KeyFrameExtractor(mediaProcessor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AudioTrackExtractor audioTrackExtractor() {
        return new AudioTrackExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public VideoProcessor videoProcessor(
            KeyFrameExtractor keyFrameExtractor,
            AudioTrackExtractor audioTrackExtractor,
            AudioTranscriber audioTranscriber,
            MediaProperties properties) {
        return new VideoProcessor(keyFrameExtractor, audioTrackExtractor, audioTranscriber, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProviderRegistry.class)
    public MultimodalRouter multimodalRouter(
            ProviderRegistry providerRegistry,
            CircuitBreakerManager circuitBreakerManager,
            MediaProcessor mediaProcessor,
            MediaValidator mediaValidator,
            @Nullable VideoProcessor videoProcessor,
            LlmRouter llmRouter) {
        return new MultimodalRouter(
                providerRegistry, circuitBreakerManager,
                mediaProcessor, mediaValidator,
                videoProcessor, llmRouter);
    }
}
