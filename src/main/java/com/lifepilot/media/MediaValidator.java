package com.lifepilot.media;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.media.config.MediaProperties;

/**
 * 媒体校验器。
 * <p>
 * 在媒体处理前校验内容的合法性，包括数据非空、文件大小、MIME 类型和数量限制。
 * 校验失败时抛出 {@link MediaValidationException}，携带具体失败原因。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class MediaValidator {

    private final MediaProperties properties;
    private final MediaType mediaType;

    /**
     * 构造媒体校验器。
     *
     * @param properties 媒体处理配置属性
     * @param mediaType  MIME 类型检测与分类工具
     */
    public MediaValidator(MediaProperties properties, MediaType mediaType) {
        this.properties = properties;
        this.mediaType = mediaType;
    }

    /**
     * 校验单个媒体内容。
     * <p>
     * 依次执行：数据非空校验 → 文件大小校验 → MIME 类型校验。
     *
     * @param media 待校验的媒体内容
     * @throws MediaValidationException 校验失败时抛出
     */
    public void validate(MediaContent media) {
        // 数据非空校验
        if (media.data().length == 0) {
            throw new MediaValidationException("媒体数据不能为空");
        }

        String mime = media.mimeType();

        // 文件大小校验（根据 MIME 类型使用不同限制）
        validateSize(media, mime);

        // MIME 类型校验（检查是否在支持的格式列表中）
        validateMimeType(media, mime);
    }

    /**
     * 批量校验媒体内容列表。
     * <p>
     * 先检查图片数量限制（全局前置条件），然后使用 Virtual Thread 并行校验各条目。
     *
     * @param mediaList 待校验的媒体内容列表
     * @throws MediaValidationException 校验失败时抛出
     */
    public void validateAll(List<MediaContent> mediaList) {
        // 前置检查：图片数量限制（不可并行，全局条件）
        long imageCount = mediaList.stream()
                .filter(mc -> mediaType.isImage(mc.mimeType()))
                .count();
        int maxPerRequest = properties.getImage().getMaxPerRequest();
        if (imageCount > maxPerRequest) {
            throw new MediaValidationException(
                    "单次请求图片数量超过限制: %d/%d".formatted(imageCount, maxPerRequest));
        }

        // 并行校验：Virtual Thread + CompletableFuture
        int timeout = properties.getValidation().getParallelTimeoutSeconds();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = mediaList.stream()
                    .map(media -> CompletableFuture.runAsync(() -> validate(media), executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).get(timeout, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MediaValidationException mve) throw mve;
            throw new MediaValidationException("媒体校验失败: " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new MediaValidationException("媒体校验超时（%d 秒）".formatted(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaValidationException("媒体校验被中断");
        }
    }

    /**
     * 根据 MIME 类型校验文件大小。
     */
    private void validateSize(MediaContent media, String mime) {
        long maxSize;
        String category;

        if (mediaType.isImage(mime)) {
            maxSize = properties.getImage().getMaxSizeBytes();
            category = "图片";
        } else if (mediaType.isDocument(mime)) {
            maxSize = properties.getDocument().getMaxSizeBytes();
            category = "文档";
        } else if (mediaType.isAudio(mime)) {
            maxSize = properties.getAudio().getMaxSizeBytes();
            category = "音频";
        } else if (mediaType.isVideo(mime)) {
            maxSize = properties.getVideo().getMaxSizeBytes();
            category = "视频";
        } else {
            // 未知类型不做大小校验，交给 MIME 类型校验拒绝
            return;
        }

        if (media.sizeBytes() > maxSize) {
            throw new MediaValidationException(
                    "%s文件大小超过限制: %d 字节，最大允许 %d 字节".formatted(category, media.sizeBytes(), maxSize));
        }
    }

    /**
     * 校验 MIME 类型是否在支持的格式列表中。
     */
    private void validateMimeType(MediaContent media, String mime) {
        List<String> supportedFormats;

        if (mediaType.isImage(mime)) {
            supportedFormats = properties.getImage().getSupportedFormats();
        } else if (mediaType.isDocument(mime)) {
            supportedFormats = properties.getDocument().getSupportedFormats();
        } else if (mediaType.isAudio(mime)) {
            supportedFormats = properties.getAudio().getSupportedFormats();
        } else if (mediaType.isVideo(mime)) {
            supportedFormats = properties.getVideo().getSupportedFormats();
        } else {
            throw new MediaValidationException("不支持的媒体类型: " + mime);
        }

        // 将 MIME 类型映射为短格式名称，再检查是否在支持列表中
        String format = mimeToFormat(mime);
        if (!supportedFormats.contains(format)) {
            throw new MediaValidationException(
                    "不支持的媒体格式: %s，支持的格式: %s".formatted(format, supportedFormats));
        }
    }

    /**
     * MIME 类型到短格式名称的映射表。
     * <p>
     * 部分 MIME 类型的子类型与常用格式名称不一致（如 {@code application/msword} → {@code doc}），
     * 需要通过映射表转换。未在映射表中的 MIME 类型直接提取 {@code /} 后的子类型。
     */
    private static final Map<String, String> MIME_TO_FORMAT = Map.ofEntries(
            // 文档类型
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("text/markdown", "md"),
            Map.entry("text/plain", "txt"),
            // 音频类型（子类型与格式名不一致的）
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/x-m4a", "m4a"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/x-flac", "flac")
    );

    /**
     * 将 MIME 类型映射为短格式名称。
     * <p>
     * 优先查找映射表，未命中时提取 {@code /} 后的子类型。
     * 例如 {@code image/jpeg} → {@code jpeg}，{@code application/pdf} → {@code pdf}。
     *
     * @param mimeType MIME 类型字符串
     * @return 短格式名称
     */
    private static String mimeToFormat(String mimeType) {
        // 去除 MIME 参数（如 audio/webm;codecs=opus → audio/webm）
        String baseMime = mimeType.contains(";") ? mimeType.substring(0, mimeType.indexOf(';')).trim() : mimeType;

        String mapped = MIME_TO_FORMAT.get(baseMime);
        if (mapped != null) {
            return mapped;
        }
        int slashIndex = baseMime.indexOf('/');
        if (slashIndex >= 0 && slashIndex < baseMime.length() - 1) {
            return baseMime.substring(slashIndex + 1);
        }
        return baseMime;
    }
}
