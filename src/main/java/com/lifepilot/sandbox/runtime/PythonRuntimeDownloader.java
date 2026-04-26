package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Python 运行时下载器 — 流式下载 + SHA-256 校验。
 *
 * <p>下载流程：
 * <ol>
 *   <li>流式下载到 {@code <target>.partial}，期间通过 {@link Consumer} 回调上报已写入字节数</li>
 *   <li>下载完成后单独获取 SHA-256 摘要文件，与 partial 的实际摘要做大小写无关比较</li>
 *   <li>校验通过则原子重命名 partial 为目标文件；失败则删除 partial 与目标文件并抛出 {@link IOException}</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-26
 */
public class PythonRuntimeDownloader {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntimeDownloader.class);

    /** 单次缓冲区大小，64KB 对网络与磁盘吞吐都比较友好 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 下载文件并校验 SHA-256。失败时清理部分文件并抛异常。
     *
     * @param fileUrl          目标文件 URL
     * @param sha256Url        SHA-256 摘要文件 URL（内容首段为十六进制字符串）
     * @param targetFile       本地目标路径
     * @param progressConsumer 进度回调，参数为已写入字节数（每个 buffer 触发一次）
     * @throws IOException          网络异常、写入异常或校验失败
     * @throws InterruptedException HTTP 请求被中断
     */
    public void download(String fileUrl, String sha256Url, Path targetFile,
                         Consumer<Long> progressConsumer) throws IOException, InterruptedException {
        Files.createDirectories(targetFile.getParent());
        Path partial = targetFile.resolveSibling(targetFile.getFileName() + ".partial");

        try {
            // 1. 流式下载到 partial 文件，边下边算进度回调
            var fileReq = HttpRequest.newBuilder(URI.create(fileUrl)).GET().build();
            HttpResponse<InputStream> resp = httpClient.send(fileReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + fileUrl);
            }

            try (var in = resp.body();
                 var out = Files.newOutputStream(partial)) {
                byte[] buf = new byte[BUFFER_SIZE];
                long total = 0L;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    progressConsumer.accept(total);
                }
            }

            // 2. 拉取 SHA-256 摘要文件，取首段（兼容 "<hash>  <filename>" 格式）
            var hashReq = HttpRequest.newBuilder(URI.create(sha256Url)).GET().build();
            HttpResponse<String> hashResp = httpClient.send(hashReq, HttpResponse.BodyHandlers.ofString());
            if (hashResp.statusCode() != 200) {
                throw new IOException("HTTP " + hashResp.statusCode() + " for " + sha256Url);
            }
            String expected = hashResp.body().trim().split("\\s+")[0].toLowerCase();

            // 3. 计算实际摘要并对比
            String actual = computeSha256(partial);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 校验失败: 期望 " + expected + ", 实际 " + actual);
            }

            // 4. 原子重命名为最终文件
            Files.move(partial, targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("下载完成: {} ({} bytes, sha256={})", targetFile, Files.size(targetFile), actual);
        } catch (Exception e) {
            // 失败统一清理：partial 与 targetFile 都尝试删除
            try { Files.deleteIfExists(partial); } catch (IOException ignored) { /* 清理失败不掩盖原异常 */ }
            try { Files.deleteIfExists(targetFile); } catch (IOException ignored) { /* 清理失败不掩盖原异常 */ }
            if (e instanceof IOException io) {
                throw io;
            }
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw new IOException("下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式计算文件的 SHA-256 十六进制摘要（小写）。
     */
    private static String computeSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，运行时没有视为环境致命错误
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
