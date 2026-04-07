package com.lifepilot.knowledge.config;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.lifepilot.knowledge.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Token 计数器自动配置 — 创建 {@link TokenCounter} Bean。
 *
 * <p>根据 {@code lifepilot.knowledge.tokenizer.encoding} 配置选择编码：
 * <ul>
 *   <li>{@code cl100k_base} — GPT-4 / text-embedding-3（默认）</li>
 *   <li>{@code o200k_base} — GPT-4o / o1 系列</li>
 *   <li>{@code heuristic} — 启发式估算兜底</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-07
 */
@AutoConfiguration(before = KnowledgeAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TokenCounterAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TokenCounterAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter(KnowledgeBaseProperties props) {
        String encoding = props.tokenizer().encoding();

        if ("heuristic".equalsIgnoreCase(encoding)) {
            log.info("TokenCounter 使用启发式估算模式");
            return new TokenCounter.Heuristic();
        }

        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            EncodingType encodingType = switch (encoding.toLowerCase()) {
                case "o200k_base" -> EncodingType.O200K_BASE;
                case "cl100k_base" -> EncodingType.CL100K_BASE;
                default -> {
                    log.warn("未知编码 '{}'，回退到 cl100k_base", encoding);
                    yield EncodingType.CL100K_BASE;
                }
            };
            var enc = registry.getEncoding(encodingType);
            log.info("TokenCounter 使用 jtokkit 精确计数: encoding={}", encodingType);
            return new TokenCounter.Jtokkit(enc);
        } catch (Exception e) {
            log.warn("jtokkit 初始化失败，回退到启发式估算: {}", e.getMessage());
            return new TokenCounter.Heuristic();
        }
    }
}
