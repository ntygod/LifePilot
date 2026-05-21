package com.lifepilot.memory.consumption.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆消费层配置属性。
 *
 * <p>绑定 {@code lifepilot.memory.consumption} 配置前缀。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.memory.consumption")
public class MemoryConsumptionProperties {

    /** 触发压缩的 Token 阈值，默认 4000。 */
    private int compressionThresholdTokens = 4000;

    // ─── HotDigest 热记忆摘要配置 ───

    /** 热记忆摘要配置。 */
    private HotDigest hotDigest = new HotDigest();

    // ─── Compression 对话压缩配置 ───

    /** 对话压缩配置。 */
    private Compression compression = new Compression();

    // ─── EpisodicCleanup 情景记忆清理配置 ───

    /** 情景记忆自动清理配置。 */
    private EpisodicCleanup episodicCleanup = new EpisodicCleanup();

    /**
     * L3.5 热记忆摘要配置。
     *
     * <p>控制从 L3 可消费实体派生的小预算 Prompt 摘要。</p>
     */
    @Setter
    @Getter
    public static class HotDigest {

        /** 是否启用热记忆摘要，默认 true。 */
        private boolean enabled = true;

        /** 用户画像摘要 token 预算。 */
        private int userProfileTokenBudget = 500;

        /** 项目记忆摘要 token 预算。 */
        private int projectMemoryTokenBudget = 400;

        /** 任务级经验摘要 token 预算。 */
        private int experienceTokenBudget = 500;

        /** 事实摘要 token 预算。 */
        private int factsTokenBudget = 400;

        /** 用户画像最多条目数。 */
        private int userProfileMaxEntries = 6;

        /** 项目记忆最多条目数。 */
        private int projectMemoryMaxEntries = 4;

        /** 经验最多条目数。 */
        private int experienceMaxEntries = 3;

        /** 事实最多条目数。 */
        private int factsMaxEntries = 4;
    }

    /**
     * 对话压缩配置 — 控制压缩策略、滑动窗口大小和窗口重叠。
     */
    @Setter
    @Getter
    public static class Compression {

        /** 压缩策略：whole / sliding-window，默认 sliding-window。 */
        private String strategy = "sliding-window";

        /** 滑动窗口大小（消息数），默认 20。 */
        private int windowSize = 20;

        /** 窗口重叠消息数，默认 2。 */
        private int windowOverlap = 2;
    }

    /**
     * L2 情景记忆自动清理配置 — 控制过期对话记录的定时清理策略。
     */
    @Setter
    @Getter
    public static class EpisodicCleanup {

        /** 清理 Cron 表达式，默认每日凌晨 5:00。 */
        private String cron = "0 0 5 * * *";

        /** 保留天数，默认 90。 */
        private int retentionDays = 90;

        /** 单次最大清理数量，默认 500。 */
        private int maxCleanupPerRun = 500;
    }
}
