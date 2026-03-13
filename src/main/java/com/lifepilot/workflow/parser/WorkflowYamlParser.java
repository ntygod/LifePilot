package com.lifepilot.workflow.parser;

import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.notification.Urgency;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.model.ErrorStrategy;
import com.lifepilot.workflow.model.OptionItem;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowInputParam;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.ApprovalStep;
import com.lifepilot.workflow.model.WorkflowStep.ConditionStep;
import com.lifepilot.workflow.model.WorkflowStep.LlmStep;
import com.lifepilot.workflow.model.WorkflowStep.LoopStep;
import com.lifepilot.workflow.model.WorkflowStep.MediaRef;
import com.lifepilot.workflow.model.WorkflowStep.NoopStep;
import com.lifepilot.workflow.model.WorkflowStep.NotifyStep;
import com.lifepilot.workflow.model.WorkflowStep.ParallelStep;
import com.lifepilot.workflow.model.WorkflowStep.SkillStep;
import com.lifepilot.workflow.model.WorkflowStep.SubWorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.ToolStep;
import com.lifepilot.workflow.model.WorkflowStep.WaitStep;
import com.lifepilot.workflow.model.WorkflowTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.CronTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.EventTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.ManualTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.WebhookTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流 YAML 解析器。
 *
 * <p>将 YAML 文本解析为 {@link WorkflowDefinition}，支持所有步骤类型、
 * 触发器类型、输入参数元数据、工作流级变量/标签、步骤级超时等字段。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowYamlParser {

    private static final Logger log = LoggerFactory.getLogger(WorkflowYamlParser.class);

    private static final Set<String> KNOWN_STEP_TYPES = Set.of(
            "skill", "tool", "llm", "condition", "loop", "parallel",
            "sub-workflow", "noop", "wait", "approval", "notify"
    );
    private static final Set<String> KNOWN_TRIGGER_TYPES = Set.of("cron", "event", "manual", "webhook");
    private static final Set<String> KNOWN_ERROR_STRATEGY_TYPES = Set.of("retry", "skip", "fail", "compensate");

    private final WorkflowConfigProperties configProperties;

    public WorkflowYamlParser() {
        this.configProperties = null;
    }

    public WorkflowYamlParser(WorkflowConfigProperties configProperties) {
        this.configProperties = configProperties;
    }

    // ==================== 主解析入口 ====================

    @SuppressWarnings("unchecked")
    public Result<WorkflowDefinition, List<String>> parse(String yaml) {
        List<String> errors = new ArrayList<>();

        Map<String, Object> root;
        try {
            Object parsed = new Yaml().load(yaml);
            if (!(parsed instanceof Map<?, ?> map)) {
                errors.add("YAML 根节点必须是 Map 类型");
                return new Result.Err<>(errors);
            }
            root = (Map<String, Object>) map;
        } catch (Exception e) {
            errors.add("YAML 语法错误: " + e.getMessage());
            return new Result.Err<>(errors);
        }

        String id = getString(root, "id");
        String name = getString(root, "name");
        if (id == null || id.isBlank()) {
            errors.add("缺少必填字段: id");
        }
        if (name == null || name.isBlank()) {
            errors.add("缺少必填字段: name");
        }
        if (!root.containsKey("steps")) {
            errors.add("缺少必填字段: steps");
        }

        String description = getString(root, "description");
        String version = getString(root, "version");
        boolean enabled = getBoolean(root, "enabled", true);
        List<WorkflowTrigger> triggers = parseTriggers(root.get("triggers"), errors);
        Map<String, WorkflowInputParam> inputs = parseInputs(root.get("inputs"), errors);

        Set<String> stepIds = new HashSet<>();
        List<WorkflowStep> steps = parseSteps(root.get("steps"), errors, stepIds);
        if (steps.isEmpty() && root.containsKey("steps")) {
            errors.add("steps 列表不能为空");
        }

        for (WorkflowStep step : steps) {
            if (step.dependsOn() == null) {
                continue;
            }
            for (String dep : step.dependsOn()) {
                if (!stepIds.contains(dep)) {
                    errors.add("步骤 '%s' 的 dependsOn 引用了不存在的步骤 ID: %s".formatted(step.id(), dep));
                }
            }
        }

        // 解析工作流级变量和标签
        Map<String, Object> variables = parseVariables(root.get("variables"));
        List<String> tags = parseStringList(root.get("tags"));
        Map<String, String> metadata = parseMetadata(root.get("metadata"));

        if (!errors.isEmpty()) {
            log.debug("YAML 解析失败，共 {} 个错误", errors.size());
            return new Result.Err<>(errors);
        }

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version(version)
                .enabled(enabled)
                .triggers(triggers)
                .inputs(inputs)
                .steps(steps)
                .variables(variables)
                .tags(tags)
                .metadata(metadata)
                .build();
        log.debug("YAML 解析成功: id={}", id);
        return new Result.Ok<>(definition);
    }

    // ==================== 触发器解析 ====================

    @SuppressWarnings("unchecked")
    private List<WorkflowTrigger> parseTriggers(Object triggersObj, List<String> errors) {
        if (triggersObj == null) {
            return List.of();
        }
        if (!(triggersObj instanceof List<?> list)) {
            errors.add("triggers 必须是列表类型");
            return List.of();
        }

        List<WorkflowTrigger> triggers = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("triggers[%d] 必须是 Map 类型".formatted(i));
                continue;
            }

            Map<String, Object> triggerMap = (Map<String, Object>) map;
            String type = getString(triggerMap, "type");
            if (type == null || type.isBlank()) {
                errors.add("triggers[%d] 缺少必填字段: type".formatted(i));
                continue;
            }
            if (!KNOWN_TRIGGER_TYPES.contains(type)) {
                errors.add("triggers[%d] 未知触发器类型: %s".formatted(i, type));
                continue;
            }

            switch (type) {
                case "cron" -> {
                    String cron = getString(triggerMap, "cron");
                    if (cron == null || cron.isBlank()) {
                        errors.add("triggers[%d] cron 触发器缺少必填字段: cron".formatted(i));
                    } else {
                        triggers.add(new CronTrigger(cron));
                    }
                }
                case "event" -> {
                    String eventType = getString(triggerMap, "eventType");
                    if (eventType == null || eventType.isBlank()) {
                        errors.add("triggers[%d] event 触发器缺少必填字段: eventType".formatted(i));
                    } else {
                        triggers.add(new EventTrigger(eventType));
                    }
                }
                case "manual" -> triggers.add(new ManualTrigger());
                case "webhook" -> {
                    String secret = getString(triggerMap, "secret");
                    triggers.add(new WebhookTrigger(secret));
                }
                default -> errors.add("triggers[%d] 未知触发器类型: %s".formatted(i, type));
            }
        }
        return triggers;
    }

    // ==================== 输入参数解析 ====================

    @SuppressWarnings("unchecked")
    private Map<String, WorkflowInputParam> parseInputs(Object inputsObj, List<String> errors) {
        if (inputsObj == null) {
            return Map.of();
        }
        if (!(inputsObj instanceof Map<?, ?> map)) {
            errors.add("inputs 必须是 Map 类型");
            return Map.of();
        }

        Map<String, WorkflowInputParam> inputs = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            String paramName = String.valueOf(entry.getKey());
            Object paramValue = entry.getValue();
            if (!(paramValue instanceof Map<?, ?> paramMap)) {
                errors.add("inputs.%s 必须是 Map 类型".formatted(paramName));
                continue;
            }
            Map<String, Object> pm = (Map<String, Object>) paramMap;
            String type = getString(pm, "type");
            if (type == null) {
                type = "string";
            }
            boolean required = getBoolean(pm, "required", false);
            Object defaultValue = pm.get("defaultValue");
            if (defaultValue == null) {
                defaultValue = pm.get("default");
            }
            inputs.put(paramName, new WorkflowInputParam(
                    paramName,
                    type,
                    required,
                    defaultValue,
                    getString(pm, "description"),
                    getString(pm, "inputType"),
                    parseOptions(pm.get("options")),
                    getString(pm, "placeholder"),
                    getString(pm, "example"),
                    getString(pm, "validationPattern"),
                    getString(pm, "validationMessage")
            ));
        }
        return inputs;
    }

    // ==================== 元数据与变量解析 ====================

    private Map<String, String> parseMetadata(Object metadataObj) {
        if (!(metadataObj instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            metadata.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return metadata;
    }

    /** 解析工作流级变量定义。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseVariables(Object variablesObj) {
        if (!(variablesObj instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    // ==================== 步骤解析 ====================

    @SuppressWarnings("unchecked")
    private List<WorkflowStep> parseSteps(Object stepsObj, List<String> errors, Set<String> stepIds) {
        if (stepsObj == null) {
            return List.of();
        }
        if (!(stepsObj instanceof List<?> list)) {
            errors.add("steps 必须是列表类型");
            return List.of();
        }

        List<WorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("steps[%d] 必须是 Map 类型".formatted(i));
                continue;
            }
            WorkflowStep step = parseSingleStep((Map<String, Object>) map, errors, stepIds);
            if (step != null) {
                steps.add(step);
            }
        }
        return steps;
    }

    private WorkflowStep parseSingleStep(Map<String, Object> map, List<String> errors, Set<String> stepIds) {
        String id = getString(map, "id");
        String name = getString(map, "name");
        String type = getString(map, "type");

        if (id == null || id.isBlank()) {
            errors.add("步骤缺少必填字段: id");
            return null;
        }
        if (name == null || name.isBlank()) {
            errors.add("步骤 '%s' 缺少必填字段: name".formatted(id));
            return null;
        }
        if (type == null || type.isBlank()) {
            errors.add("步骤 '%s' 缺少必填字段: type".formatted(id));
            return null;
        }
        if (!stepIds.add(id)) {
            errors.add("重复的步骤 ID: %s".formatted(id));
            return null;
        }
        if (!KNOWN_STEP_TYPES.contains(type)) {
            errors.add("步骤 '%s' 未知步骤类型: %s".formatted(id, type));
            return null;
        }

        ErrorStrategy errorStrategy = parseErrorStrategy(map.get("errorStrategy"), id, errors, stepIds);
        List<String> dependsOn = parseDependsOn(map.get("dependsOn"));
        Integer timeoutSeconds = getInteger(map, "timeoutSeconds");

        return switch (type) {
            case "skill" -> parseSkillStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            case "tool" -> parseToolStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            case "llm" -> parseLlmStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            case "condition" -> parseConditionStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors, stepIds);
            case "loop" -> parseLoopStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors, stepIds);
            case "parallel" -> parseParallelStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors, stepIds);
            case "sub-workflow" -> parseSubWorkflowStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            case "noop" -> new NoopStep(id, name, dependsOn, errorStrategy, timeoutSeconds);
            case "wait" -> parseWaitStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            case "approval" -> parseApprovalStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            case "notify" -> parseNotifyStep(id, name, map, dependsOn, errorStrategy, timeoutSeconds, errors);
            default -> {
                errors.add("步骤 '%s' 未知步骤类型: %s".formatted(id, type));
                yield null;
            }
        };
    }

    // ==================== 各步骤类型解析 ====================

    private SkillStep parseSkillStep(String id, String name, Map<String, Object> map,
                                     List<String> dependsOn, ErrorStrategy errorStrategy,
                                     Integer timeoutSeconds, List<String> errors) {
        String skillId = getString(map, "skillId");
        if (skillId == null || skillId.isBlank()) {
            errors.add("步骤 '%s' (skill) 缺少必填字段: skillId".formatted(id));
            return null;
        }
        return new SkillStep(id, name, skillId, parseStringMap(map.get("params")), dependsOn, errorStrategy, timeoutSeconds);
    }

    private ToolStep parseToolStep(String id, String name, Map<String, Object> map,
                                   List<String> dependsOn, ErrorStrategy errorStrategy,
                                   Integer timeoutSeconds, List<String> errors) {
        String toolId = getString(map, "toolId");
        if (toolId == null || toolId.isBlank()) {
            errors.add("步骤 '%s' (tool) 缺少必填字段: toolId".formatted(id));
            return null;
        }
        return new ToolStep(id, name, toolId, parseStringMap(map.get("params")), dependsOn, errorStrategy, timeoutSeconds);
    }

    private LlmStep parseLlmStep(String id, String name, Map<String, Object> map,
                                 List<String> dependsOn, ErrorStrategy errorStrategy,
                                 Integer timeoutSeconds, List<String> errors) {
        String scene = getString(map, "scene");
        if (scene == null || scene.isBlank()) {
            errors.add("步骤 '%s' (llm) 缺少必填字段: scene".formatted(id));
            return null;
        }
        if ("workflow".equalsIgnoreCase(scene.trim())) {
            errors.add("步骤 '%s' (llm) scene 不能写为 workflow，请使用具体的任务意图标识".formatted(id));
            return null;
        }

        String capabilityName = getString(map, "capability");
        if (capabilityName == null || capabilityName.isBlank()) {
            errors.add("步骤 '%s' (llm) 缺少必填字段: capability".formatted(id));
            return null;
        }

        ProviderCapability capability;
        try {
            capability = ProviderCapability.valueOf(capabilityName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            errors.add("步骤 '%s' (llm) capability 非法: %s".formatted(id, capabilityName));
            return null;
        }

        String promptTemplate = getString(map, "prompt");
        if (promptTemplate == null || promptTemplate.isBlank()) {
            errors.add("步骤 '%s' (llm) 缺少必填字段: prompt".formatted(id));
            return null;
        }

        String outputSchema = getString(map, "outputSchema");
        String modelName = getString(map, "modelName");
        String preferredProviderId = getString(map, "preferredProviderId");
        List<MediaRef> media = parseMediaRefs(map.get("media"), id, errors);
        if (!media.isEmpty() && capability != ProviderCapability.VISION) {
            errors.add("步骤 '%s' (llm) 配置了 media 时，capability 必须为 VISION".formatted(id));
            return null;
        }
        if (media.isEmpty() && capability == ProviderCapability.VISION) {
            errors.add("步骤 '%s' (llm) capability 为 VISION 时，至少需要提供一个 media".formatted(id));
            return null;
        }

        return new LlmStep(id, name, scene.trim(), capability, promptTemplate,
                outputSchema, modelName, preferredProviderId, media,
                dependsOn, errorStrategy, timeoutSeconds);
    }

    private ConditionStep parseConditionStep(String id, String name, Map<String, Object> map,
                                             List<String> dependsOn, ErrorStrategy errorStrategy,
                                             Integer timeoutSeconds, List<String> errors,
                                             Set<String> stepIds) {
        String condition = getString(map, "condition");
        if (condition == null || condition.isBlank()) {
            errors.add("步骤 '%s' (condition) 缺少必填字段: condition".formatted(id));
            return null;
        }
        List<WorkflowStep> thenSteps = parseSteps(map.get("then"), errors, stepIds);
        List<WorkflowStep> elseSteps = parseSteps(map.get("else"), errors, stepIds);
        return new ConditionStep(id, name, condition, thenSteps, elseSteps, dependsOn, errorStrategy, timeoutSeconds);
    }

    private LoopStep parseLoopStep(String id, String name, Map<String, Object> map,
                                   List<String> dependsOn, ErrorStrategy errorStrategy,
                                   Integer timeoutSeconds, List<String> errors,
                                   Set<String> stepIds) {
        String items = getString(map, "items");
        if (items == null || items.isBlank()) {
            errors.add("步骤 '%s' (loop) 缺少必填字段: items".formatted(id));
            return null;
        }
        String loopVar = getString(map, "loopVar");
        if (loopVar == null || loopVar.isBlank()) {
            errors.add("步骤 '%s' (loop) 缺少必填字段: loopVar".formatted(id));
            return null;
        }
        return new LoopStep(id, name, items, loopVar, parseSteps(map.get("body"), errors, stepIds),
                dependsOn, errorStrategy, timeoutSeconds);
    }

    @SuppressWarnings("unchecked")
    private ParallelStep parseParallelStep(String id, String name, Map<String, Object> map,
                                           List<String> dependsOn, ErrorStrategy errorStrategy,
                                           Integer timeoutSeconds, List<String> errors,
                                           Set<String> stepIds) {
        Object branchesObj = map.get("branches");
        if (branchesObj == null) {
            errors.add("步骤 '%s' (parallel) 缺少必填字段: branches".formatted(id));
            return null;
        }
        if (!(branchesObj instanceof List<?> branchesList)) {
            errors.add("步骤 '%s' (parallel) branches 必须是列表类型".formatted(id));
            return null;
        }

        List<List<WorkflowStep>> branches = new ArrayList<>();
        for (int i = 0; i < branchesList.size(); i++) {
            Object branch = branchesList.get(i);
            if (!(branch instanceof List<?> branchStepsList)) {
                errors.add("步骤 '%s' (parallel) branches[%d] 必须是列表类型".formatted(id, i));
                continue;
            }

            List<WorkflowStep> branchSteps = new ArrayList<>();
            for (int j = 0; j < branchStepsList.size(); j++) {
                Object stepObj = branchStepsList.get(j);
                if (!(stepObj instanceof Map<?, ?> stepMap)) {
                    errors.add("步骤 '%s' (parallel) branches[%d][%d] 必须是 Map 类型".formatted(id, i, j));
                    continue;
                }
                WorkflowStep step = parseSingleStep((Map<String, Object>) stepMap, errors, stepIds);
                if (step != null) {
                    branchSteps.add(step);
                }
            }
            branches.add(branchSteps);
        }
        return new ParallelStep(id, name, branches, dependsOn, errorStrategy, timeoutSeconds);
    }

    private SubWorkflowStep parseSubWorkflowStep(String id, String name, Map<String, Object> map,
                                                 List<String> dependsOn, ErrorStrategy errorStrategy,
                                                 Integer timeoutSeconds, List<String> errors) {
        String workflowId = getString(map, "workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            errors.add("步骤 '%s' (sub-workflow) 缺少必填字段: workflowId".formatted(id));
            return null;
        }
        return new SubWorkflowStep(id, name, workflowId, parseStringMap(map.get("params")),
                dependsOn, errorStrategy, timeoutSeconds);
    }

    private WaitStep parseWaitStep(String id, String name, Map<String, Object> map,
                                   List<String> dependsOn, ErrorStrategy errorStrategy,
                                   Integer timeoutSeconds, List<String> errors) {
        Object durationObj = map.get("durationSeconds");
        if (durationObj == null) {
            errors.add("步骤 '%s' (wait) 缺少必填字段: durationSeconds".formatted(id));
            return null;
        }
        long durationSeconds;
        try {
            durationSeconds = ((Number) durationObj).longValue();
        } catch (ClassCastException e) {
            errors.add("步骤 '%s' (wait) durationSeconds 必须是数字类型".formatted(id));
            return null;
        }
        return new WaitStep(id, name, durationSeconds, dependsOn, errorStrategy, timeoutSeconds);
    }

    private ApprovalStep parseApprovalStep(String id, String name, Map<String, Object> map,
                                           List<String> dependsOn, ErrorStrategy errorStrategy,
                                           Integer timeoutSeconds, List<String> errors) {
        String message = getString(map, "message");
        if (message == null || message.isBlank()) {
            errors.add("步骤 '%s' (approval) 缺少必填字段: message".formatted(id));
            return null;
        }
        List<String> approvers = parseStringList(map.get("approvers"));
        if (approvers.isEmpty()) {
            approvers = List.of("owner");
        }

        int defaultTimeout = configProperties != null
                ? configProperties.getApproval().getDefaultTimeoutSeconds()
                : 86400;
        boolean defaultAutoApprove = configProperties != null
                && configProperties.getApproval().isAutoApproveOnTimeout();

        int approvalTimeoutSeconds = getInt(map, "timeoutSeconds", defaultTimeout);
        boolean autoApproveOnTimeout = getBoolean(map, "autoApproveOnTimeout", defaultAutoApprove);
        return new ApprovalStep(id, name, message, approvers, approvalTimeoutSeconds,
                autoApproveOnTimeout, dependsOn, errorStrategy, timeoutSeconds);
    }

    private NotifyStep parseNotifyStep(String id, String name, Map<String, Object> map,
                                       List<String> dependsOn, ErrorStrategy errorStrategy,
                                       Integer timeoutSeconds, List<String> errors) {
        String targetUserId = getString(map, "targetUserId");
        if (targetUserId == null || targetUserId.isBlank()) {
            errors.add("步骤 '%s' (notify) 缺少必填字段: targetUserId".formatted(id));
            return null;
        }
        String content = getString(map, "content");
        if (content == null || content.isBlank()) {
            errors.add("步骤 '%s' (notify) 缺少必填字段: content".formatted(id));
            return null;
        }
        String contentType = getString(map, "contentType");
        if (contentType == null || contentType.isBlank()) {
            contentType = "TEXT";
        }
        String urgencyStr = getString(map, "urgency");
        Urgency urgency;
        try {
            urgency = urgencyStr != null ? Urgency.valueOf(urgencyStr.trim().toUpperCase()) : Urgency.MEDIUM;
        } catch (IllegalArgumentException e) {
            errors.add("步骤 '%s' (notify) urgency 非法: %s".formatted(id, urgencyStr));
            return null;
        }
        return new NotifyStep(id, name, targetUserId, content, contentType, urgency,
                dependsOn, errorStrategy, timeoutSeconds);
    }

    // ==================== 错误策略解析（重试默认值从配置读取） ====================

    @SuppressWarnings("unchecked")
    private ErrorStrategy parseErrorStrategy(Object strategyObj,
                                             String stepId,
                                             List<String> errors,
                                             Set<String> stepIds) {
        if (strategyObj == null) {
            return null;
        }
        if (!(strategyObj instanceof Map<?, ?> map)) {
            errors.add("步骤 '%s' errorStrategy 必须是 Map 类型".formatted(stepId));
            return null;
        }

        Map<String, Object> strategyMap = (Map<String, Object>) map;
        String type = getString(strategyMap, "type");
        if (type == null || type.isBlank()) {
            errors.add("步骤 '%s' errorStrategy 缺少必填字段: type".formatted(stepId));
            return null;
        }
        if (!KNOWN_ERROR_STRATEGY_TYPES.contains(type)) {
            errors.add("步骤 '%s' errorStrategy 未知策略类型: %s".formatted(stepId, type));
            return null;
        }

        return switch (type) {
            case "retry" -> {
                // 从 configProperties 读取默认值，未注入时使用硬编码兜底
                int defaultMaxAttempts = configProperties != null
                        ? configProperties.getRetry().getMaxAttempts() : 3;
                long defaultInitialDelayMs = configProperties != null
                        ? configProperties.getRetry().getInitialDelayMs() : 500L;
                long defaultMaxDelayMs = configProperties != null
                        ? configProperties.getRetry().getMaxDelayMs() : 5000L;
                int maxAttempts = getInt(strategyMap, "maxAttempts", defaultMaxAttempts);
                long initialDelayMs = getLong(strategyMap, "initialDelayMs", defaultInitialDelayMs);
                long maxDelayMs = getLong(strategyMap, "maxDelayMs", defaultMaxDelayMs);
                yield new ErrorStrategy.Retry(maxAttempts, initialDelayMs, maxDelayMs);
            }
            case "skip" -> new ErrorStrategy.Skip(getString(strategyMap, "reason") != null
                    ? getString(strategyMap, "reason")
                    : "");
            case "fail" -> new ErrorStrategy.Fail();
            case "compensate" -> {
                Object compensationStepObj = strategyMap.get("compensationStep");
                if (compensationStepObj == null) {
                    errors.add("步骤 '%s' compensate 策略缺少必填字段: compensationStep".formatted(stepId));
                    yield null;
                }
                if (!(compensationStepObj instanceof Map<?, ?> compensationMap)) {
                    errors.add("步骤 '%s' compensate 策略 compensationStep 必须是 Map 类型".formatted(stepId));
                    yield null;
                }
                WorkflowStep compensationStep = parseSingleStep((Map<String, Object>) compensationMap, errors, stepIds);
                if (compensationStep == null) {
                    yield null;
                }
                yield new ErrorStrategy.Compensate(compensationStep);
            }
            default -> {
                errors.add("步骤 '%s' errorStrategy 未知策略类型: %s".formatted(stepId, type));
                yield null;
            }
        };
    }

    // ==================== 辅助解析方法 ====================

    private static List<String> parseDependsOn(Object dependsOnObj) {
        if (dependsOnObj == null) {
            return List.of();
        }
        if (dependsOnObj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        }
        return List.of(String.valueOf(dependsOnObj));
    }

    private static List<String> parseStringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        }
        return List.of(String.valueOf(obj));
    }

    @SuppressWarnings("unchecked")
    private static List<MediaRef> parseMediaRefs(Object mediaObj, String stepId, List<String> errors) {
        if (mediaObj == null) {
            return List.of();
        }
        if (!(mediaObj instanceof List<?> list)) {
            errors.add("步骤 '%s' (llm) media 必须是列表类型".formatted(stepId));
            return List.of();
        }

        List<MediaRef> mediaRefs = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("步骤 '%s' (llm) media[%d] 必须是 Map 类型".formatted(stepId, i));
                continue;
            }
            Map<String, Object> mediaMap = (Map<String, Object>) map;
            String source = getString(mediaMap, "source");
            if (source == null || source.isBlank()) {
                errors.add("步骤 '%s' (llm) media[%d] 缺少必填字段: source".formatted(stepId, i));
                continue;
            }
            mediaRefs.add(new MediaRef(
                    source.trim(),
                    getString(mediaMap, "mimeType"),
                    getString(mediaMap, "fileName")
            ));
        }
        return List.copyOf(mediaRefs);
    }

    /** 解析 InputParam 的 options 枚举选项列表。 */
    @SuppressWarnings("unchecked")
    private static List<OptionItem> parseOptions(Object optionsObj) {
        if (!(optionsObj instanceof List<?> list)) {
            return null;
        }
        List<OptionItem> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> optMap = (Map<String, Object>) map;
                String value = getString(optMap, "value");
                String label = getString(optMap, "label");
                if (value != null) {
                    result.add(new OptionItem(value, label != null ? label : value));
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    // ==================== 基础类型读取工具方法 ====================

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读取可选的 Integer 值，为 null 时返回 null。 */
    @Nullable
    private static Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> parseStringMap(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }
}
