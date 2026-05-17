package com.lifepilot.tool.artifact;

import java.util.Set;

/**
 * 工具产物过滤配置。
 *
 * <p>承载 {@link ArtifactFilter#accept(java.nio.file.Path, long, ArtifactFilterConfig)} 所需的全部
 * 阈值与排除集合。配置由 Spring Boot 通过 {@code GatewayDeliveryProperties.toFilterConfig()} 注入；
 * 测试场景可调 {@link #defaultConfig()} 直接拿默认值。</p>
 *
 * @param maxSizeMb           单产物大小上限（MB）；超过则在工具执行器层直接丢弃。{@code <=0} 时回退默认 50
 * @param maxCountPerTool     单次工具调用产物数上限；超过则按 mtime 倒序截取。{@code <=0} 时回退默认 20
 * @param excludedDirs        排除的目录段名集合（路径任意一段命中即拒绝）
 * @param excludedExtensions  排除的扩展名集合（不含点）
 *
 * @author zsg
 * @since 2026-05-17
 */
public record ArtifactFilterConfig(
        int maxSizeMb,
        int maxCountPerTool,
        Set<String> excludedDirs,
        Set<String> excludedExtensions
) {

    private static final int DEFAULT_MAX_SIZE_MB = 50;
    private static final int DEFAULT_MAX_COUNT = 20;
    private static final Set<String> DEFAULT_EXCLUDED_DIRS =
            Set.of(".git", "node_modules", "__pycache__", ".venv", "target");
    private static final Set<String> DEFAULT_EXCLUDED_EXTENSIONS =
            Set.of("tmp", "log", "swp", "swo");

    /**
     * 紧凑构造器：阈值兜底 + 集合防御性拷贝。
     */
    public ArtifactFilterConfig {
        if (maxSizeMb <= 0) {
            maxSizeMb = DEFAULT_MAX_SIZE_MB;
        }
        if (maxCountPerTool <= 0) {
            maxCountPerTool = DEFAULT_MAX_COUNT;
        }
        excludedDirs = (excludedDirs != null) ? Set.copyOf(excludedDirs) : DEFAULT_EXCLUDED_DIRS;
        excludedExtensions = (excludedExtensions != null)
                ? Set.copyOf(excludedExtensions)
                : DEFAULT_EXCLUDED_EXTENSIONS;
    }

    /**
     * 返回内置默认配置；测试场景或未注入 Properties 时使用。
     */
    public static ArtifactFilterConfig defaultConfig() {
        return new ArtifactFilterConfig(
                DEFAULT_MAX_SIZE_MB,
                DEFAULT_MAX_COUNT,
                DEFAULT_EXCLUDED_DIRS,
                DEFAULT_EXCLUDED_EXTENSIONS
        );
    }
}
