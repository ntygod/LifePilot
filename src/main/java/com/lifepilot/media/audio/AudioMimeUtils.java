package com.lifepilot.media.audio;

/**
 * 音频 MIME 类型工具方法。
 *
 * @author zsg
 * @since 2026-04-06
 */
public final class AudioMimeUtils {

    private AudioMimeUtils() {}

    /**
     * 从 MIME 类型提取文件扩展名，自动剥离参数（如 {@code webm;codecs=opus}）。
     *
     * @param mimeType MIME 类型（如 "audio/wav"、"audio/webm;codecs=opus"）
     * @return 文件扩展名（如 ".wav"），null 或无效输入返回 ".wav"
     */
    public static String extractExtension(String mimeType) {
        if (mimeType == null || !mimeType.contains("/")) {
            return ".wav";
        }
        String subType = mimeType.substring(mimeType.indexOf('/') + 1);
        // 剥离 MIME 参数（如 "webm;codecs=opus" → "webm"）
        int semicolon = subType.indexOf(';');
        if (semicolon >= 0) {
            subType = subType.substring(0, semicolon).strip();
        }
        return switch (subType) {
            case "mpeg" -> ".mp3";
            case "x-wav", "wav" -> ".wav";
            case "x-flac", "flac" -> ".flac";
            case "ogg" -> ".ogg";
            case "mp4", "x-m4a", "m4a" -> ".m4a";
            case "webm" -> ".webm";
            default -> "." + subType;
        };
    }
}
