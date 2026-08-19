# AWS Ubuntu GPU Hang 진단 도구

AWS 상의 Ubuntu GPU 인스턴스가 **반복적으로 멈추는(hang / freeze / SSH 무응답)** 상황에서, **누구(사용자)** 의 **어떤 프로세스**가 원인에 가까운지 로그로 추적한다.

핵심 파일:

| 파일 | 설명 |
| --- | --- |
| `gpu_hang_diagnostics.py` | 수집 · 분석 · 상시 모니터 (표준 라이브러리만 사용) |
| `README.md` | 사용 설명서 (이 문서) |

패키지 설치가 필요 없다. Python 3.9+ 만 있으면 된다.

---

## 왜 이 도구가 필요한가

인스턴스가 멈추면 SSH가 끊기고, 재부팅 뒤에는 **당시 실행 중이던 프로세스가 이미 사라진다.**  
커널 로그에 단서가 남아 있는 경우도 있고, 로그를 남기지 못한 채 죽는 경우도 있다.

이 도구는 두 축으로 원인을 좁힌다.

1. **사후 분석 (`run` / `collect` + `analyze`)**  
   `journalctl`(이전 부팅 포함), `dmesg`, syslog, NVIDIA Xid, OOM killer, hung_task, NVMe/ENA timeout, 로그인 기록을 모아 **시간창으로 상관 분석**한다.
2. **사전 샘플링 (`monitor`)**  
   hang이 나기 **직전** GPU를 점유한 사용자·PID·명령줄·메모리·loadavg 를 JSONL로 남긴다.  
   반복 hang이면 **모니터를 켜 두는 것이 가장 중요하다.**

---

## 빠른 시작 (인스턴스가 다시 살아난 직후)

GPU 인스턴스에 파일을 복사한 뒤:

```bash
# 1) 스크립트 업로드 예
scp gpu_hang_diagnostics.py ubuntu@<gpu-instance>:~/gpu_hang_diagnostics.py

# 2) 인스턴스에서 수집 + 분석 (가능하면 root)
sudo python3 ~/gpu_hang_diagnostics.py run -o /tmp/gpu-hang-dump --since "14 days ago"
```

결과:

```
/tmp/gpu-hang-dump/report.md     # 사람이 읽는 결론 (누가 / 어떤 프로세스)
/tmp/gpu-hang-dump/report.json   # 기계가 읽는 전체 이벤트
/tmp/gpu-hang-dump/*.txt         # 원본 스냅샷
```

`report.md` 맨 위 **결론 (누가 / 어떤 프로세스)** 표를 먼저 보면 된다.

Windows에서 수집본만 분석할 수도 있다.

```powershell
python tools\gpu-hang-diagnostics\gpu_hang_diagnostics.py analyze --dump-dir C:\path\to\gpu-hang-dump
```

---

## 명령어

```text
python3 gpu_hang_diagnostics.py <command> [options]
```

명령을 생략하면 `run` 과 같다.

### `run` — 현재 호스트에서 수집 후 즉시 분석 (권장)

```bash
sudo python3 gpu_hang_diagnostics.py run -o /tmp/gpu-hang-dump --since "14 days ago"
```

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `-o`, `--output-dir` | `./gpu-hang-dump` | 스냅샷과 보고서 저장 위치 |
| `--since` | (없음) | `journalctl --since` 값. 예: `7 days ago`, `2026-08-01` |
| `--window-minutes` | `20` | 같은 hang으로 묶을 시간 창(분) |

### `collect` — 로그만 수집

인스턴스에서 수집한 뒤 노트북으로 가져와 분석할 때 사용한다.

```bash
sudo python3 gpu_hang_diagnostics.py collect -o /tmp/gpu-hang-dump --since "14 days ago"
# 이후 디렉터리를 로컬로 복사
```

수집 항목 요약:

- 호스트/커널: `uname`, `uptime`, `ps`, `free`, `df`, `dmesg -T`
- NVIDIA: `nvidia-smi`, compute apps, GPU 메트릭
- 저널: 현재 부팅(`-b 0`)과 **직전 부팅(`-b -1`, `-b -2`)** — hang 후 재부팅이면 직전 부팅이 핵심
- 파일: `/var/log/syslog`, `kern.log`, `auth.log`, `/etc/passwd`(UID→사용자명, 해시 없음)
- AWS IMDS: instance-id, instance-type, Spot 중단 이벤트
- 이미 `monitor`가 돌고 있으면 그 JSONL도 같이 복사

