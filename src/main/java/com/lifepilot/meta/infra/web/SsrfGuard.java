package com.lifepilot.meta.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF（Server-Side Request Forgery）防护守卫 — 在 HTTP 请求发出前校验目标 URL。
 *
 * <p>拦截清单：
 * <ul>
 *   <li>非 http/https 协议（file/ftp/jar 等）</li>
 *   <li>IPv4 loopback（127.0.0.0/8）、RFC1918（10/8、172.16/12、192.168/16）、link-local（169.254/16，含云 metadata）、0.0.0.0</li>
 *   <li>IPv6 loopback（::1）、unique-local（fc00::/7）、link-local（fe80::/10）</li>
 *   <li>已知云 metadata 域名（无需 DNS 即拦截，防止域名 → 公网代理绕过）</li>
 * </ul>
 * </p>
 *
 * <p>防 DNS rebinding：对 {@link InetAddress#getAllByName(String)} 返回的<strong>每一个</strong> IP 做校验，
 * 任意一个命中黑名单即拦截，避免攻击者通过动态 DNS 将恶意域名解析到内网 IP。</p>
 *
 * <p>allowlist 支持：企业内网场景可把必要的内部域名/IP 写入配置放行，
 * allowlist 匹配发生在 DNS 解析前（快判）和 IP 校验后（IP 文本匹配）。</p>
 *
 * <p>可通过 {@link #enabled()} 或 {@link #disabled()} 静态工厂快速构建，
 * 测试场景下常用 {@code disabled()} 放行本地 127.0.0.1 测试服务器。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    /** 允许的 URL scheme — 其他协议一律拒绝。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * 已知云 metadata 域名集合（小写）— 无需 DNS 解析即拦截。
     *
     * <p>防止 {@code metadata.google.internal} 等域名被配置的公网 DNS 解析到代理地址后绕过 IP 检查。</p>
     */
    private static final Set<String> CLOUD_METADATA_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.aws.internal",
            "metadata.azure.com",
            "169.254.169.254"
    );

    /** 云 metadata 域名前缀匹配（如 {@code instance-data.ec2.internal}）。 */
    private static final List<String> CLOUD_METADATA_PREFIXES = List.of(
            "instance-data"
    );

    /** localhost 别名集合。 */
    private static final Set<String> LOCALHOST_ALIASES = Set.of(
            "localhost",
            "localhost.localdomain",
            "ip6-localhost",
            "ip6-loopback"
    );

    private final boolean enabled;
    private final List<String> allowlist;

    /**
     * @param enabled 是否启用拦截。{@code false} 时 {@link #check(String)} 直接返回不做任何校验。
     * @param allowlist 允许的 host / IP 字面量列表（不区分大小写），命中任一即放行。
     */
    public SsrfGuard(boolean enabled, List<String> allowlist) {
        this.enabled = enabled;
        this.allowlist = allowlist == null
                ? List.of()
                : allowlist.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .toList();
    }

    /** 默认启用、无 allowlist 的守卫 — 生产默认配置。 */
    public static SsrfGuard enabled() {
        return new SsrfGuard(true, List.of());
    }

    /** 禁用守卫 — 测试场景放行本地服务器专用，不要在生产使用。 */
    public static SsrfGuard disabled() {
        return new SsrfGuard(false, List.of());
    }

    /**
     * 校验目标 URL 是否安全可访问，违规时抛 {@link SsrfBlockedException}。
     *
     * <p>流程：
     * <ol>
     *     <li>URL 非空 + 可解析校验</li>
     *     <li>scheme 必须是 http / https</li>
     *     <li>host 非空</li>
     *     <li>host 命中云 metadata 域名 → 拦截（先于 allowlist，避免 allowlist 误配）</li>
     *     <li>host 命中 allowlist → 放行</li>
     *     <li>host 是 localhost 别名 → 拦截</li>
     *     <li>DNS 解析出的<strong>每一个</strong> IP 校验，命中黑名单即拦截；allowlist 中的 IP 文本匹配放行</li>
     * </ol>
     * </p>
     */
    public void check(String url) {
        if (!enabled) {
            return;
        }

        if (url == null || url.isBlank()) {
            throw new SsrfBlockedException("URL 为空");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new SsrfBlockedException("非法 URL 格式: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new SsrfBlockedException("不支持的协议: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("URL 缺少主机名: " + url);
        }

        String normalizedHost = normalizeHost(host);

        // 云 metadata 域名拦截 — 先于 allowlist，避免误配导致暴露云凭据端点
        if (isCloudMetadataHost(normalizedHost)) {
            throw new SsrfBlockedException("云 metadata 域名禁止访问: " + normalizedHost);
        }

        // allowlist 按 host 文本快判
        if (isAllowlisted(normalizedHost)) {
            log.debug("SSRF 放行（allowlist 命中 host）: {}", normalizedHost);
            return;
        }

        // localhost 别名拦截（在 allowlist 之后，企业若显式配置 localhost 也允许放行）
        if (LOCALHOST_ALIASES.contains(normalizedHost)) {
            throw new SsrfBlockedException("禁止访问 localhost: " + normalizedHost);
        }

        // DNS 全 IP 校验 — 防 DNS rebinding
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalizedHost);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("DNS 解析失败: " + normalizedHost);
        }

        for (InetAddress addr : addresses) {
            String ipText = addr.getHostAddress();
            // allowlist 放行单个 IP（企业配置 "127.0.0.1" 时生效）
            if (isAllowlisted(ipText.toLowerCase(Locale.ROOT))) {
                log.debug("SSRF 放行（allowlist 命中 IP）: host={}, ip={}", normalizedHost, ipText);
                continue;
            }
            String blockReason = classifyBlockedAddress(addr);
            if (blockReason != null) {
                throw new SsrfBlockedException(
                        "禁止访问内网地址（%s → %s）: %s".formatted(normalizedHost, ipText, blockReason));
            }
        }
    }

    /**
     * 校验 {@link InetAddress}，若命中黑名单返回拦截原因，放行则返回 null。
     */
    private String classifyBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            return "loopback";
        }
        if (addr.isAnyLocalAddress()) {
            return "any-local (0.0.0.0/::)";
        }
        if (addr.isLinkLocalAddress()) {
            return "link-local";
        }
        if (addr.isSiteLocalAddress()) {
            // Java 的 isSiteLocal 覆盖 10/8、172.16/12、192.168/16
            return "IPv4 site-local (RFC1918)";
        }
        if (addr instanceof Inet4Address ipv4) {
            // 双重保险：手判 172.16/12（Java 已经覆盖，留作防御性代码）
            byte[] b = ipv4.getAddress();
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 172 && second >= 16 && second <= 31) {
                return "IPv4 RFC1918 (172.16/12)";
            }
            // 手判 169.254/16 link-local（Java 已覆盖，防御）
            if (first == 169 && second == 254) {
                return "IPv4 link-local (169.254/16)";
            }
        } else if (addr instanceof Inet6Address ipv6) {
            byte[] b = ipv6.getAddress();
            int firstByte = b[0] & 0xff;
            // fc00::/7 unique-local，首字节 1111110x（0xFC 或 0xFD）
            if ((firstByte & 0xfe) == 0xfc) {
                return "IPv6 unique-local (fc00::/7)";
            }
            // fe80::/10 link-local，首 10 位 1111111010，即 firstByte=0xFE 且第 2 字节高 2 位 10
            if (firstByte == 0xfe && ((b[1] & 0xc0) == 0x80)) {
                return "IPv6 link-local (fe80::/10)";
            }
        }
        return null;
    }

    /**
     * 规范化 host —— 小写 + 去掉 IPv6 字面量的方括号。
     *
     * <p>{@link URI#getHost()} 对 IPv6 会返回带 {@code [ ]} 的字面量，
     * 但 {@link InetAddress#getByName(String)} 的数字字面量不应带方括号。</p>
     */
    private String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            return lower.substring(1, lower.length() - 1);
        }
        return lower;
    }

    private boolean isCloudMetadataHost(String host) {
        if (CLOUD_METADATA_HOSTS.contains(host)) {
            return true;
        }
        for (String prefix : CLOUD_METADATA_PREFIXES) {
            if (host.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowlisted(String hostOrIp) {
        return allowlist.contains(hostOrIp);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getAllowlist() {
        return new ArrayList<>(allowlist);
    }
}
