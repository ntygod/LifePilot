package com.lifepilot.media.audio;

import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SpeechSynthesizer 单元测试。
 *
 * 覆盖同步合成、流式合成和文本截断逻辑。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class SpeechSynthesizerTest {

    @Mock
    private TextToSpeechModel textToSpeechModel;

    private MediaProperties properties;

    private SpeechSynthesizer synthesizer;

    @BeforeEach
    void setUp() {
        properties = new MediaProperties();
        properties.getTts().setMaxTextLength(10);
        properties.getTts().setVoice("test-voice");
        properties.getTts().setSpeed(1.0f);
        properties.getTts().setOutputFormat("mp3");

        synthesizer = new SpeechSynthesizer(textToSpeechModel, properties);
    }

    @Test
    void truncateIfNeeded_短文本不截断() {
        String text = "短文本";

        String result = synthesizer.truncateIfNeeded(text);

        assertEquals(text, result);
    }

    @Test
    void truncateIfNeeded_长文本被截断并追加省略提示() {
        String text = "这是一个非常长的文本内容";

        String truncated = synthesizer.truncateIfNeeded(text);

        // 最大长度 10，后面应追加省略提示
        assertEquals(10, truncated.indexOf("…"), "省略号前长度应等于最大长度");
    }

    @Test
    void synthesize_调用底层模型并返回输出数据() {
        String text = "hello world";
        byte[] audio = new byte[] {1, 2, 3};

        TextToSpeechResponse response = mock(TextToSpeechResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput()).thenReturn(audio);
        when(textToSpeechModel.call(any(TextToSpeechPrompt.class))).thenReturn(response);

        byte[] output = synthesizer.synthesize(text);

        assertArrayEquals(audio, output);
        verify(textToSpeechModel).call(any(TextToSpeechPrompt.class));
    }

    @Test
    void stream_调用底层模型并映射为音频流() {
        String text = "hello stream";
        byte[] chunk1 = new byte[] {1};
        byte[] chunk2 = new byte[] {2, 3};

        TextToSpeechResponse response1 = mock(TextToSpeechResponse.class, RETURNS_DEEP_STUBS);
        when(response1.getResult().getOutput()).thenReturn(chunk1);

        TextToSpeechResponse response2 = mock(TextToSpeechResponse.class, RETURNS_DEEP_STUBS);
        when(response2.getResult().getOutput()).thenReturn(chunk2);

        when(textToSpeechModel.stream(any(TextToSpeechPrompt.class)))
                .thenReturn(Flux.just(response1, response2));

        byte[][] collected = synthesizer.stream(text)
                .collectList()
                .block()
                .toArray(new byte[0][]);

        assertArrayEquals(chunk1, collected[0]);
        assertArrayEquals(chunk2, collected[1]);
    }
}

