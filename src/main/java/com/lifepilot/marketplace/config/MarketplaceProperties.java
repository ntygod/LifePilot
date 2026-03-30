package com.lifepilot.marketplace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 扩展市场配置属性。
 *
 * <p>绑定 {@code lifepilot.marketplace} 配置前缀，支持 Skill / Agent / Workflow 三种扩展类型的市场配置。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "lifepilot.marketplace")
public class MarketplaceProperties {

    /** 市场功能总开关，默认 true。 */
    private boolean enabled = true;

    /** Marketplace 插件协议兼容版本，默认 1.0.0。 */
    private String compatibilityVersion = "1.0.0";

    /** 索引源 URL 列表，默认包含官方索引。 */
    private List<String> indexSources = List.of(
            "https://raw.githubusercontent.com/ntygod/ZhiWei-index/main/index.json"
    );

    /** 索引缓存 TTL（小时），默认 24。 */
    private int cacheTtlHours = 24;

    /** 是否自动检查更新，默认 true。 */
    private boolean autoCheckUpdates = true;

    /** Marketplace 远程访问配置。 */
    private Http http = new Http();

    /** 安全扫描配置。 */
    private Security security = new Security();

    /** 扩展安装目录配置。 */
    private InstallDirs installDirs = new InstallDirs();

    /**
     * 安全扫描配置。
     */
    @Getter
    @Setter
    public static class Security {

        /** 是否阻止 HIGH 风险扩展安装（不允许用户覆盖），默认 false。 */
        private boolean blockHighRisk = false;

        /** Prompt 注入检测正则模式列表。 */
        private List<String> injectionPatterns = List.of(
                "(?i)ignore\\s+(all\\s+)?previous\\s+instructions?",
                "(?i)ignore\\s+(all\\s+)?prior",
                "(?i)disregard\\s+(all\\s+)?(above|previous)",
                "(?i)forget\\s+(all\\s+)?previous"
        );
    }

    /**
     * 扩展安装目录配置 — 按类型指定本地安装路径。
     */
    @Getter
    @Setter
    public static class InstallDirs {

        /** Skill 扩展安装目录。 */
        private String skills = "${user.home}/.zhiwei/skills";

        /** Agent 模板安装目录。 */
        private String agents = "${user.home}/.zhiwei/agents";

        /** Workflow 模板安装目录。 */
        private String workflows = "${user.home}/.zhiwei/workflows";

        /** 渠道插件安装目录。 */
        private String channels = "${user.home}/.zhiwei/channels";
    }

    /**
     * Marketplace 远程访问配置。
     */
    @Getter
    @Setter
    public static class Http {

        /** 单个远程地址最大重试次数。 */
        private int maxAttempts = 3;

        /** 两次重试之间的退避毫秒数。 */
        private long retryBackoffMillis = 500;

        /** 是否为 raw.githubusercontent.com 启用镜像回退。 */
        private boolean enableGithubMirrorFallback = true;
    }
}
