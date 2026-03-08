package com.lifepilot.meta.infra.reason;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 思考工具执行器 — Agent 的内部推理草稿板。
 *
 * <p>接收 Agent 的推理内容并返回确认字符串。推理内容不输出给用户，
 * 仅用于 Agent 的内部思考过程（step-by-step reasoning）。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class ThinkToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ThinkToolExecutor.class);

    /**
     * 执行思考记录。
     *
     * @param input 工具输入，必需参数 reasoning（推理内容）
     * @return 确认字符串
     */
    public ToolResult execute(ToolInput input) {
        try {
            String reasoning = input.getParam("reasoning", String.class);

            if (reasoning.isBlank()) {
                return ToolResult.error("推理内容不能为空");
            }

            log.debug("Agent 思考记录: length={}", reasoning.length());

            return ToolResult.success(Map.of(
                    "status", "思考已记录",
                    "reasoning", reasoning,
                    "length", reasoning.length()
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("思考工具执行失败: {}", e.getMessage(), e);
            return ToolResult.error("思考工具执行失败: " + e.getMessage());
        }
    }
}
