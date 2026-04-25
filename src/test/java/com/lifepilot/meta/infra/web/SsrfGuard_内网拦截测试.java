package com.lifepilot.meta.infra.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SsrfGuard 内网地址和云 metadata 拦截测试。
 *
 * <p>覆盖范围：
 * <ul>
 *     <li>IPv4 loopback / RFC1918 私网 / link-local 拦截</li>
 *     <li>IPv6 loopback / unique-local / link-local 拦截</li>
 *     <li>云 metadata 域名拦截（无需 DNS 解析即触发）</li>
 *     <li>allowlist 放行和 disabled 模式全放行</li>
 *     <li>非法 URL 防御性拦截</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
class SsrfGuard_内网拦截测试 {

    @Test
    void 拦截_IPv4_loopback() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://127.0.0.1/admin"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("127.0.0.1");
        assertThatThrownBy(() -> guard.check("http://127.1.2.3/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_RFC1918_私网段() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://10.0.0.1/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://172.16.0.1/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://192.168.1.1/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_link_local_云_metadata() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_IPv6_loopback() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://[::1]/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_IPv6_unique_local() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://[fc00::1]/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://[fd12:3456::1]/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_IPv6_link_local() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://[fe80::1]/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_云_metadata_域名() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://metadata.google.internal/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://metadata.aws.internal/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://metadata.azure.com/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://instance-data.ec2.internal/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_国产云_metadata_域名() {
        var guard = SsrfGuard.enabled();
        // 阿里云
        assertThatThrownBy(() -> guard.check("http://metadata.aliyuncs.com/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check("http://100.100.100.200/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class);
        // 腾讯云
        assertThatThrownBy(() -> guard.check("http://metadata.tencentyun.com/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class);
        // 华为云
        assertThatThrownBy(() -> guard.check("http://metadata.huaweicloud.com/openstack/latest/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_meta_data_前缀变体() {
        var guard = SsrfGuard.enabled();
        // 形如 meta-data.xxx 的变体仍应被前缀匹配拦截
        assertThatThrownBy(() -> guard.check("http://meta-data.internal.corp/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_0_0_0_0_任意地址() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://0.0.0.0/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 拦截_localhost_域名() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("http://localhost/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void allowlist_放行_IP_和域名() {
        var guard = new SsrfGuard(true, List.of("127.0.0.1", "internal.corp"));
        assertThatCode(() -> guard.check("http://127.0.0.1/")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("http://internal.corp/")).doesNotThrowAnyException();
        // allowlist 以外的内网仍应被拦截
        assertThatThrownBy(() -> guard.check("http://192.168.1.1/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 禁用时全部放行() {
        var guard = SsrfGuard.disabled();
        assertThatCode(() -> guard.check("http://127.0.0.1/")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("http://169.254.169.254/")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("http://metadata.google.internal/")).doesNotThrowAnyException();
    }

    @Test
    void 无效_URL_抛异常() {
        var guard = SsrfGuard.enabled();
        assertThatThrownBy(() -> guard.check("not-a-url"))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check(""))
                .isInstanceOf(SsrfBlockedException.class);
        assertThatThrownBy(() -> guard.check(null))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void 禁止_file_协议() {
        var guard = SsrfGuard.enabled();
        // 非 http/https 协议应被拦截，避免 file://、ftp:// 等协议绕过
        assertThatThrownBy(() -> guard.check("file:///etc/passwd"))
                .isInstanceOf(SsrfBlockedException.class);
    }
}
