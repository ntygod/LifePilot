package com.lifepilot.interaction;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;

/**
 * 用户确认服务接口。
 *
 * <p>HIGH/CRITICAL 风险工具需要用户确认。
 * 具体实现由交互层（CLI/Web）提供。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public interface UserConfirmationService {

    /**
     * 请求用户确认。
     *
     * @param tool 工具契约
     * @param input 工具输入
     * @param message 确认消息
     * @return 用户是否确认
     */
    boolean requestConfirmation(ToolContract tool, ToolInput input, String message);
}
