package com.lifepilot.memory.eval.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 记忆评估 Harness 自动装配。
 *
 * <p>只有在 {@code lifepilot.memory.eval.enabled=true} 时激活。默认关闭，
 * 防止生产环境误注册评估相关 Bean。</p>
 *
 * <p>各子组件（Loader / Judge / Probe / Reporter / Runner）的 Bean 注册
 * 在后续任务中逐步补充到本类。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryEvalProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.memory.eval", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class MemoryEvalAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryEvalAutoConfiguration.class);

    public MemoryEvalAutoConfiguration(MemoryEvalProperties properties) {
        log.info("记忆评估 Harness 已启用，模式={}，基线路径={}",
                properties.getMode(), properties.getRegression().getBaselinePath());
    }
}
