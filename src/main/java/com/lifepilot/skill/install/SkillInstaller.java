package com.lifepilot.skill.install;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 统一 Skill 安装入口。
 *
 * <p>四种来源（BUILTIN / USER_IMPORTED / MARKETPLACE / AUTO_GENERATED）都走同一条
 * 四步流水线：</p>
 * <ol>
 *   <li>parse —— 用 {@link MarkdownSkillParser} 解析 SKILL.md frontmatter + body</li>
 *   <li>validate —— 先 {@link SkillDescriptionValidator} 再 {@link SkillBodyValidator}</li>
 *   <li>writeFile —— 在 {@code targetDir/<name>/SKILL.md} 写入原始内容</li>
 *   <li>upsertDb —— {@link SkillInstallationRepository#upsert} 刷新 skills 表元数据</li>
 * </ol>
 *
 * <p>设计约束（plan B.2）：</p>
 * <ul>
 *   <li>任一步骤失败必须短路：不写文件、不入表（失败不残留）</li>
 *   <li>AUTO_GENERATED 不做人工确认，默认 {@code enabled = true}</li>
 *   <li>checksum 以 SKILL.md 原始文本 UTF-8 字节的 SHA-256 十六进制小写串为准</li>
 *   <li>不负责 references/scripts/assets 复制 —— 由上层调用方管理</li>
 *   <li>不负责 SkillRegistry 注册 —— 由 SkillDiscoveryRegistrar / SkillFileWatcher 承担（B.3）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
public class SkillInstaller {

    private final MarkdownSkillParser parser;
    private final SkillDescriptionValidator descriptionValidator;
    private final SkillBodyValidator bodyValidator;
    private final SkillInstallationRepository repository;

    public SkillInstaller(MarkdownSkillParser parser,
                          SkillDescriptionValidator descriptionValidator,
                          SkillBodyValidator bodyValidator,
                          SkillInstallationRepository repository) {
        this.parser = parser;
        this.descriptionValidator = descriptionValidator;
        this.bodyValidator = bodyValidator;
        this.repository = repository;
    }

    /**
     * 执行统一安装流水线。
     *
     * @param request 安装请求（来源类型 + 原始 SKILL.md 文本 + 目标目录 + 可选溯源字段）
     * @return 最终写入 skills 表的 {@link SkillInstallation} 记录
     * @throws IOException              写文件失败
     * @throws IllegalArgumentException parse / validate 任一环节不通过
     */
    public SkillInstallation install(InstallRequest request) throws IOException {
        // 1. 解析（parse 失败即短路，不写文件不入表）
        ParsedSkill parsed = parser.parse(request.skillMdContent());

        // 2. 校验（description + body 两道）
        descriptionValidator.validate(parsed.frontmatter().description());
        bodyValidator.validate(parsed.body());

        // 3. 写文件：targetDir/<name>/SKILL.md
        Path dir = request.targetDir().resolve(parsed.frontmatter().name());
        Files.createDirectories(dir);
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, request.skillMdContent(), StandardCharsets.UTF_8);

        // 4. Upsert 元数据到 skills 表
        String checksum = sha256(request.skillMdContent());
        Instant now = Instant.now();
        var install = new SkillInstallation(
                parsed.frontmatter().name(),
                request.sourceType(),
                request.sourceUri(),
                dir.toString(),
                parsed.frontmatter().version(),
                true,
                request.marketplaceId(),
                checksum,
                now,
                now,
                null);
        repository.upsert(install);
        return install;
    }

    /**
     * 统一安装请求。
     *
     * @param sourceType      安装来源类型（枚举四值之一）
     * @param sourceUri       溯源 URI（可空；BUILTIN 常为 classpath:... / USER_IMPORTED 为 file://... 等）
     * @param marketplaceId   Marketplace 唯一标识（仅 MARKETPLACE 来源需要，否则为 null）
     * @param skillMdContent  SKILL.md 文件完整文本
     * @param targetDir       目标根目录（真正落盘路径为 {@code targetDir/<name>/SKILL.md}）
     */
    public record InstallRequest(
            SkillSourceType sourceType,
            String sourceUri,
            String marketplaceId,
            String skillMdContent,
            Path targetDir
    ) {
        /** SKILL.md 原文长度上限（100K 字符），防御 SnakeYAML 解析巨量输入耗尽内存 / CPU。 */
        public static final int MAX_SKILL_MD_LENGTH = 100_000;

        public InstallRequest {
            if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
            if (skillMdContent == null || skillMdContent.isBlank())
                throw new IllegalArgumentException("skillMdContent 不能为空");
            if (skillMdContent.length() > MAX_SKILL_MD_LENGTH) {
                throw new IllegalArgumentException(
                        "skillMdContent 超过 " + MAX_SKILL_MD_LENGTH
                                + " 字符限制（当前 " + skillMdContent.length() + "）");
            }
            if (targetDir == null) throw new IllegalArgumentException("targetDir 不能为空");
        }
    }

    /**
     * 对给定字符串以 UTF-8 编码计算 SHA-256，输出 64 位小写十六进制串。
     */
    private static String sha256(String s) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须支持 SHA-256，理论上不可达
            throw new IllegalStateException("JVM 不支持 SHA-256 算法", e);
        }
    }
}
