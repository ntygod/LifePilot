package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.ResponseContent.CardContent;
import com.lifepilot.interaction.model.ResponseContent.CardContent.CardAction;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.tool.ToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 渠道权限审批服务 — 通过飞书/钉钉/企微卡片交互完成工具授权审批。
 *
 * <p>流程：
 * <ol>
 *   <li>向用户发送带"允许/拒绝"按钮的交互卡片</li>
 *   <li>用户点击按钮 → connector 回调 card_action 事件</li>
 *   <li>{@link ChannelRuntimeIngressService} 拦截 {@code permission_approval:*} 事件，
 *       调用 {@link #resolveApproval(String, boolean)} 完成 Future</li>
 *   <li>Agent 线程从阻塞中恢复，继续执行</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-04-15
 */
public class ChannelPermissionApprovalService implements PermissionApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ChannelPermissionApprovalService.class);

    /** 卡片回调事件名前缀，用于在入站时识别审批回调。 */
    public static final String CALLBACK_EVENT_PREFIX = "permission_approval:";

    private final ChannelDeliveryDispatcher deliveryDispatcher;
    private final ChannelInstanceService channelInstanceService;
    private final ChannelUserMappingCache userMappingCache;
    private final long approvalTimeoutSeconds;

    /** requestId → 审批结果 Future。 */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    public ChannelPermissionApprovalService(ChannelDeliveryDispatcher deliveryDispatcher,
                                            ChannelInstanceService channelInstanceService,
                                            ChannelUserMappingCache userMappingCache,
                                            long approvalTimeoutSeconds) {
        this.deliveryDispatcher = deliveryDispatcher;
        this.channelInstanceService = channelInstanceService;
        this.userMappingCache = userMappingCache;
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
    }

    @Override
    @Nullable
    public ExecutionGrant requestApproval(ToolContract tool, PermissionRequest request,
                                          @Nullable Map<String, String> approvalContext) {
        if (approvalContext == null) {
            return null;
        }
        String channelInstanceId = approvalContext.get("channelInstanceId");
        String userId = approvalContext.get("userId");
        if (channelInstanceId == null || channelInstanceId.isBlank()
                || userId == null || userId.isBlank()) {
            log.debug("审批上下文缺少渠道信息，渠道审批不适用: toolId={}", tool.id());
            return null;
        }

        // 校验渠道实例存在且活跃
        var instance = channelInstanceService.find(channelInstanceId).orElse(null);
        if (instance == null || !instance.enabled()) {
            log.warn("渠道实例不可用，无法发起审批: instanceId={}", channelInstanceId);
            return null;
        }

        // 解析平台用户 ID（内部 userId 可能是 "default"，需要转为飞书 ou_xxx 等）
        String platformUserId = userMappingCache.resolve(channelInstanceId);
        if (platformUserId == null || platformUserId.isBlank()) {
            // 回退：内部 userId 本身可能就是平台格式
            platformUserId = "default".equals(userId) ? null : userId;
        }
        if (platformUserId == null || platformUserId.isBlank()) {
            log.warn("无法解析平台用户 ID，渠道审批不可用: instanceId={}, userId={}", channelInstanceId, userId);
            return null;
        }

        String requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<Boolean>();
        pendingApprovals.put(requestId, future);

        String sessionId = approvalContext.get("sessionId");

        try {
            // 发送审批卡片
            sendApprovalCard(instance, platformUserId, sessionId, tool, request, requestId);
            log.info("渠道权限审批卡片已发送: requestId={}, toolId={}, instanceId={}, platformUserId={}",
                    requestId, tool.id(), channelInstanceId, platformUserId);

            // 阻塞等待用户响应
            boolean approved = future.get(approvalTimeoutSeconds, TimeUnit.SECONDS);
            if (!approved) {
                log.info("渠道权限审批被拒绝: requestId={}, toolId={}", requestId, tool.id());
                return null;
            }

            // 用户批准 — 创建会话级授权（渠道审批不支持细粒度范围选择，默认会话级）
            log.info("渠道权限审批通过: requestId={}, toolId={}", requestId, tool.id());
            return ExecutionGrant.sessionScoped(
                    request.sessionId(),
                    request.actionType(),
                    request.riskLevel(),
                    request.userId(),
                    "渠道卡片审批通过"
            );

        } catch (TimeoutException e) {
            log.info("渠道权限审批超时: requestId={}, toolId={}", requestId, tool.id());
            return null;
        } catch (Exception e) {
            log.error("渠道权限审批异常: requestId={}, toolId={}", requestId, tool.id(), e);
            return null;
        } finally {
            pendingApprovals.remove(requestId);
        }
    }

    /**
     * 解析审批回调 — 由 {@link ChannelRuntimeIngressService} 在拦截到卡片回调时调用。
     *
     * @param requestId 审批请求 ID
     * @param approved  用户是否批准
     * @return true 表示成功匹配到待处理的审批请求
     */
    public boolean resolveApproval(String requestId, boolean approved) {
        var future = pendingApprovals.remove(requestId);
        if (future == null) {
            log.debug("渠道审批请求不存在或已过期: requestId={}", requestId);
            return false;
        }
        future.complete(approved);
        return true;
    }

    /**
     * 通过渠道发送审批卡片。
     */
    private void sendApprovalCard(ChannelInstance instance,
                                  String platformUserId,
                                  @Nullable String sessionId,
                                  ToolContract tool,
                                  PermissionRequest request,
                                  String requestId) {
        String title = "🔐 工具授权请求";
        String body = buildApprovalBody(tool, request);

        var approveAction = CardAction.callback(
                "✅ 允许",
                CALLBACK_EVENT_PREFIX + requestId + ":approved"
        );
        var rejectAction = CardAction.callback(
                "❌ 拒绝",
                CALLBACK_EVENT_PREFIX + requestId + ":rejected"
        );

        var cardContent = new CardContent(title, body, List.of(approveAction, rejectAction));

        // Target 同时传 userId 和 sessionId — 飞书 connector 用 chat_id(oc_xxx) 作为 receive_id
        var deliveryRequest = new ChannelRuntimeDeliveryRequest(
                instance.instanceId(),
                requestId,
                DeliveryMode.ASYNC_PUSH,
                new ChannelRuntimeDeliveryRequest.Target(platformUserId, sessionId, Map.of()),
                deliveryDispatcher.buildContent(cardContent),
                List.of(),
                Map.of("permissionApproval", true, "requestId", requestId)
        );

        deliveryDispatcher.deliver(instance, deliveryRequest);
    }

    private String buildApprovalBody(ToolContract tool, PermissionRequest request) {
        String toolName = tool.name() != null ? tool.name() : tool.id();
        String action = switch (request.actionType()) {
            case EXECUTE_SHELL -> "执行本地命令";
            case WRITE_FILE -> "修改文件";
            case DELETE_FILE -> "删除文件";
            case HTTP_REQUEST -> "访问外部网络";
            case BROWSER_AUTOMATION -> "浏览器自动化";
            case WRITE_MEMORY -> "写入长期记忆";
            case CREATE_SCHEDULE -> "创建定时任务";
            default -> "执行工具操作";
        };
        return "工具 **%s** 请求 %s 权限\n风险等级: %s".formatted(toolName, action, request.riskLevel());
    }

    /**
     * 判断事件名是否为权限审批回调。
     */
    public static boolean isApprovalCallback(String eventName) {
        return eventName != null && eventName.startsWith(CALLBACK_EVENT_PREFIX);
    }

    /**
     * 从回调事件名中提取 requestId 和决策。
     *
     * @param eventName 格式: {@code permission_approval:{requestId}:approved} 或 {@code permission_approval:{requestId}:rejected}
     * @return [requestId, "approved"/"rejected"]，格式非法时返回 null
     */
    @Nullable
    public static String[] parseCallback(String eventName) {
        if (!isApprovalCallback(eventName)) return null;
        String rest = eventName.substring(CALLBACK_EVENT_PREFIX.length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon <= 0) return null;
        return new String[]{rest.substring(0, lastColon), rest.substring(lastColon + 1)};
    }
}
