package com.lifepilot.media.video;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.config.MediaProperties;

/**
 * 关键帧提取器。
 *
 * <p>基于 JavaCV（{@link FFmpegFrameGrabber}）按均匀采样策略从视频中提取代表性帧。
 * 采样间隔根据视频时长自适应调整：</p>
 * <ul>
 *   <li>≤ 30 秒：每 2 秒 1 帧，最多 15 帧</li>
 *   <li>30 秒 ~ 5 分钟：每 5 秒 1 帧，最多 60 帧</li>
 *   <li>5 ~ 30 分钟：每 15 秒 1 帧，最多 120 帧</li>
 *   <li>&gt; 30 分钟：每 30 秒 1 帧，最多 120 帧</li>
 * </ul>
 *
 * <p>提取的帧图片经 {@link MediaProcessor} 预处理后封装为 {@link MediaContent}。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class KeyFrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(KeyFrameExtractor.class);

    private final MediaProcessor mediaProcessor;
    private final MediaProperties properties;

    /**
     * 构造关键帧提取器。
     *
     * @param mediaProcessor 图片预处理器
     * @param properties     媒体处理配置
     */
    public KeyFrameExtractor(MediaProcessor mediaProcessor, MediaProperties properties) {
        this.mediaProcessor = mediaProcessor;
        this.properties = properties;
    }

    /**
     * 从视频文件中提取关键帧。
     *
     * <p>使用 {@link FFmpegFrameGrabber} 打开视频，根据时长计算采样间隔，
     * 按间隔抓取帧并转换为 JPEG 字节数据，经 {@link MediaProcessor} 预处理后
     * 封装为 {@link MediaContent} 列表返回。</p>
     *
     * @param videoFile 视频临时文件路径
     * @return 预处理后的关键帧 MediaContent 列表
     * @throws VideoProcessException 视频解码或帧提取失败
     */
    public List<MediaContent> extract(Path videoFile) {
        try (var grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            grabber.start();

            long durationMicros = grabber.getLengthInTime();
            int durationSeconds = (int) (durationMicros / 1_000_000);
            log.debug("视频时长: {}秒, 文件: {}", durationSeconds, videoFile.getFileName());

            int intervalSeconds = calculateInterval(durationSeconds);
            int maxFrames = Math.min(calculateMaxFrames(durationSeconds), properties.getVideo().getMaxKeyFrames());
            log.debug("采样策略: 间隔={}秒, 最大帧数={}", intervalSeconds, maxFrames);

            List<MediaContent> keyFrames = new ArrayList<>();

            try (var converter = new Java2DFrameConverter()) {
                for (int t = 0; t < durationSeconds && keyFrames.size() < maxFrames; t += intervalSeconds) {
                    grabber.setTimestamp((long) t * 1_000_000L);
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        log.debug("时间戳 {}秒 处无法抓取帧，跳过", t);
                        continue;
                    }

                    var image = converter.convert(frame);
                    if (image == null) {
                        log.debug("时间戳 {}秒 处帧转换失败，跳过", t);
                        continue;
                    }

                    // 编码为 JPEG
                    var baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "jpeg", baos);
                    byte[] data = baos.toByteArray();

                    var mc = new MediaContent(
                            UUID.randomUUID().toString(),
                            "image/jpeg",
                            data,
                            "frame-%04d.jpg".formatted(keyFrames.size()),
                            data.length,
                            Map.of("timestamp", String.valueOf(t))
                    );

                    // 经 MediaProcessor 预处理
                    keyFrames.add(mediaProcessor.process(mc));
                }
            }

            grabber.stop();
            log.info("关键帧提取完成: 文件={}, 时长={}秒, 提取帧数={}", videoFile.getFileName(), durationSeconds, keyFrames.size());
            return List.copyOf(keyFrames);
        } catch (IOException e) {
            throw new VideoProcessException("视频关键帧提取失败: " + videoFile.getFileName(), e);
        }
    }

    /**
     * 获取视频时长（秒）。
     *
     * @param videoFile 视频文件路径
     * @return 视频时长（秒）
     * @throws VideoProcessException 无法读取视频时长
     */
    public int getDurationSeconds(Path videoFile) {
        try (var grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            grabber.start();
            long durationMicros = grabber.getLengthInTime();
            int durationSeconds = (int) (durationMicros / 1_000_000);
            grabber.stop();
            return durationSeconds;
        } catch (IOException e) {
            throw new VideoProcessException("无法获取视频时长: " + videoFile.getFileName(), e);
        }
    }

    /**
     * 根据视频时长计算采样间隔（秒）。
     *
     * @param durationSeconds 视频时长（秒）
     * @return 采样间隔（秒）
     */
    int calculateInterval(int durationSeconds) {
        if (durationSeconds <= 30) {
            return 2;
        } else if (durationSeconds <= 300) {
            return 5;
        } else if (durationSeconds <= 1800) {
            return 15;
        } else {
            return 30;
        }
    }

    /**
     * 根据视频时长计算最大帧数。
     *
     * @param durationSeconds 视频时长（秒）
     * @return 最大帧数
     */
    int calculateMaxFrames(int durationSeconds) {
        if (durationSeconds <= 30) {
            return 15;
        } else if (durationSeconds <= 300) {
            return 60;
        } else {
            return 120;
        }
    }
}
