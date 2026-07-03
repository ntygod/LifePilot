#!/usr/bin/env python3
"""系统一键诊断：CPU/内存/磁盘/端口/进程，输出 JSON。

用法:
  python diagnose.py [--json]

LLM 调用示例:
  shell.exec(command="python {skill_scripts_dir}/diagnose.py --json")
"""

import json
import os
import platform
import subprocess
import sys


def run(cmd, timeout=15):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip() or r.stderr.strip()
    except Exception as e:
        return f"error: {e}"


def get_cpu():
    if platform.system() == "Windows":
        return run("powershell -c \"(Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 1 -MaxSamples 2).CounterSamples[-1].CookedValue\"")
    else:
        out = run("top -bn1 | head -5")
        return out


def get_memory():
    if platform.system() == "Windows":
        return run("powershell -c \"Get-CimInstance Win32_OperatingSystem | Select-Object @{N='TotalGB';E={[math]::Round($_.TotalVisibleMemorySize/1MB,2)}},@{N='FreeGB';E={[math]::Round($_.FreePhysicalMemory/1MB,2)}}\"")
    else:
        return run("free -h")


def get_disk():
    if platform.system() == "Windows":
        return run("powershell -c \"Get-PSDrive -PSProvider FileSystem | Where-Object {$_.Used} | Select-Object Name,@{N='UsedGB';E={[math]::Round($_.Used/1GB,2)}},@{N='FreeGB';E={[math]::Round($_.Free/1GB,2)}}\"")
    else:
        return run("df -h")


def get_ports():
    if platform.system() == "Windows":
        return run("netstat -ano | findstr LISTENING")
    else:
        return run("ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null")


def get_uptime():
    if platform.system() == "Windows":
        return run("powershell -c \"(Get-CimInstance Win32_OperatingSystem).LastBootUpTime\"")
    else:
        return run("uptime")


def main():
    use_json = "--json" in sys.argv

    result = {
        "platform": platform.platform(),
        "python": sys.version,
        "uptime": get_uptime(),
        "cpu": get_cpu(),
        "memory": get_memory(),
        "disk": get_disk(),
        "ports": get_ports(),
    }

    if use_json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        for k, v in result.items():
            print(f"\n=== {k} ===")
            print(v)


if __name__ == "__main__":
    main()
