package com.lifepilot.sandbox.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.ExecutionRecord;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;
import com.lifepilot.sandbox.model.Language;
import com.lifepilot.sandbox.model.ValidationResult;
import com.lifepilot.sandbox.model.Violation;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.RiskLevel;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;

/**
 * 代码执行工具 — 作为 BuiltinTool 注册到 DynamicToolRegistry。
 *
 * <p>编排预检、会话管理、执行和审计的完整流程：
 * 语言校验 → CodeValidator 预检 → SessionManager 获取沙箱 → 执行 → 审计持久化 → 返回结果。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class CodeExecuteTool {

    private static final Logger log = LoggerFactory.getLogger(CodeExecuteTool.class);

    private final CodeValidator validator;
    private final SandboxSessionManager sessionManager;
    private final SandboxRepository repository;
    private final SandboxConfigProperties config;

    /**
     * 创建代码执行工具。
     *
     * @param validator      代码预检器
     * @param sessionManager 会话管理器
     * @param repository     审计持久化仓储
     * @param config         沙箱配置
     */
    public CodeExecuteTool(CodeValidator validator,
                           SandboxSessionManager sessionManager,
                           SandboxRepository repository,
                           SandboxConfigProperties config) {
        this.validator = validator;
        this.sessionManager = sessionManager;
        this.repository = repository;
        this.config = config;
    }

    /**
     * 构建 BuiltinTool 实例用于注册。
     *
     * @return BuiltinTool 实例，id="code.execute", riskLevel=CRITICAL
     */
    public BuiltinTool buildTool() {
        return BuiltinTool.builder()
                .id("code.execute")
                .name("代码执行")
                .description("在安全沙箱中执行代码（Python/JavaScript/Shell）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("language", "code", "sessionId"),
                        "properties", Map.of(
                                "language", Map.of("type", "string", "enum", List.of("python", "javascript", "shell")),
                                "code", Map.of("type", "string"),
                                "sessionId", Map.of("type", "string")
                        )
                )))
                .riskLevel(RiskLevel.CRITICAL)
                .idempotent(false)
                .executor(this::execute)
                .build();
    }

    /**
     * 工具执行逻辑（作为 ToolExecutor 注入 BuiltinTool）。
     *
     * <p>执行流程：语言校验 → 预检 → 获取沙箱 → 执行 → 审计 → 返回结果。</p>
     *
     * @param input 工具输入，包含 language、code、sessionId 参数
     * @return 执行结果
     */
    public ToolResult execute(ToolInput input) {
        String languageStr = input.getParam("language", String.class);
        String code = input.getParam("code", String.class);
        String sessionId = input.getParam("sessionId", String.class);
        String codeHash = sha256(code);

        try {
            // 1. 校验语言是否在支持列表中
            if (!config.getSupportedLanguages().contains(languageStr.toLowerCase())) {
                return ToolResult.error("不支持的语言: " + languageStr);
            }

            // 2. 解析 Language 枚举
            Language language = Language.fromString(languageStr).orElse(null);
            if (language == null) {
                return ToolResult.error("不支持的语言: " + languageStr);
            }

            // 3. CodeValidator 预检
            ValidationResult validation = validator.validate(language, code);
            if (!validation.passed()) {
                // 持久化 REJECTED 审计记录
                persistRecord(sessionId, language, codeHash, code.length(),
                        "unknown", false, validation.violations().size(),
                        null, null, null, null, "REJECTED",
                        formatViolations(validation.violations()));
                return ToolResult.error("代码预检未通过: " + formatViolations(validation.violations()));
            }

            // 4. 获取沙箱实例
            SandboxBooter booter;
            try {
                booter = sessionManager.getOrCreate(sessionId);
            } catch (IllegalStateException e) {
                return ToolResult.error("活跃会话数已达上限");
            }

            // 5. 构建执行请求并执行
            var request = new ExecutionRequest(
                    language, code,
                    config.getExecutionTimeoutSeconds(),
                    booter.workingDirectory());
            ExecutionResult result = booter.execute(request);

            // 6. 持久化审计记录
            persistRecord(sessionId, language, codeHash, code.length(),
                    booter.type(), true, 0,
                    result.exitCode(),
                    result.stdout().getBytes(StandardCharsets.UTF_8).length,
                    result.stderr().getBytes(StandardCharsets.UTF_8).length,
                    result.durationMs(), result.state().name(), null);

            // 7. 返回执行结果
            return ToolResult.success(Map.of(
                    "stdout", result.stdout(),
                    "stderr", result.stderr(),
                    "exitCode", result.exitCode(),
                    "state", result.state().name(),
                    "durationMs", result.durationMs()
            ));

        } catch (Exception e) {
            // 未预期异常：持久化 FAILED 记录
            log.error("代码执行未预期异常: sessionId={}, error={}", sessionId, e.getMessage(), e);
            Language language = Language.fromString(languageStr).orElse(null);
            if (language != null) {
                persistRecord(sessionId, language, codeHash, code.length(),
                        "unknown", true, 0,
                        null, null, null, null, "FAILED", e.getMessage());
            }
            return ToolResult.error(e.getMessage());
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 持久化执行审计记录（降级：写入失败仅记录日志，不影响主流程）。
     */
    private void persistRecord(String sessionId, Language language, String codeHash,
                               int codeLength, String booterType, boolean validationPassed,
                               int violationCount, Integer exitCode, Integer stdoutLength,
                               Integer stderrLength, Long durationMs, String state,
                               String errorMessage) {
        try {
            Instant now = Instant.now();
            var record = new ExecutionRecord(
                    UUID.randomUUID().toString(),
                    sessionId, language, codeHash, codeLength, booterType,
                    validationPassed, violationCount, exitCode,
                    stdoutLength, stderrLength, durationMs,
                    state, errorMessage, now, now);
            repository.insert(record);
            log.debug("审计记录已持久化: id={}, state={}", record.id(), state);
        } catch (Exception e) {
            log.error("审计记录写入失败（降级跳过）: sessionId={}, state={}, error={}",
                    sessionId, state, e.getMessage());
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
     *
     * @param input 输入字符串
     * @return 十六进制哈希字符串
     */
    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有 JVM 中都可用，不应发生
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
