use std::time::{Duration, Instant};

/// 轮询后端健康检查端点，直到返回 UP 或超时
pub async fn wait_for_backend(port: u16, timeout: Duration) -> Result<(), String> {
    let url = format!("http://127.0.0.1:{}/actuator/health", port);
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(2))
        .build()
        .map_err(|e| format!("创建 HTTP 客户端失败: {}", e))?;

    let start = Instant::now();
    let interval = Duration::from_millis(500);

    loop {
        if start.elapsed() > timeout {
            return Err(format!(
                "后端健康检查超时（已等待 {}s）",
                timeout.as_secs()
            ));
        }

        match client.get(&url).send().await {
            Ok(resp) if resp.status().is_success() => {
                log::info!("后端健康检查通过（端口 {}）", port);
                return Ok(());
            }
            Ok(resp) => {
                log::debug!("健康检查响应状态: {}", resp.status());
            }
            Err(e) => {
                log::debug!("健康检查连接失败: {}", e);
            }
        }

        tokio::time::sleep(interval).await;
    }
}
