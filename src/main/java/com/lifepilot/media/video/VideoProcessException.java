package com.lifepilot.media.video;

/**
 * 视频处理异常。
 * <p>
 * 当视频解码、关键帧提取或音轨分离失败时抛出。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class VideoProcessException extends RuntimeException {

    /**
     * 构造视频处理异常。
     *
     * @param message 异常描述信息
     */
    public VideoProcessException(String message) {
        super(message);
    }

    /**
     * 构造视频处理异常（含原因）。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public VideoProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
