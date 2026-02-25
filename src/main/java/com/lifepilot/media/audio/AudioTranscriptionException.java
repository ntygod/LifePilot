package com.lifepilot.media.audio;

/**
 * 音频转录异常。
 * <p>
 * 当所有语音转录方式均失败时抛出。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class AudioTranscriptionException extends RuntimeException {

    /**
     * 构造音频转录异常。
     *
     * @param message 异常描述信息
     */
    public AudioTranscriptionException(String message) {
        super(message);
    }

    /**
     * 构造音频转录异常（含原因）。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public AudioTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
