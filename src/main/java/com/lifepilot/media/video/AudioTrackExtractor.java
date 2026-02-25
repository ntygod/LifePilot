package com.lifepilot.media.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音轨分离器。
 *
 * <p>基于 JavaCV（{@link FFmpegFrameGrabber} / {@link FFmpegFrameRecorder}）
 * 从视频中分离音频轨道，输出为 WAV 格式。</p>
 *
 * <p>无音轨的视频返回 {@link Optional#empty()}。
 * 处理完成后确保临时文件被清理（try-finally）。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class AudioTrackExtractor {

    private static final Logger log = LoggerFactory.getLogger(AudioTrackExtractor.class);

    /**
     * 从视频文件中分离音频轨道。
     *
     * @param videoFile 视频临时文件路径
     * @return 音频 WAV byte[] 数据，无音轨时返回空 Optional
     * @throws VideoProcessException 音轨分离失败
     */
    public Optional<byte[]> extract(Path videoFile) {
        try (var grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            grabber.start();

            // 检查视频是否包含音频流
            if (grabber.getAudioChannels() <= 0) {
                log.debug("视频无音频轨道: {}", videoFile.getFileName());
                grabber.stop();
                return Optional.empty();
            }

            log.debug("检测到音频轨道: channels={}, sampleRate={}, 文件={}",
                    grabber.getAudioChannels(), grabber.getSampleRate(), videoFile.getFileName());

            // 创建临时 WAV 文件
            Path tempAudio = Files.createTempFile("audio-track-", ".wav");
            try {
                try (var recorder = new FFmpegFrameRecorder(tempAudio.toFile(), grabber.getAudioChannels())) {
                    recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
                    recorder.setSampleRate(grabber.getSampleRate());
                    recorder.setAudioChannels(grabber.getAudioChannels());
                    recorder.setFormat("wav");
                    recorder.start();

                    Frame frame;
                    while ((frame = grabber.grabSamples()) != null) {
                        recorder.record(frame);
                    }

                    recorder.stop();
                }

                grabber.stop();
                byte[] audioData = Files.readAllBytes(tempAudio);
                log.info("音轨分离完成: 文件={}, 音频大小={}B", videoFile.getFileName(), audioData.length);
                return Optional.of(audioData);
            } finally {
                deleteSilently(tempAudio);
            }
        } catch (IOException e) {
            throw new VideoProcessException("视频音轨分离失败: " + videoFile.getFileName(), e);
        }
    }

    /**
     * 静默删除文件，忽略异常。
     *
     * @param path 文件路径
     */
    private void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时文件删除失败: {}", path, e);
        }
    }
}
