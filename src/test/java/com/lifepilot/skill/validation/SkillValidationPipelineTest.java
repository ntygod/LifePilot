package com.lifepilot.skill.validation;

import com.lifepilot.skill.validation.FormatValidator.FormatValidationResult;
import com.lifepilot.skill.validation.SandboxValidator.SandboxValidationResult;
import com.lifepilot.skill.validation.SecurityValidator.SecurityValidationResult;
import com.lifepilot.skill.validation.SkillValidationResult.ValidationStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link SkillValidationPipeline} 单元测试。
 *
 * <p>使用 Mockito 模拟三个验证器，验证管线编排逻辑和短路行为。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class SkillValidationPipelineTest {

    @Mock
    private FormatValidator formatValidator;

    @Mock
    private SecurityValidator securityValidator;

    @Mock
    private SandboxValidator sandboxValidator;

    private SkillValidationPipeline pipeline;

    private static final String YAML_CONTENT = "skill:\n  id: test\n";
    private static final Map<String, Object> PARSED_MAP = Map.of("skill", Map.of("id", "test"));

    @BeforeEach
    void setUp() {
        pipeline = new SkillValidationPipeline(formatValidator, securityValidator, sandboxValidator);
    }

    // ─────────────────────────────────────────────
    //  三阶段全部通过
    // ─────────────────────────────────────────────

    @Test
    void 三阶段全部通过_返回allPassed() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(true, List.of(), PARSED_MAP));
        when(securityValidator.validate(PARSED_MAP))
                .thenReturn(new SecurityValidationResult(true, List.of()));
        when(sandboxValidator.validate(YAML_CONTENT))
                .thenReturn(new SandboxValidationResult(true, List.of()));

        SkillValidationResult result = pipeline.validate(YAML_CONTENT);

        assertThat(result.passed()).isTrue();
        assertThat(result.failedStage()).isNull();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void 三阶段全部通过_三个验证器均被调用() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(true, List.of(), PARSED_MAP));
        when(securityValidator.validate(PARSED_MAP))
                .thenReturn(new SecurityValidationResult(true, List.of()));
        when(sandboxValidator.validate(YAML_CONTENT))
                .thenReturn(new SandboxValidationResult(true, List.of()));

        pipeline.validate(YAML_CONTENT);

        verify(formatValidator).validate(YAML_CONTENT);
        verify(securityValidator).validate(PARSED_MAP);
        verify(sandboxValidator).validate(YAML_CONTENT);
    }

    // ─────────────────────────────────────────────
    //  格式验证失败 — 短路
    // ─────────────────────────────────────────────

    @Test
    void 格式验证失败_返回FORMAT阶段失败() {
        var formatErrors = List.of("YAML 语法错误", "缺少必填字段");
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(false, formatErrors, null));

        SkillValidationResult result = pipeline.validate(YAML_CONTENT);

        assertThat(result.passed()).isFalse();
        assertThat(result.failedStage()).isEqualTo(ValidationStage.FORMAT);
        assertThat(result.errors()).containsExactlyElementsOf(formatErrors);
    }

    @Test
    void 格式验证失败_安全和沙箱验证器不被调用() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(false, List.of("错误"), null));

        pipeline.validate(YAML_CONTENT);

        verify(formatValidator).validate(YAML_CONTENT);
        verify(securityValidator, never()).validate(any());
        verify(sandboxValidator, never()).validate(anyString());
    }

    // ─────────────────────────────────────────────
    //  安全验证失败 — 短路
    // ─────────────────────────────────────────────

    @Test
    void 安全验证失败_返回SECURITY阶段失败() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(true, List.of(), PARSED_MAP));
        var securityErrors = List.of("工具不存在: unknown-tool");
        when(securityValidator.validate(PARSED_MAP))
                .thenReturn(new SecurityValidationResult(false, securityErrors));

        SkillValidationResult result = pipeline.validate(YAML_CONTENT);

        assertThat(result.passed()).isFalse();
        assertThat(result.failedStage()).isEqualTo(ValidationStage.SECURITY);
        assertThat(result.errors()).containsExactlyElementsOf(securityErrors);
    }

    @Test
    void 安全验证失败_沙箱验证器不被调用() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(true, List.of(), PARSED_MAP));
        when(securityValidator.validate(PARSED_MAP))
                .thenReturn(new SecurityValidationResult(false, List.of("安全错误")));

        pipeline.validate(YAML_CONTENT);

        verify(formatValidator).validate(YAML_CONTENT);
        verify(securityValidator).validate(PARSED_MAP);
        verify(sandboxValidator, never()).validate(anyString());
    }

    // ─────────────────────────────────────────────
    //  沙箱验证失败
    // ─────────────────────────────────────────────

    @Test
    void 沙箱验证失败_返回SANDBOX阶段失败() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(true, List.of(), PARSED_MAP));
        when(securityValidator.validate(PARSED_MAP))
                .thenReturn(new SecurityValidationResult(true, List.of()));
        var sandboxErrors = List.of("system-prompt 长度超过沙箱限制");
        when(sandboxValidator.validate(YAML_CONTENT))
                .thenReturn(new SandboxValidationResult(false, sandboxErrors));

        SkillValidationResult result = pipeline.validate(YAML_CONTENT);

        assertThat(result.passed()).isFalse();
        assertThat(result.failedStage()).isEqualTo(ValidationStage.SANDBOX);
        assertThat(result.errors()).containsExactlyElementsOf(sandboxErrors);
    }

    @Test
    void 沙箱验证失败_格式和安全验证器均被调用() {
        when(formatValidator.validate(YAML_CONTENT))
                .thenReturn(new FormatValidationResult(true, List.of(), PARSED_MAP));
        when(securityValidator.validate(PARSED_MAP))
                .thenReturn(new SecurityValidationResult(true, List.of()));
        when(sandboxValidator.validate(YAML_CONTENT))
                .thenReturn(new SandboxValidationResult(false, List.of("沙箱错误")));

        pipeline.validate(YAML_CONTENT);

        verify(formatValidator).validate(YAML_CONTENT);
        verify(securityValidator).validate(PARSED_MAP);
        verify(sandboxValidator).validate(YAML_CONTENT);
    }
}
