package com.lifepilot.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaType 单元测试。
 *
 * 覆盖 PNG/JPEG/GIF 魔数字节检测、扩展名伪造场景、
 * 无法识别字节返回 octet-stream 以及图片/文档/音频/视频分类逻辑。
 *
 * @author zsg
 * @since 2026-07-01
 */
class MediaTypeTest {

    private final MediaType mediaType = new MediaType();

    @Test
    void detect_png魔数字节优先于扩展名伪造() {
        // PNG 文件头: 89 50 4E 47 0D 0A 1A 0A
        byte[] pngBytes = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x00
        };

        String detected = mediaType.detect(pngBytes, "fake.txt");

        assertEquals("image/png", detected);
        assertTrue(mediaType.isImage(detected));
        assertFalse(mediaType.isDocument(detected));
    }

    @Test
    void detect_jpeg魔数字节正确识别jpeg() {
        // JPEG 文件头: FF D8 FF
        byte[] jpegBytes = new byte[] {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                0x00, 0x01, 0x02, 0x03
        };

        String detected = mediaType.detect(jpegBytes, "photo.jpg");

        assertEquals("image/jpeg", detected);
        assertTrue(mediaType.isImage(detected));
    }

    @Test
    void detect_gif魔数字节正确识别gif() {
        // GIF87a 头部: 47 49 46 38 37 61
        byte[] gifBytes = new byte[] {
                0x47, 0x49, 0x46, 0x38, 0x37, 0x61,
                0x00, 0x00, 0x00
        };

        String detected = mediaType.detect(gifBytes, "anim.dat");

        assertEquals("image/gif", detected);
        assertTrue(mediaType.isImage(detected));
    }

    @Test
    void detect_无法识别的字节返回octetStream() {
        byte[] randomBytes = new byte[] { 0x00, 0x01, 0x02, 0x03 };

        String detected = mediaType.detect(randomBytes, "unknown.bin");

        assertEquals("application/octet-stream", detected);
        assertFalse(mediaType.isImage(detected));
        assertFalse(mediaType.isDocument(detected));
        assertFalse(mediaType.isAudio(detected));
        assertFalse(mediaType.isVideo(detected));
    }

    @Test
    void 媒体类型分类_图片文档音频视频互斥() {
        assertAll(
                () -> {
                    String mime = "image/png";
                    assertTrue(mediaType.isImage(mime));
                    assertFalse(mediaType.isDocument(mime));
                    assertFalse(mediaType.isAudio(mime));
                    assertFalse(mediaType.isVideo(mime));
                },
                () -> {
                    String mime = "application/pdf";
                    assertTrue(mediaType.isDocument(mime));
                    assertFalse(mediaType.isImage(mime));
                    assertFalse(mediaType.isAudio(mime));
                    assertFalse(mediaType.isVideo(mime));
                },
                () -> {
                    String mime = "audio/mpeg";
                    assertTrue(mediaType.isAudio(mime));
                    assertFalse(mediaType.isImage(mime));
                    assertFalse(mediaType.isDocument(mime));
                    assertFalse(mediaType.isVideo(mime));
                },
                () -> {
                    String mime = "video/mp4";
                    assertTrue(mediaType.isVideo(mime));
                    assertFalse(mediaType.isImage(mime));
                    assertFalse(mediaType.isDocument(mime));
                    assertFalse(mediaType.isAudio(mime));
                },
                () -> {
                    String mime = null;
                    assertFalse(mediaType.isImage(mime));
                    assertFalse(mediaType.isDocument(mime));
                    assertFalse(mediaType.isAudio(mime));
                    assertFalse(mediaType.isVideo(mime));
                }
        );
    }
}

