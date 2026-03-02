package com.lifepilot.media.video;

import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * VideoProcessor 单元测试。
 *
 * 覆盖视频大小/时长超限、正常处理含音轨和无音轨的分支。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class VideoProcessorTest {

    private MediaProperties properties;

    @Mock
    private KeyFrameExtractor keyFrameExtractor;

    @Mock
    private AudioTrackExtractor audioTrackExtractor;

    @Mock
    private AudioTranscriber audioTranscriber;

    private VideoProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new MediaProperties();
        properties.getVideo().setMaxSizeBytes(1024);
        properties.getVideo().setMaxDurationSeconds(60);

        processor = new VideoProcessor(keyFrameExtractor, audioTrackExtractor, audioTranscriber, properties);
    }

    @Test
    void 视频大小超过限制时抛出VideoProcessException() {
        byte[] data = new byte[2048]; // 大于 maxSizeBytes

        VideoProcessException ex = assertThrows(
                VideoProcessException.class,
                () -> processor.process(data, "video/mp4")
        );

        assertTrue(ex.getMessage().contains("视频文件大小超过限制"));
    }

    @Test
    void 视频时长超过限制时抛出VideoProcessException() throws Exception {
        byte[] data = new byte[512];

        // 时长超限
        when(keyFrameExtractor.getDurationSeconds(any())).thenReturn(120);

        VideoProcessException ex = assertThrows(
                VideoProcessException.class,
                () -> processor.process(data, "video/mp4")
        );

        assertTrue(ex.getMessage().contains("视频时长超过限制"));
    }

    @Test
    void 含音轨视频正常处理并返回转录文本() throws Exception {
        byte[] data = new byte[512];

        when(keyFrameExtractor.getDurationSeconds(any())).thenReturn(10);

        MediaContent frame = new MediaContent(
                "f1",
                "image/jpeg",
                new byte[] {1},
                "f1.jpg",
                1,
                Map.of()
        );
        when(keyFrameExtractor.extract(any())).thenReturn(List.of(frame));

        byte[] audio = new byte[] {3, 4};
        when(audioTrackExtractor.extract(any())).thenReturn(Optional.of(audio));
        when(audioTranscriber.transcribe(audio, "audio/wav")).thenReturn("转录文本");

        VideoProcessResult result = processor.process(data, "video/mp4");

        assertEquals(10, result.durationSeconds());
        assertEquals(1, result.keyFrames().size());
        assertEquals(1, result.frameCount());
        assertEquals("转录文本", result.transcript());
    }

    @Test
    void 无音轨视频仅返回关键帧不包含转录() throws Exception {
        byte[] data = new byte[512];

        when(keyFrameExtractor.getDurationSeconds(any())).thenReturn(8);

        MediaContent frame = new MediaContent(
                "f1",
                "image/jpeg",
                new byte[] {1},
                "f1.jpg",
                1,
                Map.of()
        );
        when(keyFrameExtractor.extract(any())).thenReturn(List.of(frame));

        when(audioTrackExtractor.extract(any())).thenReturn(Optional.empty());

        VideoProcessResult result = processor.process(data, "video/mp4");

        assertEquals(8, result.durationSeconds());
        assertEquals(1, result.keyFrames().size());
        assertEquals(1, result.frameCount());
        assertNull(result.transcript());
    }
}

