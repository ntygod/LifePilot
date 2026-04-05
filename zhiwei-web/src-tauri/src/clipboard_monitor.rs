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

/// 检测剪贴板内容中的结构化意图
fn detect_intent(content: &str) -> Option<ClipboardIntent> {
    let trimmed = content.trim();

    // 快递单号：顺丰(SF)、中通(ZTO)、圆通(YT)、韵达(YD)、申通等
    if let Some(m) = regex_match(trimmed, r"^(?:SF|JD|YT|YD|ZTO|STO|EMS)\d{10,18}$") {
        return Some(ClipboardIntent {
            intent_type: "TRACKING_NUMBER".to_string(),
            value: m,
        });
    }
    // 纯数字运单号（12-18位）
    if let Some(m) = regex_match(trimmed, r"^\d{12,18}$") {
        return Some(ClipboardIntent {
            intent_type: "TRACKING_NUMBER".to_string(),
            value: m,
        });
    }

    // 航班号：两字母+3-4位数字
    if let Some(m) = regex_match(trimmed, r"^[A-Z]{2}\d{3,4}$") {
        return Some(ClipboardIntent {
            intent_type: "FLIGHT_NUMBER".to_string(),
            value: m,
        });
    }

    // 车次：G/D/C/Z/T/K + 数字
    if let Some(m) = regex_match(trimmed, r"^[GCDZTK]\d{1,4}$") {
        return Some(ClipboardIntent {
            intent_type: "TRAIN_NUMBER".to_string(),
            value: m,
        });
    }

    None
    // URL 和手机号故意不检测 — 太频繁，噪音大
}

fn regex_match(text: &str, pattern: &str) -> Option<String> {
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
        Ok(_) => log::debug!("剪贴板意图上报成功"),
        Err(e) => log::debug!("剪贴板意图上报失败: {}", e),
    }
}

struct ClipboardIntent {
    intent_type: String,
    value: String,
}
