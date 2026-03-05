package com.lifepilot.skill.marketplace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Skill 市场配置属性。
 *
 * <p>绑定 {@code lifepilot.marketplace} 配置前缀。</p>
 *
 * @author zsg
 * @since 2026-03-05
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

    /**
     * 安全扫描配置。
     */
    @Getter
    @Setter
    public static class Security {

        /** 是否阻止 HIGH 风险 Skill 安装（不允许用户覆盖），默认 false。 */
        private boolean blockHighRisk = false;

        /** Prompt 注入检测正则模式列表。 */
        private List<String> injectionPatterns = List.of(
                "(?i)ignore\\s+(all\\s+)?previous\\s+instructions?",
                "(?i)ignore\\s+(all\\s+)?prior",
                "(?i)disregard\\s+(all\\s+)?(above|previous)",
                "(?i)forget\\s+(all\\s+)?previous"
        );
    }
}
