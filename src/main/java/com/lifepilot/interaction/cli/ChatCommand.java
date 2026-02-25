package com.lifepilot.interaction.cli;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 交互式对话命令 — 维持多轮对话会话。
 *
 * <p>调用 AgentLoop 处理用户消息，支持流式响应输出、
 * 会话管理（/new 新建、/exit 退出）和异常处理。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ChatCommand {

    private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

    private static final String PROMPT = "lifepilot> ";
    private static final String CHANNEL = "cli";

    private final AgentLoop agentLoop;

    /**
     * 创建交互式对话命令。
     *
     * @param agentLoop Agent 核心控制循环
     */
    public ChatCommand(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * 启动对话会话。
     *
     * <p>生成 UUID 作为 sessionId，进入读取-处理循环。
     * 支持 /exit、/quit 退出，/new 创建新会话，Ctrl+D 优雅退出。
     * AgentLoop 异常时捕获并输出错误信息，保持会话可继续。</p>
     *
     * @param lineReader JLine LineReader
     * @param renderer   响应渲染器
     */
    public void startSession(LineReader lineReader, ResponseRenderer renderer) {
        String sessionId = UUID.randomUUID().toString();
        renderer.info("欢迎使用 LifePilot 对话模式！输入 /exit 退出，/new 开始新会话。");
        log.info("对话会话已启动: sessionId={}", sessionId);

        while (true) {
            String line;
            try {
                line = lineReader.readLine(PROMPT);
            } catch (UserInterruptException e) {
                // Ctrl+C：提示用户如何退出
                renderer.info("输入 /exit 退出");
                continue;
            } catch (EndOfFileException e) {
                // Ctrl+D：优雅退出
                log.info("对话会话结束（Ctrl+D）: sessionId={}", sessionId);
                renderer.info("再见！");
                break;
            }

            // 跳过空行
            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }

            // 处理特殊命令
            if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                log.info("对话会话结束: sessionId={}", sessionId);
                renderer.info("再见！");
                break;
            }

            if ("/new".equalsIgnoreCase(input)) {
                sessionId = UUID.randomUUID().toString();
                renderer.info("新会话已创建: " + sessionId);
                log.info("新对话会话: sessionId={}", sessionId);
                continue;
            }

            // 构造请求并调用 AgentLoop
            try {
                AgentRequest request = new AgentRequest(input, sessionId, CHANNEL);
                AgentResponse response = agentLoop.run(request);
                renderer.info(response.content());
            } catch (Exception e) {
                log.warn("AgentLoop 执行异常: sessionId={}, error={}", sessionId, e.getMessage());
                renderer.error("处理请求时发生错误: " + e.getMessage());
            }
        }
    }
}
