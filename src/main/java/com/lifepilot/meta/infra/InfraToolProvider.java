package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.env.DateTimeToolExecutor;
import com.lifepilot.meta.infra.env.SystemInfoToolExecutor;
import com.lifepilot.meta.infra.env.UserProfileToolExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.*;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 基础工具提供者 — 注册 Infrastructure Tool 到 DynamicToolRegistry。
 *
 * <p>所有基础工具 tags 含 {@code "infrastructure"}，始终对所有调用者可用。
 * 当前注册环境感知工具（3 个），后续任务将逐步添加其他类别工具。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
@BuiltinSkill(id = "builtin.infrastructure", order = 1)
public class InfraToolProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(InfraToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final MetaProperties properties;
    @Nullable
    private final Object sandboxBooter;
    @Nullable
    private final Object interactionBridge;

    public InfraToolProvider(MetaProperties properties,
                             @Nullable Object sandboxBooter,
                             @Nullable Object interactionBridge) {
        this.properties = properties;
        this.sandboxBooter = sandboxBooter;
        this.interactionBridge = interactionBridge;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("builtin.infrastructure")
                .name("基础工具集")
                .description("Agent 通用执行能力工具集，包含环境感知、信息获取、推理辅助、Shell 执行、浏览器自动化、代码执行、文件系统和交互控制")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt("基础工具集提供 Agent 的通用执行能力，无需额外激活即可使用。")
                .allowedTools(List.of(
                        "builtin.env.datetime",
                        "builtin.env.user-profile",
                        "builtin.env.system-info"
                ))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.LIGHTWEIGHT)
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        // 环境感知工具（3 个）
        var dateTimeExecutor = new DateTimeToolExecutor(properties);
        var userProfileExecutor = new UserProfileToolExecutor(properties);
        var systemInfoExecutor = new SystemInfoToolExecutor();

        toolRegistry.registerBuiltinTool(buildDateTimeTool(dateTimeExecutor));
        toolRegistry.registerBuiltinTool(buildUserProfileTool(userProfileExecutor));
        toolRegistry.registerBuiltinTool(buildSystemInfoTool(systemInfoExecutor));

        log.info("基础工具注册完成: count=3, category=env");
    }

    // ─────────────────────────────────────────────
    //  环境感知工具构建
    // ─────────────────────────────────────────────

    /** 构建日期时间工具。 */
    private BuiltinTool buildDateTimeTool(DateTimeToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.datetime")
                .name("获取当前日期时间")
                .description("获取当前日期、时间、星期和时区信息，可选覆盖时区")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timezone", Map.of("type", "string",
                                        "description", "时区 ID（如 Asia/Shanghai），不传则使用用户配置或系统时区")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建用户画像工具。 */
    private BuiltinTool buildUserProfileTool(UserProfileToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.user-profile")
                .name("获取用户偏好")
                .description("获取用户偏好配置，包括时区、缓存 TTL 等信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建系统信息工具。 */
    private BuiltinTool buildSystemInfoTool(SystemInfoToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.system-info")
                .name("获取系统信息")
                .description("获取操作系统、JVM 版本、可用内存和磁盘空间等系统信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }
}
