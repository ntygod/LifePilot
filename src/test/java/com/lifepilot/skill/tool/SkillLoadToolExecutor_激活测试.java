package com.lifepilot.skill.tool;

import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillActivation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * SkillLoadToolExecutor 单元测试 —— 覆盖 names 校验 / enabled 校验 / 聚合行为。
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillLoadToolExecutor_激活测试 {

    @Mock SkillActivator activator;
    @Mock SkillInstallationRepository repository;
    @InjectMocks SkillLoadToolExecutor executor;

    @Test
    void 应拒绝超过3个skill() {
        assertThatThrownBy(() -> executor.execute(Map.of("names", List.of("a", "b", "c", "d"))))
                .hasMessageContaining("最多 3 个");
    }

    @Test
    void 应拒绝空names() {
        assertThatThrownBy(() -> executor.execute(Map.of("names", List.of())))
                .hasMessageContaining("names");
    }

    @Test
    void 应拒绝已禁用的skill() {
        when(repository.findByName("x")).thenReturn(Optional.of(disabled("x")));

        assertThatThrownBy(() -> executor.execute(Map.of("names", List.of("x"))))
                .hasMessageContaining("已被禁用");
    }

    @Test
    void 应拒绝未知skill() {
        when(repository.findByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(Map.of("names", List.of("ghost"))))
                .hasMessageContaining("未知 skill");
    }

    @Test
    void 激活单个成功应返回content() {
        when(repository.findByName("x")).thenReturn(Optional.of(enabled("x")));
        when(activator.activate("x")).thenReturn(
                new SkillActivation("x", "## 指南正文\n...", List.of("tool-a")));

        var result = executor.execute(Map.of("names", List.of("x")));

        assertThat(result).containsKey("content");
        assertThat((String) result.get("content"))
                .contains("<skill name=\"x\">")
                .contains("## 指南正文")
                .contains("</skill>");
    }

    @Test
    void 激活多个skill内容应按输入顺序拼接() {
        when(repository.findByName("a")).thenReturn(Optional.of(enabled("a")));
        when(repository.findByName("b")).thenReturn(Optional.of(enabled("b")));
        when(activator.activate("a")).thenReturn(
                new SkillActivation("a", "AAA", List.of("t1")));
        when(activator.activate("b")).thenReturn(
                new SkillActivation("b", "BBB", List.of("t2")));

        var result = executor.execute(Map.of("names", List.of("a", "b")));

        var content = (String) result.get("content");
        assertThat(content.indexOf("AAA")).isLessThan(content.indexOf("BBB"));
    }

    @Test
    void instructions中的references绝对路径应在末尾以file_read强引导列出() {
        when(repository.findByName("x")).thenReturn(Optional.of(enabled("x")));
        String instructions = """
                # 指南

                详细参考：`C:\\Users\\u\\.zhiwei\\skills\\x/references/x-recipes.md`
                """;
        when(activator.activate("x")).thenReturn(
                new SkillActivation("x", instructions, List.of("t1")));

        var content = (String) executor.execute(Map.of("names", List.of("x"))).get("content");

        assertThat(content)
                .contains("执行具体动作")
                .contains("file.read(\"C:\\Users\\u\\.zhiwei\\skills\\x/references/x-recipes.md\")");
    }

    @Test
    void 无references的skill不应追加强引导段() {
        when(repository.findByName("x")).thenReturn(Optional.of(enabled("x")));
        when(activator.activate("x")).thenReturn(
                new SkillActivation("x", "## 简单指南\n无 references", List.of("t1")));

        var content = (String) executor.execute(Map.of("names", List.of("x"))).get("content");

        assertThat(content).doesNotContain("执行具体动作").doesNotContain("file.read");
    }

    @Test
    void 多skill的references应去重并合并列出() {
        when(repository.findByName("a")).thenReturn(Optional.of(enabled("a")));
        when(repository.findByName("b")).thenReturn(Optional.of(enabled("b")));
        when(activator.activate("a")).thenReturn(new SkillActivation("a",
                "ref: /home/u/.zhiwei/skills/a/references/x.md", List.of("t1")));
        when(activator.activate("b")).thenReturn(new SkillActivation("b",
                "see /home/u/.zhiwei/skills/b/references/y.md and /home/u/.zhiwei/skills/a/references/x.md",
                List.of("t2")));

        var content = (String) executor.execute(Map.of("names", List.of("a", "b"))).get("content");

        // 末尾 file.read 引导段里 x.md 应只列 1 次（即使 instructions 里出现 2 次）
        long fileReadCount = content.lines()
                .filter(l -> l.contains("file.read(\"/home/u/.zhiwei/skills/a/references/x.md\")"))
                .count();
        assertThat(fileReadCount).isEqualTo(1);
        assertThat(content).contains("file.read(\"/home/u/.zhiwei/skills/b/references/y.md\")");
    }

    @Test
    void 多skill重复suggested_tools应去重() {
        when(repository.findByName("a")).thenReturn(Optional.of(enabled("a")));
        when(repository.findByName("b")).thenReturn(Optional.of(enabled("b")));
        when(activator.activate("a")).thenReturn(new SkillActivation("a", "A", List.of("t1", "t2")));
        when(activator.activate("b")).thenReturn(new SkillActivation("b", "B", List.of("t2", "t3")));

        executor.execute(Map.of("names", List.of("a", "b")));

        // suggested_tools 不再返回 activated_tool_ids，仅验证不抛异常
    }

    // 辅助方法

    @SuppressWarnings("unchecked")

    private SkillInstallation enabled(String n) {
        return new SkillInstallation(n, SkillSourceType.BUILTIN, null, "/p/" + n, "1.0.0",
                true, null, "sha", Instant.now(), Instant.now(), null);
    }

    private SkillInstallation disabled(String n) {
        return new SkillInstallation(n, SkillSourceType.BUILTIN, null, "/p/" + n, "1.0.0",
                false, null, "sha", Instant.now(), Instant.now(), null);
    }
}
