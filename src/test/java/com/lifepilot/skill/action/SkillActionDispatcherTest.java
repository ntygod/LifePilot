package com.lifepilot.skill.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link SkillActionDispatcher} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class SkillActionDispatcherTest {

    @Mock
    private HttpActionExecutor httpExecutor;
    @Mock
    private ShellActionExecutor shellExecutor;
    @Mock
    private ChainActionExecutor chainExecutor;
    @Mock
    private TemplateActionExecutor templateExecutor;
    @Mock
    private VariableResolver variableResolver;

    private SkillActionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new SkillActionDispatcher(
                httpExecutor, shellExecutor, chainExecutor, templateExecutor, variableResolver);
    }

    @Test
    void HttpAction_路由到httpExecutor() {
        var action = new SkillAction.HttpAction("GET", "https://api.example.com", Map.of(), Map.of(), null);
        var params = Map.<String, Object>of("key", "value");
        var expected = ActionResult.success("ok");
        when(httpExecutor.execute(action, params)).thenReturn(expected);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertSame(expected, result);
        verify(httpExecutor).execute(action, params);
        verifyNoInteractions(shellExecutor, chainExecutor, templateExecutor);
    }

    @Test
    void ShellAction_无注入_路由到shellExecutor() {
        var action = new SkillAction.ShellAction("echo hello", 10);
        var params = Map.<String, Object>of("name", "world");
        var expected = ActionResult.success("hello world");
        when(variableResolver.containsShellInjection("world")).thenReturn(false);
        when(shellExecutor.execute(action, params)).thenReturn(expected);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertSame(expected, result);
        verify(variableResolver).containsShellInjection("world");
        verify(shellExecutor).execute(action, params);
    }

    @Test
    void ShellAction_检测到注入_返回错误不调用executor() {
        var action = new SkillAction.ShellAction("echo ${params.input}", 10);
        var params = Map.<String, Object>of("input", "hello; rm -rf /");
        when(variableResolver.containsShellInjection("hello; rm -rf /")).thenReturn(true);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertFalse(result.success());
        assertTrue(result.output().contains("Shell 注入检测"));
        verifyNoInteractions(shellExecutor);
    }

    @Test
    void ShellAction_多个参数_任一注入即拦截() {
        var action = new SkillAction.ShellAction("cmd", 10);
        var params = Map.<String, Object>of("safe", "ok", "danger", "val|bad");
        // Map 迭代顺序不确定，使用 lenient 避免 UnnecessaryStubbingException
        lenient().when(variableResolver.containsShellInjection("ok")).thenReturn(false);
        when(variableResolver.containsShellInjection("val|bad")).thenReturn(true);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertFalse(result.success());
        verifyNoInteractions(shellExecutor);
    }

    @Test
    void ChainAction_路由到chainExecutor() {
        var steps = List.of(new SkillAction.ChainStep("skill-a", Map.of(), "out"));
        var action = new SkillAction.ChainAction(steps);
        var params = Map.<String, Object>of("key", "value");
        var expected = ActionResult.success("chain done");
        when(chainExecutor.execute(action, params)).thenReturn(expected);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertSame(expected, result);
        verify(chainExecutor).execute(action, params);
        verifyNoInteractions(httpExecutor, shellExecutor, templateExecutor);
    }

    @Test
    void TemplateAction_路由到templateExecutor_传递previousResult() {
        var action = new SkillAction.TemplateAction("结果: ${result.value}");
        var params = Map.<String, Object>of();
        var previousResult = Map.<String, Object>of("value", "42");
        var expected = ActionResult.success("结果: 42");
        when(templateExecutor.execute(action, previousResult)).thenReturn(expected);

        ActionResult result = dispatcher.dispatch(action, params, previousResult);

        assertSame(expected, result);
        verify(templateExecutor).execute(action, previousResult);
        verifyNoInteractions(httpExecutor, shellExecutor, chainExecutor);
    }

    @Test
    void ShellAction_空参数_不触发注入检查_直接执行() {
        var action = new SkillAction.ShellAction("echo hello", 10);
        var params = Map.<String, Object>of();
        var expected = ActionResult.success("hello");
        when(shellExecutor.execute(action, params)).thenReturn(expected);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertSame(expected, result);
        verify(shellExecutor).execute(action, params);
        verifyNoInteractions(variableResolver);
    }

    @Test
    void ShellAction_参数值为null_跳过注入检查() {
        var action = new SkillAction.ShellAction("echo", 10);
        // HashMap 允许 null 值
        var params = new java.util.HashMap<String, Object>();
        params.put("key", null);
        var expected = ActionResult.success("done");
        when(shellExecutor.execute(eq(action), any())).thenReturn(expected);

        ActionResult result = dispatcher.dispatch(action, params, null);

        assertTrue(result.success());
        verifyNoInteractions(variableResolver);
    }
}
