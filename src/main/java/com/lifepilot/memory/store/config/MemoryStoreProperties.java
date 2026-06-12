package com.lifepilot.memory.store.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆存储层配置属性。
 *
 * <p>绑定 {@code lifepilot.memory.store} 配置前缀。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.memory.store")
public class MemoryStoreProperties {

    /** sqlite-vec 向量维度，默认 1024。 */
    private int embeddingDimensions = 1024;

    /** 冲突检测语义匹配阈值 [0.0, 1.0]，默认 0.92。 */
    private float semanticMatchThreshold = 0.92f;

    /** 向量数据库 JDBC URL。 */
    private String vectorDbUrl = "jdbc:sqlite:" + System.getProperty("user.home") + "/.zhiwei/vectors.db";

    /** 向量数据库 busy_timeout（毫秒），默认 30000。 */
    private int busyTimeoutMs = 30000;

    /** L4 程序记忆配置。 */
    private Procedural procedural = new Procedural();

    /**
     * L4 程序记忆配置。
     */
    @Setter
    @Getter
    public static class Procedural {
        private int maxTemplates = 200;
        private float minReliability = 0.7f;
        private int minUseCount = 2;
        private int staleDays = 90;
        private float matchThreshold = 0.6f;
        private float defaultImportance = 0.5f;
        private boolean templateEnabled = true;
    }
}
