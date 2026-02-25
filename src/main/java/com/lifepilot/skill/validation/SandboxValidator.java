package com.lifepilot.skill.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 沙箱验证器 — 在隔离环境中验证 Skill 定义的内部一致性。
 *
 * <p>校验规则：
 * <ul>
 *   <li>YAML 内容可成功解析为结构化 Map</li>
 *   <li>system-prompt 长度不超过 5000 字符（沙箱安全限制）</li>
 *   <li>工具列表数量不超过 10 个（沙箱安全限制）</li>
 * </ul></p>
 *
 * <p>使用临时文件进行解析验证，完成后清理临时文件。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SandboxValidator {

    private static final Logger log = LoggerFactory.getLogger(SandboxValidator.class);

    /** 沙箱安全限制：system-prompt 最大长度。 */
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 5000;

    /** 沙箱安全限制：工具列表最大数量。 */
    private static final int MAX_TOOLS_COUNT = 10;

    /**
     * 在沙箱中验证 YAML 内容。
     *
     * <p>验证流程：
     * <ol>
     *   <li>使用 SnakeYAML 解析 YAML 内容</li>
     *   <li>提取 skill 节点</li>
     *   <li>校验 system-prompt 长度</li>
     *   <li>校验 allowed-tools 数量</li>
     *   <li>使用临时文件验证 YAML 可被完整解析</li>
     * </ol></p>
     *
     * @param yamlContent YAML 字符串
     * @return 校验结果
     */
    @SuppressWarnings("unchecked")
    public SandboxValidationResult validate(String yamlContent) {
        List<String> errors = new ArrayList<>();

        // 1. 解析 YAML
        Map<String, Object> yamlMap;
        try {
            var yaml = new Yaml();
            Object parsed = yaml.load(yamlContent);
            if (!(parsed instanceof Map<?, ?> rawMap)) {
                log.debug("沙箱验证失败: YAML 解析结果不是 Map 类型");
                return new SandboxValidationResult(false, List.of("YAML 解析结果不是 Map 类型"));
            }
            yamlMap = (Map<String, Object>) rawMap;
        } catch (YAMLException e) {
            log.debug("沙箱验证失败: YAML 解析异常: {}", e.getMessage());
            return new SandboxValidationResult(false, List.of("YAML 解析失败: " + e.getMessage()));
        }

        // 2. 提取 skill 节点
        Object skillObj = yamlMap.get("skill");
        if (!(skillObj instanceof Map<?, ?> rawSkill)) {
            log.debug("沙箱验证失败: 缺少 skill 根节点");
            return new SandboxValidationResult(false, List.of("缺少 skill 根节点"));
        }
        Map<String, Object> skill = (Map<String, Object>) rawSkill;

        // 3. 校验 system-prompt 长度
        Object promptObj = skill.get("system-prompt");
        if (promptObj instanceof String systemPrompt) {
            if (systemPrompt.length() > MAX_SYSTEM_PROMPT_LENGTH) {
                errors.add("system-prompt 长度超过沙箱限制: " + systemPrompt.length()
                        + " > " + MAX_SYSTEM_PROMPT_LENGTH);
            }
        }

        // 4. 校验 allowed-tools 数量
        Object toolsObj = skill.get("allowed-tools");
        if (toolsObj instanceof List<?> toolsList) {
            if (toolsList.size() > MAX_TOOLS_COUNT) {
                errors.add("allowed-tools 数量超过沙箱限制: " + toolsList.size()
                        + " > " + MAX_TOOLS_COUNT);
            }
        }

        // 5. 使用临时文件验证 YAML 可被完整解析
        verifyWithTempFile(yamlContent, errors);

        boolean passed = errors.isEmpty();
        if (passed) {
            log.debug("沙箱验证通过");
        } else {
            log.debug("沙箱验证失败: 错误数={}", errors.size());
        }
        return new SandboxValidationResult(passed, errors);
    }

    /**
     * 使用临时文件验证 YAML 内容可被完整解析。
     *
     * <p>将 YAML 内容写入临时文件，尝试读取并解析，验证文件 I/O 和解析的完整性。
     * 完成后清理临时文件。</p>
     *
     * @param yamlContent YAML 字符串
     * @param errors      错误收集列表
     */
    private void verifyWithTempFile(String yamlContent, List<String> errors) {
        Path tempFile = null;
        try {
            // 创建临时文件
            tempFile = Files.createTempFile("sandbox-skill-", ".yml");
            Files.writeString(tempFile, yamlContent);

            // 从临时文件读取并解析，验证完整性
            String content = Files.readString(tempFile);
            var yaml = new Yaml();
            Object parsed = yaml.load(content);
            if (!(parsed instanceof Map<?, ?>)) {
                errors.add("临时文件解析验证失败: 解析结果不是 Map 类型");
            }
        } catch (IOException e) {
            errors.add("临时文件操作失败: " + e.getMessage());
            log.debug("沙箱验证临时文件操作失败: {}", e.getMessage());
        } catch (YAMLException e) {
            errors.add("临时文件 YAML 解析失败: " + e.getMessage());
            log.debug("沙箱验证临时文件解析失败: {}", e.getMessage());
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.debug("清理沙箱临时文件失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 沙箱验证结果。
     *
     * @param passed 是否通过
     * @param errors 错误信息列表
     */
    public record SandboxValidationResult(boolean passed, List<String> errors) {
        /** 防御性拷贝。 */
        public SandboxValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
