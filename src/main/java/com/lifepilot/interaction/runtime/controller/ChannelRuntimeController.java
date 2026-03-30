package com.lifepilot.interaction.runtime.controller;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.runtime.ChannelRuntimeIngressService;
import com.lifepilot.interaction.runtime.ChannelRuntimeProtocol;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventResponse;
import com.lifepilot.interaction.service.ChannelInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 渠道运行时统一入口。
 *
 * <p>供外部 connector 通过实例级 token 调用，统一上报事件与心跳。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
@RestController
@RequestMapping("/api/channel-runtime")
@ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true)
public class ChannelRuntimeController {

    private static final Logger log = LoggerFactory.getLogger(ChannelRuntimeController.class);

    private final ChannelInstanceService channelInstanceService;
    private final ChannelRuntimeIngressService channelRuntimeIngressService;

    public ChannelRuntimeController(ChannelInstanceService channelInstanceService,
                                    ChannelRuntimeIngressService channelRuntimeIngressService) {
        this.channelInstanceService = channelInstanceService;
        this.channelRuntimeIngressService = channelRuntimeIngressService;
    }

    @PostMapping("/instances/{instanceId}/events")
    public ResponseEntity<?> submitEvent(@PathVariable String instanceId,
                                         @RequestHeader(name = ChannelRuntimeProtocol.HEADER_INSTANCE_TOKEN,
                                                 required = false) @Nullable String instanceToken,
                                         @RequestBody ChannelRuntimeEventRequest request) {
        Optional<ResponseEntity<?>> authFailure = authenticate(instanceId, instanceToken);
        if (authFailure.isPresent()) {
            return authFailure.get();
        }
        try {
            ChannelRuntimeEventResponse response = channelRuntimeIngressService.processEvent(instanceId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("渠道运行时事件请求无效: instanceId={}, error={}", instanceId, e.getMessage());
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.error("渠道运行时事件处理异常: instanceId={}", instanceId, e);
            return ResponseEntity.internalServerError().body(errorBody("渠道运行时处理失败"));
        }
    }

    @PostMapping("/instances/{instanceId}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String instanceId,
                                       @RequestHeader(name = ChannelRuntimeProtocol.HEADER_INSTANCE_TOKEN,
                                               required = false) @Nullable String instanceToken) {
        Optional<ResponseEntity<?>> authFailure = authenticate(instanceId, instanceToken);
        if (authFailure.isPresent()) {
            return authFailure.get();
        }
        try {
            ChannelInstance instance = channelRuntimeIngressService.recordHeartbeat(instanceId);
            return ResponseEntity.ok(instance);
        } catch (IllegalArgumentException e) {
            log.warn("渠道运行时心跳请求无效: instanceId={}, error={}", instanceId, e.getMessage());
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.error("渠道运行时心跳处理异常: instanceId={}", instanceId, e);
            return ResponseEntity.internalServerError().body(errorBody("渠道运行时心跳处理失败"));
        }
    }

    private Optional<ResponseEntity<?>> authenticate(String instanceId, @Nullable String instanceToken) {
        ChannelInstance instance = channelInstanceService.find(instanceId).orElse(null);
        if (instance == null) {
            return Optional.of(ResponseEntity.notFound().build());
        }
        if (instanceToken == null || instanceToken.isBlank()) {
            return Optional.of(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("缺少实例令牌")));
        }
        String expectedToken = resolveInstanceToken(instance);
        if (expectedToken == null || expectedToken.isBlank()) {
            return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("渠道实例未配置运行时令牌")));
        }
        if (!expectedToken.equals(instanceToken)) {
            return Optional.of(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("实例令牌无效")));
        }
        return Optional.empty();
    }

    @Nullable
    private String resolveInstanceToken(ChannelInstance instance) {
        if (instance.secretConfig() == null) {
            return null;
        }
        Object raw = instance.secretConfig().get(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY);
        return raw instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    private Map<String, Object> errorBody(String message) {
        return Map.of("error", message);
    }
}
