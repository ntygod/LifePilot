package com.lifepilot.media.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.core.io.FileSystemResource;
import org.springframework.lang.Nullable;

import com.lifepilot.media.config.MediaProperties;

/**
 * 语音转文字转录器。
 *
 * <p>采用"本地优先"级联策略（借鉴 OpenClaw 音频处理架构）：
 * 优先使用本地 Whisper CLI，不可用时回退到云端 STT Provider。</p>
 *
 * <p>级联顺序：
 * <ol>
 *   <li>本地 Whisper CLI（{@link WhisperCliTranscriber}）</li>
 *   <li>云端 Spring AI {@link TranscriptionModel}</li>
 *   <li>全部不可用 → 抛出 {@link AudioTranscriptionException}</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class AudioTranscriber {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriber.class);

    /** 本地 Whisper CLI 转录器，可能为 null。 */
    private final @Nullable WhisperCliTranscriber whisperCli;

    /** 云端 Spring AI 转录模型，可能为 null。 */
    private final @Nullable TranscriptionModel transcriptionModel;

    /** 媒体处理配置。 */
    private final MediaProperties properties;

    /**
     * 构造 AudioTranscriber。
     *
     * @param whisperCli        本地 Whisper CLI 转录器，null 表示不可用
     * @param transcriptionModel 云端转录模型，null 表示不可用
     * @param properties         媒体处理配置
     */
    public AudioTranscriber(
            @Nullable WhisperCliTranscriber whisperCli,
            @Nullable TranscriptionModel transcriptionModel,
            MediaProperties properties) {
        this.whisperCli = whisperCli;
        this.transcriptionModel = transcriptionModel;
        this.properties = properties;
    }

    /**
     * 将音频转录为文本。
     *
     * <p>采用"本地优先"级联策略：
     * <ol>
     *   <li>检查本地 Whisper CLI 是否可用，可用则调用本地转录</li>
     *   <li>本地失败或不可用，检查云端 TranscriptionModel 是否可用</li>
     *   <li>全部不可用，抛出 {@link AudioTranscriptionException}</li>
     * </ol>
     *
     * @param audioData 音频二进制数据
     * @param mimeType  音频 MIME 类型（如 "audio/wav"）
     * @return 转录文本
     * @throws AudioTranscriptionException 所有转录方式均失败
     */
    public String transcribe(byte[] audioData, String mimeType) {
        // 1. 本地 Whisper CLI 优先
        if (whisperCli != null && whisperCli.isAvailable()) {
            try {
                log.debug("使用本地 Whisper CLI 转录");
                return whisperCli.transcribe(audioData, mimeType);
            } catch (AudioTranscriptionException e) {
                log.warn("本地 Whisper CLI 转录失败，尝试云端回退: {}", e.getMessage());
            }
        }

        // 2. 云端 TranscriptionModel 回退
        if (transcriptionModel != null) {
            try {
                log.debug("使用云端 TranscriptionModel 转录");
                return transcribeWithSpringAi(audioData, mimeType);
            } catch (Exception e) {
                log.warn("云端 TranscriptionModel 转录失败: {}", e.getMessage());
            }
        }

        // 3. 全部不可用
        throw new AudioTranscriptionException("所有语音转录方式均不可用");
    }

    /**
     * 通过 Spring AI TranscriptionModel 执行云端转录。
     *
     * <p>将音频数据写入临时文件，通过 {@link FileSystemResource} 包装后
     * 创建 {@link AudioTranscriptionPrompt}，调用模型获取转录结果。</p>
     *
     * @param audioData 音频二进制数据
     * @param mimeType  音频 MIME 类型
     * @return 转录文本
     * @throws IOException 临时文件操作失败
     */
    private String transcribeWithSpringAi(byte[] audioData, String mimeType) throws IOException {
        Path tempFile = Files.createTempFile("audio-transcribe-", extractExtension(mimeType));
        try {
            Files.write(tempFile, audioData);
            var resource = new FileSystemResource(tempFile.toFile());
            var prompt = new AudioTranscriptionPrompt(resource);
            var response = transcriptionModel.call(prompt);
            return response.getResult().getOutput();
        } finally {
            deleteSilently(tempFile);
        }
    }

    /**
     * 从 MIME 类型提取文件扩展名。
     *
     * @param mimeType MIME 类型（如 "audio/wav"）
     * @return 文件扩展名（如 ".wav"）
     */
    private String extractExtension(String mimeType) {
        if (mimeType == null || !mimeType.contains("/")) {
            return ".wav";
        }
        String subType = mimeType.substring(mimeType.indexOf('/') + 1);
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

    /**
     * 静默删除文件，忽略异常。
     *
     * @param path 文件路径，null 时跳过
     */
    private void deleteSilently(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("临时文件删除失败: {}", path, e);
            }
        }
    }
}
