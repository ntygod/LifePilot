package com.lifepilot.media.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.tts.DefaultTextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;

import com.lifepilot.media.config.MediaProperties;

import reactor.core.publisher.Flux;

/**
 * 文字转语音合成器。
 *
 * <p>基于 Spring AI {@link TextToSpeechModel} 接口，支持同步和流式合成。
 * 文本长度超过配置的最大限制时自动截断并添加省略提示。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class SpeechSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SpeechSynthesizer.class);

    /** 截断后追加的省略提示。 */
    private static final String TRUNCATION_SUFFIX = "…（内容已截断）";

    private final TextToSpeechModel textToSpeechModel;
    private final MediaProperties properties;

    public SpeechSynthesizer(TextToSpeechModel textToSpeechModel, MediaProperties properties) {
        this.textToSpeechModel = textToSpeechModel;
        this.properties = properties;
    }

    /**
     * 同步合成语音。
     *
     * @param text 待合成的文本
     * @return 音频二进制数据
     */
    public byte[] synthesize(String text) {
        var truncated = truncateIfNeeded(text);
        var prompt = buildPrompt(truncated);
        log.debug("TTS 同步合成: 文本长度={}, 截断={}", text.length(), truncated.length() != text.length());
        TextToSpeechResponse response = textToSpeechModel.call(prompt);
        return response.getResult().getOutput();
    }

    /**
     * 流式合成语音。
     *
     * @param text 待合成的文本
     * @return 音频数据流
     */
    public Flux<byte[]> stream(String text) {
        var truncated = truncateIfNeeded(text);
        var prompt = buildPrompt(truncated);
        log.debug("TTS 流式合成: 文本长度={}, 截断={}", text.length(), truncated.length() != text.length());
        return textToSpeechModel.stream(prompt)
                .map(response -> response.getResult().getOutput());
    }

    /**
     * 文本超过最大长度时截断并追加省略提示。
     */
    String truncateIfNeeded(String text) {
        int maxLength = properties.getTts().getMaxTextLength();
        if (text.length() <= maxLength) {
            return text;
        }
        log.info("TTS 文本超过最大长度限制: length={}, max={}", text.length(), maxLength);
        return text.substring(0, maxLength) + TRUNCATION_SUFFIX;
    }

    /**
     * 构建 TTS 请求，携带语音风格、语速和输出格式配置。
     */
    private TextToSpeechPrompt buildPrompt(String text) {
        var ttsConfig = properties.getTts();
        var options = DefaultTextToSpeechOptions.builder()
                .voice(ttsConfig.getVoice())
                .speed(ttsConfig.getSpeed())
                .format(ttsConfig.getOutputFormat())
                .build();
        return new TextToSpeechPrompt(text, options);
    }
}
