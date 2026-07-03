package com.lifepilot.agent.learning.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceRecord;
import com.lifepilot.observability.trace.TraceStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentTraceWriter 单元测试 — 验证轨迹落库的 SQL 与关键字段映射。
 *
 * @author zsg
 * @since 2026-06-06
 */
class AgentTraceWriter_单元测试 {

    private JdbcTemplate jdbcTemplate;
    private AgentTraceWriter writer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        writer = new AgentTraceWriter(jdbcTemplate, new ObjectMapper());
    }

    private TraceRecord buildRecord(List<TraceStep> steps) {
        Instant now = Instant.now();
        return new TraceRecord(
                "trace-1", "session-1", "帮我查一下天气",
                now, now, 1200L, steps.size(), 500, 300, 200,
                true, null, "已完成", null, steps, null);
    }

    @Test
    @DisplayName("写入 agent_traces 主记录 + 每个步骤写 agent_trace_steps")
    void persist_写入主记录与步骤() {
        var llm = new LlmCallStep(0, Instant.now(), Duration.ofMillis(800),
                "deepseek-official", "deepseek-v4-pro", "agent_react",
                100, 50, Duration.ofMillis(800), false, 0.7, "stop");
        var tool = new ToolCallStep(1, Instant.now(), Duration.ofMillis(300),
                "web.search", "execute", "{\"q\":\"天气\"}", "{\"result\":\"晴\"}",
                true, null, RiskLevel.LOW);

        writer.persist(buildRecord(List.of(llm, tool)));

        // 1 次 agent_traces + 2 次 agent_trace_steps
        verify(jdbcTemplate).update(contains("INSERT INTO agent_traces"), any(Object[].class));
        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO agent_trace_steps"), any(Object[].class));
    }

    @Test
    @DisplayName("工具步正确映射 tool_id / tool_input_json / tool_output")
    void persist_工具步字段映射() {
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(120),
                "memory", "search", "{\"action\":\"search\"}", "{\"hits\":3}",
                true, null, RiskLevel.MEDIUM);

        writer.persist(buildRecord(List.of(tool)));

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO agent_trace_steps"), captor.capture());
        Object[] args = captor.getValue();
        // 列顺序: id, trace_id, step_index, phase_before, phase_after, action_type,
        //         action_json, tool_id, tool_input_json, tool_output, ...
        assertThat(args[1]).isEqualTo("trace-1");
        assertThat(args[5]).isEqualTo("tool_call");      // action_type
        assertThat(args[7]).isEqualTo("memory");          // tool_id
        assertThat(args[8]).isEqualTo("{\"action\":\"search\"}"); // tool_input_json
        assertThat(args[9]).isEqualTo("{\"hits\":3}");    // tool_output
    }

    @Test
    @DisplayName("agent_traces 关键字段映射: user_message=goal, model_id=首个LLM模型")
    void persist_主记录字段映射() {
        var llm = new LlmCallStep(0, Instant.now(), Duration.ofMillis(800),
                "deepseek-official", "deepseek-v4-pro", "agent_react",
                100, 50, Duration.ofMillis(800), false, 0.7, "stop");

        writer.persist(buildRecord(List.of(llm)));

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO agent_traces"), captor.capture());
        Object[] args = captor.getValue();
        // 列: id, session_id, user_message, final_output, success, ..., model_id(第11), ...
        assertThat(args[0]).isEqualTo("trace-1");
        assertThat(args[2]).isEqualTo("帮我查一下天气");  // user_message = goal
        assertThat(args[10]).isEqualTo("deepseek-v4-pro"); // model_id
    }

    @Test
    @DisplayName("持久化异常应抛出")
    void persist_异常应抛出() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(jdbcTemplate).update(any(String.class), any(Object[].class));
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(1),
                "memory", "search", null, null, true, null, RiskLevel.LOW);

        assertThatThrownBy(() -> writer.persist(buildRecord(List.of(tool))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");
    }

    @Test
    @DisplayName("sessionId 为空时应拒绝且不写库")
    void persist_sessionId为空_应拒绝且不写库() {
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(1),
                "memory", "search", null, null, true, null, RiskLevel.LOW);
        var record = new TraceRecord(
                "trace-1", " ", "帮我查一下天气",
                Instant.now(), Instant.now(), 1L, 1, 0, 0, 0,
                true, null, "已完成", null, List.of(tool), null);

        assertThatThrownBy(() -> writer.persist(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("学习轨迹 sessionId不能为空");
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    @DisplayName("totalSteps 与 steps 数量不一致时应拒绝且不写库")
    void persist_totalSteps不一致_应拒绝且不写库() {
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(1),
                "memory", "search", null, null, true, null, RiskLevel.LOW);
        var record = new TraceRecord(
                "trace-1", "session-1", "帮我查一下天气",
                Instant.now(), Instant.now(), 1L, 2, 0, 0, 0,
                true, null, "已完成", null, List.of(tool), null);

        assertThatThrownBy(() -> writer.persist(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("学习轨迹 totalSteps 必须等于 steps 数量");
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    @DisplayName("步骤耗时为空时应拒绝且不写库")
    void persist_步骤耗时为空_应拒绝且不写库() {
        var tool = new ToolCallStep(0, Instant.now(), null,
                "memory", "search", null, null, true, null, RiskLevel.LOW);

        assertThatThrownBy(() -> writer.persist(buildRecord(List.of(tool))))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("学习轨迹步骤耗时不能为空");
        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    @DisplayName("action_json 序列化失败时应抛出且不写入空步骤")
    void persist_actionJson序列化失败_应抛出且不写空步骤() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("序列化失败") {});
        writer = new AgentTraceWriter(jdbcTemplate, failingMapper);
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(1),
                "memory", "search", null, null, true, null, RiskLevel.LOW);

        assertThatThrownBy(() -> writer.persist(buildRecord(List.of(tool))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("学习轨迹: action_json 序列化失败");

        verify(jdbcTemplate).update(contains("INSERT INTO agent_traces"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO agent_trace_steps"), any(Object[].class));
    }

    @Test
    @DisplayName("主记录写入0行应失败")
    void persist_主记录写入0行应失败() {
        when(jdbcTemplate.update(contains("INSERT INTO agent_traces"), any(Object[].class)))
                .thenReturn(0);
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(1),
                "memory", "search", null, null, true, null, RiskLevel.LOW);

        assertThatThrownBy(() -> writer.persist(buildRecord(List.of(tool))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("学习轨迹主记录写入影响行数必须为 1")
                .hasMessageContaining("id=trace-1")
                .hasMessageContaining("rows=0");

        verify(jdbcTemplate, never()).update(contains("INSERT INTO agent_trace_steps"), any(Object[].class));
    }

    @Test
    @DisplayName("步骤写入0行应失败")
    void persist_步骤写入0行应失败() {
        when(jdbcTemplate.update(contains("INSERT INTO agent_trace_steps"), any(Object[].class)))
                .thenReturn(0);
        var tool = new ToolCallStep(0, Instant.now(), Duration.ofMillis(1),
                "memory", "search", null, null, true, null, RiskLevel.LOW);

        assertThatThrownBy(() -> writer.persist(buildRecord(List.of(tool))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("学习轨迹步骤写入影响行数必须为 1")
                .hasMessageContaining("id=trace-1:0")
                .hasMessageContaining("rows=0");
    }
}
