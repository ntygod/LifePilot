package com.lifepilot.sandbox.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PythonRuntimeDownloader 下载与 SHA-256 校验单元测试。
 *
 * <p>使用 JDK 内置 {@link HttpServer} 启动本地 mock 服务器，覆盖正常下载与篡改场景。
 *
 * @author zsg
 * @since 2026-04-26
 */
class PythonRuntimeDownloader_下载校验测试 {

    private HttpServer server;
    private int port;

    @BeforeEach
    void 启动mock服务器() throws IOException {
        // 端口 0 让系统分配空闲端口，避免冲突
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void 停止mock服务器() {
        server.stop(0);
    }

    @Test
    void 下载并校验SHA256通过(@TempDir Path tempDir) throws Exception {
        byte[] payload = "fake-tarball-content".getBytes();
        String sha256 = sha256Hex(payload);

        server.createContext("/file.tar.zst", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/file.tar.zst.sha256", exchange -> {
            byte[] hashBytes = sha256.getBytes();
            exchange.sendResponseHeaders(200, hashBytes.length);
            exchange.getResponseBody().write(hashBytes);
            exchange.close();
        });

        var downloader = new PythonRuntimeDownloader();
        Path target = tempDir.resolve("downloaded.tar.zst");
        BiConsumer<Long, Long> progress = (downloaded, total) -> {};

        downloader.download("http://localhost:" + port + "/file.tar.zst",
                "http://localhost:" + port + "/file.tar.zst.sha256",
                target, progress);

        assertThat(target).exists();
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    void SHA256不匹配时抛异常并删除文件(@TempDir Path tempDir) throws Exception {
        byte[] payload = "tampered".getBytes();
        server.createContext("/file.tar.zst", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/file.tar.zst.sha256", exchange -> {
            byte[] wrong = "0000000000000000000000000000000000000000000000000000000000000000".getBytes();
            exchange.sendResponseHeaders(200, wrong.length);
            exchange.getResponseBody().write(wrong);
            exchange.close();
        });

        var downloader = new PythonRuntimeDownloader();
        Path target = tempDir.resolve("bad.tar.zst");

        assertThatThrownBy(() -> downloader.download(
                "http://localhost:" + port + "/file.tar.zst",
                "http://localhost:" + port + "/file.tar.zst.sha256",
                target, (b, t) -> {}))
            .hasMessageContaining("SHA-256");

        assertThat(target).doesNotExist();
        assertThat(tempDir.resolve("bad.tar.zst.partial")).doesNotExist();
    }

    private static String sha256Hex(byte[] input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(input));
    }
}
