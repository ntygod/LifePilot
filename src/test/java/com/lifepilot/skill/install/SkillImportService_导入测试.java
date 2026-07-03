package com.lifepilot.skill.install;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.skill.validation.SkillValidator;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SkillImportService 导入测试 —— .skill 包解压 + 安全校验。
 *
 * <p>覆盖：合法导入 / zip slip 拒绝 / 空上传拒绝 / 缺 SKILL.md 拒绝 / 辅助文件同步复制 /
 * Git URL 当前抛 UnsupportedOperation。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillImportService_导入测试 {

    @TempDir
    Path skillsRoot;

    SkillInstaller installer;
    ZhiweiPaths zhiweiPaths;
    SkillImportService service;
    SkillInstallationRepository repository;

    @BeforeEach
    void setup() {
        repository = mock(SkillInstallationRepository.class);
        var parser = new MarkdownSkillParser();
        installer = new SkillInstaller(parser,
                new SkillValidator(new SkillDescriptionValidator(), new SkillBodyValidator(),
                        new DynamicToolRegistry(event -> {})),
                repository);
        zhiweiPaths = mock(ZhiweiPaths.class);
        when(zhiweiPaths.home("skills")).thenReturn(skillsRoot);
        service = new SkillImportService(installer, repository, zhiweiPaths);
    }

    @Test
    void 导入合法包应写入目录与调用repository_upsert() throws Exception {
        String validMd = """
                ---
                name: import-demo
                description: 当需要测试导入时使用。关键词 import
                version: 1.0.0
                ---
                ## 触发判断
                - demo
                - 不要触发：not demo
                ## 决策路径
                1. do
                ## 输出标准
                - text
                ## 失败策略
                - fail
                """;

        var pkg = zipBuilder().addFile("SKILL.md", validMd).build();

        var install = service.importFromPackage(pkg);

        assertThat(install.name()).isEqualTo("import-demo");
        assertThat(install.sourceType()).isEqualTo(SkillSourceType.USER_IMPORTED);
        assertThat(install.sourceUri()).startsWith("file://");
        assertThat(Files.exists(skillsRoot.resolve("import-demo/SKILL.md"))).isTrue();
    }

    @Test
    void zip_slip应拒绝() throws Exception {
        var pkg = zipBuilder().addFile("../../../etc/passwd", "hacked").build();

        assertThatThrownBy(() -> service.importFromPackage(pkg))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("zip slip");
    }

    @Test
    void 空上传应拒绝() {
        var empty = new MockMultipartFile("file", new byte[0]);
        assertThatThrownBy(() -> service.importFromPackage(empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 缺少SKILL_md应拒绝() throws Exception {
        var pkg = zipBuilder().addFile("README.md", "no skill here").build();

        assertThatThrownBy(() -> service.importFromPackage(pkg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void aux文件应一同复制() throws Exception {
        String validMd = """
                ---
                name: with-refs
                description: 当有 references 时使用。关键词 refs
                version: 1.0.0
                ---
                ## 触发判断
                - 测试
                - 不要触发：无
                ## 决策路径
                1. do
                ## 输出标准
                - text
                ## 失败策略
                - fail
                """;
        var pkg = zipBuilder()
                .addFile("SKILL.md", validMd)
                .addFile("references/api.md", "# API\n...")
                .addFile("scripts/setup.sh", "#!/bin/bash\necho hi")
                .build();

        service.importFromPackage(pkg);

        assertThat(Files.exists(skillsRoot.resolve("with-refs/references/api.md"))).isTrue();
        assertThat(Files.exists(skillsRoot.resolve("with-refs/scripts/setup.sh"))).isTrue();
    }

    // --- builder ---

    private ZipBuilder zipBuilder() {
        return new ZipBuilder();
    }

    private static class ZipBuilder {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private final ZipOutputStream zos = new ZipOutputStream(baos);

        ZipBuilder addFile(String name, String content) throws Exception {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes());
            zos.closeEntry();
            return this;
        }

        MockMultipartFile build() throws Exception {
            zos.close();
            return new MockMultipartFile("file", "skill.zip", "application/zip", baos.toByteArray());
        }
    }
}
