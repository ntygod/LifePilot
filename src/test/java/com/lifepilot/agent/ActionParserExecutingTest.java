package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentErrorType;
import com.lifepilot.agent.model.AgentPhase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionParserExecutingTest {

    @Test
    void executing_合法JSON但结构不匹配_给出更准确的错误信息() {
        ActionParser parser = new ActionParser(new ObjectMapper());

        String json = """
                {
                  "stage": "工具执行",
                  "tool_calls": [
                    {
                      "tool_name": "calendar_create_event",
                      "parameters": {"title": "与赵总会议"}
                    }
                  ]
                }
                """;

        Action a = parser.parse(AgentPhase.EXECUTING, json);
        assertThat(a).isInstanceOf(Action.ErrorRecovery.class);
        Action.ErrorRecovery er = (Action.ErrorRecovery) a;
        assertThat(er.errorType()).isEqualTo(AgentErrorType.LLM_PARSE_FAILURE);
        assertThat(er.errorMessage()).contains("EXECUTING 阶段输出无法解析");
    }

    @Test
    void executing_非法JSON_错误信息包含JSON语法解析失败() {
        ActionParser parser = new ActionParser(new ObjectMapper());

        String broken = """
                {
                  "stage": "工具执行",
                  "budget_rema...
                }
                """;

        Action a = parser.parse(AgentPhase.EXECUTING, broken);
        assertThat(a).isInstanceOf(Action.ErrorRecovery.class);
        Action.ErrorRecovery er = (Action.ErrorRecovery) a;
        assertThat(er.errorMessage()).contains("EXECUTING 阶段").contains("解析失败");
    }
}

