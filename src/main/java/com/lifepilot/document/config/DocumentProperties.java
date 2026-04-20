package com.lifepilot.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档工作空间配置属性。
 *
 * <p>Phase 0 曾有同名类（已清理），Phase 2A 重建以承载 storageDir 与生成相关参数。
 * 显式 getter/setter 对齐项目 {@code @ConfigurationProperties} 主流写法（见 MultiAgentProperties）。</p>
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

    /** 文档产物落盘目录，默认 ${zhiwei.data-dir}/documents。 */
    private String storageDir = System.getProperty("user.home") + "/.zhiwei/documents";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDefaultMaxChars() { return defaultMaxChars; }
    public void setDefaultMaxChars(int defaultMaxChars) { this.defaultMaxChars = defaultMaxChars; }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
}
