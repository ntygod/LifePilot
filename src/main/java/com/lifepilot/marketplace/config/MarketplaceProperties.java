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

    /** 索引源 URL 列表，默认包含官方索引。 */
    private List<String> indexSources = List.of(
            "https://raw.githubusercontent.com/lifepilot-skills/index/main/index.json"
    );

    /** 索引缓存 TTL（小时），默认 24。 */
    private int cacheTtlHours = 24;

    /** 是否自动检查更新，默认 true。 */
    private boolean autoCheckUpdates = true;

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
        private String skills = "${user.home}/.lifepilot/skills";

        /** Agent 模板安装目录。 */
        private String agents = "${user.home}/.lifepilot/agents";

        /** Workflow 模板安装目录。 */
        private String workflows = "${user.home}/.lifepilot/workflows";
    }
}
