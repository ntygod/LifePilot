#!/usr/bin/env python3
"""日志脱敏：从 stdin 读入，按正则替换敏感字段后输出到 stdout。

用法:
  cat app.log | python log-redact.py
  shell_exec(command="cat app.log | python {skill_scripts_dir}/log-redact.py")

替换规则:
  IPv4 → xxx.xxx.xxx.***    邮箱 → ***@domain
  手机号 → 1xx****          Token/Key → ***
"""

import re
import sys

RULES = [
    # IPv4
    (re.compile(r"(\d{1,3}\.){3}\d{1,3}"), "xxx.xxx.xxx.***"),
    # 邮箱
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "***@domain"),
    # 手机号
    (re.compile(r"1[3-9]\d{9}"), "1xx****"),
    # Token/Key/Secret
    (re.compile(r"(token|key|secret|password)['\":\s=]+[A-Za-z0-9+/=._-]{8,}", re.IGNORECASE),
     lambda m: f"{m.group(1)}=***"),
    # Authorization header
    (re.compile(r"Authorization['\":\s]*Bearer\s+[A-Za-z0-9+/=._-]+", re.IGNORECASE),
     "Authorization: Bearer ***"),
    # JWT
    (re.compile(r"eyJ[a-zA-Z0-9_-]*\.eyJ[a-zA-Z0-9_-]*\.[a-zA-Z0-9_-]+"), "<JWT>"),
]


def redact(text):
    for pattern, replacement in RULES:
        if callable(replacement):
            text = pattern.sub(replacement, text)
        else:
            text = pattern.sub(replacement, text)
    return text


def main():
    for line in sys.stdin:
        sys.stdout.write(redact(line))


if __name__ == "__main__":
    main()
