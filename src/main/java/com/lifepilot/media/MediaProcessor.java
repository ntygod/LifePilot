package com.lifepilot.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.media.config.MediaProperties;

import net.coobird.thumbnailator.Thumbnails;

/**
 * 图片预处理器。
 *
 * <p>负责图片格式检测、尺寸压缩、质量压缩和格式转换。
 * 使用 Thumbnailator 库执行图片缩放和压缩操作。</p>
 *
 * <ul>
 *   <li>最长边超过 maxDimension 时等比缩放</li>
 *   <li>JPEG 格式按 jpegQuality 质量压缩</li>
 *   <li>BMP/WebP 格式转换为 PNG 或 JPEG</li>
 *   <li>尺寸和大小均在限制内时跳过压缩，直接返回原始 MediaContent</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class MediaProcessor {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessor.class);

    private final MediaProperties properties;
    private final MediaType mediaType;

    public MediaProcessor(MediaProperties properties, MediaType mediaType) {
        this.properties = properties;
        this.mediaType = mediaType;
    }

    /**
     * 对单张图片执行预处理。
     *
     * <p>处理流程：格式检测 → 判断是否需要处理 → 尺寸压缩 → 质量压缩 → 格式转换。
     * 若图片尺寸和大小均在限制内且无需格式转换，直接返回原始 MediaContent。</p>
     *
     * @param image 待处理的图片 MediaContent
     * @return 处理后的 MediaContent（保留原始 id、fileName、metadata）
     */
    public MediaContent process(MediaContent image) {
        String mime = mediaType.detect(image.data(), image.fileName());
        log.debug("图片预处理: fileName={}, 检测 MIME={}, 大小={}B", image.fileName(), mime, image.sizeBytes());

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image.data()));
            if (img == null) {
                log.warn("无法解码图片，直接返回原始数据: fileName={}", image.fileName());
                return image;
            }

            int width = img.getWidth();
            int height = img.getHeight();
            int maxDim = Math.max(width, height);

            int maxDimension = properties.getImage().getMaxDimension();
            boolean needsResize = maxDim > maxDimension;
            boolean needsFormatConvert = "image/bmp".equals(mime) || "image/webp".equals(mime);
            boolean isJpeg = "image/jpeg".equals(mime);

            // 不需要任何处理时直接返回原始
            if (!needsResize && !needsFormatConvert && !isJpeg) {
                log.debug("图片无需处理，跳过: {}x{}, mime={}", width, height, mime);
                return image;
            }

            var baos = new ByteArrayOutputStream();
            var builder = Thumbnails.of(img);

            // 尺寸处理
            if (needsResize) {
                builder.size(maxDimension, maxDimension); // Thumbnailator 自动保持宽高比
                log.debug("图片缩放: {}x{} → 最长边不超过 {}", width, height, maxDimension);
            } else {
                builder.scale(1.0); // 保持原始尺寸
            }

            // 输出格式和质量
            String outputMime;
            if (needsFormatConvert) {
                outputMime = "image/png";
                builder.outputFormat("png");
                log.debug("格式转换: {} → png", mime);
            } else if (isJpeg) {
                outputMime = "image/jpeg";
                float quality = properties.getImage().getJpegQuality();
                builder.outputFormat("jpeg").outputQuality(quality);
                log.debug("JPEG 质量压缩: quality={}", quality);
            } else {
                // PNG、GIF 等保持原格式
                String subtype = mime.substring(mime.indexOf('/') + 1);
                outputMime = mime;
                builder.outputFormat(subtype);
            }

            builder.toOutputStream(baos);
            byte[] processedData = baos.toByteArray();

            log.info("图片预处理完成: fileName={}, 原始大小={}B, 处理后大小={}B, 输出 MIME={}",
                    image.fileName(), image.sizeBytes(), processedData.length, outputMime);

            return new MediaContent(
                    image.id(),
                    outputMime,
                    processedData,
                    image.fileName(),
                    processedData.length,
                    image.metadata()
            );
        } catch (IOException e) {
            log.warn("图片预处理失败，返回原始数据: fileName={}, 原因={}", image.fileName(), e.getMessage());
            return image;
        }
    }

    /**
     * 批量处理图片列表。
     *
     * @param images 待处理的图片 MediaContent 列表
     * @return 处理后的 MediaContent 列表
     */
    public List<MediaContent> processAll(List<MediaContent> images) {
        return images.stream()
                .map(this::process)
                .toList();
    }
}
