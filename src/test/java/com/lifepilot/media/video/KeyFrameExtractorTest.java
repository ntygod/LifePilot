package com.lifepilot.media.video;

import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KeyFrameExtractor 单元测试。
 *
 * 仅针对纯计算方法 calculateInterval / calculateMaxFrames 进行单元测试，
 * 避免引入 JavaCV 原生依赖对测试环境的要求。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class KeyFrameExtractorTest {

    @Mock
    private MediaProcessor mediaProcessor;

    private MediaProperties properties;

    private KeyFrameExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new MediaProperties();
        extractor = new KeyFrameExtractor(mediaProcessor, properties);
    }

    @Test
    void calculateInterval_根据时长返回不同采样间隔() {
        assertEquals(2, extractor.calculateInterval(10));
        assertEquals(5, extractor.calculateInterval(60));
        assertEquals(15, extractor.calculateInterval(600));
        assertEquals(30, extractor.calculateInterval(2000));
    }

    @Test
    void calculateMaxFrames_根据时长限制最大帧数() {
        assertEquals(15, extractor.calculateMaxFrames(10));
        assertEquals(60, extractor.calculateMaxFrames(60));
        assertEquals(120, extractor.calculateMaxFrames(600));
        assertEquals(120, extractor.calculateMaxFrames(2000));
    }
}

