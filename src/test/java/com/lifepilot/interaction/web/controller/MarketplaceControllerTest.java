package com.lifepilot.interaction.web.controller;

import com.lifepilot.marketplace.MarketplaceService;
import com.lifepilot.marketplace.model.ExtensionAssetContent;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionInstallation;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstallResult;
import com.lifepilot.marketplace.model.InstalledExtensionAsset;
import com.lifepilot.marketplace.model.RiskLevel;
import com.lifepilot.marketplace.model.SecurityFinding;
import com.lifepilot.marketplace.model.SecurityReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MarketplaceController 单元测试 — Mock MarketplaceService，使用 standalone MockMvc。
 *
 * @author zsg
 * @since 2026-03-05
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceControllerTest {

    @Mock
    private MarketplaceService marketplaceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new MarketplaceController(marketplaceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── GET /api/marketplace/extensions ────────────────────────

    @Nested
    class ListExtensions {

        @Test
        void 无过滤条件_返回分页结果() throws Exception {
            var pkg = testPackage("pkg-1", "测试工具", 100);
            var paged = new MarketplaceService.PagedResult<>(List.of(pkg), 0, 20, 1, 1);
            when(marketplaceService.getExtensions(null, null, null, 0, 20)).thenReturn(paged);

            mockMvc.perform(get("/api/marketplace/extensions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value("pkg-1"));

            verify(marketplaceService).getExtensions(null, null, null, 0, 20);
        }

        @Test
        void 带搜索和标签参数() throws Exception {
            var paged = new MarketplaceService.PagedResult<ExtensionPackage>(List.of(), 0, 10, 0, 0);
            when(marketplaceService.getExtensions(null, "ai", "tool", 1, 10)).thenReturn(paged);

            mockMvc.perform(get("/api/marketplace/extensions")
                            .param("search", "ai")
                            .param("tag", "tool")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content", hasSize(0)));

            verify(marketplaceService).getExtensions(null, "ai", "tool", 1, 10);
        }

        @Test
        void 按类型筛选() throws Exception {
            var pkg = testPackage("pkg-1", "测试 Agent", 50);
            var paged = new MarketplaceService.PagedResult<>(List.of(pkg), 0, 20, 1, 1);
            when(marketplaceService.getExtensions(ExtensionType.AGENT, null, null, 0, 20)).thenReturn(paged);

            mockMvc.perform(get("/api/marketplace/extensions")
                            .param("type", "AGENT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)));

            verify(marketplaceService).getExtensions(ExtensionType.AGENT, null, null, 0, 20);
        }
    }

    // ── GET /api/marketplace/extensions/{id} ──────────────────

    @Nested
    class GetExtension {

        @Test
        void 存在的包_返回200() throws Exception {
            var pkg = testPackage("pkg-1", "测试工具", 50);
            when(marketplaceService.getExtension("pkg-1")).thenReturn(Optional.of(pkg));

            mockMvc.perform(get("/api/marketplace/extensions/pkg-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("pkg-1"))
                    .andExpect(jsonPath("$.name").value("测试工具"));
        }

        @Test
        void 不存在的包_返回404() throws Exception {
            when(marketplaceService.getExtension("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/marketplace/extensions/unknown"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 已安装扩展快照_返回200() throws Exception {
            var installation = new ExtensionInstallation(
                    "pkg-1",
                    ExtensionType.CHANNEL,
                    "测试渠道",
                    "1.0.0",
                    "D:/plugins/pkg-1/channel-plugin.json",
                    "D:/plugins/pkg-1",
                    List.of(new InstalledExtensionAsset(
                            "README",
                            "docs/README.md",
                            "D:/plugins/pkg-1/docs/README.md"
                    ))
            );
            when(marketplaceService.getInstallation("pkg-1")).thenReturn(Optional.of(installation));

            mockMvc.perform(get("/api/marketplace/extensions/pkg-1/installation"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.packageId").value("pkg-1"))
                    .andExpect(jsonPath("$.type").value("CHANNEL"))
                    .andExpect(jsonPath("$.installRootPath").value("D:/plugins/pkg-1"))
                    .andExpect(jsonPath("$.assets", hasSize(1)))
                    .andExpect(jsonPath("$.assets[0].kind").value("README"));
        }

        @Test
        void 已安装扩展资产_返回文件内容() throws Exception {
            var asset = new ExtensionAssetContent(
                    "pkg-1",
                    "docs/README.md",
                    "README",
                    "text/markdown",
                    "# hello".getBytes()
            );
            when(marketplaceService.getInstallationAsset("pkg-1", "docs/README.md")).thenReturn(Optional.of(asset));

            mockMvc.perform(get("/api/marketplace/extensions/pkg-1/assets/file")
                            .param("path", "docs/README.md"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("text/markdown")))
                    .andExpect(content().bytes("# hello".getBytes()));
        }
    }

    // ── POST /api/marketplace/extensions/{id}/install ─────────

    @Nested
    class InstallExtension {

        @Test
        void 安装成功() throws Exception {
            var result = new InstallResult(true, "ext-1", ExtensionType.SKILL, null, null, null, false);
            when(marketplaceService.install("pkg-1", false)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/extensions/pkg-1/install"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.extensionId").value("ext-1"))
                    .andExpect(jsonPath("$.extensionType").value("SKILL"))
                    .andExpect(jsonPath("$.requiresConfirmation").value(false));

            verify(marketplaceService).install("pkg-1", false);
        }

        @Test
        void 高风险需要确认() throws Exception {
            var report = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "危险工具", "包含危险工具 shell_execute")),
                    RiskLevel.HIGH
            );
            var result = new InstallResult(false, null, ExtensionType.SKILL, report, null, null, true);
            when(marketplaceService.install("pkg-2", false)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/extensions/pkg-2/install"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.requiresConfirmation").value(true))
                    .andExpect(jsonPath("$.securityReport.overallRisk").value("HIGH"));
        }

        @Test
        void 确认高风险安装() throws Exception {
            var result = new InstallResult(true, "ext-2", ExtensionType.SKILL, null, null, null, false);
            when(marketplaceService.install("pkg-2", true)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/extensions/pkg-2/install")
                            .param("confirmHighRisk", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).install("pkg-2", true);
        }
    }

    // ── DELETE /api/marketplace/extensions/{id} ────────────────

    @Nested
    class UninstallExtension {

        @Test
        void 卸载成功() throws Exception {
            var result = new InstallResult(true, "ext-1", ExtensionType.SKILL, null, null, null, false);
            when(marketplaceService.uninstall("pkg-1")).thenReturn(result);

            mockMvc.perform(delete("/api/marketplace/extensions/pkg-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).uninstall("pkg-1");
        }

        @Test
        void 卸载不存在的包() throws Exception {
            var result = new InstallResult(false, null, null, null, null, "未找到已安装扩展: unknown", false);
            when(marketplaceService.uninstall("unknown")).thenReturn(result);

            mockMvc.perform(delete("/api/marketplace/extensions/unknown"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorMessage").value("未找到已安装扩展: unknown"));
        }
    }

    // ── POST /api/marketplace/index/refresh ────────────────────

    @Nested
    class RefreshIndex {

        @Test
        void 刷新成功() throws Exception {
            when(marketplaceService.refreshIndex()).thenReturn(2);

            mockMvc.perform(post("/api/marketplace/index/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(2))
                    .andExpect(jsonPath("$.message").value(containsString("2")));

            verify(marketplaceService).refreshIndex();
        }
    }

    // ── GET /api/marketplace/updates ───────────────────────────

    @Nested
    class GetUpdates {

        @Test
        void 有可用更新() throws Exception {
            var pkg = testPackage("pkg-1", "测试工具", 100);
            when(marketplaceService.getUpdates()).thenReturn(List.of(pkg));

            mockMvc.perform(get("/api/marketplace/updates"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value("pkg-1"));
        }

        @Test
        void 无可用更新() throws Exception {
            when(marketplaceService.getUpdates()).thenReturn(List.of());

            mockMvc.perform(get("/api/marketplace/updates"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ── POST /api/marketplace/extensions/{id}/upgrade ──────────

    @Nested
    class UpgradeExtension {

        @Test
        void 升级成功() throws Exception {
            var result = new InstallResult(true, "ext-1", ExtensionType.SKILL, null, null, null, false);
            when(marketplaceService.upgrade("pkg-1", false)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/extensions/pkg-1/upgrade"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).upgrade("pkg-1", false);
        }

        @Test
        void 确认高风险升级() throws Exception {
            var result = new InstallResult(true, "ext-1", ExtensionType.SKILL, null, null, null, false);
            when(marketplaceService.upgrade("pkg-1", true)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/extensions/pkg-1/upgrade")
                            .param("confirmHighRisk", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).upgrade("pkg-1", true);
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────

    private static ExtensionPackage testPackage(String id, String name, int downloads) {
        return ExtensionPackage.builder()
                .id(id)
                .name(name)
                .type(ExtensionType.SKILL)
                .description("测试描述")
                .version("1.0.0")
                .author("test-author")
                .repoUrl("https://github.com/test/repo")
                .filePath("skills/" + id + "/SKILL.md")
                .tags(List.of("tool"))
                .requirements(List.of())
                .minLifepilotVersion("1.0.0")
                .createdAt("2026-01-01T00:00:00Z")
                .updatedAt("2026-01-01T00:00:00Z")
                .downloads(downloads)
                .verified(false)
                .installed(false)
                .build();
    }
}
