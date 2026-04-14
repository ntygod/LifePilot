package com.lifepilot.skill.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 模板库 — 提供高质量 SKILL.md 模板作为生成参考。
 *
 * <p>模板从 classpath:skill-templates/ 目录加载，按场景分类。
 * SkillGenerator 根据缺口描述选择最匹配的模板注入 Prompt。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class SkillTemplateLibrary {

    private static final Logger log = LoggerFactory.getLogger(SkillTemplateLibrary.class);

    /** 内置最小模板（加载失败时的降级方案）。 */
    private static final SkillTemplate DEFAULT_TEMPLATE = new SkillTemplate(
            "default-general",
            TemplateScene.GENERAL,
            """
            ---
            id: generated-skill
            name: 自动生成 Skill
            description: 根据用户需求自动生成的 Skill
            version: 1.0.0
            suggestedTools: []
            maxSteps: 10
            timeoutSeconds: 60
            ---
            
            # 自动生成 Skill
            
            根据用户请求分析所需工具，按步骤完成任务。
            """
    );

    /** 模板场景分类。 */
    public enum TemplateScene {
        /** 数据管理类（CRUD 操作）。 */
        DATA_MANAGEMENT,
        /** 信息收集类（搜索 + 抓取 + 整理）。 */
        INFORMATION_GATHER,
        /** 文件处理类（读写 + 转换）。 */
        FILE_PROCESSING,
        /** 自动化类（Shell + 代码执行）。 */
        AUTOMATION,
        /** 浏览器任务类（导航 + 交互）。 */
        BROWSER_TASK,
        /** 通用类。 */
        GENERAL
    }

    /** 文件名到场景的映射。 */
    private static final Map<String, TemplateScene> FILENAME_SCENE_MAP = Map.of(
            "data-management.md", TemplateScene.DATA_MANAGEMENT,
            "information-gather.md", TemplateScene.INFORMATION_GATHER,
            "file-processing.md", TemplateScene.FILE_PROCESSING,
            "automation.md", TemplateScene.AUTOMATION,
            "browser-task.md", TemplateScene.BROWSER_TASK,
            "general.md", TemplateScene.GENERAL
    );

    private final Map<TemplateScene, List<SkillTemplate>> templates;

    /** 启动时从 classpath 加载模板。 */
    public SkillTemplateLibrary() {
        this.templates = loadTemplates();
    }

    /**
     * 根据缺口描述选择最匹配的模板。
     *
     * @param gap 缺口描述
     * @return 最匹配的模板，无匹配时返回 GENERAL 类型的默认模板
     */
    public SkillTemplate findBestTemplate(SkillGap gap) {
        TemplateScene scene = classifyScene(gap);
        var sceneTemplates = templates.getOrDefault(scene, List.of());
        if (!sceneTemplates.isEmpty()) {
            return sceneTemplates.getFirst();
        }
        // 尝试 GENERAL 分组
        var generalTemplates = templates.getOrDefault(TemplateScene.GENERAL, List.of());
        return generalTemplates.isEmpty() ? DEFAULT_TEMPLATE : generalTemplates.getFirst();
    }

    /**
     * 根据缺口的 suggestedTools 推断场景。
     *
     * @param gap 缺口描述
     * @return 推断的模板场景
     */
    TemplateScene classifyScene(SkillGap gap) {
        var tools = gap.suggestedTools();
        if (tools.stream().anyMatch(t -> t.contains("datastore"))) return TemplateScene.DATA_MANAGEMENT;
        if (tools.stream().anyMatch(t -> t.contains("browser"))) return TemplateScene.BROWSER_TASK;
        if (tools.stream().anyMatch(t -> t.contains("file"))) return TemplateScene.FILE_PROCESSING;
        if (tools.stream().anyMatch(t -> t.contains("shell") || t.contains("code"))) return TemplateScene.AUTOMATION;
        if (tools.stream().anyMatch(t -> t.contains("web"))) return TemplateScene.INFORMATION_GATHER;
        return TemplateScene.GENERAL;
    }

    /**
     * 从 classpath:skill-templates/ 加载模板文件。
     *
     * @return 按场景分组的模板映射
     */
    private Map<TemplateScene, List<SkillTemplate>> loadTemplates() {
        var result = new EnumMap<TemplateScene, List<SkillTemplate>>(TemplateScene.class);
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skill-templates/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                TemplateScene scene = FILENAME_SCENE_MAP.getOrDefault(filename, TemplateScene.GENERAL);
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                var template = new SkillTemplate(filename.replace(".md", ""), scene, content);
                result.computeIfAbsent(scene, k -> new java.util.ArrayList<>()).add(template);
            }
            log.info("Skill 模板库加载完成: count={}", result.values().stream().mapToInt(List::size).sum());
        } catch (IOException e) {
            log.warn("Skill 模板库加载失败，降级为内置默认模板: error={}", e.getMessage());
        }
        return Map.copyOf(result.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue())
                )));
    }
}
