package com.lifepilot.interaction.channel;

import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.cli.ResponseRenderer;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI 通道适配器，桥接 CLI 交互层到 Gateway 管道。
 *
 * <p>将用户输入字符串标准化为 {@link GatewayMessage}（{@code /} 开头为命令消息，
 * 否则为文本消息），通过 {@link MessageGateway#process} 推入中间件管道，
 * 最后通过 {@link ResponseRenderer} 渲染响应。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class CliChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(CliChannelAdapter.class);

    private final MessageGateway gateway;
    private final ResponseRenderer renderer;
    private final Terminal terminal;

    public CliChannelAdapter(MessageGateway gateway, ResponseRenderer renderer, Terminal terminal) {
        this.gateway = gateway;
        this.renderer = renderer;
        this.terminal = terminal;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.CLI;
    }

    @Override
    public GatewayMessage normalize(Object rawMessage) {
        String input = (String) rawMessage;
        MessageContent content;
        if (input.startsWith("/")) {
            content = MessageContent.CommandMessage.parse(input);
        } else {
            content = new MessageContent.TextMessage(input);
        }
        return GatewayMessage.builder()
                .channelType(ChannelType.CLI)
                .userId("cli-user")
                .sessionId("cli-session")
                .content(content)
                .channelMetadata(new ChannelMetadata.CliMetadata(
                        System.getProperty("os.name"),
                        terminal.getWidth(),
                        terminal.getType() != null))
                .build();
    }

    @Override
    public void sendResponse(String userId, GatewayResponse response) {
        if (response.isSuccess()) {
            renderer.info(response.content().toPlainText());
        } else {
            renderer.error(response.errorMessage() != null
                    ? response.errorMessage() : response.content().toPlainText());
        }
    }

    /**
     * 处理用户输入：normalize → gateway.process() → sendResponse()。
     *
     * @param userInput 用户输入文本
     */
    public void handleInput(String userInput) {
        var message = normalize(userInput);
        var response = gateway.process(message);
        sendResponse(message.userId(), response);
    }

    @Override
    public void start() {
        log.info("CLI 通道适配器已启动");
    }

    @Override
    public void stop() {
        log.info("CLI 通道适配器已停止");
    }
}
