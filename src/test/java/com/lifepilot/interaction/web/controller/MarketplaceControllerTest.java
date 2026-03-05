package com.lifepilot.interaction.web.controller;

import com.lifepilot.skill.marketplace.MarketplaceService;
import com.lifepilot.skill.marketplace.model.InstallResult;
import com.lifepilot.skill.marketplace.model.RiskLevel;
import com.lifepilot.skill.marketplace.model.SecurityFinding;
import com.lifepilot.skill.marketplace.model.SecurityReport;
import com.lifepilot.skill.marketplace.model.SkillPackage;
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

    // ── GET /api/marketplace/skills ────────────────────────────

    @Nested
    class ListSkills {

        @Test
        void 无过滤条件_返回分页结果() throws Exception {
            var pkg = testPackage("pkg-1", "测试工具", 100);
            var paged = new MarketplaceService.PagedResult<>(List.of(pkg), 0, 20, 1, 1);
            when(marketplaceService.getSkills(null, null, 0, 20)).thenReturn(paged);

            mockMvc.perform(get("/api/marketplace/skills"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value("pkg-1"));

            verify(marketplaceService).getSkills(null, null, 0, 20);
        }

        @Test
        void 带搜索和标签参数() throws Exception {
            var paged = new MarketplaceService.PagedResult<SkillPackage>(List.of(), 0, 10, 0, 0);
            when(marketplaceService.getSkills("ai", "tool", 1, 10)).thenReturn(paged);

            mockMvc.perform(get("/api/marketplace/skills")
                            .param("search", "ai")
                            .param("tag", "tool")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content", hasSize(0)));

            verify(marketplaceService).getSkills("ai", "tool", 1, 10);
        }
    }

    // ── GET /api/marketplace/skills/{id} ──────────────────────

    @Nested
    class GetSkill {

        @Test
        void 存在的包_返回200() throws Exception {
            var pkg = testPackage("pkg-1", "测试工具", 50);
            when(marketplaceService.getSkill("pkg-1")).thenReturn(Optional.of(pkg));

            mockMvc.perform(get("/api/marketplace/skills/pkg-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("pkg-1"))
                    .andExpect(jsonPath("$.name").value("测试工具"));
        }

        @Test
        void 不存在的包_返回404() throws Exception {
            when(marketplaceService.getSkill("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/marketplace/skills/unknown"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── POST /api/marketplace/skills/{id}/install ─────────────

    @Nested
    class InstallSkill {

        @Test
        void 安装成功() throws Exception {
            var result = new InstallResult(true, "skill-1", null, null, false);
            when(marketplaceService.install("pkg-1", false)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/skills/pkg-1/install"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.skillId").value("skill-1"))
                    .andExpect(jsonPath("$.requiresConfirmation").value(false));

            verify(marketplaceService).install("pkg-1", false);
        }

        @Test
        void 高风险需要确认() throws Exception {
            var report = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "dangerous-tool", "包含危险工具 shell_execute")),
                    RiskLevel.HIGH
            );
            var result = new InstallResult(false, null, report, null, true);
            when(marketplaceService.install("pkg-2", false)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/skills/pkg-2/install"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.requiresConfirmation").value(true))
                    .andExpect(jsonPath("$.securityReport.overallRisk").value("HIGH"));
        }

        @Test
        void 确认高风险安装() throws Exception {
            var result = new InstallResult(true, "skill-2", null, null, false);
            when(marketplaceService.install("pkg-2", true)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/skills/pkg-2/install")
                            .param("confirmHighRisk", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).install("pkg-2", true);
        }
    }

    // ── DELETE /api/marketplace/skills/{id} ────────────────────

    @Nested
    class UninstallSkill {

        @Test
        void 卸载成功() throws Exception {
            var result = new InstallResult(true, "skill-1", null, null, false);
            when(marketplaceService.uninstall("pkg-1")).thenReturn(result);

            mockMvc.perform(delete("/api/marketplace/skills/pkg-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).uninstall("pkg-1");
        }

        @Test
        void 卸载不存在的包() throws Exception {
            var result = new InstallResult(false, null, null, "未找到已安装的 Skill: unknown", false);
            when(marketplaceService.uninstall("unknown")).thenReturn(result);

            mockMvc.perform(delete("/api/marketplace/skills/unknown"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorMessage").value("未找到已安装的 Skill: unknown"));
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

    // ── POST /api/marketplace/skills/{id}/upgrade ──────────────

    @Nested
    class UpgradeSkill {

        @Test
        void 升级成功() throws Exception {
            var result = new InstallResult(true, "skill-1", null, null, false);
            when(marketplaceService.upgrade("pkg-1", false)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/skills/pkg-1/upgrade"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).upgrade("pkg-1", false);
        }

        @Test
        void 确认高风险升级() throws Exception {
            var result = new InstallResult(true, "skill-1", null, null, false);
            when(marketplaceService.upgrade("pkg-1", true)).thenReturn(result);

            mockMvc.perform(post("/api/marketplace/skills/pkg-1/upgrade")
                            .param("confirmHighRisk", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(marketplaceService).upgrade("pkg-1", true);
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────

    private static SkillPackage testPackage(String id, String name, int downloads) {
        return SkillPackage.builder()
                .id(id)
                .name(name)
                .description("测试描述")
                .version("1.0.0")
                .author("test-author")
                .repoUrl("https://github.com/test/repo")
                .filePath("skills/" + id + ".yaml")
                .tags(List.of("tool"))
                .downloads(downloads)
                .verified(false)
                .installed(false)
                .build();
    }
}
