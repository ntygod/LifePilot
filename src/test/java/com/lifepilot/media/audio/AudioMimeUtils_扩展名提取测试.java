package com.lifepilot.media.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AudioMimeUtils 扩展名提取测试。
 *
 * <p>覆盖 {@link AudioMimeUtils#extractExtension(String)} 的所有分支：
 * null、无斜杠、常见 MIME 类型、带参数的 MIME、未知类型。</p>
 *
 * @author zsg
 * @since 2026-04-06
 */
class AudioMimeUtils_扩展名提取测试 {

    // ========== null / 无效输入 ==========

    @Test
    void null输入返回默认wav扩展名() {
        assertEquals(".wav", AudioMimeUtils.extractExtension(null));
    }

    @Test
    void 空字符串返回默认wav扩展名() {
        assertEquals(".wav", AudioMimeUtils.extractExtension(""));
    }

    @Test
    void 无斜杠的字符串返回默认wav扩展名() {
        assertEquals(".wav", AudioMimeUtils.extractExtension("audio-wav"));
    }

    // ========== 常见 MIME 类型 ==========

    @Test
    void audio_wav返回wav扩展名() {
        assertEquals(".wav", AudioMimeUtils.extractExtension("audio/wav"));
    }

    @Test
    void audio_x_wav返回wav扩展名() {
        assertEquals(".wav", AudioMimeUtils.extractExtension("audio/x-wav"));
    }

    @Test
    void audio_mpeg返回mp3扩展名() {
        assertEquals(".mp3", AudioMimeUtils.extractExtension("audio/mpeg"));
    }

    @Test
    void audio_webm返回webm扩展名() {
        assertEquals(".webm", AudioMimeUtils.extractExtension("audio/webm"));
    }

    @Test
    void audio_ogg返回ogg扩展名() {
        assertEquals(".ogg", AudioMimeUtils.extractExtension("audio/ogg"));
    }

    @Test
    void audio_flac返回flac扩展名() {
        assertEquals(".flac", AudioMimeUtils.extractExtension("audio/flac"));
    }

    @Test
    void audio_x_flac返回flac扩展名() {
        assertEquals(".flac", AudioMimeUtils.extractExtension("audio/x-flac"));
    }

    @Test
    void audio_mp4返回m4a扩展名() {
        assertEquals(".m4a", AudioMimeUtils.extractExtension("audio/mp4"));
    }

    @Test
    void audio_m4a返回m4a扩展名() {
        assertEquals(".m4a", AudioMimeUtils.extractExtension("audio/m4a"));
    }

    @Test
    void audio_x_m4a返回m4a扩展名() {
        assertEquals(".m4a", AudioMimeUtils.extractExtension("audio/x-m4a"));
    }

    // ========== 带参数的 MIME ==========

    @Test
    void 带codecs参数的webm正确剥离参数返回webm() {
        assertEquals(".webm", AudioMimeUtils.extractExtension("audio/webm;codecs=opus"));
    }

    @Test
    void 带codecs参数且有空格的webm正确剥离参数返回webm() {
        assertEquals(".webm", AudioMimeUtils.extractExtension("audio/webm; codecs=opus"));
    }

    // ========== 未知类型 ==========

    @Test
    void 未知子类型返回点加子类型() {
        assertEquals(".aac", AudioMimeUtils.extractExtension("audio/aac"));
    }

    @Test
    void 非audio前缀的mime也能正确提取子类型() {
        // extractExtension 不检查主类型，仅取斜杠后的部分
        assertEquals(".plain", AudioMimeUtils.extractExtension("text/plain"));
    }
}
