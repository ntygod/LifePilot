package com.lifepilot.interaction.tray.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 系统托盘配置属性。
 *
 * <p>前缀 {@code lifepilot.tray}，控制托盘功能开关、图标提示和 Web UI 地址。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@ConfigurationProperties(prefix = "lifepilot.tray")
public class TrayConfigProperties {

    /** 托盘功能开关。 */
    private boolean enabled = false;

    /** 托盘图标工具提示文本。 */
    private String tooltip = "LifePilot - AI 生活助手";

    /** Web UI 地址，用于「打开 Web UI」菜单项。 */
    private String webUiUrl = "http://localhost:8080";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTooltip() {
        return tooltip;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    public String getWebUiUrl() {
        return webUiUrl;
    }

    public void setWebUiUrl(String webUiUrl) {
        this.webUiUrl = webUiUrl;
    }
}
