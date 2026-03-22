package com.lifepilot.sandbox.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱模块内部工具方法。
 *
 * <p>提取 ProcessBooter / DockerBooter / SandboxSessionManager 中的公共逻辑，
 * 包括流读取、输出截断和目录递归删除。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public final class SandboxUtils {

    private static final Logger log = LoggerFactory.getLogger(SandboxUtils.class);

    private SandboxUtils() {}

    /**
     * 读取输入流的全部内容为字节数组。
     *
     * @param inputStream 输入流（读取后自动关闭）
     * @return 字节数组，读取失败返回空数组
     */
    public static byte[] readStream(InputStream inputStream) {
        try (inputStream) {
            return inputStream.readAllBytes();
        } catch (IOException e) {
            log.warn("读取进程输出流失败: error={}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 截断输出到指定最大字节数。
     *
     * @param bytes    原始字节数组
     * @param maxBytes 最大字节数
     * @return 截断后的字符串
     */
    public static String truncateOutput(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (bytes.length <= maxBytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }

    /**
     * 递归删除目录及其内容。
     *
     * @param directory 目标目录
     * @throws IOException 删除失败时抛出
     */
    public static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: path={}, error={}", path, e.getMessage());
                        }
                    });
        }
    }
}
