package com.lifepilot.interaction.web.controller;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.install.SkillImportService;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillMarketplaceInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SkillController} Phase B.6 重接端点测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>PUT /skills/{name}/enabled 调 {@link SkillInstallationRepository#setEnabled}</li>
 *   <li>POST /skills/import 委托 {@link SkillImportService#importFromPackage}</li>
 *   <li>POST /skills/install-from-marketplace 委托 {@link SkillMarketplaceInstaller#installById}</li>
 *   <li>POST /skills 接收 {@code skillMdContent} 走 {@link SkillInstaller#install}</li>
 *   <li>GET /skills/{name}/markdown 直读文件系统</li>
 *   <li>PUT /skills/{name}/markdown 保留原 sourceType 重走 installer</li>
 *   <li>GET /skills / GET /skills/{name} / DELETE /skills/{name} 基础回归</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillController_端点测试 {

    @Mock private SkillRegistry skillRegistry;
    @Mock private McpServerRegistry mcpServerRegistry;
    @Mock private DynamicToolRegistry toolRegistry;
    @Mock private SkillInstaller skillInstaller;
    @Mock private SkillInstallationRepository installationRepository;
    @Mock private SkillImportService skillImportService;
    @Mock private SkillMarketplaceInstaller marketplaceInstaller;
    @Mock private ZhiweiPaths zhiweiPaths;

    private SkillConfigProperties skillConfig;
    private MockMvc mockMvc;

    @TempDir Path tempSkillsDir;

    @BeforeEach
    void setUp() {
        skillConfig = new SkillConfigProperties();
        when(zhiweiPaths.home("skills")).thenReturn(tempSkillsDir);
        // 默认 skillFilename 已是 SKILL.md

        var controller = new SkillController(
                skillRegistry,
                mcpServerRegistry,
                toolRegistry,
                skillConfig,
                zhiweiPaths,
                skillInstaller,
                installationRepository,
                skillImportService,
                marketplaceInstaller);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── PUT /skills/{name}/enabled ─────────────────────────────

    @Test
    void PUT_enabled应更新数据库() throws Exception {
        when(installationRepository.findByName("demo")).thenReturn(Optional.of(install("demo", SkillSourceType.USER_IMPORTED, true)));

        mockMvc.perform(put("/api/skills/demo/enabled")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("demo"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        verify(installationRepository).setEnabled("demo", false);
    }

    @Test
    void PUT_enabled_未安装返回404() throws Exception {
        when(installationRepository.findByName("missing")).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/skills/missing/enabled")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());

        verify(installationRepository, never()).setEnabled(eq("missing"), eq(true));
    }

    @Test
    void PUT_enabled_缺失enabled字段返回400() throws Exception {
        mockMvc.perform(put("/api/skills/demo/enabled")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /skills/import ─────────────────────────────────────

    @Test
    void POST_import应接受文件并返回安装信息() throws Exception {
        var file = new MockMultipartFile("file", "demo.skill", "application/zip", new byte[]{0x50, 0x4b});
        var install = install("demo", SkillSourceType.USER_IMPORTED, true);
        when(skillImportService.importFromPackage(any())).thenReturn(install);

        mockMvc.perform(multipart("/api/skills/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("demo"))
                .andExpect(jsonPath("$.data.sourceType").value("USER_IMPORTED"));

        verify(skillImportService).importFromPackage(any());
    }

    @Test
    void POST_import_参数异常返回400() throws Exception {
        var file = new MockMultipartFile("file", "bad.skill", "application/zip", new byte[]{0x50});
        when(skillImportService.importFromPackage(any()))
                .thenThrow(new IllegalArgumentException(".skill 包根目录必须包含 SKILL.md"));

        mockMvc.perform(multipart("/api/skills/import").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_import_zip安全违规返回400() throws Exception {
        var file = new MockMultipartFile("file", "evil.skill", "application/zip", new byte[]{0x50});
        when(skillImportService.importFromPackage(any()))
                .thenThrow(new SecurityException("zip slip detected: ../etc/passwd"));

        mockMvc.perform(multipart("/api/skills/import").file(file))
                .andExpect(status().isBadRequest());
    }

    // ── POST /skills/install-from-marketplace ───────────────────

    @Test
    void POST_install_from_marketplace应调用installer() throws Exception {
        var install = new SkillInstallation(
                "weekly-planner", SkillSourceType.MARKETPLACE, "https://market.example.com",
                "/fake/weekly-planner", "1.0.0", true,
                "pkg-123", "hash", Instant.now(), Instant.now(), null);
        when(marketplaceInstaller.installById("pkg-123")).thenReturn(install);

        mockMvc.perform(post("/api/skills/install-from-marketplace")
                        .contentType("application/json")
                        .content("{\"marketplaceId\":\"pkg-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("weekly-planner"))
                .andExpect(jsonPath("$.data.marketplaceId").value("pkg-123"))
                .andExpect(jsonPath("$.data.sourceType").value("MARKETPLACE"));

        verify(marketplaceInstaller).installById("pkg-123");
    }

    @Test
    void POST_install_from_marketplace_空id返回400() throws Exception {
        mockMvc.perform(post("/api/skills/install-from-marketplace")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_install_from_marketplace_索引未命中返回400() throws Exception {
        when(marketplaceInstaller.installById("unknown"))
                .thenThrow(new IllegalArgumentException("市场索引未找到包: marketplaceId=unknown"));

        mockMvc.perform(post("/api/skills/install-from-marketplace")
                        .contentType("application/json")
                        .content("{\"marketplaceId\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /skills（创建，skillMdContent 模式）────────────────

    @Test
    void POST_skills_创建走installer() throws Exception {
        var install = install("new-skill", SkillSourceType.USER_IMPORTED, true);
        when(skillInstaller.install(any())).thenReturn(install);

        String md = "---\nname: new-skill\ndescription: hello\nversion: 1.0.0\n---\nBody";
        mockMvc.perform(post("/api/skills")
                        .contentType("application/json")
                        .content("{\"skillMdContent\":" + jsonString(md) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("new-skill"));

        ArgumentCaptor<SkillInstaller.InstallRequest> captor =
                ArgumentCaptor.forClass(SkillInstaller.InstallRequest.class);
        verify(skillInstaller).install(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().sourceType())
                .isEqualTo(SkillSourceType.USER_IMPORTED);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().skillMdContent())
                .isEqualTo(md);
    }

    @Test
    void POST_skills_缺失markdown返回400() throws Exception {
        mockMvc.perform(post("/api/skills")
                        .contentType("application/json")
                        .content("{\"name\":\"legacy-structured\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /skills/{name}/markdown ────────────────────────────

    @Test
    void GET_markdown应返回SKILL_md内容() throws Exception {
        // 在 tempDir/my-skill 下写 SKILL.md
        Path skillDir = tempSkillsDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        String content = "---\nname: my-skill\ndescription: a skill\nversion: 1.0.0\n---\nBody text";
        Files.writeString(skillMd, content);

        // 不入表时退化到 skillsDirectory 路径
        when(installationRepository.findByName("my-skill")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/skills/my-skill/markdown"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(content));
    }

    @Test
    void GET_markdown_文件不存在返回404() throws Exception {
        when(installationRepository.findByName("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/skills/ghost/markdown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_markdown_优先使用表中filePath() throws Exception {
        // 安装在自定义路径（非 skillsDirectory 下）
        Path altDir = tempSkillsDir.resolve("custom-location/my-skill");
        Files.createDirectories(altDir);
        Path skillMd = altDir.resolve("SKILL.md");
        String content = "---\nname: my-skill\ndescription: from-alt\nversion: 1.0.0\n---\nBody";
        Files.writeString(skillMd, content);

        var install = new SkillInstallation(
                "my-skill", SkillSourceType.USER_IMPORTED, "file:///uploaded",
                altDir.toString(), "1.0.0", true, null,
                "fakehash", Instant.now(), Instant.now(), null);
        when(installationRepository.findByName("my-skill")).thenReturn(Optional.of(install));

        mockMvc.perform(get("/api/skills/my-skill/markdown"))
                .andExpect(status().isOk())
                .andExpect(content().string(content));
    }

    // ── PUT /skills/{name}/markdown ────────────────────────────

    @Test
    void PUT_markdown_保留原sourceType() throws Exception {
        String oldUri = "marketplace://pkg-42";
        var existing = new SkillInstallation(
                "demo", SkillSourceType.MARKETPLACE, oldUri,
                tempSkillsDir.resolve("demo").toString(), "1.0.0", true,
                "pkg-42", "oldhash", Instant.now(), Instant.now(), null);
        when(installationRepository.findByName("demo")).thenReturn(Optional.of(existing));

        var updated = new SkillInstallation(
                "demo", SkillSourceType.MARKETPLACE, oldUri,
                tempSkillsDir.resolve("demo").toString(), "1.0.1", true,
                "pkg-42", "newhash", Instant.now(), Instant.now(), null);
        when(skillInstaller.install(any())).thenReturn(updated);

        String md = "---\nname: demo\ndescription: updated\nversion: 1.0.1\n---\nNew body";
        mockMvc.perform(put("/api/skills/demo/markdown")
                        .contentType("text/markdown")
                        .content(md))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("demo"))
                .andExpect(jsonPath("$.data.sourceType").value("MARKETPLACE"))
                .andExpect(jsonPath("$.data.version").value("1.0.1"));

        ArgumentCaptor<SkillInstaller.InstallRequest> captor =
                ArgumentCaptor.forClass(SkillInstaller.InstallRequest.class);
        verify(skillInstaller).install(captor.capture());
        var req = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(req.sourceType()).isEqualTo(SkillSourceType.MARKETPLACE);
        org.assertj.core.api.Assertions.assertThat(req.sourceUri()).isEqualTo(oldUri);
        org.assertj.core.api.Assertions.assertThat(req.marketplaceId()).isEqualTo("pkg-42");
    }

    @Test
    void PUT_markdown_BUILTIN降级为USER_IMPORTED() throws Exception {
        var existing = new SkillInstallation(
                "builtin-skill", SkillSourceType.BUILTIN, "classpath:skills/builtin-skill",
                tempSkillsDir.resolve("builtin-skill").toString(), "1.0.0", true,
                null, "hash", Instant.now(), Instant.now(), null);
        when(installationRepository.findByName("builtin-skill")).thenReturn(Optional.of(existing));

        var updated = install("builtin-skill", SkillSourceType.USER_IMPORTED, true);
        when(skillInstaller.install(any())).thenReturn(updated);

        String md = "---\nname: builtin-skill\ndescription: modified\nversion: 1.0.0\n---\nBody";
        mockMvc.perform(put("/api/skills/builtin-skill/markdown")
                        .contentType("text/markdown")
                        .content(md))
                .andExpect(status().isOk());

        ArgumentCaptor<SkillInstaller.InstallRequest> captor =
                ArgumentCaptor.forClass(SkillInstaller.InstallRequest.class);
        verify(skillInstaller).install(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().sourceType())
                .isEqualTo(SkillSourceType.USER_IMPORTED);
    }

    @Test
    void PUT_markdown_空内容返回400() throws Exception {
        mockMvc.perform(put("/api/skills/demo/markdown")
                        .contentType("text/markdown")
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    // ── GET /skills / GET /skills/{name} ───────────────────────

    @Test
    void list应返回所有安装的skill() throws Exception {
        var skill = sampleSkill("demo");
        when(skillRegistry.listAll()).thenReturn(List.of(skill));

        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("demo"));
    }

    @Test
    void get单个skill不存在返回404() throws Exception {
        when(skillRegistry.find("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/skills/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_skill注销并删除表记录() throws Exception {
        var skill = sampleSkill("demo");
        when(skillRegistry.find("demo")).thenReturn(Optional.of(skill));
        when(installationRepository.findByName("demo")).thenReturn(Optional.of(install("demo", SkillSourceType.USER_IMPORTED, true)));

        mockMvc.perform(delete("/api/skills/demo"))
                .andExpect(status().isOk());

        verify(skillRegistry).unregister("demo");
        verify(installationRepository).delete("demo");
    }

    // ── enable/disable 旧端点回归 ───────────────────────────────

    @Test
    void POST_enable_旧端点走enabled路径() throws Exception {
        when(installationRepository.findByName("demo")).thenReturn(Optional.of(install("demo", SkillSourceType.USER_IMPORTED, false)));

        mockMvc.perform(post("/api/skills/demo/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        verify(installationRepository).setEnabled("demo", true);
    }

    @Test
    void POST_disable_旧端点走enabled路径() throws Exception {
        when(installationRepository.findByName("demo")).thenReturn(Optional.of(install("demo", SkillSourceType.USER_IMPORTED, true)));

        mockMvc.perform(post("/api/skills/demo/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        verify(installationRepository).setEnabled("demo", false);
    }

    // ── 辅助工厂方法 ──────────────────────────────────────────

    private static SkillInstallation install(String name, SkillSourceType type, boolean enabled) {
        return new SkillInstallation(
                name, type, null,
                "/fake/" + name, "1.0.0", enabled, null,
                "hash", Instant.now(), Instant.now(), null);
    }

    private static SkillDefinition sampleSkill(String name) {
        return SkillDefinition.builder()
                .id(name)
                .name(name)
                .description("测试用 Skill")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/fake/" + name, null))
                .instructions("body")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();
    }

    /** 把字符串包成 JSON 字符串字面量（简单转义 \ 和 "，换行编码为 \\n）。 */
    private static String jsonString(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                + "\"";
    }
}