### `analyze` — 수집본 분석

```bash
python3 gpu_hang_diagnostics.py analyze --dump-dir /tmp/gpu-hang-dump
```

`--dump-dir`에 `.txt` / `.log` / `.jsonl` 이 있으면 재수집하지 않는다.  
없으면 현재 머신에서 `collect`를 먼저 한다.

```bash
# 지금 이 인스턴스를 다시 떠서 분석
sudo python3 gpu_hang_diagnostics.py analyze --live -o /tmp/gpu-hang-dump
```

### `monitor` — hang 직전 증거 보존 (반복 장애 시 필수)

```bash
sudo mkdir -p /var/log/gpu-hang-monitor
sudo python3 gpu_hang_diagnostics.py monitor -o /var/log/gpu-hang-monitor --interval 10 --retain-hours 72
```

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `-o` | `/var/log/gpu-hang-monitor` | 샘플 JSONL 디렉터리 |
| `--interval` | `10` | 초 단위 주기 |
| `--retain-hours` | `72` | 날짜별 파일 보관 시간 |

매 주기마다 기록하는 내용:

- loadavg, RAM 사용률, 루트 디스크 사용률
- GPU 이용률 · 온도 · 전력 · 메모리
- **GPU를 점유한 프로세스: user, pid, cmd, GPU 메모리**
- RSS 상위 프로세스 (누가 RAM을 많이 쓰는지)

다음 hang 이후 `run` / `collect`를 하면 이 기록이 dump 안으로 들어가고, 보고서의 용의자 점수에 반영된다.

### `self-test` — 파서 점검

```bash
python3 gpu_hang_diagnostics.py self-test
```

---

## systemd 로 모니터 상시 실행

`/etc/systemd/system/gpu-hang-monitor.service`:

```ini
[Unit]
Description=GPU hang pre-crash sampler
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /opt/gpu-hang-diagnostics/gpu_hang_diagnostics.py monitor -o /var/log/gpu-hang-monitor --interval 10 --retain-hours 72
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
```

```bash
sudo mkdir -p /opt/gpu-hang-diagnostics /var/log/gpu-hang-monitor
sudo cp gpu_hang_diagnostics.py /opt/gpu-hang-diagnostics/
sudo systemctl daemon-reload
sudo systemctl enable --now gpu-hang-monitor.service
sudo systemctl status gpu-hang-monitor.service
```

---

## 보고서 읽는 법

`report.md` 구조:

1. **결론 (누가 / 어떤 프로세스)**  
   `who`, `process`, `pid`, 명령줄, 추정 원인, 신뢰도
2. **유력 용의자**  
   점수 순. 같은 hang 창에서 여러 프로세스가 겹치면 2·3위도 본다.
3. **사건 타임라인**  
   재발 횟수만큼 묶인 사건. 각 창의 GPU 프로세스·로그인·핵심 로그
4. **권장 조치**

신뢰도:

| 값 | 의미 |
| --- | --- |
| `high` | 커널이 프로세스명·PID·UID를 명시했거나, 모니터가 hang 직전 GPU 점유자를 찍었다 |
| `medium` | Xid/OOM/hung_task 는 있으나 사용자 매핑이 부분적 |
| `low` | hang 흔적은 있으나 주체를 특정할 로그가 부족. **모니터를 켜라** |

---

## 이 도구가 잡는 원인 유형

