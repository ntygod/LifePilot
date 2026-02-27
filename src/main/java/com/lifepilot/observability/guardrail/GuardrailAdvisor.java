package com.lifepilot.observability.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.Ordered;

/**
 * 护栏 Advisor — 通过 Spring AI Advisor 模式横切注入内容安全检查。
 *
 * <p>在 adviseCall 中：
 * <ul>
 *   <li>请求阶段：调用 {@link GuardrailEngine#checkInput} 检查用户输入内容安全</li>
 *   <li>响应阶段：调用 {@link GuardrailEngine#checkOutput} 检查 LLM 输出内容合规</li>
 * </ul>
 *
 * <p>Blocked 结果抛出 {@link GuardrailBlockedException}，
 * NeedsConfirmation 结果抛出 {@link GuardrailConfirmationRequiredException}。</p>
 *
 * <p>优先级设为 {@code HIGHEST_PRECEDENCE}，在所有其他 Advisor 之前执行。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class GuardrailAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);

    private final GuardrailEngine guardrailEngine;

    public GuardrailAdvisor(GuardrailEngine guardrailEngine) {
        this.guardrailEngine = guardrailEngine;
    }

    @Override
    public String getName() {
        return "GuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 请求阶段：检查用户输入内容安全
        checkInputSafety(request);

        // 执行 LLM 调用
        var response = chain.nextCall(request);

        // 响应阶段：检查 LLM 输出内容合规
        checkOutputCompliance(response);

        return response;
    }

    /**
     * 检查请求中的用户输入内容安全。
     */
    private void checkInputSafety(ChatClientRequest request) {
        try {
            // 提取用户消息内容
            var prompt = request.prompt();
            if (prompt == null || prompt.getInstructions() == null) {
                return;
            }

            var userContent = prompt.getInstructions().stream()
                    .filter(msg -> msg.getMessageType() == MessageType.USER)
                    .map(msg -> msg.getText())
                    .reduce("", (a, b) -> a + " " + b)
                    .trim();

            if (userContent.isEmpty()) {
                return;
            }

            var result = guardrailEngine.checkInput(userContent);
            handleResult(result);

        } catch (GuardrailBlockedException | GuardrailConfirmationRequiredException e) {
            throw e;
        } catch (Exception e) {
            // fail-open：检查异常不阻塞请求
            log.warn("输入内容安全检查异常（fail-open）: error={}", e.getMessage());
        }
    }

    /**
     * 检查 LLM 输出内容合规。
     */
    private void checkOutputCompliance(ChatClientResponse response) {
        try {
            if (response == null || response.chatResponse() == null) {
                return;
            }

            var chatResponse = response.chatResponse();
            if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                return;
            }

            var outputText = chatResponse.getResult().getOutput().getText();
            if (outputText == null || outputText.isEmpty()) {
                return;
            }

            var result = guardrailEngine.checkOutput(outputText);
            handleResult(result);

        } catch (GuardrailBlockedException | GuardrailConfirmationRequiredException e) {
            throw e;
        } catch (Exception e) {
            // fail-open：检查异常不阻塞响应
            log.warn("输出内容合规检查异常（fail-open）: error={}", e.getMessage());
        }
    }

    /**
     * 处理护栏检查结果，Blocked 和 NeedsConfirmation 抛出对应异常。
     */
    private void handleResult(GuardrailResult result) {
        switch (result) {
            case GuardrailResult.Passed _ -> { /* 通过，无需处理 */ }
            case GuardrailResult.Blocked blocked -> throw new GuardrailBlockedException(
                    blocked.policyId(), blocked.reason(), blocked.riskLevel());
            case GuardrailResult.NeedsConfirmation confirm -> throw new GuardrailConfirmationRequiredException(
                    confirm.policyId(), confirm.message(), confirm.approvalMode());
        }
    }
}
