package com.lifepilot.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档工作空间配置属性。
 *
 * <p>绑定 {@code lifepilot.document} 配置前缀，控制文档工具链的启停以及
 * document.parse 工具的默认行为参数。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@Data
@ConfigurationProperties(prefix = "lifepilot.document")
public class DocumentProperties {

    /** 是否启用文档工作空间模块，默认 true。 */
    private boolean enabled = true;

    /** document.parse 工具默认返回内容最大字符数，超出会被截断，默认 30000。 */
    private int defaultMaxChars = 30000;
}
