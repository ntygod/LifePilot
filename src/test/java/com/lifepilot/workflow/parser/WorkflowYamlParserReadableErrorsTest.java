package com.lifepilot.workflow.parser;

import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowYamlParserReadableErrorsTest {

    private WorkflowYamlParser parser;

    @BeforeEach
    void setUp() {
        parser = new WorkflowYamlParser();
    }

    @Test
    void parse_reportsReadableCapabilityError() {
        List<String> errors = parseErr("""
                id: sample
                name: sample
                steps:
                  - id: llm-1
                    name: LLM
                    type: llm
                    scene: knowledge_extraction
                    prompt: test
                """);

        assertTrue(errors.contains("步骤 'llm-1' (llm) 缺少必填字段: capability"));
    }

    @Test
    void parse_reportsReadableWorkflowSceneError() {
        List<String> errors = parseErr("""
                id: sample
                name: sample
                steps:
                  - id: llm-1
                    name: LLM
                    type: llm
                    scene: workflow
                    capability: CHAT
                    prompt: test
                """);

        assertTrue(errors.contains("步骤 'llm-1' (llm) scene 不能写为 workflow，请使用具体的任务意图标识"));
    }

    private List<String> parseErr(String yaml) {
        Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
        assertInstanceOf(Result.Err.class, result);
        return ((Result.Err<WorkflowDefinition, List<String>>) result).error();
    }
}
