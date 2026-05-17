package com.lifepilot.tool.model;

import java.util.Locale;

/**
 * 工具产物类型枚举。
 *
 * <p>用于区分文件型产物的渲染策略：图片可走平台原生 image 消息（飞书 image_key、
 * 企微 msgtype=image、Telegram sendPhoto 等），其余统一走文件消息。</p>
 *
 * <p>分类策略：mimeType 以 {@code image/} 前缀开头视为 {@link #IMAGE}，否则
 * 一律为 {@link #FILE}。本枚举不为 video / audio 单独建分类，因为 IM 平台对
 * 这些类型的处理与文件消息差异不显著，避免引入过多分支。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
public enum ArtifactKind {

    /** 通用文件产物。docx / xlsx / pdf / zip / 任意未识别二进制等。 */
    FILE,

    /** 图片产物。mimeType 以 {@code image/} 前缀开头。 */
    IMAGE;

    /**
     * 按 mimeType 推断 ArtifactKind。
     *
     * @param mimeType RFC 6838 mimeType；为 {@code null} 或空白时退化为 {@link #FILE}
     * @return 推断结果
     */
    public static ArtifactKind fromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return FILE;
        }
        return mimeType.toLowerCase(Locale.ROOT).startsWith("image/") ? IMAGE : FILE;
    }
}
