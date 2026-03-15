package com.lifepilot.interaction;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户确认服务空实现 — 默认自动确认。
 *
 * <p>作为 {@code @ConditionalOnMissingBean} 的兜底 Bean，
 * 当交互层提供具体实现（如 {@code CliUserConfirmationService}）时自动被覆盖。
 * 所有确认请求自动通过。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class NoOpUserConfirmationService implements UserConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(NoOpUserConfirmationService.class);

    @Override
    public boolean requestConfirmation(ToolContract tool, ToolInput input, String message,
                                       @Nullable String streamId) {
        log.warn("用户确认服务未实现，自动确认: toolId={}, risk={}",
                tool.id(), tool.riskLevel());
        return true;
    }
}
