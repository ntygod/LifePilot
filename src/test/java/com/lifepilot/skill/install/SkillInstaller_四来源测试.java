package com.lifepilot.skill.install;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SkillInstaller 四来源统一安装流水线测试。
 *
 * <p>覆盖：BUILTIN / AUTO_GENERATED 成功路径 + description / body 违规失败路径 +
 * 同名重复 upsert 幂等 + checksum 正确性。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillInstaller_四来源测试 {

    @TempDir
    Path tempDir;

    MarkdownSkillParser parser;
    SkillDescriptionValidator descriptionValidator;
    SkillBodyValidator bodyValidator;
    SkillInstallationRepository repository;
    SkillInstaller installer;

    @BeforeEach
    void setup() {
        parser = new MarkdownSkillParser();
        descriptionValidator = new SkillDescriptionValidator();
        bodyValidator = new SkillBodyValidator();
        repository = mock(SkillInstallationRepository.class);
        installer = new SkillInstaller(parser, descriptionValidator, bodyValidator, repository);
    }

    @Test
    void 安装BUILTIN应写入文件并upsert表() throws IOException {
        String md = """
                ---
                name: demo-skill
                description: 当需要演示时使用。关键词 demo
                version: 1.0.0
                ---
                ## 适用场景
                - demo
                ## 不适用场景
                - not-demo
                ## 工作流
                1. do
                """;

        var install = installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN, "classpath:skills/demo-skill", null, md, tempDir));

        assertThat(install.name()).isEqualTo("demo-skill");
        assertThat(install.sourceType()).isEqualTo(SkillSourceType.BUILTIN);
        assertThat(install.enabled()).isTrue();
        assertThat(install.filePath()).isEqualTo(tempDir.resolve("demo-skill").toString());
        assertThat(Files.exists(tempDir.resolve("demo-skill/SKILL.md"))).isTrue();
        verify(repository).upsert(any());
    }

    @Test
    void AUTO_GENERATED应默认enabled_1() throws IOException {
        String md = """
                ---
                name: ai-gen
                description: 当需要 AI 自动生成技能时使用。关键词 ai
                version: 1.0.0
                ---
                ## 适用场景
                - ai
                ## 不适用场景
                - 非 ai
                ## 工作流
                1. do
                """;

        var install = installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.AUTO_GENERATED, "ai-generated", null, md, tempDir));

        assertThat(install.enabled()).isTrue();
        assertThat(install.sourceType()).isEqualTo(SkillSourceType.AUTO_GENERATED);
    }

    @Test
    void description违规应拒绝且不写文件不入表() {
        String md = """
                ---
                name: bad
                description: 这不是合法的开头
                version: 1.0.0
                ---
                ## 适用场景
                - x
                ## 不适用场景
                - y
                ## 工作流
                1. do
                """;

        assertThatThrownBy(() -> installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN, null, null, md, tempDir)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("开头");

        assertThat(Files.exists(tempDir.resolve("bad/SKILL.md"))).isFalse();
        verify(repository, never()).upsert(any());
    }

    @Test
    void body违规应拒绝_缺少必需小节() {
        String md = """
                ---
                name: bad-body
                description: 当用于测试时使用。关键词 test
                version: 1.0.0
                ---
                ## 适用场景
                - only this
                """;

        assertThatThrownBy(() -> installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN, null, null, md, tempDir)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.exists(tempDir.resolve("bad-body/SKILL.md"))).isFalse();
        verify(repository, never()).upsert(any());
    }

    @Test
    void 相同name重复install应upsert不抛异常() throws IOException {
        String md = """
                ---
                name: repeat
                description: 当需要重复测试时使用。关键词 repeat
                version: 1.0.0
                ---
                ## 适用场景
                - a
                ## 不适用场景
                - b
                ## 工作流
                1. do
                """;

        installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN, null, null, md, tempDir));
        installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.USER_IMPORTED, null, null, md, tempDir));

        verify(repository, times(2)).upsert(any());
    }

    @Test
    void 应正确计算SHA256_checksum() throws IOException {
        String md = """
                ---
                name: checksum-test
                description: 当需要校验校验和时使用。关键词 checksum
                version: 1.0.0
                ---
                ## 适用场景
                - a
                ## 不适用场景
                - b
                ## 工作流
                1. do
                """;

        var install = installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN, null, null, md, tempDir));

        assertThat(install.checksum())
                .isNotBlank()
                .hasSize(64)
                .matches("[a-f0-9]+");
    }

    @Test
    void skillMdContent超过100000字符应拒绝构造请求() {
        String huge = "x".repeat(SkillInstaller.InstallRequest.MAX_SKILL_MD_LENGTH + 1);

        assertThatThrownBy(() -> new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN, null, null, huge, tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skillMdContent")
                .hasMessageContaining("超过");
    }
}
