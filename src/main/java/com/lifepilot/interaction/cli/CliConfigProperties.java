package com.lifepilot.interaction.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CLI 交互层配置属性。
 *
 * <p>通过 {@code lifepilot.cli.*} 配置键管理 CLI 行为参数。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.cli")
public class CliConfigProperties {

    /** 命令历史文件路径。 */
    private String historyFile = System.getProperty("user.home") + "/.lifepilot/cli-history";

    /** 历史记录最大条数。 */
    private int maxHistorySize = 1000;

    /** 对话模式提示符。 */
    private String chatPrompt = "lifepilot> ";

    /** 普通模式提示符。 */
    private String defaultPrompt = "$ ";

    /** 流式输出刷新间隔（毫秒）。 */
    private long streamFlushIntervalMs = 50;

    /** 用户确认超时时间（秒）。 */
    private int confirmationTimeoutSeconds = 30;

    public String getHistoryFile() {
        return historyFile;
    }

    public void setHistoryFile(String historyFile) {
        this.historyFile = historyFile;
    }

    public int getMaxHistorySize() {
        return maxHistorySize;
    }

    public void setMaxHistorySize(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
    }

    public String getChatPrompt() {
        return chatPrompt;
    }

    public void setChatPrompt(String chatPrompt) {
        this.chatPrompt = chatPrompt;
    }

    public String getDefaultPrompt() {
        return defaultPrompt;
    }

    public void setDefaultPrompt(String defaultPrompt) {
        this.defaultPrompt = defaultPrompt;
    }

    public long getStreamFlushIntervalMs() {
        return streamFlushIntervalMs;
    }

    public void setStreamFlushIntervalMs(long streamFlushIntervalMs) {
        this.streamFlushIntervalMs = streamFlushIntervalMs;
    }

    public int getConfirmationTimeoutSeconds() {
        return confirmationTimeoutSeconds;
    }

    public void setConfirmationTimeoutSeconds(int confirmationTimeoutSeconds) {
        this.confirmationTimeoutSeconds = confirmationTimeoutSeconds;
    }
}
