use std::net::TcpListener;

/// 查找一个可用的 TCP 端口。
///
/// 已知限制（TOCTOU）：端口在 TcpListener Drop 后释放，Java 绑定前理论上可能被其他进程抢占。
/// 实际发生概率极低，且 Java 启动失败后 health_check 超时会触发重试。
pub fn find_available_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .expect("无法绑定到空闲端口")
        .local_addr()
        .expect("无法获取本地地址")
        .port()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 查找空闲端口() {
        let port = find_available_port();
        assert!(port > 0);
    }
}
