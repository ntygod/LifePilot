package com.lifepilot.media.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.config.MediaProperties;

/**
 * 视频预处理器。
 *
 * <p>将视频拆分为关键帧序列和音轨，分别交给图片处理和音频转录管线。
 * 借鉴 OmAgent 的分治策略。</p>
 *
 * <p>处理流程：
 * <ol>
 *   <li>校验视频大小和时长</li>
 *   <li>写入临时文件</li>
 *   <li>{@link KeyFrameExtractor#extract(Path)} → 关键帧列表</li>
 *   <li>{@link AudioTrackExtractor#extract(Path)} → 音轨数据</li>
 *   <li>若有音轨 → {@link AudioTranscriber#transcribe(byte[], String)} → 转录文本</li>
 *   <li>组装 {@link VideoProcessResult}</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class VideoProcessor {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessor.class);

    private final KeyFrameExtractor keyFrameExtractor;
    private final AudioTrackExtractor audioTrackExtractor;
    private final AudioTranscriber audioTranscriber;
    private final MediaProperties properties;

    /**
     * 构造视频预处理器。
     *
     * @param keyFrameExtractor   关键帧提取器
     * @param audioTrackExtractor 音轨分离器
     * @param audioTranscriber    音频转录器
     * @param properties          媒体处理配置
     */
    public VideoProcessor(
            KeyFrameExtractor keyFrameExtractor,
            AudioTrackExtractor audioTrackExtractor,
            AudioTranscriber audioTranscriber,
            MediaProperties properties) {
        this.keyFrameExtractor = keyFrameExtractor;
        this.audioTrackExtractor = audioTrackExtractor;
        this.audioTranscriber = audioTranscriber;
        this.properties = properties;
    }

    /**
     * 处理视频，提取关键帧和音轨转录文本。
     *
     * @param videoData 视频二进制数据
     * @param mimeType  视频 MIME 类型
     * @return 视频处理结果（关键帧列表 + 音轨转录文本）
     * @throws VideoProcessException 视频解码或处理失败
     */
    public VideoProcessResult process(byte[] videoData, String mimeType) {
        // 1. 校验视频大小
        long maxSizeBytes = properties.getVideo().getMaxSizeBytes();
        if (videoData.length > maxSizeBytes) {
            throw new VideoProcessException(
                    "视频文件大小超过限制: %dB > %dB".formatted(videoData.length, maxSizeBytes));
        }

        // 2. 写入临时文件
        Path tempFile = null;
        try {
            String extension = extractExtension(mimeType);
            tempFile = Files.createTempFile("video-process-", extension);
            Files.write(tempFile, videoData);
            log.debug("视频写入临时文件: {}, 大小={}B", tempFile, videoData.length);

            // 3. 校验视频时长
            int durationSeconds = keyFrameExtractor.getDurationSeconds(tempFile);
            int maxDurationSeconds = properties.getVideo().getMaxDurationSeconds();
            if (durationSeconds > maxDurationSeconds) {
                throw new VideoProcessException(
                        "视频时长超过限制: %d秒 > %d秒".formatted(durationSeconds, maxDurationSeconds));
            }

            // 4. 提取关键帧
            List<MediaContent> keyFrames = keyFrameExtractor.extract(tempFile);
            log.debug("关键帧提取完成: 帧数={}", keyFrames.size());

            // 5. 分离音轨
            var audioData = audioTrackExtractor.extract(tempFile);

            // 6. 音轨转录
            String transcript = null;
            if (audioData.isPresent()) {
                try {
                    transcript = audioTranscriber.transcribe(audioData.get(), "audio/wav");
                    log.debug("音轨转录完成: 文本长度={}", transcript.length());
                } catch (Exception e) {
                    log.warn("音轨转录失败，跳过转录: {}", e.getMessage());
                }
            } else {
                log.debug("视频无音轨，跳过转录");
            }

            // 7. 组装结果
            var result = new VideoProcessResult(keyFrames, transcript, durationSeconds, keyFrames.size());
            log.info("视频处理完成: 时长={}秒, 关键帧={}, 有转录={}",
                    durationSeconds, keyFrames.size(), transcript != null);
            return result;
        } catch (VideoProcessException e) {
            throw e;
        } catch (IOException e) {
            throw new VideoProcessException("视频处理失败", e);
        } finally {
            deleteSilently(tempFile);
        }
    }

    /**
     * 从 MIME 类型提取文件扩展名。
     *
     * @param mimeType MIME 类型（如 "video/mp4"）
     * @return 文件扩展名（如 ".mp4"）
     */
    private String extractExtension(String mimeType) {
        if (mimeType == null || !mimeType.contains("/")) {
            return ".mp4";
        }
        String subType = mimeType.substring(mimeType.indexOf('/') + 1);
        return switch (subType) {
            case "x-msvideo" -> ".avi";
            case "x-matroska" -> ".mkv";
            case "quicktime" -> ".mov";
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
