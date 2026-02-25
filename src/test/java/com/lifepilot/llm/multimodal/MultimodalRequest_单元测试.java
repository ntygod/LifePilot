package com.lifepilot.llm.multimodal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MultimodalRequest record 单元测试。
 *
 * @author zsg
 * @since 2026-07-01
 */
class MultimodalRequest_单元测试 {

    private static MediaContent imageMedia() {
        return new MediaContent("img-1", "image/png", new byte[]{1, 2, 3}, "test.png", 3, Map.of());
    }

    private static MediaContent videoMedia() {
        return new MediaContent("vid-1", "video/mp4", new byte[]{4, 5, 6}, "test.mp4", 3, Map.of());
    }

    private static MediaContent textMedia() {
        return new MediaContent("doc-1", "text/plain", new byte[]{7, 8}, "readme.txt", 2, Map.of());
    }

    @Test
    void 正常构造_所有字段正确赋值() {
        var media = List.of(imageMedia());
        var req = new MultimodalRequest("chat", "描述这张图片", media, "{\"type\":\"object\"}");

        assertEquals("chat", req.scene());
        assertEquals("描述这张图片", req.text());
        assertEquals(1, req.mediaList().size());
        assertEquals("{\"type\":\"object\"}", req.outputSchema());
    }

    @Test
    void null_scene_抛出NullPointerException() {
        assertThrows(NullPointerException.class,
            () -> new MultimodalRequest(null, "text", List.of(), null));
    }

    @Test
    void null_text_抛出NullPointerException() {
        assertThrows(NullPointerException.class,
            () -> new MultimodalRequest("scene", null, List.of(), null));
    }

    @Test
    void null_mediaList_初始化为空列表() {
        var req = new MultimodalRequest("scene", "text", null, null);

        assertNotNull(req.mediaList());
        assertTrue(req.mediaList().isEmpty());
    }

    @Test
    void mediaList_防御性拷贝_修改原列表不影响record() {
        var mutableList = new ArrayList<>(List.of(imageMedia()));
        var req = new MultimodalRequest("scene", "text", mutableList, null);

        mutableList.add(videoMedia());

        assertEquals(1, req.mediaList().size());
    }

    @Test
    void mediaList_不可变_修改抛出异常() {
        var req = new MultimodalRequest("scene", "text", List.of(imageMedia()), null);

        assertThrows(UnsupportedOperationException.class,
            () -> req.mediaList().add(videoMedia()));
    }

    @Test
    void hasImages_包含图片时返回true() {
        var req = new MultimodalRequest("scene", "text", List.of(imageMedia()), null);
        assertTrue(req.hasImages());
    }

    @Test
    void hasImages_不包含图片时返回false() {
        var req = new MultimodalRequest("scene", "text", List.of(textMedia()), null);
        assertFalse(req.hasImages());
    }

    @Test
    void hasImages_空列表返回false() {
        var req = new MultimodalRequest("scene", "text", List.of(), null);
        assertFalse(req.hasImages());
    }

    @Test
    void hasVideos_包含视频时返回true() {
        var req = new MultimodalRequest("scene", "text", List.of(videoMedia()), null);
        assertTrue(req.hasVideos());
    }

    @Test
    void hasVideos_不包含视频时返回false() {
        var req = new MultimodalRequest("scene", "text", List.of(imageMedia()), null);
        assertFalse(req.hasVideos());
    }

    @Test
    void hasVideos_空列表返回false() {
        var req = new MultimodalRequest("scene", "text", List.of(), null);
        assertFalse(req.hasVideos());
    }

    @Test
    void 混合媒体_hasImages和hasVideos均返回true() {
        var req = new MultimodalRequest("scene", "text",
            List.of(imageMedia(), videoMedia()), null);
        assertTrue(req.hasImages());
        assertTrue(req.hasVideos());
    }

    @Test
    void outputSchema_可选_null合法() {
        var req = new MultimodalRequest("scene", "text", List.of(), null);
        assertNull(req.outputSchema());
    }
}
