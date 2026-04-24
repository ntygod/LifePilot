package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase D 完成验证 —— 确认 classpath 下全部 26 个 BUILTIN Skill 都符合 v2 规范、
 * 经 SkillInstaller 完整流水线校验成功，不再有 skipped。
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillDiscoveryRegistrar_全量迁移测试 {

    @TempDir
    Path tempDir;

    @Test
    void 启动后classpath下全部BUILTIN_Skill都应安装成功_无任一被跳过() {
        var properties = new MetaProperties();
        var skillConfig = new SkillConfigProperties();
        skillConfig.setDirectory(tempDir.toString());

        var parser = new MarkdownSkillParser();
        var repository = mock(SkillInstallationRepository.class);
        var installer = new SkillInstaller(parser,
                new SkillDescriptionValidator(),
                new SkillBodyValidator(),
                repository);
        var registry = mock(SkillRegistry.class);
        when(registry.register(any())).thenReturn(true);

        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig, installer, parser, registry);
        registrar.afterPropertiesSet();

        ArgumentCaptor<SkillInstallation> captor = ArgumentCaptor.forClass(SkillInstallation.class);
        verify(repository, org.mockito.Mockito.atLeast(27)).upsert(captor.capture());
        List<SkillInstallation> installed = captor.getAllValues();

        // Phase D 完成：26 个迁移 + skill-creator 新建 = 27 个 BUILTIN Skill 全部落盘
        assertThat(installed).hasSize(27);

        // 检查所有预期 Skill 都在
        List<String> expected = List.of(
                "a2ui", "api-debugger", "browser-automation", "code-assistant",
                "content-creator", "cron-scheduler", "daily-manager", "data-analyst",
                "database-query", "datastore", "desktop-automation", "doc-processor",
                "document-workspace", "feishu", "file-organizer", "find-skills",
                "gitee", "github-workflow", "healthcheck", "introspection",
                "log-analyzer", "research-assistant", "skill-creator", "summarizer",
                "teaching-assistant", "web-novel-writer", "workflow-creator"
        );
        assertThat(installed.stream().map(SkillInstallation::name).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
    }
}
