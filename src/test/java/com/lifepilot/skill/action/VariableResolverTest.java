package com.lifepilot.skill.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VariableResolver} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class VariableResolverTest {

    private VariableResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VariableResolver();
    }

    // ─────────────────────────────────────────────
    //  resolve — params 占位符
    // ─────────────────────────────────────────────

    @Test
    void resolve_替换params占位符为参数值() {
        String result = resolver.resolve(
                "你好 ${params.name}",
                Map.of("name", "张三"),
                null);
        assertThat(result).isEqualTo("你好 张三");
    }

    @Test
    void resolve_params键不存在时保留占位符() {
        String result = resolver.resolve(
                "你好 ${params.unknown}",
                Map.of("name", "张三"),
                null);
        assertThat(result).isEqualTo("你好 ${params.unknown}");
    }

    // ─────────────────────────────────────────────
    //  resolve — env 占位符
    // ─────────────────────────────────────────────

    @Test
    void resolve_替换env占位符为环境变量() {
        // PATH 在所有操作系统上都存在
        String result = resolver.resolve(
                "路径: ${env.PATH}",
                Map.of(),
                null);
        String expectedPath = System.getenv("PATH");
        assertThat(result).isEqualTo("路径: " + expectedPath);
    }

    @Test
    void resolve_环境变量不存在时使用空字符串() {
        String result = resolver.resolve(
                "值: [${env.LIFEPILOT_NONEXISTENT_VAR_XYZ}]",
                Map.of(),
                null);
        assertThat(result).isEqualTo("值: []");
    }

    // ─────────────────────────────────────────────
    //  resolve — result 占位符
    // ─────────────────────────────────────────────

    @Test
    void resolve_替换result占位符为前序结果() {
        String result = resolver.resolve(
                "输出: ${result.step1}",
                Map.of(),
                Map.of("step1", "成功"));
        assertThat(result).isEqualTo("输出: 成功");
    }

    @Test
    void resolve_result为null时保留占位符() {
        String result = resolver.resolve(
                "输出: ${result.step1}",
                Map.of(),
                null);
        assertThat(result).isEqualTo("输出: ${result.step1}");
    }

    // ─────────────────────────────────────────────
    //  resolve — 边界场景
    // ─────────────────────────────────────────────

    @Test
    void resolve_未知作用域占位符保持原样() {
        // 非 params/env/result 的占位符不匹配正则，保持原样
        String result = resolver.resolve(
                "值: ${unknown.key}",
                Map.of(),
                null);
        assertThat(result).isEqualTo("值: ${unknown.key}");
    }

    @Test
    void resolve_多个占位符同时替换() {
        String result = resolver.resolve(
                "${params.greeting} ${params.name}, 路径=${env.PATH}",
                Map.of("greeting", "你好", "name", "李四"),
                null);
        String expectedPath = System.getenv("PATH");
        assertThat(result).isEqualTo("你好 李四, 路径=" + expectedPath);
    }

    @Test
    void resolve_无占位符的模板原样返回() {
        String result = resolver.resolve(
                "这是一段普通文本",
                Map.of("key", "value"),
                null);
        assertThat(result).isEqualTo("这是一段普通文本");
    }

    // ─────────────────────────────────────────────
    //  containsShellInjection
    // ─────────────────────────────────────────────

    @Test
    void containsShellInjection_检测分号() {
        assertThat(resolver.containsShellInjection("echo hello; rm -rf /")).isTrue();
    }

    @Test
    void containsShellInjection_检测管道符() {
        assertThat(resolver.containsShellInjection("cat file | grep secret")).isTrue();
    }

    @Test
    void containsShellInjection_检测与号() {
        assertThat(resolver.containsShellInjection("cmd1 && cmd2")).isTrue();
    }

    @Test
    void containsShellInjection_检测反引号() {
        assertThat(resolver.containsShellInjection("echo `whoami`")).isTrue();
    }

    @Test
    void containsShellInjection_检测美元括号() {
        assertThat(resolver.containsShellInjection("echo $(whoami)")).isTrue();
    }

    @Test
    void containsShellInjection_安全字符串返回false() {
        assertThat(resolver.containsShellInjection("echo hello world")).isFalse();
    }
}
