package com.lifepilot.meta.infra.code;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.sandbox.booter.ProcessBooter;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;
import com.lifepilot.sandbox.model.ExecutionState;
import com.lifepilot.sandbox.model.Language;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CodeExecuteToolExecutor 单元测试。
 *
 * <p>mock SandboxSessionManager，其 getOrCreate 返回 mock 的 ProcessBooter。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class CodeExecuteToolExecutorTest {

    private CodeExecuteToolExecutor executor;
    private SandboxSessionManager sessionManager;
    private SandboxBooter sandboxBooter;
    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        sessionManager = mock(SandboxSessionManager.class);
        // SandboxBooter 是 sealed interface，mock 其 permits 的 ProcessBooter
        sandboxBooter = mock(ProcessBooter.class);
        when(sessionManager.getOrCreate(anyString())).thenReturn(sandboxBooter);
        when(sandboxBooter.workingDirectory()).thenReturn(Path.of(System.getProperty("java.io.tmpdir")));
        executor = new CodeExecuteToolExecutor(properties, sessionManager, null, null);
    }

    // ─────────────────────────────────────────────
    //  沙箱不可用场景
    // ─────────────────────────────────────────────

    @Test
    void sessionManager为null时返回错误() {
        var nullExecutor = new CodeExecuteToolExecutor(properties, null, null, null);
        ToolInput input = buildInput(Map.of("code", "print('hello')"));

        ToolResult result = nullExecutor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("沙箱运行时不可用");
    }

    @Test
    void sessionManager获取实例失败时返回错误() {
        when(sessionManager.getOrCreate(anyString()))
                .thenThrow(new IllegalStateException("活跃会话数已达上限: max=5"));
        ToolInput input = buildInput(Map.of("code", "print('hello')"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("沙箱实例获取失败");
    }

    // ─────────────────────────────────────────────
    //  成功执行场景
    // ─────────────────────────────────────────────

    @Test
    void 成功执行Python代码() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("hello\n", "", 0, 150, ExecutionState.COMPLETED));

        ToolInput input = buildInput(Map.of("code", "print('hello')"));

        ToolResult result = executor.execute(input, "test-session");

        assertThat(result.ok()).isTrue();
        assertThat((int) result.data().get("exitCode")).isZero();
        assertThat((String) result.data().get("stdout")).isEqualTo("hello\n");
        assertThat((String) result.data().get("stderr")).isEmpty();
        assertThat((long) result.data().get("durationMs")).isEqualTo(150);
        assertThat((String) result.data().get("state")).isEqualTo("COMPLETED");

        // 验证传递给 SandboxBooter 的请求参数
        var captor = org.mockito.ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(sandboxBooter).execute(captor.capture());
        ExecutionRequest captured = captor.getValue();
        assertThat(captured.language()).isEqualTo(Language.PYTHON);
        assertThat(captured.code()).isEqualTo("print('hello')");
        assertThat(captured.timeoutSeconds()).isEqualTo(30);

        // 验证 sessionManager 使用了正确的 sessionId
        verify(sessionManager).getOrCreate("test-session");
    }

    @Test
    void 无sessionId时使用默认值() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("hello\n", "", 0, 150, ExecutionState.COMPLETED));

        ToolInput input = buildInput(Map.of("code", "print('hello')"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        verify(sessionManager).getOrCreate("default");
    }

    @Test
    void 指定JavaScript语言执行() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("42\n", "", 0, 80, ExecutionState.COMPLETED));

        ToolInput input = buildInput(Map.of(
                "code", "console.log(42)",
                "language", "javascript"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(sandboxBooter).execute(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo(Language.JAVASCRIPT);
    }

    @Test
    void 指定Shell语言执行() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("hello\n", "", 0, 50, ExecutionState.COMPLETED));

        ToolInput input = buildInput(Map.of(
                "code", "echo hello",
                "language", "shell"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(sandboxBooter).execute(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo(Language.SHELL);
    }

    @Test
    void 自定义超时时间() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("", "", 0, 10, ExecutionState.COMPLETED));

        ToolInput input = buildInput(Map.of(
                "code", "import time; time.sleep(1)",
                "timeoutSeconds", 60
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(sandboxBooter).execute(captor.capture());
        assertThat(captor.getValue().timeoutSeconds()).isEqualTo(60);
    }

    // ─────────────────────────────────────────────
    //  默认语言配置
    // ─────────────────────────────────────────────

    @Test
    void 默认语言从配置读取() {
        properties.getInfra().getCodeExecute().setDefaultLanguage("javascript");
        executor = new CodeExecuteToolExecutor(properties, sessionManager, null, null);

        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("", "", 0, 10, ExecutionState.COMPLETED));

        // 不指定 language，应使用配置的默认值 javascript
        ToolInput input = buildInput(Map.of("code", "console.log('test')"));

        executor.execute(input);

        var captor = org.mockito.ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(sandboxBooter).execute(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo(Language.JAVASCRIPT);
    }

    // ─────────────────────────────────────────────
    //  错误场景
    // ─────────────────────────────────────────────

    @Test
    void 缺少code参数返回错误() {
        ToolInput input = buildInput(Map.of());

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("code");
    }

    @Test
    void 不支持的语言返回错误() {
        ToolInput input = buildInput(Map.of(
                "code", "puts 'hello'",
                "language", "ruby"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不支持的语言");
        assertThat(result.error()).contains("ruby");
    }

    @Test
    void sandboxBooter执行抛异常时返回错误() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenThrow(new RuntimeException("沙箱进程崩溃"));

        ToolInput input = buildInput(Map.of("code", "print('hello')"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("代码执行失败");
        assertThat(result.error()).contains("沙箱进程崩溃");
    }

    @Test
    void 执行返回非零退出码() {
        when(sandboxBooter.execute(any(ExecutionRequest.class)))
                .thenReturn(new ExecutionResult("", "SyntaxError: invalid syntax\n", 1, 20, ExecutionState.FAILED));

        ToolInput input = buildInput(Map.of("code", "print("));

        ToolResult result = executor.execute(input);

        // 非零退出码视为执行失败，但 data 中仍包含 exitCode 和 stderr
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("代码执行失败");
        assertThat((int) result.data().get("exitCode")).isEqualTo(1);
        assertThat((String) result.data().get("stderr")).contains("SyntaxError");
        assertThat((String) result.data().get("state")).isEqualTo("FAILED");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("code.execute", params, JsonSchema.empty(), null, null);
    }
}
