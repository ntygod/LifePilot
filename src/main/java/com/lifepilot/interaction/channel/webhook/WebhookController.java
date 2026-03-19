package com.lifepilot.interaction.channel.webhook;

import java.util.Map;

import com.lifepilot.interaction.channel.dingtalk.DingtalkChannelAdapter;
import com.lifepilot.interaction.channel.feishu.FeishuChannelAdapter;
import com.lifepilot.interaction.channel.wecom.WecomChannelAdapter;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一 Webhook 端点控制器，按通道类型分发请求到对应适配器。
 *
 * <p>运行时通过 {@link ChannelConfigProvider} 检查通道启用状态，
 * 支持热加载（Web UI 切换开关后立即生效，无需重启）。
 * 所有端点内部 try-catch，异常时返回平台要求的成功响应，避免平台重试风暴。
 *
 * @author zsg
 * @since 2026-02-26
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WecomChannelAdapter wecomAdapter;
    private final DingtalkChannelAdapter dingtalkAdapter;
    private final FeishuChannelAdapter feishuAdapter;
    private final ChannelConfigProvider configProvider;

    public WebhookController(WecomChannelAdapter wecomAdapter,
                             DingtalkChannelAdapter dingtalkAdapter,
                             FeishuChannelAdapter feishuAdapter,
                             ChannelConfigProvider configProvider) {
        this.wecomAdapter = wecomAdapter;
        this.dingtalkAdapter = dingtalkAdapter;
        this.feishuAdapter = feishuAdapter;
        this.configProvider = configProvider;
    }

    // ── 企业微信 ──────────────────────────────────────────────

    /**
     * 企微 URL 验证（GET）。
     */
    @GetMapping("/wecom")
    public String wecomVerify(@RequestParam Map<String, String> params) {
        if (!configProvider.getWecomConfig().enabled()) {
            log.debug("企微通道未启用，忽略 URL 验证请求");
            return "success";
        }
        try {
            return wecomAdapter.handleVerification(params);
        } catch (Exception e) {
            log.error("企微 URL 验证异常", e);
            return "success";
        }
    }

    /**
     * 企微消息接收（POST）。
     */
    @PostMapping("/wecom")
    public String wecomMessage(@RequestParam Map<String, String> params,
                               @RequestBody String xmlBody) {
        if (!configProvider.getWecomConfig().enabled()) {
            log.debug("企微通道未启用，忽略消息请求");
            return "success";
        }
        try {
            return wecomAdapter.handleMessage(params, xmlBody);
        } catch (Exception e) {
            log.error("企微消息处理异常", e);
            return "success";
        }
    }

    // ── 钉钉 ──────────────────────────────────────────────────

    /**
     * 钉钉消息接收（POST）。
     */
    @PostMapping("/dingtalk")
    public Map<String, Object> dingtalkMessage(@RequestHeader Map<String, String> headers,
                                               @RequestBody String jsonBody) {
        if (!configProvider.getDingtalkConfig().enabled()) {
            log.debug("钉钉通道未启用，忽略消息请求");
            return Map.of();
        }
        try {
            return dingtalkAdapter.handleMessage(headers, jsonBody);
        } catch (Exception e) {
            log.error("钉钉消息处理异常", e);
            return Map.of();
        }
    }

    // ── 飞书 ──────────────────────────────────────────────────

    /**
     * 飞书事件接收（POST）。
     */
    @PostMapping("/feishu")
    public Map<String, Object> feishuEvent(@RequestBody String jsonBody) {
        if (!configProvider.getFeishuConfig().enabled()) {
            log.debug("飞书通道未启用，忽略事件请求");
            return Map.of("code", 0);
        }
        try {
            return feishuAdapter.handleEvent(jsonBody);
        } catch (Exception e) {
            log.error("飞书事件处理异常", e);
            return Map.of("code", 0);
        }
    }
}
