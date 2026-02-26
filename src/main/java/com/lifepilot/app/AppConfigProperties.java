package com.lifepilot.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用级配置属性。
 *
 * <p>前缀 {@code lifepilot.app}，包含启动模式等全局配置。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@ConfigurationProperties(prefix = "lifepilot.app")
public class AppConfigProperties {

    /** 默认启动模式，可被 --mode 命令行参数覆盖。 */
    private String launchMode = "full";

    public String getLaunchMode() {
        return launchMode;
    }

    public void setLaunchMode(String launchMode) {
        this.launchMode = launchMode;
    }
}
