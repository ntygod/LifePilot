package com.lifepilot.interaction.web.controller;

import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SkillController 单元测试 — 验证 REST API 适配新 SkillDefinition 字段。
 *
 * @author zsg
 * @since 2026-07-28
 */
@ExtendWith(MockitoExtension.class)
class SkillControllerTest {

    @Mock private SkillRegistry skillRegistry;
    @Mock private McpServerRegistry mcpServerRegistry;
    @Mock private DynamicToolRegistry toolRegistry;
    @Mock private MarkdownSkillParser markdownParser;
    @Mock private MarkdownSkillSerializer markdownSerializer;
    @Mock private MarkdownSkillLoader markdownLoader;

    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var config = new SkillConfigProperties();
        config.setDirectory(tempDir.toString());
        config.setSkillFilename("SKILL.md");

        var controller = new SkillController(
                skillRegistry, mcpServerRegistry, toolRegistry,
                markdownParser, markdownSerializer, markdownLoader, config);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private static SkillDefinition testSkill(String id, String name, List<String> suggestedTools) {
        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(name + " 描述")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("测试指令内容")
                .suggestedTools(suggestedTools)
                .metadata(Map.of())
                .build();
    }

    // ── GET /api/skills ───────────────────────────────────

    @Nested
    class ListSkills {

        @Test
        void 按suggestedTools筛选_包含指定工具() throws Exception {
            var skill1 = testSkill("s1", "Skill1", List.of("tool-a", "tool-b"));
            var skill2 = testSkill("s2", "Skill2", List.of("tool-c"));
            when(skillRegistry.listAll()).thenReturn(List.of(skill1, skill2));

            mockMvc.perform(get("/api/skills").param("toolName", "tool-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value("s1"));
        }

        @Test
        void 按suggestedTools筛选_无匹配返回空() throws Exception {
            var skill = testSkill("s1", "Skill1", List.of("tool-x"));
            when(skillRegistry.listAll()).thenReturn(List.of(skill));

            mockMvc.perform(get("/api/skills").param("toolName", "tool-not-exist"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void 无筛选参数_返回全部() throws Exception {
            var skill1 = testSkill("s1", "Skill1", List.of());
            var skill2 = testSkill("s2", "Skill2", List.of("tool-a"));
            when(skillRegistry.listAll()).thenReturn(List.of(skill1, skill2));

            mockMvc.perform(get("/api/skills"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    // ── POST /api/skills（结构化 JSON 路径） ──────────────

    @Nested
    class CreateSkill {

        @Test
        void 结构化JSON创建_使用instructions和suggestedTools() throws Exception {
            when(skillRegistry.find("test-skill")).thenReturn(Optional.empty());
            when(markdownSerializer.serialize(any())).thenReturn("---\nid: test-skill\n---\n指令");
            when(skillRegistry.register(any())).thenReturn(true);

            mockMvc.perform(post("/api/skills")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "id": "test-skill",
                                    "name": "测试技能",
                                    "description": "测试描述",
                                    "instructions": "这是指令内容",
                                    "suggestedTools": ["tool-a", "tool-b"],
                                    "metadata": {}
                                }
                                """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value("test-skill"))
                    .andExpect(jsonPath("$.instructions").value("这是指令内容"))
                    .andExpect(jsonPath("$.suggestedTools", hasSize(2)))
                    .andExpect(jsonPath("$.suggestedTools[0]").value("tool-a"));

            verify(skillRegistry).register(any(SkillDefinition.class));
        }

        @Test
        void 缺少instructions_返回400() throws Exception {
            mockMvc.perform(post("/api/skills")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "id": "test-skill",
                                    "name": "测试技能"
                                }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("instructions 不能为空"));
        }
    }

    // ── PUT /api/skills/{id}（结构化 JSON 路径） ──────────

    @Nested
    class UpdateSkill {

        @Test
        void 结构化JSON更新_使用instructions和suggestedTools() throws Exception {
            var existing = SkillDefinition.builder()
                    .id("s1")
                    .name("旧名称")
                    .description("旧描述")
                    .version("1.0.0")
                    .source(new SkillSource.UserDefined(tempDir.resolve("s1").toString()))
                    .instructions("旧指令")
                    .suggestedTools(List.of("old-tool"))
                    .metadata(Map.of())
                    .build();

            when(skillRegistry.find("s1")).thenReturn(Optional.of(existing));
            when(markdownSerializer.serialize(any())).thenReturn("---\nid: s1\n---\n新指令");
            when(skillRegistry.register(any())).thenReturn(true);

            mockMvc.perform(put("/api/skills/s1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "instructions": "新指令内容",
                                    "suggestedTools": ["new-tool-a", "new-tool-b"]
                                }
                                """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("s1"))
                    .andExpect(jsonPath("$.instructions").value("新指令内容"))
                    .andExpect(jsonPath("$.suggestedTools", hasSize(2)))
                    .andExpect(jsonPath("$.suggestedTools[0]").value("new-tool-a"));
        }

        @Test
        void 更新不存在的Skill_返回404() throws Exception {
            when(skillRegistry.find("not-exist")).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/skills/not-exist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "instructions": "新指令" }
                                """))
                    .andExpect(status().isNotFound());
        }
    }
}
