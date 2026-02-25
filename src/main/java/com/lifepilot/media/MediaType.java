package com.lifepilot.media;

import org.apache.tika.Tika;
import org.springframework.lang.Nullable;

import java.util.Set;

/**
 * MIME 类型检测与分类工具。
 * <p>
 * 使用 Apache Tika 基于文件二进制内容检测实际 MIME 类型，
 * 并提供便捷方法判断 MIME 类型所属的媒体类别（图片、文档、音频、视频）。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class MediaType {

    private final Tika tika = new Tika();

    /** 已知的文档 MIME 类型集合 */
    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown",
            "text/plain"
    );

    /**
     * 基于文件内容检测 MIME 类型。
     * <p>
     * 优先使用文件二进制内容（魔数字节）进行检测，文件名仅作为辅助参考。
     * 无法识别时返回 {@code application/octet-stream}。
     *
     * @param data     文件二进制数据
     * @param fileName 原始文件名（可选，用于辅助检测）
     * @return 检测到的 MIME 类型字符串
     */
    public String detect(byte[] data, @Nullable String fileName) {
        String detected = tika.detect(data, fileName);
        return detected != null ? detected : "application/octet-stream";
    }

    /**
     * 判断 MIME 类型是否为图片类型。
     *
     * @param mimeType MIME 类型字符串
     * @return 以 {@code image/} 开头时返回 true
     */
    public boolean isImage(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /**
     * 判断 MIME 类型是否为文档类型。
     *
     * @param mimeType MIME 类型字符串
     * @return 属于已知文档 MIME 类型集合时返回 true
     */
    public boolean isDocument(String mimeType) {
        return mimeType != null && DOCUMENT_MIME_TYPES.contains(mimeType);
    }

    /**
     * 判断 MIME 类型是否为音频类型。
     *
     * @param mimeType MIME 类型字符串
     * @return 以 {@code audio/} 开头时返回 true
     */
    public boolean isAudio(String mimeType) {
        return mimeType != null && mimeType.startsWith("audio/");
    }

    /**
     * 判断 MIME 类型是否为视频类型。
     *
     * @param mimeType MIME 类型字符串
     * @return 以 {@code video/} 开头时返回 true
     */
    public boolean isVideo(String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }
}
