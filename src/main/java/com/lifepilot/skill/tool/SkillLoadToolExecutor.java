package com.lifepilot.skill.tool;

import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.model.SkillActivation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code skill.load} 工具执行器 —— 统一的 Skill 激活入口。
 *
 * <p>逻辑：
 * <ol>
 *   <li>解析并校验 {@code names} 参数（非空且数量 ≤ {@link #MAX_SKILLS}）</li>
 *   <li>逐个校验 skill 存在且未被禁用；再委托 {@link SkillActivator#activate(String)} 完成激活</li>
 *   <li>聚合结果：将每个 activation 包裹为 {@code <skill name="X">...</skill>}，按输入顺序用空行拼接；
 *       同时对所有 activation 的 {@code suggestedTools} 按出现顺序去重合并，作为后续轮次可见的工具集</li>
 * </ol>
 *
 * <p>返回 {@code Map.of("content", String, "activated_tool_ids", List&lt;String&gt;)}，
 * 由外层 BuiltinTool 封装为 {@link com.lifepilot.tool.model.ToolResult}。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class SkillLoadToolExecutor {

    /** 单次激活最多允许的 Skill 数量 —— 避免一次性注入过多上下文。 */
    public static final int MAX_SKILLS = 3;

    /** 从已替换占位符的 instructions 中扫出 references 文件绝对路径。 */
    private static final Pattern REFERENCES_PATH_PATTERN = Pattern.compile(
            "(?:[A-Za-z]:\\\\[^\\s)`'\"]+|/[^\\s)`'\"]+)[/\\\\]references[/\\\\][\\w.-]+\\.md");

    private final SkillActivator activator;
    private final SkillInstallationRepository repository;

    public SkillLoadToolExecutor(SkillActivator activator,
                                 SkillInstallationRepository repository) {
        this.activator = activator;
        this.repository = repository;
    }

    /**
     * 执行激活。
     *
     * @param params 工具参数，必须包含 {@code names}（List&lt;String&gt;，1 ≤ size ≤ 3）
     * @return {@code { "content": String, "activated_tool_ids": List<String> }}
     * @throws IllegalArgumentException 参数不合法、skill 不存在或已被禁用
     */
    public Map<String, Object> execute(Map<String, Object> params) {
        List<String> names = parseNames(params);

        // 预校验 —— 全部 skill 必须存在且启用后，再进入真正的激活阶段，
        // 避免「激活一半后某个 skill 失败」导致状态半残留。
        for (String name : names) {
            SkillInstallation install = repository.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("未知 skill: " + name));
            if (!install.enabled()) {
                throw new IllegalArgumentException(
                        "skill '" + name + "' 已被禁用，无法激活");
            }
        }

        // 激活 + 聚合
        StringBuilder content = new StringBuilder();
        // 用 LinkedHashSet 保留首次出现顺序并自动去重
        LinkedHashSet<String> distinctToolIds = new LinkedHashSet<>();
        LinkedHashSet<String> distinctReferences = new LinkedHashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            SkillActivation activation = activator.activate(name);
            if (i > 0) {
                content.append("\n\n");
            }
            String instructions = activation.instructions();
            content.append("<skill name=\"").append(name).append("\">\n")
                    .append(instructions)
                    .append("\n</skill>");
            collectReferences(instructions, distinctReferences);
            if (activation.suggestedTools() != null) {
                for (String toolId : activation.suggestedTools()) {
                    if (toolId != null && !toolId.isBlank()) {
                        distinctToolIds.add(toolId);
                    }
                }
            }
        }

        // references 强引导：让 LLM 在收到当回合就看到具体路径，避免凭印象做事。
        if (!distinctReferences.isEmpty()) {
            content.append("\n\n执行具体动作（写命令 / 生成产物 / 套用格式 / 查陌生参数）前必读：");
            for (String ref : distinctReferences) {
                content.append("\n- file.read(\"").append(ref).append("\")");
            }
        }

        return Map.of(
                "content", content.toString(),
                "activated_tool_ids", List.copyOf(distinctToolIds)
        );
    }

    /** 从 instructions 中扫出 references 绝对路径加入集合。 */
    private static void collectReferences(String instructions, LinkedHashSet<String> sink) {
        if (instructions == null || instructions.isEmpty()) {
            return;
        }
        Matcher m = REFERENCES_PATH_PATTERN.matcher(instructions);
        while (m.find()) {
            sink.add(m.group());
        }
    }

    /**
     * 解析 {@code names} 参数 —— 允许 List&lt;?&gt;（元素转字符串后 trim），拒绝空列表与超过 {@link #MAX_SKILLS} 个。
     */
    private static List<String> parseNames(Map<String, Object> params) {
        Object raw = Objects.requireNonNull(params, "params 不能为空").get("names");
        if (raw == null) {
            throw new IllegalArgumentException("缺少必需参数: names");
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException("参数 names 必须是字符串数组");
        }
        List<String> names = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item == null) continue;
            String s = String.valueOf(item).trim();
            if (!s.isEmpty()) {
                names.add(s);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("参数 names 不能为空");
        }
        if (names.size() > MAX_SKILLS) {
            throw new IllegalArgumentException(
                    "一次最多 3 个 skill，当前传入 " + names.size() + " 个");
        }
        return names;
    }
}
