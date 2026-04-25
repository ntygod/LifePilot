package com.lifepilot.meta.infra.browser;

import com.lifepilot.agent.suspend.model.SuspendSignal;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器人机接管请求工具 — 让 Agent 主动挂起等待用户在浏览器内完成人工操作。
 *
 * <p>适用于验证码、登录墙、扫码登录、人机验证、账号保护等场景。
 * 通过工具输出 {@code _suspend=true} + {@code _suspendReason.type="BrowserTakeover"}
 * 让 {@code ToolExecutionCoordinator.parseSuspendReasonFromOutput} 转换为
 * {@link com.lifepilot.agent.model.SuspendReason.BrowserTakeover}，
 * 进入标准挂起-恢复流程。</p>
 *
 * <p>用户在前端弹窗完成操作并点击继续后，Agent 会收到
 * {@link com.lifepilot.agent.suspend.model.ResumePayload.BrowserTakeoverCompleted}
 * 并从当前进度继续执行。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class BrowserHumanTakeoverExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserHumanTakeoverExecutor.class);

    private final MetaProperties properties;

    public BrowserHumanTakeoverExecutor(MetaProperties properties) {
        this.properties = properties;
    }

    /**
     * 触发 Agent 挂起等待人工接管。
     *
     * @param input 工具输入，必需 {@code reason}（展示给用户的接管原因），可选 {@code sessionId}（默认 default）
     * @return 成功结果中携带 {@code _suspend=true} + 结构化 {@code _suspendReason}，由协调器转换为运行时挂起
     */
    public ToolResult execute(ToolInput input) {
        String reason;
        try {
            reason = input.getParam("reason", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: reason（向用户说明为什么需要接管，如 '需要扫码登录'）");
        }
        if (reason.isBlank()) {
            return ToolResult.error("参数 reason 不能为空");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
        Instant requestedAt = Instant.now();
        // 从 MetaProperties 读取接管超时秒数，供前端弹窗统一使用，避免双端硬编码不同步。
        int timeoutSeconds = properties.getInfra().getBrowser().getTakeover().getTimeoutSeconds();

        var suspendReasonPayload = new LinkedHashMap<String, Object>();
        suspendReasonPayload.put(SuspendSignal.FIELD_TYPE, "BrowserTakeover");
        suspendReasonPayload.put("sessionId", sessionId);
        suspendReasonPayload.put("reason", reason);
        suspendReasonPayload.put("requestedAt", requestedAt.toString());
        suspendReasonPayload.put("timeoutSeconds", timeoutSeconds);

        var data = new LinkedHashMap<String, Object>();
        data.put(SuspendSignal.FIELD_SUSPEND, true);
        data.put(SuspendSignal.FIELD_REASON, Map.copyOf(suspendReasonPayload));
        data.put("message", "等待用户在浏览器中完成人工接管：" + reason);
        data.put("sessionId", sessionId);

        log.info("浏览器请求人机接管: sessionId={}, reason={}, timeoutSeconds={}",
                sessionId, reason, timeoutSeconds);
        return ToolResult.success(Map.copyOf(data));
    }
}
