package com.lifepilot.interaction.cli.config;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.interaction.cli.ChatCommand;
import com.lifepilot.interaction.cli.CliConfigProperties;
import com.lifepilot.interaction.cli.CliShell;
import com.lifepilot.interaction.cli.CliUserConfirmationService;
import com.lifepilot.interaction.cli.CommandRouter;
import com.lifepilot.interaction.cli.QuickCommand;
import com.lifepilot.interaction.cli.ResponseRenderer;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

/**
 * CLI 交互层 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.cli.enabled=true}（默认）激活，
 * 注册所有 CLI 交互层核心 Bean。</p>
 *
 * <p>注册的 Bean 包括：
 * <ul>
 *   <li>{@link Terminal} — JLine 3 终端实例，共享给所有 CLI 组件</li>
 *   <li>{@link ResponseRenderer} — 格式化输出渲染器</li>
 *   <li>{@link ChatCommand} — 交互式对话命令</li>
 *   <li>{@link QuickCommand} — 快捷命令分发器</li>
 *   <li>{@link CommandRouter} — 命令路由器</li>
 *   <li>{@link CliUserConfirmationService} — 终端用户确认服务（{@code @Primary} 替换 NoOp 实现）</li>
 *   <li>{@link CliShell} — JLine 3 交互式 Shell 主循环</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.cli.enabled", matchIfMissing = true)
@EnableConfigurationProperties(CliConfigProperties.class)
public class CliAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CliAutoConfiguration.class);

    /**
     * 创建 JLine 3 终端实例。
     *
     * <p>使用系统终端，共享给 {@link ResponseRenderer}、
     * {@link CliUserConfirmationService} 和 {@link CliShell}。</p>
     *
     * @return JLine Terminal 实例
     * @throws IOException 终端创建失败时抛出
     */
    @Bean(destroyMethod = "close")
    public Terminal cliTerminal() throws IOException {
        log.info("CLI 终端初始化");
        return TerminalBuilder.builder()
                .system(true)
                .build();
    }

    /**
     * 创建响应渲染器。
     *
     * @param terminal JLine 终端实例
     * @return 响应渲染器
     */
    @Bean
    public ResponseRenderer responseRenderer(Terminal terminal) {
        return new ResponseRenderer(terminal);
    }

    /**
     * 创建交互式对话命令。
     *
     * @param agentLoop Agent 核心控制循环
     * @return 对话命令
     */
    @Bean
    public ChatCommand chatCommand(AgentLoop agentLoop) {
        return new ChatCommand(agentLoop);
    }

    /**
     * 创建快捷命令分发器。
     *
     * @param toolRegistry      动态工具注册中心
     * @param providerRegistry  LLM Provider 注册表
     * @param mcpServerRegistry MCP Server 注册中心
     * @param skillRegistry     Skill 注册中心
     * @return 快捷命令分发器
     */
    @Bean
    public QuickCommand quickCommand(DynamicToolRegistry toolRegistry,
                                     ProviderRegistry providerRegistry,
                                     McpServerRegistry mcpServerRegistry,
                                     SkillRegistry skillRegistry) {
        return new QuickCommand(toolRegistry, providerRegistry, mcpServerRegistry, skillRegistry);
    }

    /**
     * 创建命令路由器。
     *
     * @param chatCommand  对话命令
     * @param quickCommand 快捷命令
     * @return 命令路由器
     */
    @Bean
    public CommandRouter commandRouter(ChatCommand chatCommand, QuickCommand quickCommand) {
        return new CommandRouter(chatCommand, quickCommand);
    }

    /**
     * 创建 CLI 用户确认服务。
     *
     * <p>使用 {@code @Primary} 替换 {@code NoOpUserConfirmationService}，
     * 在 CLI 模式下通过终端请求用户确认高风险工具执行。</p>
     *
     * @param terminal JLine 终端实例
     * @param config   CLI 配置属性
     * @return CLI 用户确认服务
     */
    @Bean
    @Primary
    public UserConfirmationService cliUserConfirmationService(Terminal terminal,
                                                              CliConfigProperties config) {
        log.info("CLI 用户确认服务已注册，替换 NoOp 实现");
        return new CliUserConfirmationService(terminal, config);
    }

    /**
     * 创建 CLI Shell 主循环。
     *
     * @param commandRouter 命令路由器
     * @param config        CLI 配置属性
     * @param terminal      JLine 终端实例
     * @param renderer      响应渲染器
     * @return CLI Shell
     */
    @Bean
    public CliShell cliShell(CommandRouter commandRouter,
                             CliConfigProperties config,
                             Terminal terminal,
                             ResponseRenderer renderer) {
        log.info("CLI Shell 初始化完成");
        return new CliShell(commandRouter, config, terminal, renderer);
    }
}
