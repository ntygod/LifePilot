use std::time::Duration;

use tauri::AppHandle;

/// 剪贴板轮询间隔（秒）
const POLL_INTERVAL_SECS: u64 = 3;

/// 启动剪贴板监控 — 检测剪贴板变化并识别结构化意图。
pub fn start_clipboard_monitor(_app: AppHandle, backend_port: u16) {
    std::thread::Builder::new()
        .name("clipboard-monitor".to_string())
        .spawn(move || {
            log::info!("剪贴板监控启动: interval={}s, port={}", POLL_INTERVAL_SECS, backend_port);
            let mut last_content = String::new();
            loop {
                std::thread::sleep(Duration::from_secs(POLL_INTERVAL_SECS));
                if let Some(content) = read_clipboard() {
                    if content != last_content && !content.is_empty() {
                        last_content = content.clone();
                        if let Some(intent) = detect_intent(&content) {
                            report_to_backend(backend_port, &intent);
                        }
                    }
                }
            }
        })
        .expect("剪贴板监控线程创建失败");
}

/// 读取系统剪贴板文本
fn read_clipboard() -> Option<String> {
    #[cfg(target_os = "windows")]
    {
        read_clipboard_windows()
    }
    #[cfg(not(target_os = "windows"))]
    {
        None
    }
}

#[cfg(target_os = "windows")]
fn read_clipboard_windows() -> Option<String> {
    use windows::Win32::System::DataExchange::{
        CloseClipboard, GetClipboardData, OpenClipboard,
    };
    use windows::Win32::System::Memory::{GlobalLock, GlobalUnlock};
    use windows::Win32::System::Ole::CF_UNICODETEXT;

    unsafe {
        if OpenClipboard(None).is_err() {
            return None;
        }
        let result = (|| {
            let handle = GetClipboardData(CF_UNICODETEXT.0 as u32).ok()?;
            let hmem = windows::Win32::Foundation::HGLOBAL(handle.0);
            let ptr = GlobalLock(hmem) as *const u16;
            if ptr.is_null() {
                return None;
            }
            let mut len = 0;
            while *ptr.add(len) != 0 {
                len += 1;
            }
            let slice = std::slice::from_raw_parts(ptr, len);
            let text = String::from_utf16_lossy(slice);
            let _ = GlobalUnlock(hmem);
            Some(text.trim().to_string())
        })();
        let _ = CloseClipboard();
        result
    }
}

/// 检测剪贴板内容中的结构化意图。
///
/// 使用搜索模式（非锚定），从剪贴板文本中提取匹配片段，
/// 允许单号前后有其他文字（如"快递单号：SF1234567890123"）。
fn detect_intent(content: &str) -> Option<ClipboardIntent> {
    let trimmed = content.trim();

    // 快递单号：顺丰(SF)、京东(JD)、圆通(YT)、韵达(YD)、中通(ZTO)、申通(STO)、EMS
    if let Some(m) = regex_search(trimmed, r"(?i)\b(?:SF|JD|YT|YD|ZTO|STO|EMS)\d{10,18}\b") {
        return Some(ClipboardIntent {
            intent_type: "TRACKING_NUMBER".to_string(),
            value: m.to_uppercase(),
        });
    }

    // 纯数字运单号（12-18位，前后需要有边界）
    if let Some(m) = regex_search(trimmed, r"(?<!\d)\d{12,18}(?!\d)") {
        // 排除明显不是运单的情况（如手机号11位、身份证18位但含X等）
        let len = m.len();
        if (12..=18).contains(&len) {
            return Some(ClipboardIntent {
                intent_type: "TRACKING_NUMBER".to_string(),
                value: m,
            });
        }
    }

    // 航班号：两字母+3-4位数字（不区分大小写）
    if let Some(m) = regex_search(trimmed, r"(?i)\b[A-Z]{2}\d{3,4}\b") {
        return Some(ClipboardIntent {
            intent_type: "FLIGHT_NUMBER".to_string(),
            value: m.to_uppercase(),
        });
    }

    // 车次：G/D/C/Z/T/K + 数字（不区分大小写）
    if let Some(m) = regex_search(trimmed, r"(?i)\b[GCDZTK]\d{1,4}\b") {
        return Some(ClipboardIntent {
            intent_type: "TRAIN_NUMBER".to_string(),
            value: m.to_uppercase(),
        });
    }

    None
}

/// 在文本中搜索匹配片段（非锚定），返回第一个匹配。
fn regex_search(text: &str, pattern: &str) -> Option<String> {
    let re = regex::Regex::new(pattern).ok()?;
    re.find(text).map(|m| m.as_str().to_string())
}

/// 上报剪贴板意图到后端
fn report_to_backend(port: u16, intent: &ClipboardIntent) {
    let url = format!("http://127.0.0.1:{}/api/context/clipboard-intent", port);
    let body = serde_json::json!({
        "intentType": intent.intent_type,
        "value": intent.value,
    });

    log::info!("剪贴板意图识别: type={}, value={}", intent.intent_type, intent.value);

    match reqwest::blocking::Client::new()
        .post(&url)
        .json(&body)
        .timeout(Duration::from_secs(3))
        .send()
    {
        Ok(resp) => log::info!(
            "剪贴板意图上报成功: status={}", resp.status()
        ),
        Err(e) => log::warn!("剪贴板意图上报失败: {}", e),
    }
}

struct ClipboardIntent {
    intent_type: String,
    value: String,
}
