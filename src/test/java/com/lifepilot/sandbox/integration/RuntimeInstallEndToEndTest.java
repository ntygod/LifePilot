package com.lifepilot.sandbox.integration;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import com.github.luben.zstd.ZstdOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python 运行时端到端安装测试 — mock HTTP 服务器 + 现场打 fake tarball + 验证 Ready。
 *
 * <p>此测试不依赖 Spring 容器，纯组合 PythonRuntimeManager + RuntimeInstallProgressEmitter，
 * 通过 JDK 内置 HttpServer 模拟下载源，端口 0 由 OS 动态分配避开 CI 冲突。</p>
 *
 * <p>fake tarball 在内存中现场打包：tar -> zstd -> ByteArrayOutputStream，包含
 * {@code python/VERSION} 与 {@code python/bin/python} 两个最小条目，匹配
 * checkStatus 对 VERSION 文件 + 可执行文件存在性的双重检查。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
class RuntimeInstallEndToEndTest {

    @Test
    void mockHTTP_install_状态变为Ready(@TempDir Path tempDir) throws Exception {
        // 1. 现场打 fake tarball
        byte[] tarball = buildFakeTarball();
        String sha256 = sha256Hex(tarball);

        // 2. 启动 mock HTTP 服务器（端口 0 让 OS 分配，规避 CI 冲突）
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/file.tar.zst", ex -> {
            ex.sendResponseHeaders(200, tarball.length);
            ex.getResponseBody().write(tarball);
            ex.close();
        });
        server.createContext("/file.tar.zst.sha256", ex -> {
            byte[] hashBytes = sha256.getBytes();
            ex.sendResponseHeaders(200, hashBytes.length);
            ex.getResponseBody().write(hashBytes);
            ex.close();
        });
        server.start();

        try {
            // 3. 配置指向 mock URL
            var config = new SandboxConfigProperties();
            config.getRuntime().getPython().setBundledVersion("0.0.0-test");
            config.getRuntime().getPython().setInstallPath(tempDir.resolve("python").toString());
            config.getRuntime().getPython().setDownloadUrlTemplate(
                "http://localhost:" + port + "/file.tar.zst");
            config.getRuntime().getPython().setSha256UrlTemplate(
                "http://localhost:" + port + "/file.tar.zst.sha256");

            var manager = new PythonRuntimeManager(config);
            var emitter = new RuntimeInstallProgressEmitter();

            // 4. install — 同步等待 CompletableFuture 完成
            manager.install(emitter).get();

            // 5. 验证 Ready 状态 + 版本号匹配
            var status = manager.checkStatus();
            assertThat(status).isInstanceOf(RuntimeStatus.Ready.class);
            assertThat(((RuntimeStatus.Ready) status).version()).isEqualTo("0.0.0-test");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 内存中现场打包一个 tar.zst，包含两个条目：
     * <ul>
     *   <li>{@code python/VERSION} — 内容 "0.0.0-test"，与配置 bundledVersion 匹配</li>
     *   <li>{@code python/bin/python} — fake shell 脚本，mode 0755（Windows 下被忽略不影响）</li>
     * </ul>
     */
    private byte[] buildFakeTarball() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zstd = new ZstdOutputStream(bytes);
             var tar = new TarArchiveOutputStream(zstd)) {
            // 加 python/VERSION
            byte[] version = "0.0.0-test".getBytes();
            var versionEntry = new TarArchiveEntry("python/VERSION");
            versionEntry.setSize(version.length);
            tar.putArchiveEntry(versionEntry);
            tar.write(version);
            tar.closeArchiveEntry();

            // 加 python/bin/python（fake 可执行文件）
            byte[] py = "#!/bin/sh\necho fake".getBytes();
            var pyEntry = new TarArchiveEntry("python/bin/python");
            pyEntry.setSize(py.length);
            pyEntry.setMode(0755);
            tar.putArchiveEntry(pyEntry);
            tar.write(py);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    /** 计算字节数组的 SHA-256 十六进制摘要（小写）。 */
    private static String sha256Hex(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
}
