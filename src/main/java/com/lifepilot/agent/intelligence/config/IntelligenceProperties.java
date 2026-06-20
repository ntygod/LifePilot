package com.lifepilot.agent.intelligence.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能层配置属性。
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.intelligence")
public class IntelligenceProperties {

    /** 智能层总开关，默认 true。 */
    private boolean enabled = true;

    /** 决策信号注入开关，默认 true。 */
    private boolean decisionSignalEnabled = true;

    /** 经验匹配最低置信度，低于此值不注入决策信号，默认 0.6。 */
    private float minExperienceConfidence = 0.6f;

    /** 环境感知缓存时间（秒），默认 60。 */
    private int environmentCacheTtlSeconds = 60;

    /** 工具健康滑动窗口大小，默认 20。 */
    private int toolHealthWindowSize = 20;
}
