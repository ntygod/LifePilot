package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillDiscoveryRegistrar 单元测试 —— 验证 BUILTIN 安装流水线接入 {@link SkillInstaller}。
 *
 * <p>覆盖：
 * <ul>
 *   <li>启动扫描时成功安装至少一个 v2 格式 Skill（daily-manager）</li>
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
    private SkillConfigProperties skillConfig;
    private SkillInstallationRepository repository;
    private SkillInstaller installer;
    private MarkdownSkillParser parser;
    private SkillRegistry registry;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        skillConfig = new SkillConfigProperties();
        skillConfig.setDirectory(tempDir.toString());

        parser = new MarkdownSkillParser();
        repository = mock(SkillInstallationRepository.class);
        installer = new SkillInstaller(parser,
                new SkillDescriptionValidator(),
                new SkillBodyValidator(),
                repository);
        registry = mock(SkillRegistry.class);
        when(registry.register(any())).thenReturn(true);
    }

    @Test
    void afterPropertiesSet_应安装v2格式Skill成功落盘() {
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig, installer, repository, parser, registry);

        registrar.afterPropertiesSet();

        // daily-manager 已迁移到 v2 格式，应成功落盘
        Path skillFile = tempDir.resolve("daily-manager/SKILL.md");
        assertThat(skillFile).exists();

        // SkillInstaller 至少被调用一次（BUILTIN 安装）
        verify(repository, org.mockito.Mockito.atLeastOnce()).upsert(any(SkillInstallation.class));

        // SkillRegistry 接收到至少一个 v2 Definition 且 sourceType 信息正确
        ArgumentCaptor<SkillDefinition> captor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(registry, org.mockito.Mockito.atLeastOnce()).register(captor.capture());
        List<SkillDefinition> registered = captor.getAllValues();
        assertThat(registered).anyMatch(d -> d.id().equals("daily-manager"));
    }

    @Test
    void afterPropertiesSet_老格式Skill应被WARN跳过但不阻断其他安装() {
        // 26 个老格式 Skill 会被 parser 拒绝（id 已废弃），但至少 daily-manager 成功
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig, installer, repository, parser, registry);

        registrar.afterPropertiesSet();

        // 捕获所有 upsert 的 SkillInstallation，只能是 v2 格式已迁移的
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

        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig, installer, repository, parser, registry);
        registrar.afterPropertiesSet();

        // 禁用后不应有任何 upsert 或 register 调用
        verify(repository, never()).upsert(any());
        verify(registry, never()).register(any());

        // 用户目录也不应有任何文件生成
        assertThat(Files.exists(tempDir.resolve("daily-manager/SKILL.md"))).isFalse();
    }
}
