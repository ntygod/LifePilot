package com.lifepilot.interaction.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 渠道分发与文件产物相关配置。
 *
 * <p>绑定 {@code lifepilot.gateway.delivery} 配置前缀。覆盖两类阈值：</p>
 * <ul>
 *   <li><b>工具执行器层硬限制</b>：{@code artifactMaxSizeMb}、{@code artifactMaxCountPerTool}、
 *       {@code artifactExcludedDirs}、{@code artifactExcludedExtensions} —— 用于
 *       {@link ArtifactFilterConfig}，决定哪些文件能登记为工具产物（防止 OOM 与噪声污染）</li>
 *   <li><b>渠道分发层降级阈值</b>：{@code platformMaxSize} —— 决定单产物在不同 IM
 *       平台触发降级提示的临界值（飞书 30MB / 企微 20MB / 钉钉 30MB / Telegram
 *       50MB / Web 200MB；超过则不投递文件 delivery，主消息追加路径降级提示）</li>
 * </ul>
 *
 * <p>两层阈值互不干扰：第一层防系统过载，第二层适配各平台业务限制。</p>
 *
 * @param artifactMaxSizeMb           单产物大小硬上限（MB），超过则在工具执行器层直接丢弃
 * @param artifactMaxCountPerTool     单次工具调用产物数上限
 * @param artifactExcludedDirs        过滤排除的目录段名列表
 * @param artifactExcludedExtensions  过滤排除的扩展名列表（不含点）
 * @param platformMaxSize             各 IM 平台单产物大小上限映射（MB）
 *
 * @author zsg
 * @since 2026-05-17
 */
@ConfigurationProperties(prefix = "lifepilot.gateway.delivery")
public record GatewayDeliveryProperties(
        @DefaultValue("50") int artifactMaxSizeMb,
        @DefaultValue("20") int artifactMaxCountPerTool,
        @DefaultValue({".git", "node_modules", "__pycache__", ".venv", "target"})
        List<String> artifactExcludedDirs,
        @DefaultValue({"tmp", "log", "swp", "swo"})
        List<String> artifactExcludedExtensions,
        @DefaultValue PlatformMaxSize platformMaxSize
) {

    /** 默认平台上限兜底值（MB），未配置或未匹配的平台按此回退。 */
    public static final int FALLBACK_PLATFORM_MAX_SIZE_MB = 50;

    /**
     * 构造对应的 {@link ArtifactFilterConfig}，供 {@code ArtifactFilter} 使用。
     */
    public ArtifactFilterConfig toFilterConfig() {
        return new ArtifactFilterConfig(
                artifactMaxSizeMb,
                artifactMaxCountPerTool,
                new HashSet<>(artifactExcludedDirs),
                new HashSet<>(artifactExcludedExtensions)
        );
    }

    /**
     * 查询指定平台的单产物上限（MB）；未配置时回落到 {@link #FALLBACK_PLATFORM_MAX_SIZE_MB}。
     *
     * @param platform 平台名（小写，如 {@code feishu} / {@code wecom} / {@code dingtalk} /
     *                 {@code telegram} / {@code web}）
     * @return 上限 MB
     */
    public int resolvePlatformMaxSizeMb(String platform) {
        if (platform == null || platform.isBlank() || platformMaxSize == null) {
            return FALLBACK_PLATFORM_MAX_SIZE_MB;
        }
        Map<String, Integer> map = platformMaxSize.toMap();
        Integer mb = map.get(platform.toLowerCase());
        return mb != null && mb > 0 ? mb : FALLBACK_PLATFORM_MAX_SIZE_MB;
    }

    /**
     * 各 IM 平台单产物大小上限。Spring Boot 不直接绑定 {@code Map<String, Integer>} 时
     * 用 record 字段更可读。
     *
     * @param feishu   飞书平台 MB 上限（默认 30）
     * @param wecom    企微平台 MB 上限（默认 20）
     * @param dingtalk 钉钉平台 MB 上限（默认 30）
     * @param telegram Telegram 平台 MB 上限（默认 50）
     * @param web      Web 平台 MB 上限（默认 200，本地下载基本无限制）
     */
    public record PlatformMaxSize(
            @DefaultValue("30") int feishu,
            @DefaultValue("20") int wecom,
            @DefaultValue("30") int dingtalk,
            @DefaultValue("50") int telegram,
            @DefaultValue("200") int web
    ) {
        /**
         * 转成小写键的 Map 便于按平台名查询。
         */
        public Map<String, Integer> toMap() {
            return Map.of(
                    "feishu", feishu,
                    "wecom", wecom,
                    "dingtalk", dingtalk,
                    "telegram", telegram,
                    "web", web
            );
        }
    }
}
