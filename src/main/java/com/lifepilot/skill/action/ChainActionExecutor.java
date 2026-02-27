package com.lifepilot.skill.action;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能串联执行器 — 按步骤顺序激活多个 Skill。
 *
 * <p>前一步的输出作为后一步的输入参数，通过 ${result.xxx} 引用。
 * 任一步骤失败则终止后续步骤。</p>
 *
 * <p>L1 回归：每个步骤通过 SkillRegistry 查找 Skill 定义，
 * 将 systemPrompt 作为 TemplateAction 渲染执行，不再依赖 SubAgentFactory。
 * 使用 {@link SkillActionDispatcher} 的引用通过 Lazy 注入避免循环依赖。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ChainActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChainActionExecutor.class);

    private final SkillRegistry skillRegistry;
    private final VariableResolver variableResolver;
    private final SkillConfigProperties config;

    /**
     * 构造技能串联执行器。
     *
     * @param skillRegistry    Skill 注册中心
     * @param variableResolver 变量替换引擎
     * @param config           Skill 配置属性
     */
    public ChainActionExecutor(SkillRegistry skillRegistry,
                               VariableResolver variableResolver,
                               SkillConfigProperties config) {
        this.skillRegistry = skillRegistry;
        this.variableResolver = variableResolver;
        this.config = config;
    }

    /**
     * 执行技能串联。
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验步骤数不超过配置上限</li>
     *   <li>校验所有引用的 Skill ID 在 SkillRegistry 中存在</li>
     *   <li>空步骤列表直接返回成功</li>
     *   <li>按顺序依次执行每个 Skill 的 TemplateAction，前一步输出注入后一步输入</li>
     *   <li>任一步骤失败终止后续步骤</li>
     * </ol></p>
     *
     * @param action Chain 动作定义
     * @param params 初始输入参数
     * @return 最终执行结果
     */
    public ActionResult execute(SkillAction.ChainAction action, Map<String, Object> params) {
        List<SkillAction.ChainStep> steps = action.steps();

        // 1. 校验步骤数不超过配置上限
        int maxSteps = config.getChainAction().getMaxSteps();
        if (steps.size() > maxSteps) {
            log.warn("技能串联步骤数超限: steps={}, maxSteps={}", steps.size(), maxSteps);
            return ActionResult.error("技能串联步骤数超过上限: " + steps.size() + " > " + maxSteps);
        }

        // 2. 校验所有引用的 Skill ID 存在
        List<String> missingSkills = steps.stream()
                .map(SkillAction.ChainStep::skill)
                .distinct()
                .filter(skillId -> skillRegistry.find(skillId).isEmpty())
                .toList();
        if (!missingSkills.isEmpty()) {
            log.warn("技能串联引用的 Skill 不存在: missingSkills={}", missingSkills);
            return ActionResult.error("技能串联引用的 Skill 不存在: " + String.join(", ", missingSkills));
        }

        // 3. 空步骤列表直接返回成功
        if (steps.isEmpty()) {
            return ActionResult.success("");
        }

        // 4. 按顺序依次执行每个 Skill 的 TemplateAction
        Map<String, Object> stepOutputs = new HashMap<>();
        String lastOutput = "";

        // TemplateActionExecutor 用于渲染每个步骤的 systemPrompt
        var templateExecutor = new TemplateActionExecutor(variableResolver);

        for (int i = 0; i < steps.size(); i++) {
            SkillAction.ChainStep step = steps.get(i);

            // 构建当前步骤的输入字符串 — 解析 step.params 中的变量引用
            String resolvedInput = buildStepInput(step.params(), params, stepOutputs);

            log.debug("技能串联执行步骤: index={}, skill={}, input={}", i, step.skill(), resolvedInput);

            // 查找 Skill 定义，将 systemPrompt 作为 TemplateAction 渲染
            SkillDefinition skillDef = skillRegistry.find(step.skill()).orElse(null);
            if (skillDef == null) {
                // 理论上不会到这里（前面已校验），防御性处理
                return ActionResult.error("技能串联步骤 " + i + " Skill 不存在: " + step.skill());
            }

            // 将 resolvedInput 作为前序结果传入 TemplateAction
            var templateAction = new SkillAction.TemplateAction(skillDef.systemPrompt());
            Map<String, Object> previousResult = new HashMap<>(stepOutputs);
            previousResult.put("input", resolvedInput);
            ActionResult stepResult = templateExecutor.execute(templateAction, previousResult);

            // 步骤失败则终止
            if (!stepResult.success()) {
                log.warn("技能串联步骤失败: index={}, skill={}, output={}", i, step.skill(), stepResult.output());
                return ActionResult.error(
                        "技能串联步骤 " + i + " 失败 (skill=" + step.skill() + "): " + stepResult.output());
            }

            // 存储步骤输出，供后续步骤引用
            lastOutput = stepResult.output();
            stepOutputs.put(step.output(), lastOutput);

            log.debug("技能串联步骤完成: index={}, skill={}", i, step.skill());
        }

        // 5. 返回最终结果
        return ActionResult.success(lastOutput);
    }

    /**
     * 构建步骤输入字符串 — 解析参数中的变量引用。
     *
     * <p>步骤的 params Map 值可能包含 ${result.xxx} 引用前一步输出。
     * 使用 VariableResolver 进行变量替换，stepOutputs 作为 result 上下文。</p>
     *
     * @param stepParams  步骤参数 Map
     * @param params      初始输入参数
     * @param stepOutputs 前序步骤输出 Map
     * @return 解析后的输入字符串
     */
    private String buildStepInput(Map<String, String> stepParams,
                                  Map<String, Object> params,
                                  Map<String, Object> stepOutputs) {
        if (stepParams.isEmpty()) {
            return "";
        }

        // 对每个参数值进行变量替换
        Map<String, String> resolvedParams = stepParams.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> variableResolver.resolve(entry.getValue(), params, stepOutputs)
                ));

        // 单个参数时直接返回值，多个参数时拼接为 key=value 格式
        if (resolvedParams.size() == 1) {
            return resolvedParams.values().iterator().next();
        }
        return resolvedParams.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
