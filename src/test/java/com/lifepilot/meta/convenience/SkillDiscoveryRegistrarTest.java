package com.lifepilot.meta.convenience;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.skill.validation.SkillValidator;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillDiscoveryRegistrar 单元测试 —— 验证 BUILTIN 安装流水线接入 {@link SkillInstaller}。
 *
 * <p>覆盖：
 * <ul>
 *   <li>启动扫描时成功安装至少一个 v3 格式 Skill（daily-manager）</li>
 *   <li>老 v1 格式 Skill 被 WARN 跳过不阻断</li>
 *   <li>禁用开关时不触发扫描</li>
 *   <li>SkillInstaller 被正确调用，SkillRegistry 接收 Definition</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillDiscoveryRegistrarTest {

    private MetaProperties properties;
    private ZhiweiPaths zhiweiPaths;
    private SkillInstallationRepository repository;
    private SkillInstaller installer;
    private MarkdownSkillParser parser;
    private SkillValidator validator;
    private SkillRegistry registry;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        zhiweiPaths = mock(ZhiweiPaths.class);
        when(zhiweiPaths.home("skills")).thenReturn(tempDir);

        parser = new MarkdownSkillParser();
        repository = mock(SkillInstallationRepository.class);
        validator = new SkillValidator(new SkillDescriptionValidator(), new SkillBodyValidator(),
                new DynamicToolRegistry(event -> {}));
        installer = new SkillInstaller(parser, validator, repository);
        registry = mock(SkillRegistry.class);
        when(registry.register(any())).thenReturn(true);
    }

    @Test
    void afterPropertiesSet_应安装v3格式Skill成功落盘() {
        var registrar = new SkillDiscoveryRegistrar(
                properties, zhiweiPaths, installer, repository, parser, validator, registry);

        registrar.afterPropertiesSet();

        // daily-manager 已迁移到 v3 格式，应成功落盘
        Path skillFile = tempDir.resolve("daily-manager/SKILL.md");
        assertThat(skillFile).exists();

        // SkillInstaller 至少被调用一次（BUILTIN 安装）
        verify(repository, org.mockito.Mockito.atLeastOnce()).upsert(any(SkillInstallation.class));

        // SkillRegistry 接收到至少一个 v3 Definition 且 sourceType 信息正确
        ArgumentCaptor<SkillDefinition> captor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(registry, org.mockito.Mockito.atLeastOnce()).register(captor.capture());
        List<SkillDefinition> registered = captor.getAllValues();
        assertThat(registered).anyMatch(d -> d.id().equals("daily-manager"));
    }

    @Test
    void afterPropertiesSet_老格式Skill应被WARN跳过但不阻断其他安装() {
        // 26 个老格式 Skill 会被 parser 拒绝（id 已废弃），但至少 daily-manager 成功
        var registrar = new SkillDiscoveryRegistrar(
                properties, zhiweiPaths, installer, repository, parser, validator, registry);

        registrar.afterPropertiesSet();

        // 捕获所有 upsert 的 SkillInstallation，只能是 v3 格式已迁移的
        ArgumentCaptor<SkillInstallation> captor = ArgumentCaptor.forClass(SkillInstallation.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).upsert(captor.capture());
        List<String> installedNames = new ArrayList<>();
        for (SkillInstallation inst : captor.getAllValues()) {
            installedNames.add(inst.name());
            assertThat(inst.sourceType()).isEqualTo(SkillSourceType.BUILTIN);
        }
        assertThat(installedNames).contains("daily-manager");
    }

    @Test
    void afterPropertiesSet_功能禁用时应跳过整个扫描() {
        properties.getSkillDiscovery().setEnabled(false);

        var registrar = new SkillDiscoveryRegistrar(
                properties, zhiweiPaths, installer, repository, parser, validator, registry);
        registrar.afterPropertiesSet();

        // 禁用后不应有任何 upsert 或 register 调用
        verify(repository, never()).upsert(any());
        verify(registry, never()).register(any());

        // 用户目录也不应有任何文件生成
        assertThat(Files.exists(tempDir.resolve("daily-manager/SKILL.md"))).isFalse();
    }

    @Test
    void afterPropertiesSet_本地已存在的Skill不应被出厂版本覆盖() throws Exception {
        // 预置：用户本地已有 daily-manager 且手改过 SKILL.md（版本号故意低于出厂 3.0.2）
        Path skillFolder = tempDir.resolve("daily-manager");
        Files.createDirectories(skillFolder);
        Path localSkillMd = skillFolder.resolve("SKILL.md");
        String userEdited = """
                ---
                name: daily-manager
                description: 当用户需要用户自定义的多步任务规划与日报汇总时使用，这是用户手改过的描述占位。
                version: 1.0.0
                ---
                用户自定义正文，绝对不能被出厂版本覆盖。
                """;
        Files.writeString(localSkillMd, userEdited, StandardCharsets.UTF_8);

        // skills 表已记录该 skill（version 低于出厂 3.0.2，旧逻辑会误判为 UPGRADE 并覆盖）
        when(repository.findByName("daily-manager")).thenReturn(Optional.of(new SkillInstallation(
                "daily-manager", SkillSourceType.BUILTIN, "classpath:skills/daily-manager",
                skillFolder.toString(), "1.0.0", true, null, "oldchecksum",
                Instant.now(), Instant.now(), null)));

        var registrar = new SkillDiscoveryRegistrar(
                properties, zhiweiPaths, installer, repository, parser, validator, registry);
        registrar.afterPropertiesSet();

        // 本地文件必须原样保留，不被出厂 3.0.2 覆盖
        assertThat(Files.readString(localSkillMd, StandardCharsets.UTF_8)).isEqualTo(userEdited);
        // KEEP 决策不应对 daily-manager 重装 / 写表
        verify(repository, never()).upsert(argThat(i -> "daily-manager".equals(i.name())));
    }
}
