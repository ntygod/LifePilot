package com.lifepilot.interaction.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jline.reader.History;
import org.jline.reader.impl.history.DefaultHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命令历史工厂 — 根据配置创建 JLine History 实例。
 *
 * <p>尝试创建文件持久化历史，若路径不可写则降级为内存历史。
 * 返回 {@link HistoryConfig} 记录，供 CliShell 配置 LineReader 使用。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class CliHistoryFactory {

    private static final Logger log = LoggerFactory.getLogger(CliHistoryFactory.class);

    private CliHistoryFactory() {
        // 工具类，禁止实例化
    }

    /**
     * 历史配置记录，包含 History 实例及 LineReader 所需的配置参数。
     *
     * @param history     JLine History 实例
     * @param historyFile 历史文件路径（降级为内存历史时为 null）
     * @param maxSize     最大历史条数
     * @param persistent  是否持久化到文件
     */
    public record HistoryConfig(
            History history,
            Path historyFile,
            int maxSize,
            boolean persistent
    ) {}

    /**
     * 根据配置创建历史实例。
     *
     * <p>优先创建文件持久化历史，自动创建父目录。
     * 若路径不可写（IOException、SecurityException），降级为内存历史并记录 WARN 日志。</p>
     *
     * @param config CLI 配置属性
     * @return 历史配置记录
     */
    public static HistoryConfig createHistory(CliConfigProperties config) {
        var history = new DefaultHistory();
        var maxSize = config.getMaxHistorySize();
        var filePath = Path.of(config.getHistoryFile());

        try {
            // 确保父目录存在
            var parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            // 验证文件可写：若文件已存在检查可写性，否则尝试创建
            if (Files.exists(filePath)) {
                if (!Files.isWritable(filePath)) {
                    log.warn("历史文件不可写，降级为内存历史: path={}", filePath);
                    return new HistoryConfig(history, null, maxSize, false);
                }
            } else {
                // 尝试创建文件以验证路径可写
                Files.createFile(filePath);
            }

            log.info("命令历史持久化已启用: path={}, maxSize={}", filePath, maxSize);
            return new HistoryConfig(history, filePath, maxSize, true);

        } catch (IOException e) {
            log.warn("历史文件路径不可写，降级为内存历史: path={}, error={}", filePath, e.getMessage());
            return new HistoryConfig(history, null, maxSize, false);
        } catch (SecurityException e) {
            log.warn("历史文件路径无访问权限，降级为内存历史: path={}, error={}", filePath, e.getMessage());
            return new HistoryConfig(history, null, maxSize, false);
        }
    }
}
