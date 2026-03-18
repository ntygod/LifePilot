package com.lifepilot.llm.multimodal.gemini;

import com.lifepilot.media.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Gemini File API 客户端，负责视频文件上传与状态轮询。
 *
 * <p>使用 Spring {@link RestClient} 调用 Gemini File API，支持断点续传（resumable upload）协议。
 * API Key 和基础 URL 从 {@link MediaProperties.NativeVideo} 配置读取，不硬编码。
 *
 * @author zsg
 * @since 2026-03-18
 */
public class GeminiFileApiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiFileApiClient.class);

    private final RestClient restClient;
    private final MediaProperties.NativeVideo config;

    public GeminiFileApiClient(MediaProperties.NativeVideo config) {
        this.config = config;
        this.restClient = RestClient.builder()
                .baseUrl(config.getGeminiApiBaseUrl())
                .defaultHeader("x-goog-api-key", config.getGeminiApiKey())
                .build();
    }

    /**
     * 上传视频文件到 Gemini File API（resumable upload 协议）。
     *
     * @param videoData   视频二进制数据
     * @param mimeType    MIME 类型（如 video/mp4）
     * @param displayName 显示名称
     * @return 上传结果，包含文件 URI
     */
    public GeminiFileUploadResult upload(byte[] videoData, String mimeType, String displayName) {
        if (videoData.length > config.getMaxFileSizeBytes()) {
            throw new GeminiFileUploadException(
                    "视频文件大小 %d 超过限制 %d".formatted(videoData.length, config.getMaxFileSizeBytes()));
        }

        try {
            // 第一步：发起 resumable upload，获取上传 URI
            String uploadUri = initiateResumableUpload(mimeType, displayName, videoData.length);

            // 第二步：上传文件数据
            var response = restClient.put()
                    .uri(uploadUri)
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(videoData.length))
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .body(videoData)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("file")) {
                throw new GeminiFileUploadException("Gemini File API 上传响应缺少 file 字段");
            }

            @SuppressWarnings("unchecked")
            var file = (Map<String, Object>) response.get("file");
            String fileUri = (String) file.get("uri");
            String state = (String) file.getOrDefault("state", "PROCESSING");
            Number size = (Number) file.getOrDefault("sizeBytes", 0L);

            log.info("Gemini 文件上传成功: uri={}, state={}", fileUri, state);
            return new GeminiFileUploadResult(fileUri, mimeType, state, size.longValue());

        } catch (GeminiFileUploadException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiFileUploadException("Gemini 文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 轮询文件处理状态，直到 ACTIVE 或超时。
     *
     * <p>采用指数退避策略，初始间隔从配置读取。
     *
     * @param fileUri 文件 URI（files/{fileId} 格式）
     */
    public void awaitActive(String fileUri) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = config.getPollTimeoutSeconds() * 1000L;
        long intervalMs = config.getPollIntervalMs();
        int attempt = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                @SuppressWarnings("unchecked")
                var response = (Map<String, Object>) restClient.get()
                        .uri("/v1beta/{fileUri}", fileUri)
                        .retrieve()
                        .body(Map.class);

                if (response != null) {
                    String state = (String) response.getOrDefault("state", "PROCESSING");
                    if ("ACTIVE".equals(state)) {
                        log.info("Gemini 文件已就绪: uri={}", fileUri);
                        return;
                    }
                    log.debug("Gemini 文件处理中: uri={}, state={}, attempt={}", fileUri, state, attempt);
                }

                // 指数退避等待
                long delay = Math.min(intervalMs * (1L << attempt), timeoutMs / 4);
                Thread.sleep(delay);
                attempt++;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GeminiFileUploadException("Gemini 文件状态轮询被中断: " + fileUri, e);
            } catch (GeminiFileUploadException e) {
                throw e;
            } catch (Exception e) {
                throw new GeminiFileUploadException("Gemini 文件状态查询失败: " + e.getMessage(), e);
            }
        }

        throw new GeminiFileUploadException(
                "Gemini 文件状态轮询超时（%d 秒）: %s".formatted(config.getPollTimeoutSeconds(), fileUri));
    }

    /**
     * 发起 resumable upload，返回上传 URI。
     */
    private String initiateResumableUpload(String mimeType, String displayName, int totalBytes) {
        var metadata = Map.of("file", Map.of("display_name", displayName));

        var response = restClient.post()
                .uri("/upload/v1beta/files")
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .header("X-Goog-Upload-Header-Content-Length", String.valueOf(totalBytes))
                .contentType(MediaType.APPLICATION_JSON)
                .body(metadata)
                .retrieve()
                .toBodilessEntity();

        String uploadUri = response.getHeaders().getFirst("X-Goog-Upload-URL");
        if (uploadUri == null || uploadUri.isBlank()) {
            throw new GeminiFileUploadException("Gemini resumable upload 未返回上传 URI");
        }

        log.debug("Gemini resumable upload 已初始化: uploadUri={}", uploadUri);
        return uploadUri;
    }
}
