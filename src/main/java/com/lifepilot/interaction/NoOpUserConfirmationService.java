package com.lifepilot.interaction;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户确认服务空实现 — 默认自动确认。
 *
 * <p>在交互层（CLI/Web）实现之前使用此占位实现。
 * 所有确认请求自动通过。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class NoOpUserConfirmationService implements UserConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(NoOpUserConfirmationService.class);

    @Override
    public boolean requestConfirmation(ToolContract tool, ToolInput input, String message) {
        log.warn("用户确认服务未实现，自动确认: toolId={}, risk={}",
                tool.id(), tool.riskLevel());
        return true;
    }
}
