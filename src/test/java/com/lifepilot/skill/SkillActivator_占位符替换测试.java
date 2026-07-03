package com.lifepilot.skill;

import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.event.SkillLifecycleEvent;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillActivator} v3 单元测试 —— 覆盖查表 + 三占位符替换 + 启用校验。
 *
 * <p>测试策略：mock {@link SkillInstallationRepository} 与 {@link SkillRegistry}，
 * 验证激活流程对不同输入（未知 name / disabled / 各占位符指令）的处理。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillActivator_占位符替换测试 {

    @Mock
    private SkillRegistry registry;

    @Mock
    private SkillInstallationRepository repository;

    @Mock
    private ApplicationEventPublisher publisher;

    // 真实 tracker —— 指标操作无副作用，便于观察计数。
    private final SkillMetricsTracker metricsTracker = new SkillMetricsTracker();

    private SkillActivator activator;

    @BeforeEach
    void setUp() {
        activator = new SkillActivator(registry, repository, metricsTracker, publisher);
    }

    @Test
    void skill_dir占位符应被替换为实际路径() {
        String filePath = "/home/user/.zhiwei/skills/docwriter";
        givenInstalled("docwriter", filePath, true);
        givenRegistered("docwriter", "参考本 Skill 根目录 {skill_dir} 下的模板文件。", List.of());

        SkillActivation result = activator.activate("docwriter");

        assertThat(result.instructions())
                .contains(filePath)
                .doesNotContain("{skill_dir}");
    }

    @Test
    void skill_references_dir占位符应被替换为filePath加references子目录() {
        String filePath = "/srv/skills/search";
        givenInstalled("search", filePath, true);
        givenRegistered("search",
                "详细 API 列表见 {skill_references_dir}/api.md",
                List.of("search_bm25"));

        SkillActivation result = activator.activate("search");

        assertThat(result.instructions())
                .contains("/srv/skills/search/references/api.md")
                .doesNotContain("{skill_references_dir}");
    }

    @Test
    void skill_scripts_dir占位符应被替换为filePath加scripts子目录() {
        String filePath = "/opt/zhiwei/skills/build";
        givenInstalled("build", filePath, true);
        givenRegistered("build",
                "执行 `python {skill_scripts_dir}/compile.py` 进行编译。",
                List.of());

        SkillActivation result = activator.activate("build");

        assertThat(result.instructions())
                .contains("/opt/zhiwei/skills/build/scripts/compile.py")
                .doesNotContain("{skill_scripts_dir}");
    }

    @Test
    void 三种占位符可在同一文档中同时替换() {
        String filePath = "/data/skills/complex";
        givenInstalled("complex", filePath, true);
        givenRegistered("complex",
                "根目录：{skill_dir}\n参考：{skill_references_dir}/doc.md\n脚本：{skill_scripts_dir}/run.sh",
                List.of());

        SkillActivation result = activator.activate("complex");

        assertThat(result.instructions())
                .contains("根目录：/data/skills/complex")
                .contains("参考：/data/skills/complex/references/doc.md")
                .contains("脚本：/data/skills/complex/scripts/run.sh")
                .doesNotContain("{skill_dir}")
                .doesNotContain("{skill_references_dir}")
                .doesNotContain("{skill_scripts_dir}");
    }

    @Test
    void 未知skill应抛IllegalArgumentException() {
        when(repository.findByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> activator.activate("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 skill")
                .hasMessageContaining("ghost");

        // 未知 skill 不应触发后续流程
        verify(publisher, never()).publishEvent(any());
        verify(repository, never()).updateLastActivatedAt(any(), any());
    }

    @Test
    void 禁用的skill应抛IllegalStateException() {
        givenInstalled("archived", "/skills/archived", false);

        assertThatThrownBy(() -> activator.activate("archived"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived")
                .hasMessageContaining("已被禁用");

        verify(publisher, never()).publishEvent(any());
        verify(repository, never()).updateLastActivatedAt(any(), any());
    }

    @Test
    void 已登记但未加载到注册表的skill应抛IllegalStateException() {
        givenInstalled("stale", "/skills/stale", true);
        when(registry.find("stale")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> activator.activate("stale"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void 激活成功应返回含name_instructions_suggestedTools的SkillActivation() {
        givenInstalled("todo", "/skills/todo", true);
        givenRegistered("todo", "帮助用户管理待办事项。", List.of("todo_add", "todo_list"));

        SkillActivation result = activator.activate("todo");

        assertThat(result.name()).isEqualTo("todo");
        assertThat(result.instructions()).isEqualTo("帮助用户管理待办事项。");
        assertThat(result.suggestedTools()).containsExactly("todo_add", "todo_list");
    }

    @Test
    void 激活成功应异步调用updateLastActivatedAt() {
        givenInstalled("async-test", "/skills/async-test", true);
        givenRegistered("async-test", "示例指令。", List.of());

        activator.activate("async-test");

        // 异步执行，最多等 2 秒
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository, timeout(2000)).updateLastActivatedAt(eq("async-test"), captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void 激活成功应发布Activated事件并累加指标() {
        givenInstalled("metrics", "/skills/metrics", true);
        givenRegistered("metrics", "指标示例。", List.of());

        activator.activate("metrics");
        activator.activate("metrics");

        assertThat(metricsTracker.getActivationCount("metrics")).isEqualTo(2);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(e -> e instanceof SkillLifecycleEvent.Activated act && act.skillId().equals("metrics"));
    }

    @Test
    void 不含占位符的instructions应原样返回() {
        givenInstalled("plain", "/skills/plain", true);
        givenRegistered("plain", "这是一段没有任何占位符的纯文本指令。", List.of());

        SkillActivation result = activator.activate("plain");

        assertThat(result.instructions()).isEqualTo("这是一段没有任何占位符的纯文本指令。");
    }

    // ── 辅助方法 ──

    private void givenInstalled(String name, String filePath, boolean enabled) {
        var installation = new SkillInstallation(
                name,
                SkillSourceType.USER_IMPORTED,
                null,
                filePath,
                "1.0.0",
                enabled,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null
        );
        when(repository.findByName(name)).thenReturn(Optional.of(installation));
    }

    private void givenRegistered(String name, String instructions, List<String> suggestedTools) {
        var definition = SkillDefinition.builder()
                .id(name)
                .name(name + "-display")
                .description("desc of " + name)
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/ignored", null))
                .instructions(instructions)
                .suggestedTools(suggestedTools)
                .metadata(Map.of())
                .build();
        when(registry.find(name)).thenReturn(Optional.of(definition));
    }
}
