use std::time::{Duration, Instant};
use std::io::{Read, Write};

/// 轮询后端健康检查端点，直到返回 HTTP 200 或超时。
///
/// 使用原生 TcpStream + 手写 HTTP/1.1 请求，避免 reqwest 在 Windows release 模式下
/// 可能出现的 native-tls 初始化阻塞或 async runtime 竞态问题。
pub async fn wait_for_backend(port: u16, timeout: Duration) -> Result<(), String> {
    let start = Instant::now();
    let interval = Duration::from_millis(500);

    loop {
        if start.elapsed() > timeout {
            return Err(format!(
                "后端健康检查超时（已等待 {}s）",
                timeout.as_secs()
            ));
        }

        match try_health_check(port) {
            Ok(true) => {
                log::info!("后端健康检查通过（端口 {}）", port);
                return Ok(());
            }
            Ok(false) => {
                log::debug!("健康检查响应非 200");
            }
            Err(e) => {
                log::debug!("健康检查连接失败: {}", e);
            }
        }

        tokio::time::sleep(interval).await;
    }
}

/// 使用原生 TCP 连接发送 HTTP GET 请求并检查响应状态码
fn try_health_check(port: u16) -> Result<bool, String> {
    use std::net::TcpStream;

    let addr = format!("127.0.0.1:{}", port);
    let mut stream = TcpStream::connect_timeout(
        &addr.parse().map_err(|e| format!("地址解析失败: {}", e))?,
        Duration::from_secs(2),
    )
    .map_err(|e| format!("连接失败: {}", e))?;

    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .map_err(|e| format!("设置读超时失败: {}", e))?;

    let request = format!(
        "GET /actuator/health HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nConnection: close\r\n\r\n",
        port
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|e| format!("发送请求失败: {}", e))?;

    let mut response = Vec::new();
    if let Err(e) = stream.read_to_end(&mut response) {
        log::trace!("读取健康检查响应失败: {}", e);
    }

    let response_str = String::from_utf8_lossy(&response);
    // 检查 HTTP 状态行是否包含 "200"
    Ok(response_str.starts_with("HTTP/1.1 200") || response_str.starts_with("HTTP/1.0 200"))
}
