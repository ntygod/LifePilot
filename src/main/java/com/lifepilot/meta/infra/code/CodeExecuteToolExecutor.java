package com.lifepilot.meta.infra.code;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.Language;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultMeta;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码执行工具 — 桥接 {@link SandboxBooter} 在沙箱中执行代码。
 *
 * <p>支持 Python / JavaScript / Shell 三种语言，默认语言从
 * {@link MetaProperties.Infra.CodeExecute#getDefaultLanguage()} 读取。
 * {@code SandboxBooter} 不可用时返回错误。RiskLevel HIGH。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class CodeExecuteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(CodeExecuteToolExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final MetaProperties.Infra.CodeExecute codeConfig;
    @Nullable
    private final SandboxBooter sandboxBooter;

    public CodeExecuteToolExecutor(MetaProperties properties, @Nullable SandboxBooter sandboxBooter) {
        this.codeConfig = properties.getInfra().getCodeExecute();
        this.sandboxBooter = sandboxBooter;
    }

    /**
     * 执行代码。
     *
     * @param input 工具输入，必需参数 code，可选 language 和 timeoutSeconds
     * @return 包含 stdout、stderr、exitCode 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        // 检查沙箱可用性
        if (sandboxBooter == null || !sandboxBooter.available()) {
            return ToolResult.error("沙箱运行时不可用，请检查沙箱配置");
        }

        // 提取必需参数 code
        String code;
        try {
            code = input.getParam("code", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: code");
        }

        // 提取可选参数 language，默认从配置读取
        String languageStr = input.getOptionalParam("language", String.class)
                .orElse(codeConfig.getDefaultLanguage());

        // 解析语言枚举
        var languageOpt = Language.fromString(languageStr);
        if (languageOpt.isEmpty()) {
            return ToolResult.error("不支持的语言: " + languageStr + "，支持 python/javascript/shell");
        }

        // 提取可选参数 timeoutSeconds
        int timeoutSeconds = input.getOptionalParam("timeoutSeconds", Number.class)
                .map(Number::intValue)
                .orElse(DEFAULT_TIMEOUT_SECONDS);

        // 构建执行请求并执行
        var request = new ExecutionRequest(
                languageOpt.get(),
                code,
                timeoutSeconds,
                Path.of(System.getProperty("user.home"))
        );

        try {
            var result = sandboxBooter.execute(request);

            var data = new LinkedHashMap<String, Object>();
            data.put("exitCode", result.exitCode());
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("durationMs", result.durationMs());
            data.put("state", result.state().name());

            log.debug("代码执行完成: language={}, exitCode={}, durationMs={}",
                    languageStr, result.exitCode(), result.durationMs());

            // exitCode 非零视为执行失败
            if (result.exitCode() != 0) {
                return new ToolResult(false, Map.copyOf(data),
                        "代码执行失败: exitCode=" + result.exitCode(), ToolResultMeta.empty());
            }
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("代码执行失败: language={}, error={}", languageStr, e.getMessage(), e);
            return ToolResult.error("代码执行失败: " + e.getMessage());
        }
    }
}