| 카테고리 | 전형적인 증상 | 누가/무엇이 남는지 |
| --- | --- | --- |
| NVIDIA Xid 79, 119, 62, 43 | `nvidia-smi` 자체 hang, 인스턴스 전체 동결 | Xid 로그의 `pid`, `name` + 모니터 GPU 프로세스 |
| OOM killer | 메모리 고갈 후 멈춤/재부팅 | `task=python3,pid=...,uid=...` → `/etc/passwd`로 사용자명 |
| hung_task / soft lockup | `blocked for more than 120 seconds` | 커널이 남긴 comm:pid |
| NVMe I/O timeout | 디스크 hang → SSH 포함 전부 무응답 | 시스템/스토리지. 특정 앱이 Dirty 페이지를 폭주시켰을 수 있음 |
| ENA timeout | 인스턴스는 살아 있는데 SSH만 불가 | 네트워크 드라이버. AWS status check 대조 |
| CUDA OOM | GPU 메모리 부족 | 해당 학습 프로세스 |
| Kernel panic | 강제 재부팅 | 패닉 직전 스택의 프로세스 |
| Spot interruption | 갑자기 죽음 | IMDS `spot/instance-action` |

자주 나오는 NVIDIA Xid:

| Xid | 의미 | hang 연관 |
| --- | --- | --- |
| 31 | GPU memory page fault | 높음 — 잘못된 GPU 메모리 접근 |
| 43 | GPU stopped processing | 높음 |
| 79 | GPU has fallen off the bus | 치명 — 버스에서 GPU 소실 |
| 119 | GSP RPC timeout | 치명 — `nvidia-smi`도 멈춤 |
| 48 / 95 / 140 | ECC 오류 | 하드웨어 불량 가능 |

---

## 권한

| 동작 | 권장 권한 | 이유 |
| --- | --- | --- |
| `collect` / `run` | `sudo` | `journalctl`, `/var/log/syslog`, `dmesg`, 타 사용자 프로세스 cmdline |
| `monitor` | `root` 권장 | 모든 GPU 잡의 UID/명령줄 |
| `analyze` (이미 수집된 dump) | 일반 사용자 | 파일만 읽음 |

root가 아니면 저널/syslog가 비어 `inconclusive`로 끝날 수 있다.

수집하지 **않는** 것: `/etc/shadow`, SSH 키, `.env`.  
다만 `/proc/<pid>/cmdline` 에는 학습 스크립트 인자가 들어갈 수 있으므로 dump 공유 범위를 제한하라.

---

## 권장 운영 순서 (반복 hang)

```text
1. 인스턴스가 살아 있는 동안 monitor 를 systemd 로 상시 실행
2. hang 발생 → AWS 콘솔에서 Status check / 재부팅
3. SSH 복구 직후 즉시:
     sudo python3 gpu_hang_diagnostics.py run -o /tmp/gpu-hang-dump --since "14 days ago"
4. report.md 의 who / process / 사건 시각을
   CloudWatch CPUUtilization, GPUUtilization, StatusCheckFailed 와 대조
5. 용의자 사용자에게 해당 시각 잡(학습 스크립트, docker, tmux) 확인 요청
```

AWS 쪽에서 같이 보면 좋은 항목:

- EC2 **Status checks** (Instance / System)
- CloudWatch `StatusCheckFailed`, `CPUUtilization`, `NetworkPacketsDrop`
- NVIDIA 드라이버 버전 (`nvidia-smi` 헤더) 과 인스턴스 타입 (g4/g5/g6/p4 등)
- Spot 여부, 예약된 유지보수 이벤트

---

## 한계

- hang **도중**에는 이 스크립트도 실행되지 않는다. 복구 후 또는 모니터가 미리 남긴 파일로 분석한다.
- PID는 재부팅 후 재사용된다. 과거 PID를 현재 `ps`와 함부로 연결하지 않는다. (OOM/Xid 로그와 모니터 스냅샷만 신뢰)
- 로그가 rotate 되어 사라지면 증거가 없다. `--since`를 넉넉히 주고, 가능하면 hang 직후 수집한다.
- 하이퍼바이저/호스트 하드웨어 장애는 게스트 로그만으로는 `system` 수준까지만 보인다. AWS Support + 콘솔 메트릭이 필요하다.
- Docker/Kubernetes 잡은 UID가 `root` 또는 컨테이너 UID로 보일 수 있다. cmdline 과 cgroup 문자열을 함께 본다.

---

## 로컬 파서 테스트

개발/CI에서 정규식이 깨지지 않았는지 확인:

```bash
python tools/gpu-hang-diagnostics/gpu_hang_diagnostics.py self-test
```

성공 시 `self-test ok` 와 샘플 로그 기준 `who=mluser process=python3` 가 출력된다.
