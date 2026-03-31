package com.lifepilot.meta.infra.code;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.code.kernel.KernelExecutionResult;
import com.lifepilot.meta.infra.code.kernel.PersistentKernelManager;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.model.*;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultMeta;
import com.lifepilot.tool.model.ToolResultStatus;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 代码执行工具 — 通过 {@link SandboxSessionManager} 获取会话级沙箱实例执行代码。
 *
 * <p>支持 Python / JavaScript / Shell 三种语言，默认语言从
 * {@link MetaProperties.Infra.CodeExecute#getDefaultLanguage()} 读取。
 * 集成 {@link CodeValidator} 预检和 {@link SandboxRepository} 审计持久化。</p>
 *
 * <p>当请求中包含 {@code kernelId} 参数且 {@link PersistentKernelManager} 可用时，
 * 路由到持久内核执行（跨调用保持变量状态）；否则走原有沙箱路径。</p>
 *
 * <p>执行流程：参数提取 → 内核路由判断 → 会话沙箱获取 → 预检（如有 CodeValidator）→ 执行 → 审计持久化 → 返回结果。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class CodeExecuteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(CodeExecuteToolExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final MetaProperties.Infra.CodeExecute codeConfig;
    @Nullable
    private final SandboxSessionManager sessionManager;
    @Nullable
    private final CodeValidator validator;
    @Nullable
    private final SandboxRepository repository;
    @Nullable
    private final PersistentKernelManager kernelManager;

    public CodeExecuteToolExecutor(MetaProperties properties,
                                   @Nullable SandboxSessionManager sessionManager,
                                   @Nullable CodeValidator validator,
                                   @Nullable SandboxRepository repository) {
        this(properties, sessionManager, validator, repository, null);
    }

    /**
     * 创建代码执行工具（支持持久内核路由）。
     *
     * @param properties       配置属性
     * @param sessionManager   沙箱会话管理器（可选）
     * @param validator        代码预检器（可选）
     * @param repository       审计仓库（可选）
     * @param kernelManager    持久内核管理器（可选），不为 null 时支持 kernelId 路由
     */
    public CodeExecuteToolExecutor(MetaProperties properties,
                                   @Nullable SandboxSessionManager sessionManager,
                                   @Nullable CodeValidator validator,
                                   @Nullable SandboxRepository repository,
                                   @Nullable PersistentKernelManager kernelManager) {
        this.codeConfig = properties.getInfra().getCodeExecute();
        this.sessionManager = sessionManager;
        this.validator = validator;
        this.repository = repository;
        this.kernelManager = kernelManager;
    }

    /**
     * 执行代码。
     *
     * <p>执行流程：参数提取 → 会话沙箱获取 → 预检（如有 CodeValidator）→ 执行 → 审计持久化 → 返回结果。</p>
     *
     * @param input     工具输入，必需参数 code，可选 language 和 timeoutSeconds
     * @param sessionId 会话 ID，用于获取会话级沙箱实例和审计关联
     * @return 包含 stdout、stderr、exitCode 的结构化结果
     */
    public ToolResult execute(ToolInput input, String sessionId) {
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

        // 提取可选参数 timeoutSeconds
        int timeoutSeconds = input.getOptionalParam("timeoutSeconds", Number.class)
                .map(Number::intValue)
                .orElse(DEFAULT_TIMEOUT_SECONDS);

        // ── 持久内核路由 ──
        // 当 kernelId 参数存在且 PersistentKernelManager 可用时，路由到持久内核
        Optional<String> kernelIdOpt = input.getOptionalParam("kernelId", String.class);
        if (kernelIdOpt.isPresent() && kernelManager != null) {
            // 持久内核同样需要安全预检
            if (validator != null) {
                var languageOpt = Language.fromString(languageStr);
                if (languageOpt.isPresent()) {
                    ValidationResult validation = validator.validate(languageOpt.get(), code);
                    if (!validation.passed()) {
                        String violationMsg = formatViolations(validation.violations());
                        log.warn("持久内核代码预检未通过: kernelId={}, violations={}", kernelIdOpt.get(), violationMsg);
                        return ToolResult.error("代码预检未通过: " + violationMsg);
                    }
                }
            }
            return executeViaKernel(kernelIdOpt.get(), languageStr, code, timeoutSeconds);
        }

        // ── 沙箱路径（原有逻辑） ──
        // 检查 SessionManager 可用性
        if (sessionManager == null) {
            return ToolResult.error("沙箱运行时不可用，请检查沙箱配置");
        }

        // 解析语言枚举
        var languageOpt = Language.fromString(languageStr);
        if (languageOpt.isEmpty()) {
            return ToolResult.error("不支持的语言: " + languageStr + "，支持 python/javascript/shell");
        }
        Language language = languageOpt.get();
        String codeHash = sha256(code);

        // CodeValidator 预检（如果可用）
        if (validator != null) {
            ValidationResult validation = validator.validate(language, code);
            if (!validation.passed()) {
                String violationMsg = formatViolations(validation.violations());
                persistRecord(sessionId, language, codeHash, code.length(),
                        "unknown", false, validation.violations().size(),
                        null, null, null, null, "REJECTED", violationMsg);
                return ToolResult.error("代码预检未通过: " + violationMsg);
            }
        }

        // 获取会话级沙箱实例
        SandboxBooter booter;
        try {
            booter = sessionManager.getOrCreate(sessionId);
        } catch (IllegalStateException e) {
            log.warn("获取沙箱实例失败: sessionId={}, error={}", sessionId, e.getMessage());
            return ToolResult.error("沙箱实例获取失败: " + e.getMessage());
        }

        // 构建执行请求并执行
        var request = new ExecutionRequest(
                language,
                code,
                timeoutSeconds,
                booter.workingDirectory()
        );

        try {
            var result = booter.execute(request);

            var data = new LinkedHashMap<String, Object>();
            data.put("exitCode", result.exitCode());
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("durationMs", result.durationMs());
            data.put("state", result.state().name());

            // 审计持久化
            persistRecord(sessionId, language, codeHash, code.length(),
                    booter.type(), true, 0,
                    result.exitCode(),
                    result.stdout().getBytes(StandardCharsets.UTF_8).length,
                    result.stderr().getBytes(StandardCharsets.UTF_8).length,
                    result.durationMs(), result.state().name(), null);

            log.debug("代码执行完成: sessionId={}, language={}, exitCode={}, durationMs={}",
                    sessionId, languageStr, result.exitCode(), result.durationMs());

            // exitCode 非零视为执行失败
            if (result.exitCode() != 0) {
                return new ToolResult(ToolResultStatus.ERROR, Map.copyOf(data),
                        "代码执行失败: exitCode=" + result.exitCode(), ToolResultMeta.empty());
            }
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("代码执行失败: sessionId={}, language={}, error={}", sessionId, languageStr, e.getMessage(), e);
            persistRecord(sessionId, language, codeHash, code.length(),
                    "unknown", true, 0,
                    null, null, null, null, "FAILED", e.getMessage());
            return ToolResult.error("代码执行失败: " + e.getMessage());
        }
    }

    /**
     * 兼容无 sessionId 的调用（向后兼容）。
     *
     * @param input 工具输入
     * @return 执行结果
     */
    public ToolResult execute(ToolInput input) {
        return execute(input, "default");
    }

    // ==================== 持久内核执行路径 ====================

    /**
     * 通过持久内核执行代码 — 跨调用保持变量状态。
     *
     * @param kernelId       内核标识
     * @param language       语言
     * @param code           代码
     * @param timeoutSeconds 超时时间（秒）
     * @return 执行结果
     */
    private ToolResult executeViaKernel(String kernelId, String language, String code, int timeoutSeconds) {
        String codeHash = sha256(code);
        try {
            var kernel = kernelManager.getOrCreate(kernelId, language);
            var result = kernel.execute(code, timeoutSeconds);

            var data = new LinkedHashMap<String, Object>();
            data.put("kernelId", kernelId);
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("durationMs", result.durationMs());
            data.put("persistent", true);

            if (result.error() != null) {
                data.put("error", result.error());
                // 审计持久化（内核执行失败）
                var languageOpt = Language.fromString(language);
                if (languageOpt.isPresent()) {
                    persistRecord(kernelId, languageOpt.get(), codeHash, code.length(),
                            "kernel", true, 0, 1, null, null,
                            (long) result.durationMs(), "FAILED", result.error());
                }
                return new ToolResult(ToolResultStatus.ERROR, Map.copyOf(data),
                        "内核执行失败: " + result.error(), ToolResultMeta.empty());
            }

            // 审计持久化（内核执行成功）
            var languageOpt = Language.fromString(language);
            if (languageOpt.isPresent()) {
                persistRecord(kernelId, languageOpt.get(), codeHash, code.length(),
                        "kernel", true, 0, 0,
                        result.stdout().getBytes(StandardCharsets.UTF_8).length,
                        result.stderr().getBytes(StandardCharsets.UTF_8).length,
                        (long) result.durationMs(), "COMPLETED", null);
            }

            log.debug("内核代码执行完成: kernelId={}, language={}, durationMs={}",
                    kernelId, language, result.durationMs());
            return ToolResult.success(Map.copyOf(data));
        } catch (IllegalStateException e) {
            log.warn("内核执行失败: kernelId={}, error={}", kernelId, e.getMessage());
            return ToolResult.error("内核执行失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("内核执行异常: kernelId={}, language={}, error={}", kernelId, language, e.getMessage(), e);
            return ToolResult.error("内核执行异常: " + e.getMessage());
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 持久化执行审计记录（降级：写入失败仅记录日志，不影响主流程）。
     */
    private void persistRecord(@Nullable String sessionId, Language language, String codeHash,
                               int codeLength, String booterType, boolean validationPassed,
                               int violationCount, @Nullable Integer exitCode,
                               @Nullable Integer stdoutLength, @Nullable Integer stderrLength,
                               @Nullable Long durationMs, String state,
                               @Nullable String errorMessage) {
        if (repository == null) {
            return;
        }
        try {
            Instant now = Instant.now();
            var record = new ExecutionRecord(
                    UUID.randomUUID().toString(),
                    sessionId != null ? sessionId : "no-session",
                    language, codeHash, codeLength, booterType,
                    validationPassed, violationCount, exitCode,
                    stdoutLength, stderrLength, durationMs,
                    state, errorMessage, now, now);
            repository.insert(record);
            log.debug("审计记录已持久化: id={}, sessionId={}, state={}", record.id(), sessionId, state);
        } catch (Exception e) {
            log.error("审计记录写入失败（降级跳过）: state={}, error={}", state, e.getMessage());
        }
    }

    /**
     * 格式化违规项列表为可读字符串。
     */
    private static String formatViolations(List<Violation> violations) {
        return violations.stream()
                .map(v -> "[%s] 第%d行: %s".formatted(v.severity(), v.lineNumber(), v.description()))
                .collect(Collectors.joining("; "));
    }

    /**
     * 计算字符串的 SHA-256 哈希。
     */
    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
