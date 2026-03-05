package com.lifepilot.skill.marketplace.version;

import com.lifepilot.skill.marketplace.model.InstalledSkill;
import com.lifepilot.skill.marketplace.model.SkillPackage;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SemVer 版本解析器 — 提供版本比较、兼容性检查和升级检测。
 *
 * <p>版本格式：MAJOR.MINOR.PATCH，可选 "v" 前缀（如 "v1.2.3"）。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class VersionResolver {

    /**
     * 按 SemVer 规则比较两个版本号。
     *
     * <p>自动去除 "v" 前缀，按 MAJOR → MINOR → PATCH 数值逐段比较。</p>
     *
     * @param v1 版本号 A
     * @param v2 版本号 B
     * @return -1（v1 &lt; v2）、0（相等）、1（v1 &gt; v2）
     * @throws IllegalArgumentException 版本格式不合法
     */
    public int compareSemVer(String v1, String v2) {
        int[] parts1 = parseSemVer(v1);
        int[] parts2 = parseSemVer(v2);

        for (int i = 0; i < 3; i++) {
            if (parts1[i] < parts2[i]) return -1;
            if (parts1[i] > parts2[i]) return 1;
        }
        return 0;
    }

    /**
     * 检查当前应用版本是否满足最低版本要求。
     *
     * @param currentVersion 当前 LifePilot 版本
     * @param minVersion     Skill 要求的最低版本
     * @return true 表示兼容（当前版本 ≥ 最低版本）
     * @throws IllegalArgumentException 版本格式不合法
     */
    public boolean checkCompatibility(String currentVersion, String minVersion) {
        if (minVersion == null || minVersion.isBlank()) {
            return true;
        }
        return compareSemVer(currentVersion, minVersion) >= 0;
    }

    /**
     * 查找有可用更新的已安装 Skill 列表。
     *
     * <p>将 InstalledSkill.packageId 与 SkillPackage.id 匹配，
     * 比较版本号，返回远程版本高于本地版本的 SkillPackage 列表。</p>
     *
     * @param installedSkills 已安装 Skill 列表
     * @param remotePackages  远程可用 Skill 包列表
     * @return 有可用更新的 SkillPackage 列表
     */
    public List<SkillPackage> findUpdates(List<InstalledSkill> installedSkills,
                                          List<SkillPackage> remotePackages) {
        // 按 id 索引远程包，便于快速查找
        Map<String, SkillPackage> remoteMap = remotePackages.stream()
                .collect(Collectors.toMap(SkillPackage::id, Function.identity(), (a, b) -> {
                    // 相同 id 保留版本更高的
                    return compareSemVer(a.version(), b.version()) >= 0 ? a : b;
                }));

        return installedSkills.stream()
                .filter(installed -> {
                    var remote = remoteMap.get(installed.packageId());
                    if (remote == null) return false;
                    try {
                        return compareSemVer(remote.version(), installed.version()) > 0;
                    } catch (IllegalArgumentException e) {
                        // 版本格式不合法，跳过
                        return false;
                    }
                })
                .map(installed -> remoteMap.get(installed.packageId()))
                .toList();
    }

    /**
     * 解析 SemVer 版本字符串为 [MAJOR, MINOR, PATCH] 数组。
     *
     * @param version 版本字符串（支持 "v" 前缀）
     * @return 三元素整数数组
     * @throws IllegalArgumentException 格式不合法
     */
    private int[] parseSemVer(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("版本号不能为空");
        }

        String normalized = version.strip();
        // 去除 "v" 或 "V" 前缀
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        // 去除 SNAPSHOT 等后缀（取 "-" 之前的部分）
        int dashIndex = normalized.indexOf('-');
        if (dashIndex > 0) {
            normalized = normalized.substring(0, dashIndex);
        }

        String[] segments = normalized.split("\\.");
        if (segments.length != 3) {
            throw new IllegalArgumentException("版本号格式不合法，期望 MAJOR.MINOR.PATCH: " + version);
        }

        try {
            return new int[]{
                    Integer.parseInt(segments[0]),
                    Integer.parseInt(segments[1]),
                    Integer.parseInt(segments[2])
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本号包含非数字段: " + version, e);
        }
    }
}
