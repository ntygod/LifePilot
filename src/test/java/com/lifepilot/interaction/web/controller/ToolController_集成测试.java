package com.lifepilot.interaction.web.controller;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ToolController HTTP 端点集成测试，使用 MockMvc 验证端点注册和基础行为。
 *
 * <p>测试重点：端点可达性、基础 happy path，以及典型异常分支（如 404 / 409）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ToolController_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configure(@NonNull DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "tool-ctrl-it-" + DB_ID + ".db").toString().replace("\\", "/"));
        registry.add("lifepilot.memory.vector-db-url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "tool-ctrl-it-vec-" + DB_ID + ".db").toString().replace("\\", "/"));

        // 启用 Web 通道和 Tool/Skill/Workflow 模块，以确保 WebAutoConfiguration 注册 ToolController
        registry.add("lifepilot.gateway.channels.web.enabled", () -> "true");
        registry.add("lifepilot.tool.enabled", () -> "true");
        registry.add("lifepilot.skills.enabled", () -> "true");
        registry.add("lifepilot.workflow.enabled", () -> "true");

        // 启用 YAML Tool 持久化（使用 tmp 目录）
        registry.add("lifepilot.tool.yaml.enabled", () -> "true");
        registry.add("lifepilot.tool.yaml.base-dir",
                () -> Path.of(tmpDir, "tool-ctrl-it-yaml-" + DB_ID).toString().replace("\\", "/"));
    }

    @Autowired
    MockMvc mockMvc;

    // ── 基础端点可达性 ──────────────────────────────────────────

    @Test
    void Tool列表端点_可达() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk());
    }

    @Test
    void 不存在的Tool_详情返回404() throws Exception {
        mockMvc.perform(get("/api/tools/non-exists-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void 不存在的Tool_usage返回404() throws Exception {
        mockMvc.perform(get("/api/tools/non-exists-id/usage"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ── 创建 & 查询 YAML Tool ─────────────────────────────────

    @Test
    void 创建YamlTool_成功后可在列表与详情中看到() throws Exception {
        String toolId = "it-test-yaml-tool";

        String requestBody = """
                {
                  "id": "%s",
                  "name": "集成测试 Tool",
                  "description": "用于 ToolController 集成测试的 YAML Tool",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "query": { "type": "string" }
                    },
                    "required": ["query"]
                  },
                  "outputSchema": {
                    "type": "object",
                    "properties": {
                      "result": { "type": "string" }
                    }
                  },
                  "budget": {
                    "timeoutSeconds": 5,
                    "maxRetries": 0,
                    "maxCostCents": 100
                  },
                  "riskLevel": "LOW",
                  "idempotent": true,
                  "tags": ["it", "yaml"]
                }
                """.formatted(toolId);

        // 创建 Tool
        mockMvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(toolId))
                .andExpect(jsonPath("$.name").value("集成测试 Tool"))
                .andExpect(jsonPath("$.source").value("yaml"))
                .andExpect(jsonPath("$.budget.timeoutSeconds").value(5));

        // 列表中可见
        mockMvc.perform(get("/api/tools")
                        .param("source", "yaml")
                        .param("name", "集成测试"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(toolId)));

        // 详情可获取
        mockMvc.perform(get("/api/tools/{id}", toolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(toolId))
                .andExpect(jsonPath("$.inputSchema.type").value("object"))
                .andExpect(jsonPath("$.budget.timeoutSeconds").value(5));
    }

    @Test
    void 创建重复ID的YamlTool_返回409() throws Exception {
        String toolId = "it-dup-yaml-tool";

        String requestBody = """
                {
                  "id": "%s",
                  "name": "重复 Tool 1"
                }
                """.formatted(toolId);

        // 首次创建成功
        mockMvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // 第二次创建同 ID，返回 409
        mockMvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    // ── 更新 & 删除 YAML Tool ─────────────────────────────────

    @Test
    void 更新已存在的YamlTool_成功并可在详情中看到更新字段() throws Exception {
        String toolId = "it-update-yaml-tool";

        // 先创建
        String createBody = """
                {
                  "id": "%s",
                  "name": "待更新 Tool"
                }
                """.formatted(toolId);

        mockMvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        // 更新描述和预算
        String updateBody = """
                {
                  "description": "已更新描述",
                  "budget": {
                    "timeoutSeconds": 60,
                    "maxRetries": 3,
                    "maxCostCents": 9999
                  },
                  "riskLevel": "HIGH",
                  "idempotent": false,
                  "tags": ["updated", "yaml"]
                }
                """;

        mockMvc.perform(put("/api/tools/{id}", toolId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("已更新描述"))
                .andExpect(jsonPath("$.budget.timeoutSeconds").value(60))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.idempotent").value(false))
                .andExpect(jsonPath("$.tags", containsInAnyOrder("updated", "yaml")));
    }

    @Test
    void 删除YamlTool_在未被引用时成功() throws Exception {
        String toolId = "it-delete-yaml-tool";

        // 创建
        String createBody = """
                {
                  "id": "%s",
                  "name": "待删除 Tool"
                }
                """.formatted(toolId);

        mockMvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        // 删除
        mockMvc.perform(delete("/api/tools/{id}", toolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tool 删除成功"));

        // 再次查询应返回 404
        mockMvc.perform(get("/api/tools/{id}", toolId))
                .andExpect(status().isNotFound());
    }

    @Test
    void 启用禁用端点_存在与不存在的Tool均返回合理状态码() throws Exception {
        String toolId = "it-enable-toggle-tool";

        // 先创建
        String createBody = """
                {
                  "id": "%s",
                  "name": "启用禁用 Tool"
                }
                """.formatted(toolId);

        mockMvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        // 启用
        mockMvc.perform(post("/api/tools/{id}/enable", toolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tool 已启用"));

        // 禁用
        mockMvc.perform(post("/api/tools/{id}/disable", toolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tool 已禁用"));

        // 不存在的 ID
        mockMvc.perform(post("/api/tools/{id}/enable", "non-exists-id"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tools/{id}/disable", "non-exists-id"))
                .andExpect(status().isNotFound());
    }
}

