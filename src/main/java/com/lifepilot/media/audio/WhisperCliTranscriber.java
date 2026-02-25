package com.lifepilot.media.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地 Whisper CLI 适配器。
 *
 * <p>通过 {@link ProcessBuilder} 调用 whisper.cpp CLI 执行语音转录。
 * 支持自动检测 PATH 中的 {@code whisper-cli} 或 {@code whisper} 命令，
 * 也支持指定具体的 CLI 路径。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class WhisperCliTranscriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperCliTranscriber.class);

    /** 转录进程超时时间（秒）。 */
    private static final long TIMEOUT_SECONDS = 60;

    /** 配置的 Whisper CLI 路径（"auto" 表示自动检测）。 */
    private final String whisperCliPath;

    /** Whisper 模型名称（tiny/base/small/medium/large）。 */
    private final String whisperModel;

    /** 缓存的已解析 CLI 路径，null 表示尚未解析或不可用。 */
    private volatile String resolvedCliPath;

    /**
     * 构造 WhisperCliTranscriber。
     *
     * @param whisperCliPath CLI 路径，"auto" 表示自动检测 PATH
     * @param whisperModel   Whisper 模型名称
     */
    public WhisperCliTranscriber(String whisperCliPath, String whisperModel) {
        this.whisperCliPath = whisperCliPath;
        this.whisperModel = whisperModel;
    }

    /**
     * 检测 Whisper CLI 是否可用。
     *
     * <p>首次调用时执行检测并缓存结果，后续调用直接返回缓存值。
     * 当 {@code whisperCliPath} 为 "auto" 时，依次尝试 {@code whisper-cli}
     * 和 {@code whisper} 命令；否则检查指定路径是否存在且可执行。</p>
     *
     * @return CLI 可用返回 true，否则返回 false
     */
    public boolean isAvailable() {
        if (resolvedCliPath != null) {
            return true;
        }
        if ("auto".equals(whisperCliPath)) {
            resolvedCliPath = findInPath("whisper-cli");
            if (resolvedCliPath == null) {
                resolvedCliPath = findInPath("whisper");
            }
        } else {
            if (Files.isExecutable(Path.of(whisperCliPath))) {
                resolvedCliPath = whisperCliPath;
            }
        }
        return resolvedCliPath != null;
    }

    /**
     * 执行语音转录。
     *
     * <p>将音频数据写入临时文件，通过 ProcessBuilder 调用 Whisper CLI 执行转录，
     * 解析输出文本文件获取转录结果。超时默认 60 秒，超时后强制销毁进程。</p>
     *
     * @param audioData 音频二进制数据
     * @param mimeType  音频 MIME 类型（如 "audio/wav"）
     * @return 转录文本
     * @throws AudioTranscriptionException 转录失败
     */
    public String transcribe(byte[] audioData, String mimeType) {
        if (!isAvailable()) {
            throw new AudioTranscriptionException("Whisper CLI 不可用");
        }

        String extension = extractExtension(mimeType);
        Path tempFile = null;
        Path outputFile = null;
        try {
            // 写入临时文件
            tempFile = Files.createTempFile("whisper-input-", extension);
            Files.write(tempFile, audioData);

            // 构建 CLI 命令
            ProcessBuilder pb = new ProcessBuilder(
                    resolvedCliPath,
                    "--model", whisperModel,
                    "--output-txt",
                    tempFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);

            log.debug("执行 Whisper CLI: {}", pb.command());
            Process process = pb.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AudioTranscriptionException("Whisper CLI 转录超时（超过 " + TIMEOUT_SECONDS + " 秒）");
            }

            if (process.exitValue() != 0) {
                String errorOutput = new String(process.getInputStream().readAllBytes());
                throw new AudioTranscriptionException(
                        "Whisper CLI 转录失败，退出码: " + process.exitValue() + "，输出: " + errorOutput);
            }

            // 解析输出文本文件（与输入同名但扩展名为 .txt）
            String tempFileName = tempFile.toAbsolutePath().toString();
            outputFile = Path.of(tempFileName + ".txt");
            if (!Files.exists(outputFile)) {
                throw new AudioTranscriptionException("Whisper CLI 输出文件不存在: " + outputFile);
            }

            String transcript = Files.readString(outputFile).trim();
            log.debug("Whisper CLI 转录完成，文本长度: {}", transcript.length());
            return transcript;

        } catch (AudioTranscriptionException e) {
            throw e;
        } catch (IOException e) {
            throw new AudioTranscriptionException("Whisper CLI 转录 I/O 异常", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AudioTranscriptionException("Whisper CLI 转录被中断", e);
        } finally {
            deleteSilently(tempFile);
            deleteSilently(outputFile);
        }
    }

    /**
     * 从 MIME 类型提取文件扩展名。
     *
     * @param mimeType MIME 类型（如 "audio/wav"）
     * @return 文件扩展名（如 ".wav"）
     */
    private String extractExtension(String mimeType) {
        if (mimeType == null || !mimeType.contains("/")) {
            return ".wav";
        }
        String subType = mimeType.substring(mimeType.indexOf('/') + 1);
        // 处理常见的 MIME 子类型映射
        return switch (subType) {
            case "mpeg" -> ".mp3";
            case "x-wav", "wav" -> ".wav";
            case "x-flac", "flac" -> ".flac";
            case "ogg" -> ".ogg";
            case "mp4", "x-m4a", "m4a" -> ".m4a";
            case "webm" -> ".webm";
            default -> "." + subType;
        };
    }

    /**
     * 在系统 PATH 中查找指定命令。
     *
     * @param command 命令名称
     * @return 命令的完整路径，未找到返回 null
     */
    private String findInPath(String command) {
        try {
            // Unix 使用 which，Windows 使用 where
            String whichCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            Process p = new ProcessBuilder(whichCmd, command).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return new String(p.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception e) {
            log.debug("CLI 检测失败: command={}", command, e);
        }
        return null;
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
