package com.lifepilot.interaction.web.a2ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.interaction.web.model.A2uiComponentTree;

/**
 * A2UI 组件树校验器。
 * <p>
 * 校验规则：
 * <ol>
 *   <li>每个组件的 id 非空且在树内唯一</li>
 *   <li>每个组件的 children 引用的 ID 在树内存在</li>
 *   <li>组件数量不超过 maxComponentsPerTree（超出时截断并记录 WARN）</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-11
 */
public class A2uiComponentValidator {

    private static final Logger log = LoggerFactory.getLogger(A2uiComponentValidator.class);

    /**
     * 校验结果。
     *
     * @param valid        是否通过校验（截断不算校验失败）
     * @param errors       校验错误列表
     * @param truncatedTree 截断后的组件树（仅当组件数量超限时非 null）
     */
    public record ValidationResult(boolean valid, List<String> errors, @Nullable A2uiComponentTree truncatedTree) {

        /**
         * 便捷构造：无截断场景。
         */
        public ValidationResult(boolean valid, List<String> errors) {
            this(valid, List.copyOf(errors), null);
        }

        public ValidationResult(boolean valid, List<String> errors, @Nullable A2uiComponentTree truncatedTree) {
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.truncatedTree = truncatedTree;
        }
    }

    private A2uiComponentValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验组件树合法性。
     *
     * @param tree                 组件树
     * @param maxComponentsPerTree 最大组件数量限制
     * @return 校验结果
     */
    public static ValidationResult validate(A2uiComponentTree tree, int maxComponentsPerTree) {
        if (tree == null) {
            return new ValidationResult(false, List.of("组件树不能为 null"));
        }

        var errors = new ArrayList<String>();
        var components = tree.components();
        A2uiComponentTree truncatedTree = null;

        // 规则 3：组件数量超限时截断
        if (components.size() > maxComponentsPerTree) {
            log.warn("A2UI 组件树超过最大限制: count={}, max={}, 将截断到 {} 个组件",
                    components.size(), maxComponentsPerTree, maxComponentsPerTree);
            errors.add("组件数量超过限制 %d，已截断到 %d 个".formatted(components.size(), maxComponentsPerTree));
            components = components.subList(0, maxComponentsPerTree);
            truncatedTree = new A2uiComponentTree(components);
        }

        // 收集所有 id 用于后续校验
        var idSet = new HashSet<String>();
        var duplicateIds = new HashSet<String>();

        for (var component : components) {
            // 规则 1：id 非空
            if (component.id() == null || component.id().isBlank()) {
                errors.add("组件 id 不能为空");
                continue;
            }
            // 规则 1：id 唯一
            if (!idSet.add(component.id())) {
                duplicateIds.add(component.id());
            }
        }

        if (!duplicateIds.isEmpty()) {
            errors.add("组件 id 重复: %s".formatted(duplicateIds));
        }

        // 规则 2：children 引用的 ID 必须在树内存在
        for (var component : components) {
            if (component.id() == null || component.id().isBlank()) {
                continue; // 已在上面报告过空 id 错误
            }
            for (var childId : component.children()) {
                if (!idSet.contains(childId)) {
                    errors.add("组件 '%s' 的 children 引用了不存在的 id: '%s'".formatted(component.id(), childId));
                }
            }
        }

        // 截断不算校验失败；id 为空、id 重复、children 引用缺失才算校验失败
        boolean hasStructuralErrors = !duplicateIds.isEmpty()
                || errors.stream().anyMatch(e -> e.contains("id 不能为空") || e.contains("引用了不存在的 id"));

        return new ValidationResult(!hasStructuralErrors, errors, truncatedTree);
    }
}
