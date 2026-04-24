package com.lifepilot.skill.validation;

import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Skill 运行期依赖门控器 —— 加载期判断 bins / env / os / tools 是否满足。
 * 不满足的 skill 将从 ContextAssembler.buildSkillCatalog() 输出中剔除。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillRequirementGate {

    private final DynamicToolRegistry toolRegistry;
    private final Supplier<String> osSupplier;
    private final Function<String, Boolean> binResolver;
    private final Function<String, String> envResolver;

    /** 生产构造器：自动检测当前 OS + shell 探测 bin + 读 env 变量。 */
    @Autowired
    public SkillRequirementGate(DynamicToolRegistry toolRegistry) {
        this(toolRegistry, SkillRequirementGate::currentOs,
                SkillRequirementGate::binaryExistsOnPath, System::getenv);
    }

    /** 测试用构造器：允许注入自定义 OS / bin / env 解析器。 */
    public SkillRequirementGate(DynamicToolRegistry toolRegistry,
                                 Supplier<String> osSupplier,
                                 Function<String, Boolean> binResolver,
                                 Function<String, String> envResolver) {
        this.toolRegistry = toolRegistry;
        this.osSupplier = osSupplier;
        this.binResolver = binResolver;
        this.envResolver = envResolver;
    }

    /**
     * 判断指定 requires 是否全部满足。
     * 任一维度（bin / env / os / tool）不满足即拒绝。
     */
    public boolean satisfies(SkillRequires requires) {
        for (String bin : requires.bins()) {
            if (!binResolver.apply(bin)) {
                return false;
            }
        }
        for (String env : requires.env()) {
            String val = envResolver.apply(env);
            if (val == null || val.isBlank()) {
                return false;
            }
        }
        if (!requires.os().isEmpty() && !requires.os().contains(osSupplier.get())) {
            return false;
        }
        for (String toolId : requires.tools()) {
            if (toolRegistry.resolve(toolId).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 当前操作系统标识：windows / darwin / linux。 */
    private static String currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        return "linux";
    }

    /**
     * 生产环境 bin 探测：执行 {@code bin --version}，2 秒内成功退出视为存在。
     * Windows 下对无 --version 支持的工具可能误判，后续可迭代为更鲁棒的 PATH 搜索。
     */
    private static boolean binaryExistsOnPath(String bin) {
        try {
            var p = Runtime.getRuntime().exec(new String[]{bin, "--version"});
            return p.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
