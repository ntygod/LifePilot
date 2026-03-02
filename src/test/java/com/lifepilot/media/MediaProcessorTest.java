package com.lifepilot.media;

import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * MediaProcessor 单元测试。
 *
 * 覆盖小图跳过压缩、大图缩放、BMP→PNG 转换、JPEG 质量压缩等核心分支。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class MediaProcessorTest {

    private MediaProperties properties;

    @Mock
    private MediaType mediaType;

    private MediaProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new MediaProperties();
        // 为了让测试更明显，降低最大尺寸和 JPEG 质量阈值
        properties.getImage().setMaxDimension(256);
        properties.getImage().setJpegQuality(0.5f);

        processor = new MediaProcessor(properties, mediaType);
    }

    @Test
    void 小图在限制内且非jpeg和无需格式转换时直接返回原始内容() throws Exception {
        BufferedImage img = createImage(128, 128, Color.RED);
        byte[] pngBytes = toBytes(img, "png");

        when(mediaType.detect(any(), any())).thenReturn("image/png");

        MediaContent original = new MediaContent(
                "id-1",
                "image/png",
                pngBytes,
                "small.png",
                pngBytes.length,
                Map.of("k", "v")
        );

        MediaContent processed = processor.process(original);

        assertSame(original, processed, "无需处理时应直接返回原始对象");
    }

    @Test
    void 大图超过最大尺寸时进行等比缩放() throws Exception {
        // 宽 1024 高 512，大于 maxDimension=256
        BufferedImage img = createImage(1024, 512, Color.BLUE);
        byte[] pngBytes = toBytes(img, "png");

        when(mediaType.detect(any(), any())).thenReturn("image/png");

        MediaContent original = new MediaContent(
                "id-2",
                "image/png",
                pngBytes,
                "large.png",
                pngBytes.length,
                Map.of()
        );

        MediaContent processed = processor.process(original);

        assertNotSame(original, processed);
        assertEquals("image/png", processed.mimeType());

        BufferedImage processedImg = ImageIO.read(new java.io.ByteArrayInputStream(processed.data()));
        assertNotNull(processedImg);
        int maxDim = Math.max(processedImg.getWidth(), processedImg.getHeight());
        assertTrue(maxDim <= properties.getImage().getMaxDimension(),
                "缩放后的最长边应不超过配置的最大尺寸");
    }

    @Test
    void bmp或webp按规则转换为png() throws Exception {
        BufferedImage img = createImage(400, 300, Color.GREEN);
        byte[] pngBytes = toBytes(img, "png");

        // 虽然底层数据是 PNG，这里只关心 detect 返回的 MIME 触发逻辑分支
        when(mediaType.detect(any(), any())).thenReturn("image/bmp");

        MediaContent original = new MediaContent(
                "id-3",
                "image/bmp",
                pngBytes,
                "image.bmp",
                pngBytes.length,
                Map.of()
        );

        MediaContent processed = processor.process(original);

        assertEquals("image/png", processed.mimeType());
        assertNotEquals(original.sizeBytes(), processed.sizeBytes());
    }

    @Test
    void jpeg按配置质量进行压缩() throws Exception {
        BufferedImage img = createImage(800, 600, Color.ORANGE);
        byte[] jpegBytes = toBytes(img, "jpeg");

        when(mediaType.detect(any(), any())).thenReturn("image/jpeg");

        MediaContent original = new MediaContent(
                "id-4",
                "image/jpeg",
                jpegBytes,
                "photo.jpg",
                jpegBytes.length,
                Map.of()
        );

        MediaContent processed = processor.process(original);

        assertEquals("image/jpeg", processed.mimeType());
        assertNotSame(original, processed);
        assertTrue(processed.sizeBytes() <= original.sizeBytes(),
                "压缩后的 JPEG 大小通常应小于或等于原始大小");
    }

    // --- 辅助方法 ---

    private static BufferedImage createImage(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static byte[] toBytes(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}

