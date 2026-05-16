package com.lifepilot.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档工作空间配置属性。
 *
 * <p>文档产物落盘目录由 {@link com.lifepilot.config.path.ZhiweiPaths#home(String)} 统一提供，
 * 本类仅承载文档模块的行为参数（大小限制、GC 策略等）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ConfigurationProperties(prefix = "lifepilot.document")
public class DocumentProperties {

    /** 是否启用文档工作空间，默认 true。 */
    private boolean enabled = true;

    /** document.parse 读取内容时的默认最大字符数（Phase 0 的遗留配置，保留兼容）。 */
    private int defaultMaxChars = 30000;

    /** P2-13：单次 checkout / applyPatch 允许的文件大小上限（字节），默认 20MB。超限直接拒绝。 */
    private long maxFileSize = 20L * 1024 * 1024;

    /** P2-14：commit 后保留 working 副本的天数，超过则后台 GC 清理（默认 30 天）。{@code <=0} 表示不自动清理。 */
    private int workingRetentionDays = 30;

    /** P2-14：孤儿扫描 / retention 清理的定时间隔（分钟），默认 60。{@code <=0} 禁用调度。 */
    private int gcIntervalMinutes = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDefaultMaxChars() { return defaultMaxChars; }
    public void setDefaultMaxChars(int defaultMaxChars) { this.defaultMaxChars = defaultMaxChars; }

    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }

    public int getWorkingRetentionDays() { return workingRetentionDays; }
    public void setWorkingRetentionDays(int workingRetentionDays) { this.workingRetentionDays = workingRetentionDays; }

    public int getGcIntervalMinutes() { return gcIntervalMinutes; }
    public void setGcIntervalMinutes(int gcIntervalMinutes) { this.gcIntervalMinutes = gcIntervalMinutes; }
}
