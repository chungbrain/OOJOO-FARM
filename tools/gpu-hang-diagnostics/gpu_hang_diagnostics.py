#!/usr/bin/env python3
"""AWS Ubuntu GPU 인스턴스 반복 hang 원인 분석기.

커널/NVIDIA/OOM/I/O/로그인 로그와 (선택) 사전 샘플링 기록을 상관 분석하여
어떤 사용자(who)의 어떤 프로세스(which)가 hang을 유발했는지 추정한다.

표준 라이브러리만 사용한다. 대상 인스턴스에 패키지 설치가 필요 없다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
import traceback
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

try:
    import pwd  # Linux
except ImportError:  # Windows 등에서 수집본만 분석할 때
    pwd = None  # type: ignore[assignment]


TOOL_VERSION = "1.0.0"
ISO = "%Y-%m-%dT%H:%M:%S%z"

# ---------------------------------------------------------------------------
# NVIDIA Xid 코드 (공식 문서를 바탕으로 hang 진단에 자주 쓰이는 항목)
# https://docs.nvidia.com/deploy/xid-errors/
# ---------------------------------------------------------------------------
XID_CATALOG: dict[int, dict[str, str]] = {
    13: {"title": "Graphics Engine Exception", "hang": "medium",
         "hint": "GPU 엔진 예외. 잘못된 CUDA 커널/메모리 접근일 수 있다."},
    31: {"title": "GPU memory page fault", "hang": "high",
         "hint": "GPU 페이지 폴트. 사용자 프로세스의 잘못된 GPU 메모리 접근."},
    32: {"title": "Invalid or corrupted push buffer", "hang": "medium",
         "hint": "명령 버퍼 손상. 드라이버/앱 버그 가능."},
    43: {"title": "GPU stopped processing", "hang": "high",
         "hint": "GPU가 작업을 멈춤. 장시간 커널 또는 드라이버 이슈."},
    45: {"title": "Preemptive cleanup (process died)", "hang": "low",
         "hint": "프로세스가 비정상 종료되어 GPU 컨텍스트가 정리됨."},
    48: {"title": "Double bit ECC error", "hang": "high",
         "hint": "GPU 메모리 ECC 이중 비트 오류. 하드웨어 불량 가능."},
    61: {"title": "Framebuffer DMA error", "hang": "medium",
         "hint": "Framebuffer DMA 오류."},
    62: {"title": "Internal micro-controller halt", "hang": "high",
         "hint": "GPU 내부 마이크로컨트롤러 정지. 드라이버/펌웨어 hang."},
    63: {"title": "ECC page retirement (single bit)", "hang": "low",
         "hint": "ECC 페이지 폐기. 누적되면 메모리 불량 징후."},
    64: {"title": "ECC page retirement (double bit)", "hang": "medium",
         "hint": "이중 비트 ECC로 페이지 폐기."},
    69: {"title": "GPU stopped processing (GSP)", "hang": "high",
         "hint": "GSP 모드에서 GPU 처리 중단."},
    74: {"title": "NVLink error", "hang": "high",
         "hint": "NVLink 오류. 멀티 GPU 통신 실패."},
    79: {"title": "GPU has fallen off the bus", "hang": "critical",
         "hint": "GPU가 PCIe 버스에서 사라짐. 인스턴스가 완전히 멈춘 것처럼 보임."},
    94: {"title": "Contained ECC error", "hang": "medium",
         "hint": "격리된 ECC 오류."},
    95: {"title": "Uncontained ECC error", "hang": "high",
         "hint": "비격리 ECC 오류. GPU 리셋/hang 가능."},
    109: {"title": "Context switch timeout", "hang": "high",
         "hint": "컨텍스트 스위치 타임아웃. 장시간 GPU 점유 프로세스 의심."},
    119: {"title": "GSP RPC timeout", "hang": "critical",
         "hint": "GSP RPC 타임아웃. nvidia-smi 자체도 멈추는 전형적인 hang."},
    120: {"title": "GSP error", "hang": "high",
         "hint": "GSP 펌웨어 오류."},
    140: {"title": "Unrecovered ECC error", "hang": "high",
         "hint": "복구 실패 ECC. 하드웨어 점검 필요."},
    154: {"title": "GPU recovery required", "hang": "high",
         "hint": "GPU 복구 작업 필요. 드라이버 리셋 또는 재부팅."},
}

SEVERITY_ORDER = {"critical": 4, "high": 3, "medium": 2, "low": 1, "info": 0}


@dataclass
class Event:
    ts: Optional[str]
    category: str
    severity: str
    summary: str
    process: str = ""
    pid: Optional[int] = None
    user: str = ""
    uid: Optional[int] = None
    source: str = ""
    raw: str = ""
    extra: dict[str, Any] = field(default_factory=dict)

    def ts_dt(self) -> Optional[datetime]:
        return parse_iso(self.ts) if self.ts else None


@dataclass
class Suspect:
    user: str
    process: str
    pid: Optional[int]
    score: float
    reasons: list[str]
    last_cmd: str = ""


# ---------------------------------------------------------------------------
# 시간 / 유틸
# ---------------------------------------------------------------------------
def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def to_iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S%z")


def parse_iso(value: str) -> Optional[datetime]:
    if not value:
        return None
    value = value.strip()
    for fmt in (
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%dT%H:%M:%S.%f%z",
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%d %H:%M:%S%z",
        "%Y-%m-%d %H:%M:%S",
    ):
        try:
            dt = datetime.strptime(value.replace("Z", "+0000") if fmt.endswith("%z") else value, fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            continue
    return None


SYSLOG_RE = re.compile(
    r"^(?P<mon>Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+"
    r"(?P<day>\d{1,2})\s+(?P<time>\d{2}:\d{2}:\d{2})(?:\s+(?P<year>\d{4}))?"
)
JOURNAL_RE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[+-]\d{2}:?\d{2}|Z)?)"
)
DMESG_T_RE = re.compile(
    r"^\[(?P<dt>[A-Z][a-z]{2}\s+[A-Z][a-z]{2}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}\s+\d{4})\]"
)
DMESG_BOOT_RE = re.compile(r"^\[(?P<sec>\d+\.\d+)\]")
MONTHS = {
    "Jan": 1, "Feb": 2, "Mar": 3, "Apr": 4, "May": 5, "Jun": 6,
    "Jul": 7, "Aug": 8, "Sep": 9, "Oct": 10, "Nov": 11, "Dec": 12,
}


def parse_log_timestamp(line: str, year_hint: int, boot_epoch: Optional[float] = None) -> Optional[datetime]:
    m = JOURNAL_RE.match(line)
    if m:
        raw = m.group("ts")
        raw = raw.replace("Z", "+0000")
        if re.search(r"[+-]\d{2}:\d{2}$", raw):
            raw = raw[:-3] + raw[-2:]
        dt = parse_iso(raw)
        if dt:
            return dt
        try:
            dt = datetime.fromisoformat(m.group("ts").replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            pass

    m = DMESG_T_RE.match(line)
    if m:
        try:
            dt = datetime.strptime(m.group("dt"), "%a %b %d %H:%M:%S %Y")
            return dt.replace(tzinfo=timezone.utc)
        except ValueError:
            pass

    m = SYSLOG_RE.match(line)
    if m:
        year = int(m.group("year") or year_hint)
        month = MONTHS[m.group("mon")]
        day = int(m.group("day"))
        hh, mm, ss = (int(x) for x in m.group("time").split(":"))
        try:
            return datetime(year, month, day, hh, mm, ss, tzinfo=timezone.utc)
        except ValueError:
            return None

    m = DMESG_BOOT_RE.match(line)
    if m and boot_epoch is not None:
        return datetime.fromtimestamp(boot_epoch + float(m.group("sec")), tz=timezone.utc)
    return None


def run_cmd(cmd: list[str] | str, timeout: int = 30, cwd: Optional[str] = None) -> tuple[int, str, str]:
    if isinstance(cmd, str):
        args: list[str] | str = cmd
        shell = True
    else:
        args = cmd
        shell = False
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd,
            shell=shell,
        )
        return proc.returncode, proc.stdout or "", proc.stderr or ""
    except subprocess.TimeoutExpired:
        return 124, "", f"timeout after {timeout}s: {cmd}"
    except FileNotFoundError:
        return 127, "", f"not found: {cmd}"
    except Exception as exc:  # noqa: BLE001
        return 1, "", str(exc)


def safe_read(path: Path, max_bytes: int = 32 * 1024 * 1024) -> str:
    try:
        data = path.read_bytes()
        if len(data) > max_bytes:
            data = data[-max_bytes:]
        return data.decode("utf-8", errors="replace")
    except Exception:
        return ""


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def truncate_cmd(cmd: str, limit: int = 240) -> str:
    cmd = " ".join(cmd.split())
    return cmd if len(cmd) <= limit else cmd[: limit - 3] + "..."


def uid_to_user(uid: int, uid_map: dict[int, str]) -> str:
    if uid in uid_map:
        return uid_map[uid]
    if pwd is not None:
        try:
            return pwd.getpwuid(uid).pw_name
        except Exception:
            pass
    return str(uid)


def parse_passwd(text: str) -> dict[int, str]:
    mapping: dict[int, str] = {}
    for line in text.splitlines():
        parts = line.split(":")
        if len(parts) >= 3 and parts[2].isdigit():
            mapping[int(parts[2])] = parts[0]
    return mapping


# ---------------------------------------------------------------------------
# 이벤트 파서
# ---------------------------------------------------------------------------
RE_OOM_KILL = re.compile(
    r"Out of memory:\s+Kill process (?P<pid>\d+) \((?P<comm>[^)]+)\)",
    re.I,
)
RE_OOM_CGROUP = re.compile(
    r"Memory cgroup out of memory:\s+Killed process (?P<pid>\d+) \((?P<comm>[^)]+)\)",
    re.I,
)
RE_OOM_DETAIL = re.compile(
    r"oom-kill:.*?(?:task=(?P<comm>[^,]+),)?pid=(?P<pid>\d+),uid=(?P<uid>\d+)",
    re.I,
)
RE_HUNG_TASK = re.compile(
    r"INFO:\s+task\s+(?P<comm>[^:]+):(?P<pid>\d+)\s+blocked for more than (?P<sec>\d+) seconds",
    re.I,
)
RE_SOFT_LOCKUP = re.compile(
    r"soft lockup - CPU#\d+ stuck for \d+s!\s+\[(?P<comm>[^:]+):(?P<pid>\d+)\]",
    re.I,
)
RE_HARD_LOCKUP = re.compile(
    r"(?:hard LOCKUP|Watchdog detected hard LOCKUP).*",
    re.I,
)
RE_XID = re.compile(
    r"NVRM:\s+Xid\s+\(PCI:(?P<pci>[^)]+)\):\s+(?P<xid>\d+)"
    r"(?:,\s+pid[=:]?\s*(?P<pid>\d+))?"
    r"(?:,\s+name[=:]?\s*(?P<name>[^,\]\s]+))?",
    re.I,
)
RE_GPU_RESET = re.compile(r"NVRM:.*(GPU reset|xid 79|fallen off the bus|Resetting GPU)", re.I)
RE_NVME_TIMEOUT = re.compile(r"nvme\S*.*(I/O|IO).*(timeout|timed out)|blk_update_request:\s+I/O error", re.I)
RE_ENA = re.compile(r"\bena\b.*(reset|timed out|Failed to submit|keep-alive)", re.I)
RE_PANIC = re.compile(r"Kernel panic|BUG: unable to handle|Oops:|general protection fault", re.I)
RE_CUDA_OOM = re.compile(r"(torch\.cuda\.OutOfMemoryError|CUDA out of memory|CUDA_ERROR_OUT_OF_MEMORY)", re.I)
RE_WATCHDOG = re.compile(r"(hung_task_timeout|NMI watchdog|systemd.*watchdog|Watchdog timeout)", re.I)
RE_UFW_OR_LOGIN = re.compile(
    r"(Accepted (?:publickey|password) for (?P<user>\S+)|session opened for user (?P<user2>\S+))",
    re.I,
)
RE_SUDO = re.compile(r"sudo:\s+(?P<user>\S+)\s*:.*COMMAND=(?P<cmd>.+)$")
RE_PYTHON_WORKER = re.compile(
    r"(python[0-9.]*|pt_main_thread|pt_autograd|nv_queue|cuda-EvtHandlr|"
    r"jupyter|ipykernel|train\.py|accelerate|torchrun|deepspeed|ray::)",
    re.I,
)


def _event(**kwargs: Any) -> Event:
    kwargs.setdefault("extra", {})
    return Event(**kwargs)


def parse_line_events(
    line: str,
    source: str,
    ts: Optional[datetime],
    uid_map: dict[int, str],
) -> list[Event]:
    events: list[Event] = []
    ts_s = to_iso(ts) if ts else None
    stripped = line.rstrip("\n")

    m = RE_OOM_KILL.search(stripped) or RE_OOM_CGROUP.search(stripped)
    if m:
        events.append(_event(
            ts=ts_s, category="oom", severity="critical",
            summary=f"OOM killer가 프로세스 종료: {m.group('comm')} (pid={m.group('pid')})",
            process=m.group("comm"), pid=int(m.group("pid")),
            source=source, raw=stripped,
        ))

    m = RE_OOM_DETAIL.search(stripped)
    if m:
        uid = int(m.group("uid"))
        comm = (m.group("comm") or "").strip()
        events.append(_event(
            ts=ts_s, category="oom", severity="critical",
            summary=f"OOM 상세: task={comm or '?'} pid={m.group('pid')} uid={uid}",
            process=comm, pid=int(m.group("pid")),
            user=uid_to_user(uid, uid_map), uid=uid,
            source=source, raw=stripped,
        ))

    m = RE_HUNG_TASK.search(stripped)
    if m:
        events.append(_event(
            ts=ts_s, category="hung_task", severity="critical",
            summary=(
                f"커널 hung_task: {m.group('comm')} (pid={m.group('pid')}) 가 "
                f"{m.group('sec')}초 이상 블록"
            ),
            process=m.group("comm"), pid=int(m.group("pid")),
            source=source, raw=stripped, extra={"blocked_sec": int(m.group("sec"))},
        ))

    m = RE_SOFT_LOCKUP.search(stripped)
    if m:
        events.append(_event(
            ts=ts_s, category="soft_lockup", severity="critical",
            summary=f"soft lockup: {m.group('comm')} (pid={m.group('pid')})",
            process=m.group("comm"), pid=int(m.group("pid")),
            source=source, raw=stripped,
        ))

    if RE_HARD_LOCKUP.search(stripped) and "soft lockup" not in stripped.lower():
        events.append(_event(
            ts=ts_s, category="hard_lockup", severity="critical",
            summary="hard LOCKUP (NMI watchdog). CPU/드라이버가 응답 없음",
            source=source, raw=stripped,
        ))

    m = RE_XID.search(stripped)
    if m:
        xid = int(m.group("xid"))
        meta = XID_CATALOG.get(xid, {"title": "Unknown Xid", "hang": "medium", "hint": ""})
        pid = int(m.group("pid")) if m.group("pid") else None
        name = m.group("name") or ""
        events.append(_event(
            ts=ts_s, category="nvidia_xid",
            severity=meta.get("hang", "medium"),
            summary=f"NVIDIA Xid {xid} ({meta['title']}) pci={m.group('pci')} process={name or '?'} pid={pid or '?'}",
            process=name, pid=pid, source=source, raw=stripped,
            extra={"xid": xid, "pci": m.group("pci"), "hint": meta.get("hint", "")},
        ))
    elif RE_GPU_RESET.search(stripped):
        events.append(_event(
            ts=ts_s, category="gpu_reset", severity="critical",
            summary="GPU reset / fallen off the bus 징후",
            source=source, raw=stripped,
        ))

    if RE_NVME_TIMEOUT.search(stripped):
        events.append(_event(
            ts=ts_s, category="nvme_timeout", severity="high",
            summary="NVMe I/O timeout 또는 I/O error (디스크 hang → 인스턴스 무응답)",
            source=source, raw=stripped,
        ))

    if RE_ENA.search(stripped):
        events.append(_event(
            ts=ts_s, category="ena_timeout", severity="high",
            summary="ENA(네트워크) 드라이버 reset/timeout (SSH 불가처럼 보일 수 있음)",
            source=source, raw=stripped,
        ))

    if RE_PANIC.search(stripped):
        events.append(_event(
            ts=ts_s, category="kernel_panic", severity="critical",
            summary="커널 패닉/Oops",
            source=source, raw=stripped,
        ))

    if RE_CUDA_OOM.search(stripped):
        events.append(_event(
            ts=ts_s, category="cuda_oom", severity="high",
            summary="CUDA GPU 메모리 부족",
            source=source, raw=stripped,
        ))

    if RE_WATCHDOG.search(stripped) and not RE_HUNG_TASK.search(stripped):
        events.append(_event(
            ts=ts_s, category="watchdog", severity="high",
            summary="watchdog/hung_task 타임아웃 관련 로그",
            source=source, raw=stripped,
        ))

    m = RE_UFW_OR_LOGIN.search(stripped)
    if m:
        user = m.group("user") or m.group("user2") or ""
        events.append(_event(
            ts=ts_s, category="login", severity="info",
            summary=f"로그인: {user}",
            user=user, source=source, raw=stripped,
        ))

    m = RE_SUDO.search(stripped)
    if m:
        events.append(_event(
            ts=ts_s, category="sudo", severity="info",
            summary=f"sudo: {m.group('user')} → {truncate_cmd(m.group('cmd'))}",
            user=m.group("user"), source=source, raw=stripped,
            extra={"cmd": m.group("cmd").strip()},
        ))
    return events


def parse_text(
    text: str,
    source: str,
    year_hint: int,
    uid_map: dict[int, str],
    boot_epoch: Optional[float] = None,
) -> list[Event]:
    events: list[Event] = []
    last_ts: Optional[datetime] = None
    for line in text.splitlines():
        ts = parse_log_timestamp(line, year_hint, boot_epoch) or last_ts
        if ts:
            last_ts = ts
        events.extend(parse_line_events(line, source, ts, uid_map))
    return events


# ---------------------------------------------------------------------------
# 수집
# ---------------------------------------------------------------------------
COLLECT_COMMANDS: list[tuple[str, list[str] | str, int]] = [
    ("uname.txt", ["uname", "-a"], 10),
    ("hostname.txt", ["hostname"], 5),
    ("uptime.txt", ["uptime"], 5),
    ("date.txt", ["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"], 5),
    ("who.txt", ["who"], 5),
    ("w.txt", ["w"], 5),
    ("last.txt", ["last", "-n", "50"], 10),
    ("last-reboot.txt", ["last", "reboot", "-n", "20"], 10),
    ("last-x.txt", ["last", "-x", "-n", "40"], 10),
    ("ps.txt", ["ps", "auxww", "--sort=-rss"], 15),
    ("ps-threads.txt", ["ps", "-eL", "-o", "user,pid,lwp,stat,pcpu,pmem,comm"], 15),
    ("free.txt", ["free", "-m"], 5),
    ("df.txt", ["df", "-hT"], 5),
    ("lsblk.txt", ["lsblk", "-o", "NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT,ROTA"], 5),
    ("lscpu.txt", ["lscpu"], 5),
    ("lspci-nvidia.txt", ["bash", "-lc", "lspci -nn | grep -i -E 'nvidia|3d|vga'"], 10),
    ("dmesg.txt", ["dmesg", "-T"], 20),
    ("dmesg-kernel.txt", ["dmesg", "-T", "-k"], 20),
    ("sysctl-hung.txt", ["sysctl", "kernel.hung_task_timeout_secs", "kernel.panic",
                         "kernel.softlockup_panic", "vm.overcommit_memory"], 5),
    ("nvidia-smi.txt", ["nvidia-smi"], 20),
    ("nvidia-smi-q.txt", ["nvidia-smi", "-q"], 40),
    ("nvidia-smi-apps.txt", [
        "nvidia-smi",
        "--query-compute-apps=gpu_uuid,gpu_bus_id,pid,process_name,used_gpu_memory",
        "--format=csv",
    ], 20),
    ("nvidia-smi-gpu.txt", [
        "nvidia-smi",
        "--query-gpu=index,name,uuid,pci.bus_id,utilization.gpu,utilization.memory,"
        "memory.used,memory.total,temperature.gpu,power.draw,clocks.sm,ecc.errors.uncorrected.volatile.total,"
        "retired_pages.double_bit.count,retired_pages.single_bit_ecc.count",
        "--format=csv",
    ], 20),
    ("journal-boots.txt", ["journalctl", "--list-boots", "--no-pager"], 15),
    ("journal-current.txt", ["journalctl", "-b", "0", "--no-pager", "-o", "short-iso"], 60),
    ("journal-prev.txt", ["journalctl", "-b", "-1", "--no-pager", "-o", "short-iso"], 60),
    ("journal-prev2.txt", ["journalctl", "-b", "-2", "--no-pager", "-o", "short-iso"], 60),
    ("journal-priority-err.txt", ["journalctl", "-b", "-1", "-p", "err", "--no-pager", "-o", "short-iso"], 30),
]


COLLECT_FILES = [
    "/etc/os-release",
    "/etc/passwd",
    "/proc/loadavg",
    "/proc/meminfo",
    "/proc/uptime",
    "/proc/cmdline",
    "/var/log/syslog",
    "/var/log/kern.log",
    "/var/log/auth.log",
    "/var/log/dmesg",
    "/var/log/nvidia-installer.log",
]


def fetch_imds() -> dict[str, str]:
    """AWS Instance Metadata Service (IMDSv2 우선)."""
    out: dict[str, str] = {}
    token_code, token, _ = run_cmd(
        [
            "curl", "-sS", "-m", "2", "-X", "PUT",
            "http://169.254.169.254/latest/api/token",
            "-H", "X-aws-ec2-metadata-token-ttl-seconds: 60",
        ],
        timeout=5,
    )
    headers = ["-H", f"X-aws-ec2-metadata-token: {token.strip()}"] if token_code == 0 and token.strip() else []
    keys = [
        "instance-id",
        "instance-type",
        "ami-id",
        "placement/availability-zone",
        "hostname",
        "local-ipv4",
        "public-ipv4",
        "spot/instance-action",
        "events/maintenance/scheduled",
    ]
    for key in keys:
        cmd = ["curl", "-sS", "-m", "2", f"http://169.254.169.254/latest/meta-data/{key}"]
        if headers:
            cmd[3:3] = headers
        code, stdout, _ = run_cmd(cmd, timeout=5)
        if code == 0 and stdout.strip() and "404" not in stdout:
            out[key] = stdout.strip()
    return out


def collect_snapshot(out_dir: Path, since: Optional[str] = None) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    meta: dict[str, Any] = {
        "tool_version": TOOL_VERSION,
        "collected_at": to_iso(utc_now()),
        "host": socket.gethostname(),
        "platform": sys.platform,
        "euid": os.geteuid() if hasattr(os, "geteuid") else None,
        "cwd": str(Path.cwd()),
        "commands": {},
        "files": {},
        "imds": {},
    }

    if sys.platform.startswith("linux"):
        try:
            meta["imds"] = fetch_imds()
        except Exception as exc:  # noqa: BLE001
            meta["imds_error"] = str(exc)

    commands = list(COLLECT_COMMANDS)
    if since:
        commands.append((
            "journal-since.txt",
            ["journalctl", "--since", since, "--no-pager", "-o", "short-iso"],
            90,
        ))

    for name, cmd, timeout in commands:
        code, stdout, stderr = run_cmd(cmd, timeout=timeout)
        write_text(out_dir / name, stdout if stdout else stderr)
        meta["commands"][name] = {"cmd": cmd, "exit": code, "bytes": len(stdout)}

    for fpath in COLLECT_FILES:
        src = Path(fpath)
        dest_name = "file-" + fpath.strip("/").replace("/", "__")
        if src.is_file():
            text = safe_read(src)
            write_text(out_dir / dest_name, text)
            meta["files"][fpath] = {"ok": True, "bytes": len(text.encode("utf-8", errors="replace"))}
        else:
            meta["files"][fpath] = {"ok": False}

    nvidia_proc = Path("/proc/driver/nvidia")
    if nvidia_proc.exists():
        for p in nvidia_proc.rglob("*"):
            if p.is_file() and p.stat().st_size < 256_000:
                rel = "nvidia-proc__" + str(p.relative_to(nvidia_proc)).replace("/", "__")
                write_text(out_dir / rel, safe_read(p, 256_000))

    sampler_candidates = [
        Path("/var/log/gpu-hang-monitor"),
        Path("/var/tmp/gpu-hang-monitor"),
        Path.home() / "gpu-hang-monitor",
    ]
    for cand in sampler_candidates:
        if cand.is_dir():
            dest = out_dir / "monitor-history"
            dest.mkdir(exist_ok=True)
            for f in sorted(cand.glob("*.jsonl"))[-8:]:
                write_text(dest / f.name, safe_read(f, 64 * 1024 * 1024))
            meta["monitor_history"] = str(cand)
            break

    write_text(out_dir / "meta.json", json.dumps(meta, indent=2, ensure_ascii=False))
    return meta


# ---------------------------------------------------------------------------
# 모니터 (행 발생 전 증거 보존)
# ---------------------------------------------------------------------------
def read_proc_cmdline(pid: int) -> str:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\x00", b" ")
        return truncate_cmd(raw.decode("utf-8", errors="replace").strip())
    except Exception:
        return ""


def read_proc_rss_kb(pid: int) -> int:
    try:
        for line in Path(f"/proc/{pid}/status").read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1])
    except Exception:
        return 0
    return 0


def read_proc_uid(pid: int) -> Optional[int]:
    try:
        st = Path(f"/proc/{pid}").stat()
        return st.st_uid
    except Exception:
        return None


def list_top_processes(uid_map: dict[int, str], limit: int = 15) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    proc = Path("/proc")
    if not proc.exists():
        return rows
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        rss = read_proc_rss_kb(pid)
        if rss <= 0:
            continue
        uid = read_proc_uid(pid)
        comm = ""
        try:
            comm = (entry / "comm").read_text(encoding="utf-8", errors="replace").strip()
        except Exception:
            comm = ""
        rows.append({
            "pid": pid,
            "uid": uid,
            "user": uid_to_user(uid, uid_map) if uid is not None else "",
            "rss_mb": round(rss / 1024, 1),
            "comm": comm,
            "cmd": read_proc_cmdline(pid) or comm,
        })
    rows.sort(key=lambda r: r["rss_mb"], reverse=True)
    return rows[:limit]


def query_nvidia() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    gpus: list[dict[str, Any]] = []
    procs: list[dict[str, Any]] = []
    code, stdout, _ = run_cmd([
        "nvidia-smi",
        "--query-gpu=index,name,uuid,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
        "--format=csv,noheader,nounits",
    ], timeout=12)
    if code == 0:
        for line in stdout.splitlines():
            parts = [p.strip() for p in line.split(",")]
            if len(parts) >= 8:
                gpus.append({
                    "index": parts[0], "name": parts[1], "uuid": parts[2],
                    "util": parts[3], "mem_used_mb": parts[4], "mem_total_mb": parts[5],
                    "temp": parts[6], "power_w": parts[7],
                })
    code, stdout, _ = run_cmd([
        "nvidia-smi",
        "--query-compute-apps=gpu_bus_id,pid,process_name,used_gpu_memory",
        "--format=csv,noheader,nounits",
    ], timeout=12)
    if code == 0:
        for line in stdout.splitlines():
            parts = [p.strip() for p in line.split(",")]
            if len(parts) >= 4 and parts[1].isdigit():
                pid = int(parts[1])
                uid = read_proc_uid(pid)
                procs.append({
                    "gpu_bus_id": parts[0],
                    "pid": pid,
                    "process_name": Path(parts[2]).name,
                    "used_gpu_mb": parts[3],
                    "user": uid_to_user(uid, {}) if uid is not None else "",
                    "uid": uid,
                    "cmd": read_proc_cmdline(pid) or parts[2],
                })
    return gpus, procs


def mem_snapshot() -> dict[str, Any]:
    info: dict[str, Any] = {}
    text = safe_read(Path("/proc/meminfo"), 64_000)
    for key in ("MemTotal", "MemAvailable", "MemFree", "SwapTotal", "SwapFree", "Dirty", "Writeback"):
        m = re.search(rf"^{key}:\s+(\d+)", text, re.M)
        if m:
            info[key] = int(m.group(1))
    if "MemTotal" in info and "MemAvailable" in info and info["MemTotal"]:
        info["used_pct"] = round(100 * (1 - info["MemAvailable"] / info["MemTotal"]), 1)
    return info


def disk_used_pct(path: str = "/") -> Optional[float]:
    try:
        st = os.statvfs(path)
        total = st.f_blocks * st.f_frsize
        free = st.f_bavail * st.f_frsize
        if total:
            return round(100 * (1 - free / total), 1)
    except Exception:
        return None
    return None


def take_monitor_sample(uid_map: dict[int, str]) -> dict[str, Any]:
    load = safe_read(Path("/proc/loadavg")).split()
    gpus, gpu_procs = query_nvidia()
    return {
        "ts": to_iso(utc_now()),
        "loadavg": [float(x) for x in load[:3]] if len(load) >= 3 else [],
        "mem": mem_snapshot(),
        "disk_root_used_pct": disk_used_pct("/"),
        "gpus": gpus,
        "gpu_procs": gpu_procs,
        "top_rss": list_top_processes(uid_map),
    }


def run_monitor(out_dir: Path, interval: int, retain_hours: int) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    stop = {"flag": False}

    def _stop(signum: int, _frame: Any) -> None:
        stop["flag"] = True
        print(f"[monitor] signal {signum} received, stopping...", flush=True)

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, _stop)
        except Exception:
            pass

    uid_map = parse_passwd(safe_read(Path("/etc/passwd")))
    print(f"[monitor] sampling every {interval}s → {out_dir}", flush=True)
    while not stop["flag"]:
        sample = take_monitor_sample(uid_map)
        day = datetime.now(timezone.utc).strftime("%Y%m%d")
        path = out_dir / f"samples-{day}.jsonl"
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(sample, ensure_ascii=False) + "\n")
        cutoff = utc_now() - timedelta(hours=retain_hours)
        for old in out_dir.glob("samples-*.jsonl"):
            try:
                if datetime.strptime(old.stem.split("-")[1], "%Y%m%d").replace(tzinfo=timezone.utc) < cutoff.replace(hour=0, minute=0, second=0):
                    old.unlink()
            except Exception:
                pass
        deadline = time.time() + interval
        while time.time() < deadline and not stop["flag"]:
            time.sleep(0.5)


def load_monitor_history(dump_dir: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for folder in (dump_dir / "monitor-history", dump_dir):
        if not folder.exists():
            continue
        for f in sorted(folder.glob("samples-*.jsonl")):
            for line in safe_read(f, 64 * 1024 * 1024).splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def nearest_samples(history: list[dict[str, Any]], ts: datetime, radius_min: int) -> list[dict[str, Any]]:
    picked: list[dict[str, Any]] = []
    for row in history:
        dt = parse_iso(row.get("ts", ""))
        if not dt:
            continue
        if abs((dt - ts).total_seconds()) <= radius_min * 60:
            picked.append(row)
    return picked


# ---------------------------------------------------------------------------
# 분석 / 상관
# ---------------------------------------------------------------------------
HANG_CATEGORIES = {
    "hung_task", "soft_lockup", "hard_lockup", "nvidia_xid", "gpu_reset",
    "oom", "nvme_timeout", "ena_timeout", "kernel_panic", "cuda_oom", "watchdog",
}


def load_dump_texts(dump_dir: Path) -> dict[str, str]:
    texts: dict[str, str] = {}
    if not dump_dir.exists():
        return texts
    for p in dump_dir.iterdir():
        if p.is_file() and p.suffix in {".txt", ".log", ""} or p.name.endswith(".txt"):
            if p.name == "meta.json":
                continue
            texts[p.name] = safe_read(p)
    nested = dump_dir / "monitor-history"
    if nested.is_dir():
        pass
    return texts


def analyze_dump(dump_dir: Path, window_minutes: int) -> dict[str, Any]:
    meta: dict[str, Any] = {}
    meta_path = dump_dir / "meta.json"
    if meta_path.exists():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
        except Exception:
            meta = {}

    year_hint = utc_now().year
    collected = parse_iso(meta.get("collected_at", ""))
    if collected:
        year_hint = collected.year

    uid_map = parse_passwd(safe_read(dump_dir / "file-etc__passwd") or safe_read(Path("/etc/passwd")))
    texts = load_dump_texts(dump_dir)

    boot_epoch = None
    uptime_txt = texts.get("file-proc__uptime", "")
    if uptime_txt:
        try:
            up = float(uptime_txt.split()[0])
            boot_epoch = time.time() - up
        except Exception:
            boot_epoch = None

    events: list[Event] = []
    for name, text in texts.items():
        if name.startswith("samples-") or name == "meta.json":
            continue
        events.extend(parse_text(text, name, year_hint, uid_map, boot_epoch))

    # 중복 제거 (같은 raw+ts)
    uniq: dict[tuple[str, str, str], Event] = {}
    for ev in events:
        key = (ev.ts or "", ev.category, ev.raw[:240])
        uniq[key] = ev
    events = list(uniq.values())
    events.sort(key=lambda e: e.ts or "")

    history = load_monitor_history(dump_dir)
    current_gpu_apps = parse_nvidia_apps_csv(texts.get("nvidia-smi-apps.txt", ""), uid_map)
    hang_events = [e for e in events if e.category in HANG_CATEGORIES and e.severity in {"critical", "high"}]
    if not hang_events:
        hang_events = [e for e in events if e.category in HANG_CATEGORIES]

    clusters = cluster_events(hang_events, window_minutes)
    suspects_global: dict[tuple[str, str], Suspect] = {}
    incident_reports: list[dict[str, Any]] = []

    for cluster in clusters:
        center = cluster_center(cluster)
        nearby = events_in_window(events, center, window_minutes) if center else cluster
        samples = nearest_samples(history, center, window_minutes) if center else []
        suspects = score_suspects(cluster, nearby, samples, uid_map)
        for s in suspects:
            key = (s.user or "unknown", s.process or "unknown")
            if key not in suspects_global or s.score > suspects_global[key].score:
                suspects_global[key] = s
        incident_reports.append({
            "center_ts": to_iso(center) if center else None,
            "severity": max((e.severity for e in cluster), key=lambda x: SEVERITY_ORDER.get(x, 0)),
            "event_count": len(cluster),
            "categories": sorted({e.category for e in cluster}),
            "headline": cluster[0].summary if cluster else "",
            "events": [event_to_dict(e) for e in cluster[:30]],
            "nearby_logins": [
                event_to_dict(e) for e in nearby if e.category in {"login", "sudo"}
            ][:20],
            "suspects": [asdict(s) for s in suspects[:8]],
            "monitor_snapshot": summarize_samples(samples),
        })

    category_counts = Counter(e.category for e in hang_events)
    xid_counts = Counter(
        e.extra.get("xid") for e in events if e.category == "nvidia_xid" and e.extra.get("xid") is not None
    )

    verdict = build_verdict(incident_reports, list(suspects_global.values()), category_counts, xid_counts, meta)

    return {
        "tool_version": TOOL_VERSION,
        "analyzed_at": to_iso(utc_now()),
        "dump_dir": str(dump_dir),
        "meta": meta,
        "stats": {
            "events_total": len(events),
            "hang_events": len(hang_events),
            "incidents": len(incident_reports),
            "monitor_samples": len(history),
            "category_counts": dict(category_counts),
            "xid_counts": {str(k): v for k, v in xid_counts.items()},
        },
        "verdict": verdict,
        "suspects": [asdict(s) for s in sorted(suspects_global.values(), key=lambda x: x.score, reverse=True)[:15]],
        "incidents": incident_reports,
        "recent_hang_events": [event_to_dict(e) for e in hang_events[-80:]],
        "current_gpu_apps": current_gpu_apps,
    }


def parse_nvidia_apps_csv(text: str, uid_map: dict[int, str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in text.splitlines()[1:]:
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 4:
            continue
        pid = None
        for token in parts:
            if token.isdigit():
                pid = int(token)
                break
        name = ""
        for token in parts:
            lower = token.lower()
            if "python" in lower or token.endswith(".py") or "/" in token or "\\" in token:
                name = Path(token).name
                break
        rows.append({
            "raw": line,
            "pid": pid,
            "process": name,
            "user": uid_to_user(read_proc_uid(pid), uid_map) if pid and sys.platform.startswith("linux") else "",
        })
    return rows


def event_to_dict(ev: Event) -> dict[str, Any]:
    d = asdict(ev)
    d["raw"] = ev.raw[:500]
    return d


def cluster_events(events: list[Event], window_minutes: int) -> list[list[Event]]:
    if not events:
        return []
    dated = [(e.ts_dt(), e) for e in events]
    dated.sort(key=lambda x: x[0] or datetime.min.replace(tzinfo=timezone.utc))
    clusters: list[list[Event]] = []
    current: list[Event] = []
    last: Optional[datetime] = None
    span = timedelta(minutes=window_minutes)
    for ts, ev in dated:
        if ts is None:
            current.append(ev)
            continue
        if last is None or ts - last <= span:
            current.append(ev)
        else:
            if current:
                clusters.append(current)
            current = [ev]
        last = ts
    if current:
        clusters.append(current)
    return clusters


def cluster_center(cluster: list[Event]) -> Optional[datetime]:
    times = [e.ts_dt() for e in cluster if e.ts_dt()]
    if not times:
        return None
    times.sort()
    return times[len(times) // 2]


def events_in_window(events: list[Event], center: Optional[datetime], minutes: int) -> list[Event]:
    if not center:
        return events
    delta = timedelta(minutes=minutes)
    out = []
    for e in events:
        ts = e.ts_dt()
        if ts is None or abs(ts - center) <= delta:
            out.append(e)
    return out


def score_suspects(
    cluster: list[Event],
    nearby: list[Event],
    samples: list[dict[str, Any]],
    uid_map: dict[int, str],
) -> list[Suspect]:
    scores: dict[tuple[str, str, Optional[int]], dict[str, Any]] = defaultdict(
        lambda: {"score": 0.0, "reasons": [], "cmd": "", "uid": None}
    )

    pid_users: dict[int, str] = {}
    for ev in list(cluster) + list(nearby):
        if ev.pid and ev.user and ev.user not in {"", "unknown"}:
            pid_users[ev.pid] = ev.user
    for sample in samples:
        for gp in sample.get("gpu_procs") or []:
            pid = gp.get("pid")
            user = gp.get("user") or ""
            if pid and user:
                pid_users[int(pid)] = user
        for row in sample.get("top_rss") or []:
            pid = row.get("pid")
            user = row.get("user") or ""
            if pid and user:
                pid_users[int(pid)] = user

    def bump(user: str, process: str, pid: Optional[int], pts: float, reason: str, cmd: str = "") -> None:
        if (not user or user == "unknown") and pid in pid_users:
            user = pid_users[pid]
        user = user or "unknown"
        process = process or "unknown"
        key = (user, process, pid)
        rec = scores[key]
        rec["score"] += pts
        rec["reasons"].append(reason)
        if cmd:
            rec["cmd"] = cmd

    for ev in cluster:
        if ev.category in {"hung_task", "soft_lockup"} and ev.process:
            bump(ev.user, ev.process, ev.pid, 8.0, ev.summary)
        elif ev.category == "oom" and ev.process:
            bump(ev.user, ev.process, ev.pid, 9.0, ev.summary)
        elif ev.category == "nvidia_xid":
            xid = ev.extra.get("xid")
            pts = 9.0 if XID_CATALOG.get(int(xid), {}).get("hang") == "critical" else 6.0 if xid else 5.0
            bump(ev.user, ev.process or f"xid-{xid}", ev.pid, pts, ev.summary)
        elif ev.category in {"gpu_reset", "kernel_panic", "hard_lockup"}:
            bump(ev.user or "kernel/driver", ev.process or ev.category, ev.pid, 7.0, ev.summary)
        elif ev.category in {"nvme_timeout", "ena_timeout"}:
            bump("system", ev.category, None, 6.0, ev.summary)
        elif ev.process:
            bump(ev.user, ev.process, ev.pid, 3.0, ev.summary)

    logins = [e for e in nearby if e.category == "login" and e.user]
    sudoers = [e for e in nearby if e.category == "sudo" and e.user]

    for sample in samples:
        for gp in sample.get("gpu_procs") or []:
            user = gp.get("user") or ""
            proc = gp.get("process_name") or gp.get("cmd") or "gpu-proc"
            pid = gp.get("pid")
            mem = str(gp.get("used_gpu_mb", ""))
            bump(user, Path(str(proc)).name, pid, 5.5,
                 f"모니터: hang 직전 GPU 점유 pid={pid} user={user} mem={mem}MiB",
                 gp.get("cmd", ""))
        for row in sample.get("top_rss") or []:
            rss = float(row.get("rss_mb") or 0)
            if rss >= 8000:
                bump(row.get("user", ""), row.get("comm", ""), row.get("pid"),
                     3.0 + min(rss / 8000, 4),
                     f"모니터: hang 직전 RSS {rss}MiB pid={row.get('pid')}",
                     row.get("cmd", ""))

    # 로그인 사용자 가산 (GPU/OOM 프로세스에 사용자가 비어 있을 때)
    active_users = [e.user for e in logins[-5:]]
    for key, rec in list(scores.items()):
        user, process, pid = key
        if user in {"unknown", ""} and active_users:
            new_user = active_users[-1]
            bump(new_user, process, pid, 1.5, f"hang 창 인근 로그인 사용자로 추정: {new_user}")
        if any(s.user == user for s in sudoers):
            bump(user, process, pid, 0.8, "hang 창 인근 sudo 사용")

    suspects: list[Suspect] = []
    for (user, process, pid), rec in scores.items():
        suspects.append(Suspect(
            user=user,
            process=process,
            pid=pid,
            score=round(rec["score"], 2),
            reasons=rec["reasons"][:8],
            last_cmd=truncate_cmd(rec.get("cmd", "")),
        ))
    suspects.sort(key=lambda s: s.score, reverse=True)
    return suspects


def summarize_samples(samples: list[dict[str, Any]]) -> dict[str, Any]:
    if not samples:
        return {}
    last = samples[-1]
    gpu_procs = []
    for s in samples:
        gpu_procs.extend(s.get("gpu_procs") or [])
    # 마지막 샘플 위주
    return {
        "sample_count": len(samples),
        "last_ts": last.get("ts"),
        "loadavg": last.get("loadavg"),
        "mem_used_pct": (last.get("mem") or {}).get("used_pct"),
        "disk_root_used_pct": last.get("disk_root_used_pct"),
        "gpus": last.get("gpus"),
        "gpu_procs": last.get("gpu_procs"),
        "top_rss": last.get("top_rss", [])[:8],
    }


def build_verdict(
    incidents: list[dict[str, Any]],
    suspects: list[Suspect],
    category_counts: Counter,
    xid_counts: Counter,
    meta: dict[str, Any],
) -> dict[str, Any]:
    if not incidents and not suspects:
        return {
            "status": "inconclusive",
            "headline": "결정적 hang 증거가 로그에서 확인되지 않았다.",
            "who": "",
            "process": "",
            "likely_cause": (
                "로그 로테이션으로 증거가 사라졌거나, hang 당시 커널이 로그를 남기지 못했다. "
                "monitor 모드를 상시 실행해 다음 재발 때 프로세스 스냅샷을 남기는 것을 권장한다."
            ),
            "confidence": "low",
            "recommendations": default_recommendations(meta),
        }

    top = suspects[0] if suspects else None
    dominant_cat = category_counts.most_common(1)[0][0] if category_counts else ""
    cause_map = {
        "oom": "호스트 RAM 고갈(OOM). 대용량 학습/데이터 로더 프로세스가 메모리를 잠식했을 가능성이 높다.",
        "hung_task": "프로세스가 D-state(디스크/드라이버 대기)로 장시간 블록되어 시스템이 멈춘 것처럼 보였다.",
        "nvidia_xid": "NVIDIA GPU 드라이버/하드웨어 오류(Xid). GPU 점유 프로세스가 트리거했을 수 있다.",
        "gpu_reset": "GPU reset 또는 PCIe 버스에서 GPU 소실. 인스턴스 전체가 멈춘 것처럼 보인다.",
        "nvme_timeout": "루트/데이터 볼륨 NVMe I/O timeout. 디스크 hang이 SSH 무응답의 원인일 수 있다.",
        "ena_timeout": "ENA 네트워크 드라이버 장애. 인스턴스는 살아 있어도 SSH가 끊긴다.",
        "kernel_panic": "커널 패닉. 재부팅 전 마지막 프로세스를 확인해야 한다.",
        "soft_lockup": "CPU soft lockup. 특정 프로세스가 커널/드라이버를 오래 붙잡고 있었다.",
        "cuda_oom": "GPU 메모리 부족. 배치 크기/동시 작업 과다.",
    }
    xid_note = ""
    if xid_counts:
        xid, n = xid_counts.most_common(1)[0]
        info = XID_CATALOG.get(int(xid), {})
        xid_note = f" 가장 빈번한 Xid는 {xid} ({info.get('title', 'unknown')}, {n}회). {info.get('hint', '')}"

    headline_parts = []
    if top:
        headline_parts.append(
            f"가장 유력한 주체는 사용자 '{top.user}' 의 프로세스 '{top.process}'"
            + (f" (pid {top.pid})" if top.pid else "")
        )
    if dominant_cat:
        headline_parts.append(f"지배적 이벤트 유형은 {dominant_cat}")

    confidence = "low"
    if top and top.score >= 12:
        confidence = "high"
    elif top and top.score >= 6:
        confidence = "medium"

    recs = default_recommendations(meta)
    if dominant_cat in {"nvidia_xid", "gpu_reset"}:
        recs.insert(0, "nvidia-smi / 드라이버 hang이면 GSP 모드, 드라이버 버전, GPU 카드 상태를 점검하고 해당 학습 잡을 격리하라.")
    if dominant_cat == "oom":
        recs.insert(0, "RAM/스왑을 늘리거나 배치 크기·워커 수를 줄이고, cgroup 메모리 한도를 사용자별로 적용하라.")
    if dominant_cat == "nvme_timeout":
        recs.insert(0, "디스크 사용량, NVMe 지연, 로그 폭주(/var/log) 여부를 확인하고 CloudWatch 디스크 메트릭을 보라.")

    return {
        "status": "attributed" if top else "partial",
        "headline": ". ".join(headline_parts) + ".",
        "who": top.user if top else "",
        "process": top.process if top else "",
        "pid": top.pid if top else None,
        "command": top.last_cmd if top else "",
        "score": top.score if top else 0,
        "likely_cause": cause_map.get(dominant_cat, "복수 원인이 혼재한다.") + xid_note,
        "confidence": confidence,
        "incident_count": len(incidents),
        "reasons": top.reasons[:6] if top else [],
        "recommendations": recs[:8],
    }


def default_recommendations(meta: dict[str, Any]) -> list[str]:
    recs = [
        "다음 재발에 대비해 `monitor` 모드를 systemd 서비스로 상시 실행하라. hang 직전 GPU 점유 프로세스와 사용자가 기록된다.",
        "재발 직후 인스턴스가 살아나면 먼저 `collect`를 실행해 journal 이전 부팅(-b -1)을 보존하라.",
        "학습 잡을 사용자·conda env·작업 디렉터리별로 구분해, 동일 UID가 여러 GPU 잡을 겹치지 않게 하라.",
        "AWS 콘솔에서 Instance status check / System status check, CloudWatch GPU/CPU/Disk 메트릭을 같은 시각과 대조하라.",
        "Spot 인스턴스라면 중단 이벤트가 hang처럼 보일 수 있다. IMDS `spot/instance-action` 을 확인하라.",
    ]
    inst = (meta.get("imds") or {}).get("instance-type", "")
    if inst:
        recs.append(f"현재 인스턴스 타입은 {inst} 이다. GPU 메모리보다 큰 모델을 올리지 않는지 확인하라.")
    return recs


# ---------------------------------------------------------------------------
# 보고서
# ---------------------------------------------------------------------------
def render_markdown(report: dict[str, Any]) -> str:
    v = report.get("verdict") or {}
    stats = report.get("stats") or {}
    meta = report.get("meta") or {}
    imds = meta.get("imds") or {}
    lines: list[str] = []
    lines.append("# AWS Ubuntu GPU Hang 진단 보고서")
    lines.append("")
    lines.append(f"- 생성 시각: `{report.get('analyzed_at', '')}`")
    lines.append(f"- 도구 버전: `{report.get('tool_version', '')}`")
    lines.append(f"- 호스트: `{meta.get('host') or imds.get('hostname') or '-'}`")
    if imds:
        lines.append(
            f"- AWS: instance `{imds.get('instance-id', '-')}` "
            f"type `{imds.get('instance-type', '-')}` az `{imds.get('placement/availability-zone', '-')}`"
        )
    lines.append(f"- 수집 디렉터리: `{report.get('dump_dir', '')}`")
    lines.append("")
    lines.append("## 결론 (누가 / 어떤 프로세스)")
    lines.append("")
    lines.append(f"**{v.get('headline', '')}**")
    lines.append("")
    lines.append(f"| 항목 | 값 |")
    lines.append(f"| --- | --- |")
    lines.append(f"| 사용자 (who) | `{v.get('who') or '-'}` |")
    lines.append(f"| 프로세스 (which) | `{v.get('process') or '-'}` |")
    lines.append(f"| PID | `{v.get('pid') or '-'}` |")
    lines.append(f"| 명령줄 | `{v.get('command') or '-'}` |")
    lines.append(f"| 추정 원인 | {v.get('likely_cause') or '-'} |")
    lines.append(f"| 신뢰도 | `{v.get('confidence')}` |")
    lines.append(f"| 상태 | `{v.get('status')}` |")
    lines.append(f"| 사건 수 | {v.get('incident_count', 0)} |")
    lines.append("")
    if v.get("reasons"):
        lines.append("### 근거")
        lines.append("")
        for r in v["reasons"]:
            lines.append(f"- {r}")
        lines.append("")

    lines.append("## 통계")
    lines.append("")
    lines.append(f"- 파싱된 이벤트: {stats.get('events_total', 0)}")
    lines.append(f"- hang 관련 이벤트: {stats.get('hang_events', 0)}")
    lines.append(f"- 모니터 샘플: {stats.get('monitor_samples', 0)}")
    lines.append(f"- 카테고리: `{json.dumps(stats.get('category_counts') or {}, ensure_ascii=False)}`")
    if stats.get("xid_counts"):
        lines.append(f"- Xid 빈도: `{json.dumps(stats.get('xid_counts'), ensure_ascii=False)}`")
    lines.append("")

    current_apps = report.get("current_gpu_apps") or []
    if current_apps:
        lines.append("## 수집 시점 GPU 프로세스 (재부팅 후면 당시 프로세스와 다를 수 있음)")
        lines.append("")
        for app in current_apps[:20]:
            lines.append(f"- user=`{app.get('user') or '-'}` pid=`{app.get('pid')}` process=`{app.get('process')}` `{app.get('raw')}`")
        lines.append("")

    lines.append("## 유력 용의자")
    lines.append("")
    suspects = report.get("suspects") or []
    if not suspects:
        lines.append("기록된 용의자가 없다. monitor 히스토리가 없으면 hang 직전 프로세스를 복원하기 어렵다.")
        lines.append("")
    else:
        lines.append("| 점수 | 사용자 | 프로세스 | PID | 근거 |")
        lines.append("| ---: | --- | --- | --- | --- |")
        for s in suspects[:12]:
            reason = "; ".join(s.get("reasons") or [])[:180]
            lines.append(
                f"| {s.get('score')} | `{s.get('user')}` | `{s.get('process')}` | "
                f"{s.get('pid') or '-'} | {reason} |"
            )
        lines.append("")

    lines.append("## 사건 타임라인")
    lines.append("")
    for i, inc in enumerate(report.get("incidents") or [], 1):
        lines.append(f"### 사건 {i} — `{inc.get('center_ts') or '시각 미상'}` ({inc.get('severity')})")
        lines.append("")
        lines.append(f"- 헤드라인: {inc.get('headline')}")
        lines.append(f"- 카테고리: {', '.join(inc.get('categories') or [])}")
        snap = inc.get("monitor_snapshot") or {}
        if snap:
            lines.append(
                f"- 모니터 직전 상태: loadavg=`{snap.get('loadavg')}` "
                f"mem_used=`{snap.get('mem_used_pct')}%` disk=`{snap.get('disk_root_used_pct')}%`"
            )
            if snap.get("gpu_procs"):
                lines.append("- hang 창 GPU 프로세스:")
                for gp in snap["gpu_procs"]:
                    lines.append(
                        f"  - user=`{gp.get('user')}` pid=`{gp.get('pid')}` "
                        f"{gp.get('process_name')} gpu_mem={gp.get('used_gpu_mb')}MiB cmd=`{truncate_cmd(str(gp.get('cmd') or ''))}`"
                    )
        if inc.get("suspects"):
            top = inc["suspects"][0]
            lines.append(
                f"- 이 창의 1순위: user=`{top.get('user')}` process=`{top.get('process')}` "
                f"pid=`{top.get('pid')}` score=`{top.get('score')}`"
            )
        if inc.get("nearby_logins"):
            lines.append("- 인근 로그인/sudo:")
            for ev in inc["nearby_logins"][:8]:
                lines.append(f"  - `{ev.get('ts')}` {ev.get('summary')}")
        lines.append("- 주요 로그:")
        for ev in (inc.get("events") or [])[:12]:
            lines.append(f"  - `{ev.get('ts')}` **{ev.get('category')}** {ev.get('summary')}")
        lines.append("")

    lines.append("## 권장 조치")
    lines.append("")
    for rec in v.get("recommendations") or []:
        lines.append(f"- {rec}")
    lines.append("")
    lines.append("## Xid 참고")
    lines.append("")
    lines.append("| Xid | 의미 | hang 연관 |")
    lines.append("| --- | --- | --- |")
    for xid, info in sorted(XID_CATALOG.items()):
        lines.append(f"| {xid} | {info['title']} | {info['hang']} |")
    lines.append("")
    lines.append("---")
    lines.append(f"_generated by gpu_hang_diagnostics.py {TOOL_VERSION}_")
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# self-test
# ---------------------------------------------------------------------------
SAMPLE_LOGS = """
2026-08-12T03:10:01+0000 ubuntu sshd[1200]: Accepted publickey for mluser from 10.0.1.8
2026-08-12T03:12:11+0000 kernel: Out of memory: Kill process 18422 (python3) score 912 or sacrifice child
2026-08-12T03:12:11+0000 kernel: oom-kill:constraint=CONSTRAINT_NONE,nodemask=(null),cpuset=/,mems_allowed=0-7,oom_memcg=/,task_memcg=/user.slice,task=python3,pid=18422,uid=1001
2026-08-12T03:14:22+0000 kernel: NVRM: Xid (PCI:0000:00:1e.0): 79, pid=18422, name=python3
2026-08-12T03:14:40+0000 kernel: INFO: task python3:18422 blocked for more than 120 seconds.
2026-08-12T03:14:55+0000 kernel: nvme0n1: I/O 832 timeout
Aug 12 04:01:00 kernel: NVRM: Xid (PCI:0000:00:1e.0): 119, pid=22001, name=pt_main_thread
"""


def self_test() -> int:
    uid_map = {1001: "mluser", 1000: "ubuntu"}
    events = parse_text(SAMPLE_LOGS, "self-test", 2026, uid_map)
    cats = {e.category for e in events}
    assert "oom" in cats, cats
    assert "nvidia_xid" in cats, cats
    assert "hung_task" in cats, cats
    assert "login" in cats, cats
    oom = next(e for e in events if e.category == "oom" and e.uid == 1001)
    assert oom.user == "mluser", oom
    xid = next(e for e in events if e.extra.get("xid") == 79)
    assert xid.process == "python3" and xid.pid == 18422

    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        dump = Path(tmp)
        write_text(dump / "journal-prev.txt", SAMPLE_LOGS)
        write_text(dump / "file-etc__passwd", "mluser:x:1001:1001::/home/mluser:/bin/bash\n")
        write_text(dump / "meta.json", json.dumps({
            "collected_at": "2026-08-12T05:00:00+0000",
            "host": "gpu-dev-1",
            "imds": {"instance-id": "i-test", "instance-type": "g5.xlarge"},
        }))
        report = analyze_dump(dump, window_minutes=20)
        v = report["verdict"]
        assert v["who"] == "mluser", v
        assert v["process"] in {"python3", "xid-79"}, v
        md = render_markdown(report)
        assert "mluser" in md and "python3" in md

    print("self-test ok:", len(events), "events", sorted(cats))
    print("self-test analyze: who=%s process=%s conf=%s" % (v["who"], v["process"], v["confidence"]))
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def cmd_collect(args: argparse.Namespace) -> int:
    out = Path(args.output_dir)
    print(f"[collect] writing snapshot → {out}")
    meta = collect_snapshot(out, since=args.since)
    failed = [k for k, v in meta.get("commands", {}).items() if v.get("exit") not in (0, None) and v.get("bytes", 0) == 0]
    print(f"[collect] done. commands={len(meta.get('commands', {}))} empty_or_failed={failed[:8]}")
    if meta.get("euid") not in (0, None):
        print("[collect] root가 아니면 journal/syslog 일부가 비어 있을 수 있다. sudo로 재실행하라.")
    return 0


def cmd_analyze(args: argparse.Namespace) -> int:
    dump = Path(args.dump_dir or args.output_dir)
    has_logs = dump.exists() and any(
        p.is_file() and p.suffix in {".txt", ".log", ".jsonl"} for p in dump.rglob("*")
    )
    need_collect = bool(getattr(args, "live", False)) or not has_logs
    if need_collect:
        print(f"[analyze] live collect 후 분석한다 → {dump}")
        collect_snapshot(dump, since=args.since)
    report = analyze_dump(dump, window_minutes=args.window_minutes)
    dump.mkdir(parents=True, exist_ok=True)
    json_path = dump / "report.json"
    md_path = dump / "report.md"
    write_text(json_path, json.dumps(report, indent=2, ensure_ascii=False))
    write_text(md_path, render_markdown(report))
    v = report["verdict"]
    print("")
    print("==== 결론 ====")
    print(v.get("headline"))
    print(f"who     : {v.get('who') or '-'}")
    print(f"process : {v.get('process') or '-'}")
    print(f"pid     : {v.get('pid') or '-'}")
    print(f"cause   : {v.get('likely_cause')}")
    print(f"conf    : {v.get('confidence')}")
    print("")
    print(f"JSON : {json_path}")
    print(f"MD   : {md_path}")
    return 0


def cmd_monitor(args: argparse.Namespace) -> int:
    run_monitor(Path(args.output_dir), interval=args.interval, retain_hours=args.retain_hours)
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    args.live = True
    return cmd_analyze(args)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="gpu_hang_diagnostics.py",
        description="AWS Ubuntu GPU 인스턴스 반복 hang의 사용자/프로세스 원인을 분석한다.",
    )
    p.add_argument("--version", action="version", version=f"%(prog)s {TOOL_VERSION}")
    sub = p.add_subparsers(dest="command")

    def add_common(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("-o", "--output-dir", default="./gpu-hang-dump",
                        help="수집/보고서 출력 디렉터리")
        sp.add_argument("--since", default=None,
                        help="journalctl --since 값. 예: '7 days ago', '2026-08-01'")
        sp.add_argument("--window-minutes", type=int, default=20,
                        help="사건 묶음/상관 분석 시간 창(분)")

    sp = sub.add_parser("collect", help="로그와 시스템 스냅샷을 수집한다")
    add_common(sp)
    sp.set_defaults(func=cmd_collect)

    sp = sub.add_parser("analyze", help="수집본 또는 실시간 로그를 분석한다")
    add_common(sp)
    sp.add_argument("--dump-dir", default=None, help="이미 수집된 디렉터리 (기본: --output-dir)")
    sp.add_argument("--live", action="store_true", help="분석 전에 현재 호스트에서 collect 수행")
    sp.set_defaults(func=cmd_analyze)

    sp = sub.add_parser("monitor", help="GPU/프로세스 샘플을 주기적으로 기록한다")
    sp.add_argument("-o", "--output-dir", default="/var/log/gpu-hang-monitor")
    sp.add_argument("--interval", type=int, default=10, help="샘플 주기(초)")
    sp.add_argument("--retain-hours", type=int, default=72, help="샘플 파일 보관 시간")
    sp.set_defaults(func=cmd_monitor)

    sp = sub.add_parser("run", help="현재 호스트에서 collect + analyze (기본 권장)")
    add_common(sp)
    sp.set_defaults(func=cmd_run)

    sp = sub.add_parser("self-test", help="파서 회귀 테스트")
    sp.set_defaults(func=lambda _a: self_test())
    return p


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        args = parser.parse_args(["run"] + (argv or []))
    try:
        return int(args.func(args) or 0)
    except KeyboardInterrupt:
        return 130
    except Exception:
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
