#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Project Manager · Site management backend
"""
import argparse
import base64
import hashlib
import json
import os
import pwd
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = "/www/wwwroot/example.com"
TOKEN = ""
AURORA_API = os.environ.get("AURORA_API", "http://127.0.0.1:5004")

# 诊断日志输出到 stdout（nohup 会重定向到 site_server.log），用 [validate] 前缀便于 grep
_LOG = sys.stdout
WWW_ROOT = "/www/wwwroot"

# 备份存储根目录：独立于站点目录（/www/wwwroot）之外，站点即使被清空/清理也不会误伤备份。
# 按卡绑定域名分子目录：<BACKUP_ROOT>/<domain>/backup_<unix>.zip + index.json
BACKUP_ROOT = "/var/site_backups"
BACKUP_LOCK = "/var/site_backups/.lock"
BACKUP_EXTERNAL_CMD = ["systemctl", "restart", "example-app"]  # 后端重启命令（按部署形态调整）


def scan_site_dirs():
    result = {}
    try:
        for name in os.listdir(WWW_ROOT):
            full = os.path.join(WWW_ROOT, name)
            if os.path.isdir(full):
                result[name] = full
    except Exception:
        pass
    return result


BLOCKED_RESET = [
    "rm -rf /", "rm -fr /", "rm -rf /*", "rm -fr /*",
    "rm -rf ~", "rm -fr ~", "rm -rf $HOME", "rm -fr $HOME",
    "rm -rf /www", "rm -fr /www", "rm -rf /etc", "rm -fr /etc",
    "rm -rf /var", "rm -fr /var", "rm -rf /root", "rm -fr /root",
    "rm -rf /home", "rm -fr /home", "rm -rf /usr", "rm -fr /usr",
    "rm -rf /boot", "rm -fr /boot", "rm -rf /sys", "rm -fr /sys",
    "rm -rf /proc", "rm -fr /proc", "rm -rf /dev", "rm -fr /dev",
    "rm -rf /tmp", "rm -fr /tmp", "rm -rf /opt", "rm -fr /opt",
    "shutdown", "reboot", "mkfs.", "dd if=", ":(){", "> /dev/sda",
    "chmod -R 777 /", "chmod -R 777 /www", "chown -R", "mv /* ",
    "find / -delete", "find / -exec", "find /* -delete",
    "| bash", "| sh", "| zsh", "| python", "| python3", "| perl", "| ruby",
    "|/bin/bash", "|/bin/sh", "& bash", "& sh", "& python",
    ">/etc/", ">/root/", ">/var/", ">/www/",
    "wget ", "curl ", "nc ", "ncat ", "socat ",
    "crontab", "systemctl ", "service ",
    "iptables", "ufw ", "firewall-cmd",
    "kill -9 1", "kill -9 -1", "killall ",
    "useradd", "userdel", "passwd ", "adduser", "usermod",
    "chmod 777 /", "chmod 777 /www", "chmod 777 /root",
]
# 纯命令级危险操作（按命令首词精确匹配）。注意 at 必须精确匹配命令首词，
# 绝不能走子串匹配，否则 cat、date 等普通命令会被误拦。
DANGEROUS_CMDS = {"mkfs", "dd", "shutdown", "reboot", "poweroff", "halt", "at"}

BLOCKED_EXTENSIONS = {
    ".sh", ".bash", ".zsh", ".csh", ".ksh",
    ".py", ".pyc", ".pyw",
    ".pl", ".pm",
    ".rb",
    ".php", ".php3", ".php4", ".php5", ".phtml",
    ".jsp", ".asp", ".aspx",
    ".cgi",
    ".exe", ".bin", ".bat", ".cmd", ".com",
    ".so", ".dll", ".dylib",
    ".elf",
}


def is_blocked_file(file_name: str) -> bool:
    name = (file_name or "").lower().strip()
    for ext in BLOCKED_EXTENSIONS:
        if name.endswith(ext):
            return True
    return False


def script_allowed(perm: str) -> bool:
    """按权限等级决定是否允许创建/写入脚本文件。
    A级一律放行、B级允许（仅站点内，执行仍受 run_command 的危险检查），只有 C级禁止脚本。"""
    return perm in ("A", "B")


# ===== 卡密验证缓存（按卡密缓存）=====
_card_cache = {}  # {card_key: {"time": float, "perm": "A", "quota": int, "used": int, "domain": str, "backup_balance": int}}
# 有效会话有效期（秒）：激活成功后的卡在 TTL 内直接用缓存放行，不每请求回询 Go 后端，
# 避免瞬时抖动把刚激活的 B/C 卡误判成"卡密已失效"。到期后才重新向 Go 校验。
SESSION_TTL = 600


def validate_card(card: str, password: str, force: bool = False) -> dict:
    """向 Go 后端验证卡密，返回权限信息。失败返回 None。
    force=True 时强制回询 Go（跳过会话缓存），用于备份等需要实时余额、次数的操作。"""
    global _card_cache
    card = (card or "").strip()
    password = (password or "").strip()
    if not card or not password:
        return None

    now = time.time()
    cached = _card_cache.get(card)

    # 有效会话优先：在会话有效期内直接用缓存放行，避免每请求都打到 Go、也避免瞬时抖动把
    # 刚激活成功的卡误判成"卡密已失效"。B/C 卡反复掉线就是旧版 5 秒缓存 + 命中即 pop 导致的。
    # 需要实时数据（force=True）时跳过缓存，直接回询 Go 拿最新余额/次数。
    if not force and cached and now - cached["time"] < SESSION_TTL:
        if (card or "").strip() == OWNER_CARD:
            cached["perm"] = "A"
        print("[validate] CACHE-HIT", repr(card[-8:]), "perm=", cached.get("perm"), file=_LOG, flush=True)
        return cached

    print("[validate] QUERY-GO", repr(card[-8:]), "cached_exists=", bool(cached), file=_LOG, flush=True)

    # 失效/过期才回询 Go 后端
    try:
        payload = json.dumps({"key": card, "password": password}).encode("utf-8")
        req = urllib.request.Request(
            AURORA_API + "/api/validate-card-key",
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            res = json.loads(body)
    except Exception as e:
        res = None
        print("[validate] GO-EXC", repr(card[-8:]), "err=", repr(str(e)), file=_LOG, flush=True)

    print("[validate] GO-RESP", repr(card[-8:]), "code=", (res or {}).get("code"),
          "valid=", ((res or {}).get("data") or {}).get("valid") if res else None, file=_LOG, flush=True)

    info = None
    if res is not None and res.get("code") == 200:
        data = res.get("data", {})
        if data.get("valid"):
            info = {
                "time": now,
                "perm": data.get("permission_level", "B"),
                "quota": data.get("danger_quota", 0),
                "used": data.get("danger_used", 0),
                "domain": data.get("domain", ""),
                "backup_balance": data.get("backup_balance", 0),
            }
            _card_cache[card] = info

    if info is not None:
        # owner 卡恒定为管理员（A 级），不依赖 Go 后端的 permission_level：
        # 否则一旦后端对该卡返回非 "A"，App 里『反向代理』『管理后台』入口会被 isAdmin 判定整个吞掉。
        if (card or "").strip() == OWNER_CARD:
            info["perm"] = "A"
        return info
    # Go 回询失败/明确无效：只要有曾经有效的会话就继续放行到会话到期，绝不因瞬时失败把会话踢掉
    if cached:
        return cached
    return None


def _card_base(info) -> str:
    """按卡密信息返回该卡的文件管理根目录：A 级 = 系统根 /；B/C 级 = 该卡绑定域名对应的站点目录。

    任何卡都不会落到别人的站点目录里——这是"每张卡管理自己站点"的核心。
    """
    if not info:
        return ROOT
    if info.get("perm") == "A":
        return "/"
    domain = (info.get("domain") or "").strip()
    if domain:
        site = os.path.join(WWW_ROOT, domain)
        if os.path.isdir(site):
            return site
    return ROOT


# ===== 激活状态持久化 =====
ACTIVATED_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "activated.json")


def load_activated():
    try:
        with open(ACTIVATED_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_activated(card: str, domain: str, perm: str, quota: int, used: int, password: str):
    try:
        with open(ACTIVATED_FILE, "w", encoding="utf-8") as f:
            json.dump({
                "card": card, "domain": domain,
                "permission_level": perm, "danger_quota": quota,
                "danger_used": used, "password": password,
            }, f, ensure_ascii=False)
    except Exception:
        pass


def update_activated_used(card: str, used: int):
    data = load_activated()
    if data.get("card") == card:
        data["danger_used"] = used
        try:
            with open(ACTIVATED_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
        except Exception:
            pass


# ===== 默认启动目录（按卡存储，保存后在服务器持久化，客户端启动时拉取）=====
DEFAULT_PATH_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "default_path.json")


def _load_default_paths() -> dict:
    try:
        with open(DEFAULT_PATH_FILE, "r", encoding="utf-8") as f:
            d = json.load(f)
            return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def _save_default_path(card: str, rel: str) -> bool:
    d = _load_default_paths()
    rel = (rel or "").strip().strip("/")
    if rel:
        d[card] = rel
    else:
        d.pop(card, None)  # 空=清除，回到根目录
    try:
        with open(DEFAULT_PATH_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False)
        return True
    except Exception:
        return False


def _get_default_path(base: str, card: str) -> str:
    """返回该卡已存默认启动目录的相对路径串。越出该卡根目录或不存在则返回空(根目录)。"""
    rel = _load_default_paths().get(card, "").strip().strip("/")
    if not rel:
        return ""
    try:
        safe_join(base, rel)  # 越权抛出 PermissionError -> 当作无默认目录，回根目录
        return rel
    except Exception:
        return ""


def activate_card(card: str, password: str) -> dict:
    """调用 Go 后端验证站点卡密，验证通过后本地记录激活状态。"""
    card = (card or "").strip()
    password = (password or "").strip()
    if not card or not password:
        return {"ok": False, "error": "请输入卡密和密码"}

    info = validate_card(card, password)
    if info is None:
        return {"ok": False, "error": "卡密验证失败，请检查卡密和密码"}

    # 激活成功即刷新/覆写该卡的缓存信息，避免同卡改绑域名后 5 秒内仍回旧值
    info["time"] = time.time()
    _card_cache[card] = info

    domain_str = info.get("domain", "")
    if not domain_str:
        return {"ok": False, "error": "该卡密未绑定域名"}

    domains = [d.strip() for d in domain_str.split(",") if d.strip()]
    site_dirs = scan_site_dirs()
    matched_domain = ""
    for d in domains:
        if site_dirs.get(d) and os.path.isdir(site_dirs[d]):
            matched_domain = d
            break

    if not matched_domain:
        return {"ok": False, "error": "未在服务器上找到对应站点目录"}

    # 以实际匹配到的域名/目录为准（卡密可能绑定多个域名），并立即刷新缓存
    info["domain"] = matched_domain
    info["time"] = time.time()
    _card_cache[card] = info

    save_activated(card, matched_domain, info["perm"], info["quota"], 0, password)
    return {"ok": True, "card": card, "domain": matched_domain, "perm": info["perm"]}


def parse_root_path(root: str):
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        os.makedirs(root, exist_ok=True)
    return root


def safe_join(root: str, rel: str) -> str:
    if rel is None:
        rel = ""
    rel = rel.lstrip("/\\")
    if root == "/":
        # A 级：根为系统根 "/"，可自由访问任意路径；因 "/" 是顶层，天然无法越权
        return os.path.abspath(os.path.join("/", rel)) if rel else os.path.abspath("/")
    target = os.path.abspath(os.path.join(root, rel))
    rp = os.path.abspath(root) + os.sep
    if not target.startswith(rp) and target != os.path.abspath(root):
        raise PermissionError("路径越权")
    return target


def _guard_rel(root: str, rel) -> str:
    """操作前统一越权校验：任何操作的路径字段都必须解析后落在 root(base) 之内。
    命中越权直接抛 PermissionError，由 dispatcher 统一返回 403 拒绝，绝不动文件。
    - rel 允许为空（表示当前根目录，用于 list/mkdir/exec 等）
    - A 级 root 为 "/"，天然全部放行
    """
    rel = rel if rel is not None else ""
    return safe_join(root, rel)


# ===== B/C 级命令执行加固：低权账号 + 断网命名空间 + 脚本内容预扫 =====
# 普通客户（B/C）执行命令时，切到低权账号并以无网络命名空间运行，杜绝 root 级破坏与脚本外联。
SITE_RUN_USER = "aurorasite"  # 站点命令专用低权账号（部署时自动创建）

# 脚本文件内容里的高危特征（B 级允许跑脚本，但执行前预扫命中即拒）
SCRIPT_HIGH_RISK = [
    "rm -rf /", "rm -fr /", "rm -rf /*", "rm -fr /*",
    "rm -rf ~", "rm -rf $HOME", "rm -rf /etc", "rm -rf /var",
    "rm -rf /root", "rm -rf /home", "rm -rf /tmp", "rm -rf /www",
    "chmod -R 777 /", "chmod -R 777 /www", "chown -R",
    "shutdown", "reboot", "mkfs.", "dd if=", ":(){", "find / -delete",
    "wget ", "curl ", "nc ", "ncat ", "socat ",
    "crontab", "systemctl ", ">/etc/", ">/root/", ">/var/", "kill -9 1",
]


def _run_user_pw():
    """返回低权账号的 pwd 记录，未创建返回 None。"""
    try:
        return pwd.getpwnam(SITE_RUN_USER)
    except KeyError:
        return None


def _tools_available():
    """核验隔离工具存在；缺工具则命令执行 fail-closed。"""
    import shutil
    return shutil.which("unshare") is not None and shutil.which("setpriv") is not None and shutil.which("env") is not None


def _build_isolated_cmd(pw, cmd):
    """构建降权 + 断网 + 清环境的命令序列。返回 argv 列表。"""
    return [
        "unshare", "-n",
        "setpriv", f"--reuid={pw.pw_uid}", f"--regid={pw.pw_gid}", "--clear-groups",
        "env", "-i",
        f"HOME={pw.pw_dir or '/'}",
        "PATH=/usr/local/bin:/usr/bin:/bin",
        "/bin/sh", "-c", cmd,
    ]


def _parse_script_target(cmd):
    """从命令行里提取脚本解释器作用的脚本文件路径，非脚本命令返回 None。"""
    parts = cmd.strip().split(None, 1)
    if not parts:
        return None
    if parts[0] not in {"bash", "sh", "zsh", "csh", "python", "python3", "php", "perl", "ruby", "node", "lua"}:
        return None
    rest = parts[1].strip() if len(parts) > 1 else ""
    return (rest.split()[0] if rest.split() else "") or None


def _scan_script(root, cwd, cmd):
    """执行前扫描脚本文件内容，命中高危行为则返回命中的特征，否则返回 None。"""
    target = _parse_script_target(cmd)
    if not target or target == "-c":
        return None
    path = target if os.path.isabs(target) else os.path.join(cwd or root, target)
    path = os.path.realpath(path)
    root_real = os.path.realpath(root)
    # 只扫描当前站点根目录内的脚本（外面的一律不管，也不该能执行到）
    if not (path == root_real or path.startswith(root_real + os.sep)):
        return None
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
    except Exception:
        return None
    for bad in SCRIPT_HIGH_RISK:
        if bad in content:
            return bad
    return None


def run_command(root: str, cmd: str, client_cwd: str, perm: str, quota: int, used: int, card: str) -> dict:
    """执行终端命令，按权限等级过滤。返回 (result_dict, new_used)。"""
    rp = os.path.abspath(root)

    # 确定工作目录
    if client_cwd.strip():
        cwd = client_cwd.strip()
        if perm != "A":
            if not os.path.isdir(cwd) or (cwd != rp and not cwd.startswith(rp + os.sep)):
                cwd = rp
    else:
        cwd = "/" if perm == "A" else rp

    stripped = (cmd or "").strip()
    parts = stripped.split(None, 1)
    first = parts[0] if parts else ""

    # ===== A 级：全部放行，不做任何检查 =====
    if perm == "A":
        if first == "cd":
            target = parts[1].strip() if len(parts) > 1 else ""
            if not target:
                cwd = "/"
            else:
                candidate = os.path.abspath(os.path.join(cwd, os.path.expanduser(target)))
                if not os.path.isdir(candidate):
                    return {"ok": False, "output": f"目录不存在: {target}", "code": 1, "cwd": cwd}, used
                cwd = candidate
            return {"ok": True, "output": cwd, "code": 0, "cwd": cwd}, used

        if stripped == "pwd":
            return {"ok": True, "output": cwd, "code": 0, "cwd": cwd}, used

        if first == "ls":
            try:
                entries = sorted(os.listdir(cwd))
                if not entries:
                    return {"ok": True, "output": "(空目录)", "code": 0, "cwd": cwd}, used
                lines_out = [e + "/" if os.path.isdir(os.path.join(cwd, e)) else e for e in entries]
                return {"ok": True, "output": "\n".join(lines_out), "code": 0, "cwd": cwd}, used
            except Exception:
                return {"ok": False, "output": "列出目录失败", "code": -1, "cwd": cwd}, used

        try:
            p = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True, timeout=30)
            out = (p.stdout or "") + (p.stderr or "")
            return {"ok": True, "output": out.strip() or "(无输出)", "code": p.returncode, "cwd": cwd}, used
        except subprocess.TimeoutExpired:
            return {"ok": False, "output": "(命令执行超时 30s)", "code": -1, "cwd": cwd}, used
        except Exception:
            return {"ok": False, "output": "执行出错", "code": -1, "cwd": cwd}, used

    # ===== B 级和 C 级：安全检查 =====
    if stripped == "pwd":
        return {"ok": True, "output": cwd, "code": 0, "cwd": cwd}, used

    if first == "cd":
        target = parts[1].strip() if len(parts) > 1 else ""
        if not target:
            cwd = rp
        else:
            candidate = os.path.abspath(os.path.join(cwd, os.path.expanduser(target)))
            if candidate != rp and not candidate.startswith(rp + os.sep):
                return {"ok": False, "output": "不能越出站点根目录", "code": 1, "cwd": cwd}, used
            if not os.path.isdir(candidate):
                return {"ok": False, "output": f"目录不存在: {target}", "code": 1, "cwd": cwd}, used
            cwd = candidate
        return {"ok": True, "output": cwd, "code": 0, "cwd": cwd}, used

    # 拦截纯危险命令
    for bad in BLOCKED_RESET:
        if bad in cmd:
            return {"ok": False, "output": f"已拦截危险命令：{bad}", "code": -1, "cwd": cwd}, used
    if first in DANGEROUS_CMDS:
        return {"ok": False, "output": f"已拦截危险命令：{first}", "code": -1, "cwd": cwd}, used

    # 危险操作关键词
    DANGER_KEYWORDS = ["rm ", "rm\t", "chmod", "chown", "mv ", "cp ", "kill", "pkill", "tar ", "zip ", "unzip", "gzip", "gunzip"]
    is_dangerous = any(kw in stripped.lower() for kw in DANGER_KEYWORDS)

    if is_dangerous:
        if quota <= 0 or used >= quota:
            return {"ok": False, "output": f"危险操作次数已用完（{used}/{quota}）", "code": -1, "cwd": cwd}, used

    # C 级：禁止执行脚本
    SCRIPT_RUNNERS = {"bash", "sh", "zsh", "csh", "python", "python3", "perl", "ruby", "php", "node", "lua"}
    if perm == "C":
        if first in SCRIPT_RUNNERS and len(parts) > 1:
            target_arg = parts[1].strip().split()[0]
            target_path = os.path.abspath(os.path.join(cwd, os.path.expanduser(target_arg)))
            if target_path.startswith(rp + os.sep) or target_path == rp:
                return {"ok": False, "output": "C级权限：禁止执行脚本文件", "code": -1, "cwd": cwd}, used
        for sep in [";", "&&", "||"]:
            if sep in stripped:
                for part in stripped.split(sep):
                    pfirst = part.strip().split()[0] if part.strip().split() else ""
                    if pfirst in SCRIPT_RUNNERS:
                        return {"ok": False, "output": "C级权限：禁止通过连接符执行脚本解释器", "code": -1, "cwd": cwd}, used
        if first in ("source", ".") and len(parts) > 1:
            target_arg = parts[1].strip().split()[0]
            target_path = os.path.abspath(os.path.join(cwd, os.path.expanduser(target_arg)))
            if target_path.startswith(rp + os.sep) or target_path == rp:
                return {"ok": False, "output": "C级权限：禁止 source 脚本文件", "code": -1, "cwd": cwd}, used
    else:
        # B 级：允许站点内脚本，拦截管道执行解释器
        for sep in [";", "&&", "||"]:
            if sep in stripped:
                for part in stripped.split(sep):
                    pfirst = part.strip().split()[0] if part.strip().split() else ""
                    if pfirst in SCRIPT_RUNNERS:
                        return {"ok": False, "output": "禁止通过连接符执行脚本解释器", "code": -1, "cwd": cwd}, used

    # ls 命令
    if first == "ls":
        try:
            entries = sorted(os.listdir(cwd))
            if not entries:
                return {"ok": True, "output": "(空目录)", "code": 0, "cwd": cwd}, used
            lines_out = [e + "/" if os.path.isdir(os.path.join(cwd, e)) else e for e in entries]
            return {"ok": True, "output": "\n".join(lines_out), "code": 0, "cwd": cwd}, used
        except Exception:
            return {"ok": False, "output": "列出目录失败", "code": -1, "cwd": cwd}, used

    # 脚本预扫：B 级允许运行脚本，但执行前扫描脚本文件内容，命中高危行为即拒
    hit = _scan_script(root, cwd, stripped)
    if hit:
        return {"ok": False, "output": f"已拦截脚本中的危险操作：{hit}", "code": -1, "cwd": cwd}, used

    # B/C 级执行加固：低权账号 + 断网 + 清环境。工具或账号未就绪则 fail-closed，绝不裸跑
    pw = _run_user_pw()
    if pw is None:
        return {"ok": False, "output": "执行环境未配置（缺少低权账号 aurorasite）", "code": -1, "cwd": cwd}, used
    if not _tools_available():
        return {"ok": False, "output": "执行环境缺失隔离工具（unshare/setpriv）", "code": -1, "cwd": cwd}, used

    # 执行命令（低权 + 断网 + 清环境）
    try:
        p = subprocess.run(
            _build_isolated_cmd(pw, cmd), cwd=cwd, capture_output=True, text=True, timeout=30
        )
        out = (p.stdout or "") + (p.stderr or "")
        new_used = used
        if is_dangerous and p.returncode == 0:
            new_used = used + 1
            update_activated_used(card, new_used)
        return {"ok": True, "output": out.strip() or "(无输出)", "code": p.returncode, "cwd": cwd}, new_used
    except subprocess.TimeoutExpired:
        return {"ok": False, "output": "(命令执行超时 30s)", "code": -1, "cwd": cwd}, used
    except Exception:
        return {"ok": False, "output": "执行出错", "code": -1, "cwd": cwd}, used


def _decode_name(raw: bytes) -> str:
    """把目录条目名字节解码成字符串，兼容非 UTF-8（GBK 等）文件名，避免列表里显示成乱码。
    先按 UTF-8 解，失败再依次尝试 gb18030 / gbk / big5，都不行才用替换符兜底。"""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        pass
    for enc in ("gb18030", "gbk", "big5"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def list_dir(root: str, rel: str) -> dict:
    target = safe_join(root, rel)
    if not os.path.isdir(target):
        return {"ok": False, "error": "目录不存在"}
    items = []
    try:
        # 以字节模式读取目录，避免目录内存在非法 UTF-8（乱码）文件名时 os.listdir 抛
        # UnicodeDecodeError，从而拖垮整个目录返回“操作失败”。
        raw_names = os.listdir(target.encode("utf-8", "surrogateescape"))
    except OSError:
        raw_names = []
    for raw in sorted(raw_names):
        name = _decode_name(raw)
        p = os.path.join(target, name)
        is_dir = os.path.isdir(p)
        try:
            size = 0 if is_dir else os.path.getsize(p)
            modified = os.path.getmtime(p)
        except (OSError, ValueError):
            # 单个条目无法 stat（权限不足 / 失效的符号链接 / 并发删除 / 非法文件名）：
            # 仅跳过其元信息，仍列出该条目，避免一个坏文件拖垮整个目录返回“操作失败”。
            size = 0
            modified = 0
        items.append({"name": name, "isDir": is_dir, "size": size, "modified": modified})
    return {"ok": True, "path": rel or "/", "items": items}


def delete(root: str, rel: str) -> dict:
    """删除单个文件或文件夹。root 是站点根(base)，防御：不能删除站点根本身。"""
    target = safe_join(root, rel)
    if not os.path.exists(target):
        return {"ok": False, "error": "不存在"}
    # 【防误删】禁止整站根目录被删：解析后正好等于站点根(base)/系统根/所有站点根 直接拒。
    if _is_root_target(root, target):
        return {"ok": False, "error": "不能删除站点根目录"}
    if os.path.isdir(target):
        import shutil
        shutil.rmtree(target)
    else:
        os.remove(target)
    return {"ok": True}


def _is_root_target(root: str, target: str) -> bool:
    """判断 target 是否等于某级“根目录”（此卡的 base / 所有站点根 / 系统根），是则禁止删除。"""
    try:
        t = os.path.abspath(target)
    except Exception:
        return True
    for r in (root, os.path.abspath(root), "/", WWW_ROOT):
        try:
            if t == os.path.abspath(r):
                return True
        except Exception:
            continue
    if t == "/www/wwwroot" or t == "/www":
        return True
    return False


def batch_delete(root: str, rels: list) -> dict:
    """批量删除文件或文件夹。返回 {ok, success, failed:[{path,error}]}
    容错策略：逐项独立校验与删除——空路径/越权/指向根目录的项只跳过记入 failed，
    绝不因单个坏项抛错而中断整批，保证"能删的照常删，坏项单独列出来"
    """
    import shutil
    # 第一遍：逐项解析校验，不抛错，坏项直接进入 failed
    planned = []  # (rel, target, error)
    for rel in (rels or []):
        rel_s = (rel or "").strip()
        if not rel_s:
            planned.append((rel, None, "空路径"))
            continue
        try:
            target = safe_join(root, rel_s)  # 越权会在 safe_join 抛 PermissionError
        except PermissionError as e:
            planned.append((rel, None, str(e)))
            continue
        if _is_root_target(root, target):
            planned.append((rel, None, "不能删除站点根目录"))
            continue
        planned.append((rel, target, None))

    # 第二遍：真正删除有效项，逐个捕获异常
    success = 0
    failed = []
    for rel, target, err in planned:
        if err:
            failed.append({"path": rel, "error": err})
            continue
        try:
            if not os.path.exists(target):
                failed.append({"path": rel, "error": "不存在"})
                continue
            if os.path.isdir(target):
                shutil.rmtree(target)
            else:
                os.remove(target)
            success += 1
        except Exception as e:
            failed.append({"path": rel, "error": str(e)})
    return {"ok": True, "success": success, "failed": failed}


# ===================== 备份 / 还原 / 后端命令 =====================
# 备份的是「该卡绑定域名对应的整个站点目录」（/www/wwwroot/<domain>），而非 A 级 base("/")，
# 否则 A 级会把整个系统盘打爆。备份文件落在独立目录 BACKUP_ROOT，与站点目录解耦。

def _resolve_site_domain(info) -> tuple:
    """取该卡绑定的第一个有效域名及其站点目录。无效/不存在抛 PermissionError。
    强制要求站点目录必须严格位于 WWW_ROOT 之下（绝不允许塌缩成 /www/wwwroot 等根目录），
    否则 A 级空域名会把整站群当作"一个站点"来备份，或还原时撞上保护根目录。"""
    dom = (info.get("domain") or "").strip().split(",")[0].strip().strip("/")
    if not dom:
        raise PermissionError("未绑定具体站点目录，无法执行备份操作")
    site = os.path.join(WWW_ROOT, dom)
    if not os.path.isdir(site):
        raise PermissionError("未找到对应站点目录")
    site_abs = os.path.abspath(site)
    root_abs = os.path.abspath(WWW_ROOT)
    if site_abs == root_abs or not site_abs.startswith(root_abs + os.sep):
        raise PermissionError("绑定目录不是具体站点目录，已拒绝备份（请绑定到站点目录）")
    return dom, site_abs


def _backup_abs(domain: str, name: str = "") -> str:
    """解析备份目录；name 存在时必须是对应域名的、安全的基本文件名。"""
    dom = (domain or "").strip().strip("/")
    d = os.path.join(BACKUP_ROOT, dom)
    if name:
        base = os.path.basename(name)
        if base != name or base.endswith("/"):
            raise PermissionError("非法备份名")
        return os.path.join(d, base)
    return os.path.abspath(d)


def _read_backup_index(domain: str) -> list:
    """读取某个域名的备份元数据列表（最新在前）。目录/文件缺失返回空列表。"""
    try:
        with open(os.path.join(_backup_abs(domain), "index.json"), "r", encoding="utf-8") as f:
            data = json.load(f)
            return data.get("backups", []) if isinstance(data, dict) else []
    except Exception:
        return []


def _write_backup_index(domain: str, backups: list):
    """原子写 index.json，并清理不存在的备份文件条目。"""
    d = _backup_abs(domain)
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    valid = []
    for b in backups:
        try:
            if os.path.exists(os.path.join(d, b.get("name", ""))):
                valid.append(b)
        except Exception:
            pass
    tmp = os.path.join(d, "index.json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"domain": domain, "updated_at": int(time.time()), "backups": valid}, f)
    os.replace(tmp, os.path.join(d, "index.json"))


def create_backup(info: dict) -> dict:
    """将某卡绑定站点的整个目录打包为 zip 存到独立备份目录。"""
    import zipfile
    dom, site = _resolve_site_domain(info)
    balance = int(info.get("backup_balance", 0) or 0)
    backups = _read_backup_index(dom)
    if len(backups) >= balance:
        return {"ok": False, "error": f"备份余额不足（{len(backups)}/{balance}），请在生成卡密处添加备份余额"}

    d = _backup_abs(dom)
    try:
        os.makedirs(d, exist_ok=True)
    except Exception as e:
        return {"ok": False, "error": f"无法创建备份目录：{e}"}

    bid = int(time.time() * 1000)
    name = f"backup_{bid}.zip"
    zip_path = os.path.join(d, name)
    # 防止同一毫秒内重复创建导致文件名相同、互相覆盖：直到得到唯一文件名
    while os.path.exists(zip_path):
        bid += 1
        name = f"backup_{bid}.zip"
        zip_path = os.path.join(d, name)
    try:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for entry in os.listdir(site):
                full = os.path.join(site, entry)
                if os.path.isdir(full):
                    for dirpath, dirnames, filenames in os.walk(full):
                        for fn in filenames:
                            fp = os.path.join(dirpath, fn)
                            arc = os.path.relpath(fp, site)
                            zf.write(fp, arc)
                else:
                    zf.write(full, entry)
    except Exception as e:
        try:
            os.remove(zip_path)
        except Exception:
            pass
        return {"ok": False, "error": f"备份失败：{e}"}

    size = os.path.getsize(zip_path)
    secs = int(time.time())
    backups.append({"id": bid, "name": name, "size": size, "time": secs})
    backups.sort(key=lambda x: x.get("id", 0), reverse=True)
    _write_backup_index(dom, backups)
    return {"ok": True, "id": bid, "name": name, "size": size, "time": secs}


def list_backups(info: dict) -> dict:
    dom, _site = _resolve_site_domain(info)
    backups = _read_backup_index(dom)
    return {"ok": True, "backup_balance": int(info.get("backup_balance", 0) or 0), "backups": backups}


def _clear_site_contents(site: str):
    """清空站点根目录下所有条目的内容（不删站点根本身）。"""
    import shutil
    for entry in os.listdir(site):
        p = os.path.join(site, entry)
        try:
            if os.path.isdir(p):
                shutil.rmtree(p)
            else:
                os.remove(p)
        except Exception as e:
            raise Exception(f"清空 {entry} 失败：{e}")


def _safe_extract(zf: zipfile.ZipFile, site: str) -> int:
    """把 zip 解压到站点根，逐个校验越权路径；合法提取并加固打开额外写入保护。"""
    import zipfile
    norm = os.path.normpath(site)
    count = 0
    for info in zf.infolist():
        fname = (info.filename or "").replace("\\", "/")
        if fname.startswith("/") or fname.count("/") == 0 and fname == "..":
            raise PermissionError(f"压缩包包含越权路径：{info.filename}")
        for part in fname.split("/"):
            if part == "..":
                raise PermissionError(f"压缩包包含越权路径：{info.filename}")
        tgt = os.path.normpath(os.path.join(site, fname))
        if not tgt.startswith(norm + os.sep):
            raise PermissionError(f"压缩包包含越权路径：{info.filename}")
        if info.is_dir():
            os.makedirs(tgt, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(tgt), exist_ok=True)
        with open(tgt, "wb") as f:
            f.write(zf.read(info))
        count += 1
    return count


def restore_backup(info: dict, name: str) -> dict:
    """用某备份覆盖站点：先清空站点全部内容，再整个解压回去。"""
    import zipfile, shutil
    dom, site = _resolve_site_domain(info)
    name = (name or "").strip()
    backups = _read_backup_index(dom)
    if not any(b.get("name") == name for b in backups):
        return {"ok": False, "error": "备份不存在"}
    zip_path = _backup_abs(dom, name)
    if not os.path.isfile(zip_path):
        return {"ok": False, "error": "备份文件缺失"}
    # 双重保护：site 绝不允许是全局根目录（/、/www/wwwroot 等）。
    # 注意：不能写成 _is_root_target(site, site)——那会恒为 True 导致每次还原都失败。
    if _is_root_target(WWW_ROOT, site):
        return {"ok": False, "error": "安全校验失败"}

    try:
        # 先清空整个站点内容
        _clear_site_contents(site)
        # 再整体覆盖回去
        try:
            with zipfile.ZipFile(zip_path, "r") as zf:
                count = _safe_extract(zf, site)
        except zipfile.BadZipFile:
            return {"ok": False, "error": "备份文件不是有效的 zip，可能已损坏"}
        return {"ok": True, "restored": count}
    except Exception as e:
        return {"ok": False, "error": f"还原失败：{e}"}


def delete_backup(info: dict, name: str) -> dict:
    dom, _site = _resolve_site_domain(info)
    name = (name or "").strip()
    backups = _read_backup_index(dom)
    if not any(b.get("name") == name for b in backups):
        return {"ok": False, "error": "备份不存在"}
    zip_path = _backup_abs(dom, name)
    try:
        if os.path.exists(zip_path):
            os.remove(zip_path)
    except Exception as e:
        return {"ok": False, "error": f"删除失败：{e}"}
    _write_backup_index(dom, [b for b in backups if b.get("name") != name])
    return {"ok": True}


def restart_backend(perm: str) -> dict:
    """后端重启快捷命令：仅 A 级可用，执行白名单内的重启命令（不把任意 shell 放给管理端）。"""
    if perm != "A":
        return {"ok": False, "error": "无权限，仅A级可用"}
    cmd = BACKUP_EXTERNAL_CMD
    if not cmd:
        return {"ok": False, "error": "未配置后端重启命令"}
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        return {"ok": True, "output": (result.stdout or result.stderr or f"退出码 {result.returncode}").strip()}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "重启命令超时"}
    except Exception as e:
        return {"ok": False, "error": f"重启命令执行失败：{e}"}


def run_backend_command(perm: str, cmd: str) -> dict:
    """备份界面下方的后端命令输入框：仅 A 级可用，执行启动/重启后端等命令（下载站已对 A 级全放行）。"""
    if perm != "A":
        return {"ok": False, "error": "无权限，仅A级可执行后端命令"}
    cmd = (cmd or "").strip()
    if not cmd:
        return {"ok": False, "error": "命令为空"}
    try:
        result = subprocess.run(cmd, shell=True, executable="/bin/bash",
                                capture_output=True, text=True, timeout=60)
        out = (result.stdout or "").strip()
        err = (result.stderr or "").strip()
        text = out if out else err
        return {"ok": True, "output": text or f"退出码 {result.returncode}"}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "命令执行超时（60 秒）"}
    except Exception as e:
        return {"ok": False, "error": f"命令执行失败：{e}"}


def zip_files(root: str, rel_dir: str, rels: list, zip_name: str = "") -> dict:
    """将多个文件/文件夹打包为 zip。
    - rel_dir: 相对根目录的当前目录（zip 输出位置）
    - rels: 要打包的文件/文件夹相对路径（相对于 root）
    - zip_name: 输出 zip 文件名（不含路径），留空则自动命名
    安全：所有路径逐项过 safe_join 越权校验，任一越权整个操作拒绝并打日志。
    """
    import zipfile
    target_dir = safe_join(root, rel_dir)
    if not os.path.isdir(target_dir):
        return {"ok": False, "error": "目标目录不存在"}

    # 逐项校验所有要打包的路径，防止越权
    src_paths = []
    for rel in (rels or []):
        src = safe_join(root, rel)  # 越权直接抛 PermissionError，由 dispatcher 统一返回 403
        src_paths.append((rel, src))

    if not zip_name:
        if len(rels) == 1:
            # 单文件/单文件夹：用自身名字命名（如 myfolder → myfolder.zip）
            base = rels[0].rstrip("/").split("/")[-1]
            zip_name = base + ".zip"
        else:
            # 多项：用第一个选中项的名字 + 数量，如 "index_等3项.zip"
            first_name = rels[0].rstrip("/").split("/")[-1]
            # 去掉扩展名作为前缀
            first_base = os.path.splitext(first_name)[0]
            zip_name = f"{first_base}_等{len(rels)}项.zip"
    zip_name = os.path.basename(zip_name.strip())
    if not zip_name.lower().endswith(".zip"):
        zip_name += ".zip"
    zip_path = os.path.join(target_dir, zip_name)

    # 防重名
    if os.path.exists(zip_path):
        base = zip_name[:-4]
        i = 1
        while os.path.exists(os.path.join(target_dir, f"{base}_{i}.zip")):
            i += 1
        zip_name = f"{base}_{i}.zip"
        zip_path = os.path.join(target_dir, zip_name)

    # 【防自递归】zip 输出落在被压缩目录内时，os.walk 会读到正在增长的 zip 把自己无限打包（10G+还在压）。
    # 因此遍历时：每层都剪掉输出 zip 所在目录子树 + 跳过输出 zip 文件本身，并设产物上限防止磁盘被打满。
    OUT_LIMIT = 8 * 1024 * 1024 * 1024  # 压缩产物上限：8GB
    zip_abs = os.path.abspath(zip_path)
    target_abs = os.path.abspath(target_dir)

    # 【顶层必须是文件夹】zip 里第一个层级必须是文件夹（里面才是文件），否则后续上传/移动到别处结构会散开。
    # - 单选一个文件夹：顶层就是该文件夹本身（内容在它下面）
    # - 单选一个文件：包一层同名文件夹
    # - 多选（文件/文件夹混合或纯文件）：包一层以 zip 名（去 .zip）命名的文件夹
    single_dir = (len(src_paths) == 1 and os.path.isdir(src_paths[0][1]))
    if single_dir:
        top_prefix = os.path.basename(src_paths[0][1].rstrip("/"))
    else:
        top_prefix = (zip_name[:-4] if zip_name.lower().endswith(".zip") else zip_name)
        if not top_prefix:
            top_prefix = "archive"
    top_prefix_clean = top_prefix.replace("\\", "/").rstrip("/")

    try:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for rel, src in src_paths:
                if not os.path.exists(src):
                    continue
                if os.path.isdir(src):
                    if single_dir:
                        # 单选文件夹：内容直接挂在顶层文件夹名下面（不再包一层目标名）
                        arc_base = top_prefix_clean
                    else:
                        # 多选含文件夹：目录名作为顶层文件夹名下的下一级
                        arc_base = "/".join([top_prefix_clean, os.path.basename(src.rstrip("/"))])
                    for dirpath, dirnames, filenames in os.walk(src):
                        # 剪掉输出 zip 所在目录子树（无论压在哪个层级都要剔除，否则会读到还在写的自己）
                        dirnames[:] = [d for d in dirnames
                                       if os.path.abspath(os.path.join(dirpath, d)) != target_abs]
                        relsub = os.path.relpath(dirpath, src)
                        if relsub == ".":
                            relsub = ""
                        arc_dir = arc_base + ("/" + relsub if relsub else "")
                        for fn in list(filenames):
                            full = os.path.join(dirpath, fn)
                            if os.path.abspath(full) == zip_abs:
                                continue  # 跳过输出 zip 自身
                            zf.write(full, f"{arc_dir}/{fn}".replace("\\", "/").lstrip("/"))
                            if os.path.getsize(zip_path) > OUT_LIMIT:
                                raise zipfile.LargeZipFile("压缩产物超过安全上限")
                else:
                    if os.path.abspath(src) == zip_abs:
                        continue
                    # 文件总是放进顶层文件夹里：<顶层>/<文件名>
                    arc = f"{top_prefix_clean}/{os.path.basename(src)}".replace("\\", "/").lstrip("/")
                    zf.write(src, arc)
                    if os.path.getsize(zip_path) > OUT_LIMIT:
                        raise zipfile.LargeZipFile("压缩产物超过安全上限")
        size = os.path.getsize(zip_path)
        return {"ok": True, "name": zip_name, "size": size}
    except Exception as e:
        if os.path.exists(zip_path):
            try:
                os.remove(zip_path)
            except Exception:
                pass
        return {"ok": False, "error": f"压缩失败：{e}"}


def _zip_name(info) -> str:
    """修正 zip 条目文件名的编码，解决中文文件名解压后乱码。
    zip 规范：条目未置 UTF-8 标志位(0x800)时，zipfile 会把名字按 cp437 解码；
    而中文 Windows 打出来的包常用 GBK 且不置该标志，直接读出来就是乱码。
    这里把名字还原成原始字节，再依次尝试 utf-8 / gb18030 / gbk / big5 重新解码。
    """
    name = info.filename or ""
    if not name or name.isascii():
        return name
    if info.flag_bits & 0x800:
        return name  # 已声明 UTF-8，原样返回
    try:
        raw = name.encode("cp437")
    except UnicodeEncodeError:
        return name
    for enc in ("utf-8", "gb18030", "gbk", "big5"):
        try:
            decoded = raw.decode(enc)
        except UnicodeDecodeError:
            continue
        if "�" in decoded:
            continue
        return decoded
    return name


def _fix_extracted_names(dest: str) -> int:
    """兜底修正：把解压出来的、字节上不是合法 UTF-8 的文件名（GBK 等）改名成 UTF-8。
    只处理「字节非法 UTF-8」的名字，合法的 UTF-8 名字一律不碰。"""
    fixed = 0
    entries = []
    for dirpath, dirnames, filenames in os.walk(dest, topdown=False):
        for fn in filenames:
            entries.append((dirpath, fn))
        for dn in dirnames:
            entries.append((dirpath, dn))
    for dirpath, name in entries:
        try:
            name.encode("utf-8")
            continue  # 已是合法 UTF-8，不动
        except UnicodeEncodeError:
            pass
        raw = name.encode("utf-8", "surrogateescape")
        new_name = None
        for enc in ("gb18030", "gbk", "big5"):
            try:
                new_name = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        if not new_name or new_name == name:
            continue
        old_path = os.path.join(dirpath, name)
        new_path = os.path.join(dirpath, new_name)
        if os.path.exists(new_path):
            continue
        try:
            os.rename(old_path, new_path)
            fixed += 1
        except OSError:
            pass
    return fixed


def unzip_file(root: str, rel: str, dest_rel: str = "") -> dict:
    """解压 zip 文件。dest_rel 为空则解压到同级同名目录（去掉 .zip）。
    优先用 Python zipfile（快且安全，自带越权检查），失败时 fallback 到系统 unzip 命令（兼容性更广）。
    """
    import zipfile
    import shutil
    src = safe_join(root, rel)
    if not os.path.exists(src):
        return {"ok": False, "error": f"文件不存在：{rel}"}
    if not os.path.isfile(src):
        return {"ok": False, "error": "不是文件，无法解压"}
    if not os.access(src, os.R_OK):
        return {"ok": False, "error": "文件无读取权限"}
    fsize = os.path.getsize(src)
    if fsize == 0:
        return {"ok": False, "error": "文件大小为 0，已损坏（可能是上传时出了问题，重新上传即可）"}

    if not dest_rel:
        base_name = os.path.basename(src)
        if base_name.lower().endswith(".zip"):
            base_name = base_name[:-4]
        parent_dir = os.path.dirname(rel)
        dest_rel = os.path.join(parent_dir, base_name) if parent_dir else base_name

    dest = safe_join(root, dest_rel)
    try:
        os.makedirs(dest, exist_ok=True)
    except Exception as e:
        return {"ok": False, "error": f"无法创建目标目录：{e}"}

    # 方法一：Python zipfile（精确可控，支持越权检查，并修正文件名编码）
    py_error = None
    try:
        with zipfile.ZipFile(src, "r") as zf:
            norm_dest = os.path.normpath(dest)
            entries = []
            for info in zf.infolist():
                raw_name = (info.filename or "").replace("\\", "/")
                if raw_name.endswith("/"):
                    continue
                # 修正编码：未置 UTF-8 标志位的条目 zipfile 按 cp437 解码，中文名会乱码
                fixed = _zip_name(info).replace("\\", "/")
                target_path = os.path.normpath(os.path.join(dest, fixed))
                if not (target_path == norm_dest or target_path.startswith(norm_dest + os.sep)):
                    return {"ok": False, "error": f"压缩包包含越权路径：{raw_name}"}
                entries.append((info, fixed))
            # 逐个写出（不用 extractall，才能用修正后的文件名落盘）
            for info, fixed in entries:
                out_path = os.path.join(dest, fixed)
                parent = os.path.dirname(out_path)
                if parent:
                    os.makedirs(parent, exist_ok=True)
                with zf.open(info, "r") as fin, open(out_path, "wb") as fout:
                    shutil.copyfileobj(fin, fout)
                # 还原可执行位等权限
                try:
                    mode = info.external_attr >> 16
                    if mode:
                        os.chmod(out_path, mode & 0o7777)
                except Exception:
                    pass
            count = len(entries)
        return {"ok": True, "dest": dest_rel, "count": count}
    except zipfile.BadZipFile as e:
        py_error = f"Python zipfile 无法识别（{e}），尝试系统 unzip…"
    except Exception as e:
        py_error = f"Python 解压失败（{e}），尝试系统 unzip…"

    # 方法二：系统 unzip 命令（兼容性更广，兼容各种 zip 变体）
    try:
        result = subprocess.run(
            ["unzip", "-o", "-q", src, "-d", dest],
            capture_output=True, text=True, timeout=120
        )
        if result.returncode == 0:
            # 系统 unzip 会原样写出 GBK 字节名，兜底改名成 UTF-8，避免列表里显示乱码
            _fix_extracted_names(dest)
            # 统计解压出的文件数（只统计文件，不含目录）
            count_result = subprocess.run(
                ["unzip", "-l", src],
                capture_output=True, text=True, timeout=30
            )
            count = 0
            if count_result.returncode == 0:
                # unzip -l 输出最后一行是 "N files"，倒数第二行是合计
                lines = [l for l in count_result.stdout.splitlines() if l.strip()]
                for line in reversed(lines):
                    parts = line.split()
                    if parts and parts[0].isdigit():
                        count = int(parts[0])
                        break
            # 越权兜底：确保所有解压出的文件都在 dest 内
            for dirpath, dirnames, filenames in os.walk(dest):
                for fn in filenames:
                    full = os.path.normpath(os.path.join(dirpath, fn))
                    if not full.startswith(os.path.normpath(dest) + os.sep):
                        return {"ok": False, "error": "解压后发现越权文件，已终止"}
            return {"ok": True, "dest": dest_rel, "count": count,
                    "note": py_error}
        else:
            err_detail = result.stderr.strip() or result.stdout.strip() or f"exit code {result.returncode}"
            # 诊断信息：文件头几个字节，帮助判断文件是不是 zip
            try:
                with open(src, "rb") as f:
                    header = f.read(4)
                hex_header = header.hex()
                # zip 文件头通常是 504b0304 / 504b0506 / 504b0708
                is_likely_zip = hex_header.startswith("504b")
                hint = ""
                if not is_likely_zip:
                    hint = f"（文件头 {hex_header}，不是标准 zip 签名，文件可能已损坏或不是 zip）"
                else:
                    hint = f"（文件头 {hex_header}，看起来像 zip，但内容可能损坏）"
            except Exception:
                hint = ""
            return {"ok": False, "error": f"解压失败：{err_detail}{hint}；文件大小：{fsize} 字节；{py_error}"}
    except FileNotFoundError:
        return {"ok": False, "error": f"系统未安装 unzip 命令；{py_error}"}
    except Exception as e:
        return {"ok": False, "error": f"系统 unzip 也失败：{e}；{py_error}"}


def rename(root: str, rel: str, new_name: str, perm: str = "C") -> dict:
    target = safe_join(root, rel)
    if not os.path.exists(target):
        return {"ok": False, "error": "不存在"}
    new_name = os.path.basename(new_name.strip())
    if not new_name or new_name in (".", ".."):
        return {"ok": False, "error": "非法名称"}
    if not script_allowed(perm) and is_blocked_file(new_name):
        return {"ok": False, "error": "当前权限禁止脚本文件(C级)"}
    os.rename(target, os.path.join(os.path.dirname(target), new_name))
    return {"ok": True}


def mkdir(root: str, rel: str, name: str) -> dict:
    parent = safe_join(root, rel)
    name = os.path.basename(name.strip())
    if not name:
        return {"ok": False, "error": "非法名称"}
    os.makedirs(os.path.join(parent, name), exist_ok=True)
    return {"ok": True}


def duplicate(root: str, srcrel: str, dstrel: str, perm: str = "C") -> dict:
    import shutil
    src = safe_join(root, srcrel)
    dst = safe_join(root, dstrel)
    if not os.path.exists(src):
        return {"ok": False, "error": "源文件/文件夹不存在"}
    if not script_allowed(perm) and is_blocked_file(os.path.basename(dst)):
        return {"ok": False, "error": "当前权限禁止脚本文件(C级)"}
    if os.path.abspath(dst) == os.path.abspath(src):
        return {"ok": False, "error": "不能复制到自身"}
    try:
        if os.path.abspath(dst).startswith(os.path.abspath(src) + os.sep):
            return {"ok": False, "error": "不能复制到自身目录内部"}
    except Exception:
        pass
    if os.path.exists(dst):
        return {"ok": False, "error": "目标位置已存在同名文件/文件夹"}
    if os.path.isdir(src):
        shutil.copytree(src, dst)
    else:
        shutil.copy2(src, dst)
    return {"ok": True}


def move(root: str, srcrel: str, dstrel: str, perm: str = "C") -> dict:
    src = safe_join(root, srcrel)
    dst = safe_join(root, dstrel)
    if not os.path.exists(src):
        return {"ok": False, "error": "源文件/文件夹不存在"}
    if not script_allowed(perm) and is_blocked_file(os.path.basename(dst)):
        return {"ok": False, "error": "当前权限禁止脚本文件(C级)"}
    if os.path.abspath(dst) == os.path.abspath(src):
        return {"ok": False, "error": "目标位置与源相同"}
    try:
        if os.path.abspath(dst).startswith(os.path.abspath(src) + os.sep):
            return {"ok": False, "error": "不能移动到自身目录内部"}
    except Exception:
        pass
    if os.path.exists(dst):
        return {"ok": False, "error": "目标位置已存在同名文件/文件夹"}
    os.rename(src, dst)
    return {"ok": True}


def read_file(root: str, rel: str) -> dict:
    target = safe_join(root, rel)
    if not os.path.isfile(target):
        return {"ok": False, "error": "文件不存在"}
    if os.path.getsize(target) > 10 * 1024 * 1024:
        return {"ok": False, "error": "文件过大，请使用下载功能"}
    with open(target, "r", encoding="utf-8", errors="replace") as f:
        return {"ok": True, "content": f.read()}


def save_file(root: str, rel: str, content: str, perm: str = "C") -> dict:
    target = safe_join(root, rel)
    if not script_allowed(perm) and is_blocked_file(os.path.basename(target)):
        return {"ok": False, "error": "当前权限禁止脚本文件(C级)"}
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        f.write(content)
    return {"ok": True}


def upload(root: str, rel_dir: str, file_name: str, data_b64: str, perm: str = "C") -> dict:
    if not file_name or "/" in file_name or "\\" in file_name:
        return {"ok": False, "error": "非法文件名"}
    if not script_allowed(perm) and is_blocked_file(file_name):
        return {"ok": False, "error": "当前权限禁止脚本文件(C级)"}
    try:
        data = base64.b64decode(data_b64, validate=True)
    except Exception:
        return {"ok": False, "error": "文件数据解码失败"}
    if len(data) > 200 * 1024 * 1024:
        return {"ok": False, "error": "文件超过 200MB，请使用流式上传"}
    target = safe_join(root, os.path.join(rel_dir or "", file_name))
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "wb") as f:
        f.write(data)
    return {"ok": True, "path": os.path.relpath(target, root)}


def download(root: str, rel: str) -> dict:
    target = safe_join(root, rel)
    if not os.path.isfile(target):
        return {"ok": False, "error": "文件不存在"}
    try:
        with open(target, "rb") as f:
            data = f.read()
        return {"ok": True, "name": os.path.basename(target), "data": base64.b64encode(data).decode()}
    except Exception:
        return {"ok": False, "error": "操作失败"}


# ===== 事件驱动的 TCP 长连接推送（独立于 HTTP，端口 3003）=====
# 不做轮询。所有写操作(创建/删除/重命名/保存/上传/复制/移动)成功后调用 _notify_change()，
# 立即唤醒所有活跃连接去刷新其订阅目录并推送。
PUSH_HOST = "0.0.0.0"
PUSH_PORT = 3003

_PUSH_CONNS = {}            # conn -> (change_evt, state, state_lock, push_lock, conn)
_PUSH_CONNS_LOCK = threading.Lock()


def _notify_change():
    """任意文件变化后调用：唤醒所有连接的推送线程，使其立刻重扫并推送。"""
    with _PUSH_CONNS_LOCK:
        for e, _st, _sl, _pl, _c in list(_PUSH_CONNS.values()):
            e.set()


# ===== 服务器端外部变化实时感知（watchdog 成熟库，安全降级，不手写内核对崩溃）=====
_WD_OK = False
_WD_WATCHED = set()      # 已 watch 的 fs 绝对目录
_WD_LOCK = threading.Lock()
_WD_OBSERVER = None


def _wd_init():
    """用成熟 watchdog 库观察站点根目录的文件变化。库缺失/路径缺失则静默降级，绝不崩溃。

    关键点：只观察有界的 /www/wwwroot（递归）。绝不观察整个 "/"，否则会撞系统 inotify
    上限，导致整个观察器 start 失败，watchdog 彻底失效——那正是"外部删文件夹不推送"的根因。
    """
    global _WD_OK, _WD_OBSERVER
    try:
        from watchdog.observers import Observer
        from watchdog.events import FileSystemEventHandler
    except Exception as e:
        print(f"[wd] watchdog 未安装: {e}（外部变化走兜底重扫，几秒内仍会自动同步）", flush=True)
        _WD_OK = False
        return

    class _Handler(FileSystemEventHandler):
        def on_any_event(self, event):
            _notify_change()

    _WD_OBSERVER = Observer()
    _WD_OBSERVER.daemon = True
    watched = 0
    for d in ([WWW_ROOT] if os.path.isdir(WWW_ROOT) else []):
        try:
            _WD_OBSERVER.schedule(_Handler(), d, recursive=True)
            watched += 1
        except Exception as e:
            print(f"[wd] 观察 {d} 失败: {e}", flush=True)

    if watched:
        try:
            _WD_OBSERVER.start()
            _WD_OK = True
            print(f"[wd] watchdog 已就绪（观察 {watched} 个站点根目录，外部变化将实时推送）", flush=True)
            return
        except Exception as e:
            print(f"[wd] 观察器启动失败: {e}（外部变化走兜底重扫）", flush=True)
    else:
        print("[wd] 无可观察的站点根目录，外部变化走兜底重扫", flush=True)
    _WD_OK = False



def _recv_line(conn):
    data = b""
    while True:
        b = conn.recv(1)
        if not b:
            raise ConnectionError("closed")
        if b == b"\n":
            return data.decode("utf-8", "replace")
        data += b


def _tcp_handle(conn):
    """一个 TCP 连接的完整生命周期：鉴权 -> 订阅 -> 变化即推(无轮询)。"""
    push_lock = threading.Lock()

    def push(msg):
        with push_lock:
            conn.sendall((msg + "\n").encode("utf-8"))

    try:
        line = _recv_line(conn).strip()
        if not line.startswith("AUTH "):
            return
        card, password = line[5:].split("|", 1)
        info = validate_card(card, password)
        if info is None:
            push("ERR 卡密已失效")
            return
        perm = info.get("perm", "B")
        domain = info.get("domain", "")
        base = "/" if perm == "A" else _card_base({"perm": perm, "domain": domain})
        push("OK")
        print(f"[push] 连接建立 card={card} perm={perm}", flush=True)
    except Exception:
        try:
            conn.close()
        except Exception:
            pass
        return

    state = {"path": ""}
    state_lock = threading.Lock()
    change_evt = threading.Event()
    with _PUSH_CONNS_LOCK:
        _PUSH_CONNS[conn] = (change_evt, state, state_lock, push_lock, conn)

    def pusher():
        # 以事件驱动为主；wait(timeout) 仅作兜底周期重扫，且只有当列表真实变化才推送（服务端去重）。
        # 这样即使 watchdog 失效，服务器外部(SSH/宝塔)操作也会在几秒内自动出现在 App，无需手动刷新。
        SAFETY = 8.0
        last = None
        try:
            while True:
                change_evt.wait(timeout=SAFETY)
                change_evt.clear()
                with state_lock:
                    rel = state["path"]
                listing = list_dir(base, rel)
                if listing != last:
                    last = listing
                    push("DATA " + json.dumps(listing, ensure_ascii=False))
        except Exception:
            pass
        finally:
            with _PUSH_CONNS_LOCK:
                _PUSH_CONNS.pop(conn, None)
            try:
                conn.close()
            except Exception:
                pass

    threading.Thread(target=pusher, daemon=True).start()

    try:
        while True:
            line = _recv_line(conn).strip()
            if line.startswith("PATH "):
                rel = line[5:].lstrip("/")
                # 【安全】路径越权校验：B/C 级用户只能在自己站点目录内订阅
                try:
                    safe_join(base, rel)
                except PermissionError:
                    push("ERR 路径越权")
                    print(f"[security] push PATH 越权拒绝：base={base} rel={rel}", file=_LOG, flush=True)
                    continue
                with state_lock:
                    state["path"] = rel
                cur_rel = state["path"]
                listing = list_dir(base, cur_rel)
                push("DATA " + json.dumps(listing, ensure_ascii=False))
                print(f"[push] 订阅 {rel}", flush=True)
            elif line.startswith("QUIT"):
                break
    except Exception:
        pass
    finally:
        change_evt.set()
        with _PUSH_CONNS_LOCK:
            _PUSH_CONNS.pop(conn, None)
        try:
            conn.close()
        except Exception:
            pass


def _push_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    srv.bind((PUSH_HOST, PUSH_PORT))
    srv.listen(64)
    print(f"TCP 推送服务已启动(事件驱动): {PUSH_HOST}:{PUSH_PORT}", flush=True)
    while True:
        try:
            conn, _addr = srv.accept()
            threading.Thread(target=_tcp_handle, args=(conn,), daemon=True).start()
        except Exception as e:
            print(f"[push] accept 异常: {e}", flush=True)
            time.sleep(1)


# ===== 反向代理（仅管理员/A级，提交→审核→nginx生效）=====
# 设计原则（多层防护）：
#   1) 入口可见性：A级用户才有"反向代理"，B/C 完全不可见；"管理后台"仅平台 owner 卡密可见。
#   2) 数据隔离：反代记录按绑定域名存储；每个 A 级用户只能读/写自己绑定域名下的记录，绝不越权。
#   3) 二次校验：进入"管理后台"及审核放行必须附上服务端口令 ADMIN_PASSWORD，服务端比对。
#   4) 生效闸门：提交/修改只进待审态(pending)，审核通过才写 nginx；nginx -t 失败即回滚不生效。
#   5) 输入白名单校验：入口路径/目标字段严格校验，杜绝注入 nginx 配置。

# 平台 owner 身份（只存在于服务端，绝不写进 App）。
OWNER_CARD = "change-me"
OWNER_DOMAIN = ""
# 管理后台二次口令（仅服务端比对，App 不明文保存，只在使用时提交）。
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "")

# nginx 反代配置落点与命令（按部署形态调整；写错路径/无权限时安全降级，不崩溃）。
NGINX_CONF_DIR = "/etc/nginx/conf.d"
NGINX_TEST_CMD = ["nginx", "-t"]
NGINX_RELOAD_CMD = ["systemctl", "reload", "nginx"]

PROXY_DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "proxy_data.json")
_PROXY_LOCK = threading.Lock()


def _dup_proxy_id(raw) -> int:
    """安全解析反代记录 id；非法返回 -1（上层命中"记录不存在"等错误）。"""
    try:
        return int(raw)
    except Exception:
        return -1


def _is_owner(card_key, info) -> bool:
    """owner 判定：以平台 owner 卡密为主（唯一的身份凭据），卡密已通过校验视为本人。
    域名仅作为兜底一致性检查（某占比账号因绑定域名差异导致漏判时，不挡住管理后台入口）。"""
    if not card_key or not info:
        return False
    if (card_key or "").strip() != OWNER_CARD:
        return False
    doms = [d.strip() for d in (info.get("domain") or "").split(",") if d.strip()]
    return OWNER_DOMAIN in doms or not doms


def _load_proxy_data() -> dict:
    try:
        with open(PROXY_DATA_FILE, "r", encoding="utf-8") as f:
            d = json.load(f)
            return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def _save_proxy_data(data: dict) -> bool:
    try:
        with _PROXY_LOCK:
            with open(PROXY_DATA_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception:
        return False


def _proxy_next_id(records) -> int:
    return (max((r.get("id", 0) for r in records), default=0)) + 1


def _validate_proxy_input(body, record=None) -> str:
    """反代输入的严格白名单校验。返回错误信息；空串表示通过。所有字段杜绝 `;`/换行，防 nginx 注入。"""
    name = (body.get("name") or "").strip()
    listen_path = (body.get("listen_path") or "").strip()
    target_host = (body.get("target_host") or "").strip()
    target_port = str(body.get("target_port") or "").strip()
    target_path = (body.get("target_path") or "").strip()

    def bad(s):
        return any(ch in s for ch in (";", "\n", "\r", " ", "\t", '"', "'", "\\"))

    if not name or len(name) > 64 or bad(name):
        return "名称为空或包含非法字符"
    if not listen_path.startswith("/") or bad(listen_path):
        return "入口路径必须以 / 开头且不含非法字符"
    if not target_host or len(target_host) > 255 or bad(target_host):
        return "目标地址为空或非法"
    if not target_port.isdigit() or not (1 <= int(target_port) <= 65535):
        return "目标端口必须在 1-65535 之间"
    if len(target_path) > 255 or bad(target_path):
        return "目标路径包含非法字符"
    if record is not None:
        record["name"] = name
        record["listen_path"] = listen_path
        record["target_host"] = target_host
        record["target_port"] = int(target_port)
        record["target_path"] = target_path
    return ""


def _proxy_public(item) -> dict:
    return {
        "id": item.get("id"),
        "name": item.get("name", ""),
        "listen_path": item.get("listen_path", ""),
        "target_host": item.get("target_host", ""),
        "target_port": item.get("target_port", 0),
        "target_path": item.get("target_path", ""),
        "status": item.get("status", "pending"),
        "note": item.get("note", ""),
        "updated_time": item.get("updated_time", 0),
    }


def _proxy_nginx_file(domain: str) -> str:
    dom = "".join(ch for ch in (domain or "").lower() if ch.isalnum() or ch in ".-_")
    return os.path.join(NGINX_CONF_DIR, f"aurora_proxy_{dom}.conf")


def _site_nginx_conf_files(domain: str) -> list:
    """该域名站点在服务器上可能存在 nginx 配置的常见落点（宝塔/OSS 布局均覆盖）。"""
    cands = [
        os.path.join("/www/server/panel/vhost/nginx", f"{domain}.conf"),
        os.path.join("/www/server/panel/vhost/nginx", f"{domain}.conf.backup"),
        os.path.join("/www/server/panel/vhost/proxy", f"{domain}.conf"),
        os.path.join("/etc/nginx/conf.d", f"{domain}.conf"),
        os.path.join("/etc/nginx/sites-available", domain),
        os.path.join("/etc/nginx/sites-enabled", domain),
    ]
    return [c for c in dict.fromkeys(cands) if os.path.isfile(c)]


def _nginx_brace_end(text: str, start: int) -> int:
    """返回从 start('{') 出发的配对 '}' 下标（简单括号计数，足够解析 location 块）。"""
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def _proxy_pass_target(target: str):
    """解析 proxy_pass 目标为 (host, port, path)。目标是变量/无法映射字面量时返回空 host，上层跳过。"""
    t = (target or "").strip().rstrip(';').strip()
    if t.startswith("https://"):
        rest = t[len("https://"):]
    elif t.startswith("http://"):
        rest = t[len("http://"):]
    else:
        rest = t
    if not rest or '$' in rest or '{' in rest or rest.startswith("//"):
        return "", 0, ""
    hostport, _, path = rest.partition('/')
    hostport = hostport.strip()
    if not hostport:
        return "", 0, ""
    host, port = hostport, 80
    if ':' in hostport:
        h, _, p = hostport.rpartition(':')
        if h and p.isdigit():
            host, port = h, int(p)
        elif not p.isdigit():
            # 形如 $host 之类变量端口，无法映射，跳过
            return "", 0, ""
    return host, port, path.rstrip('/')


def _parse_existing_proxies(text: str) -> list:
    """从站点 nginx 配置解析出每个带 proxy_pass 的 location 块，返回基础信息列表。
    兼容 location 的修饰符：= 精确、~ / ~* 正则、^~ 前缀，以及裸路径。"""
    out = []
    loc_re = re.compile(r'(?m)^\s*location\s+(.*?)\s*\{')
    for m in loc_re.finditer(text):
        raw = (m.group(1) or "").strip()
        toks = [t for t in raw.split() if t]
        if not toks:
            continue
        path = toks[-1].strip().strip('"\'')
        # 去掉可能的修饰符(= ~ ~* ^~)后只剩路径
        if not path.startswith('/'):
            continue
        brace = m.end() - 1  # 正则末尾就是 '{'
        end = _nginx_brace_end(text, brace)
        block = text[brace:end + 1]
        pm = re.search(r'proxy_pass\s+([^;]+);', block)
        if not pm:
            continue
        host, port, tpath = _proxy_pass_target(pm.group(1))
        if not host:
            continue
        out.append({"listen_path": path, "target_host": host, "target_port": port, "target_path": tpath})
    return out


def _existing_proxy_views(domain: str) -> list:
    """读取该域名站点 nginx 配置，返回其上【真实已存在】的只读反代，供 App 直接展示。

    id 用负号基址（-100 递减），与 proxy_data.json 的正 id 永不冲突；
    readonly/source=existing 让 App 只读展示，不允许当作可编辑/删除记录。
    """
    seen = set()
    views = []
    kick = -100
    for conf in _site_nginx_conf_files(domain):
        try:
            with open(conf, "r", encoding="utf-8", errors="ignore") as f:
                text = f.read()
        except Exception:
            continue
        for item in _parse_existing_proxies(text):
            key = (item["listen_path"], item["target_host"], item["target_port"], item["target_path"])
            if key in seen:
                continue
            seen.add(key)
            views.append({
                "id": kick,
                "name": ("站点根路径反代" if item["listen_path"] == "/"
                         else f"{item['listen_path']}（站点已有）"),
                "listen_path": item["listen_path"],
                "target_host": item["target_host"],
                "target_port": item["target_port"],
                "target_path": item["target_path"],
                "status": "existing",
                "source": "existing",
                "readonly": True,
                "note": "站点 nginx 里已存在的反向代理，只读展示",
                "updated_time": 0,
            })
            kick -= 1
    return views


def _render_proxy_location(item) -> str:
    """单个反代记录转 nginx location 块。字段已通过白名单校验，可安全拼接入配置。"""
    loc = item.get("listen_path", "")
    host = item.get("target_host", "")
    port = int(item.get("target_port", 0))
    tpath = item.get("target_path", "").lstrip("/")
    upstream = f"http://{host}:{port}"
    if tpath:
        upstream += "/" + tpath
    return (
        f"        location = {loc} {{ proxy_pass {upstream}; }}\n"
        f"        location {loc} {{ proxy_pass {upstream}; proxy_set_header Host $host; "
        f"proxy_set_header X-Real-IP $remote_addr; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; }}\n"
    )


def _render_server_block(domain: str, approved_records) -> str:
    """把某域名下所有已审核记录，包进一个独立的 server{} 块（合法 nginx 配置，可过 nginx -t）。"""
    inner = "\n".join(_render_proxy_location(r).rstrip("\n") for r in approved_records)
    return (
        "# auto-generated by Project Manager proxy manager. Do not edit manually.\n"
        f"# reverse-proxy vhost for: {domain}\n"
        f"server {{\n"
        f"    listen 80;\n"
        f"    server_name {domain};\n"
        f"{inner}\n"
        f"}}\n"
    )


def _apply_nginx(domain: str, approved_records) -> tuple:
    """把该域名下已审核通过的记录生成独立 server 块落盘 nginx，nginx -t 通过后 reload；失败回滚原文件。返回 (ok, err)。"""
    path = _proxy_nginx_file(domain)
    try:
        rendered = _render_server_block(domain, approved_records) if approved_records else "# 无已审核反代，占位（避免误引用 old config）。\n"
        previous = None
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                previous = f.read()
        with open(path, "w", encoding="utf-8") as f:
            f.write(rendered)
        # 校验（配置为空则跳过校验，仅写文件）
        if NGINX_TEST_CMD:
            try:
                p = subprocess.run(NGINX_TEST_CMD, capture_output=True, text=True, timeout=30)
                if p.returncode != 0:
                    raise RuntimeError("nginx 配置校验失败")
            except Exception:
                # 回滚
                if previous is None:
                    try:
                        os.remove(path)
                    except Exception:
                        pass
                else:
                    try:
                        with open(path, "w", encoding="utf-8") as f:
                            f.write(previous)
                    except Exception:
                        pass
                return False, "反代配置校验未通过，未能生效"
        # 生效（重载命令为空则仅写入文件，配置在下次 reload 时生效）
        if NGINX_RELOAD_CMD:
            try:
                subprocess.run(NGINX_RELOAD_CMD, capture_output=True, text=True, timeout=30)
            except Exception:
                return False, "配置已写入，重载 nginx 失败，请稍后重试"
        return True, ""
    except Exception:
        return False, "反代配置应用失败"


def _regen_domain(domain: str) -> tuple:
    """重新生成某域名 nginx 配置（删除某条已生效记录后调用）。返回 (ok, err)。"""
    data = _load_proxy_data()
    approved = []
    for r in data.get(domain, []):
        if r.get("status") == "approved":
            approved.append(r)
    return _apply_nginx(domain, approved)


def proxy_list(domain: str, is_owner: bool = False) -> dict:
    """业务函数：返回某域名下的反代记录（App 管理项 + 站点 nginx 里已存在的只读项）。
    数据隔离由调用方保证（只传 user 自己的域名）。"""
    data = _load_proxy_data()
    items = [_proxy_public(x) for x in data.get(domain, [])]
    existing = _existing_proxy_views(domain)
    return {"ok": True, "items": items, "existing": existing}


def proxy_add(domain: str, body: dict) -> dict:
    data = _load_proxy_data()
    records = data.setdefault(domain, [])
    item = {
        "id": _proxy_next_id(records),
        "name": "",
        "listen_path": "",
        "target_host": "",
        "target_port": 0,
        "target_path": "",
        "status": "pending",
        "note": "",
        "updated_time": int(time.time()),
    }
    err = _validate_proxy_input(body, item)
    if err:
        return {"ok": False, "error": err}
    records.append(item)
    if not _save_proxy_data(data):
        return {"ok": False, "error": "保存失败"}
    return {"ok": True, "item": _proxy_public(item)}


def proxy_update(domain: str, item_id: int, body: dict) -> dict:
    data = _load_proxy_data()
    for r in data.get(domain, []):
        if r.get("id") == item_id:
            was_approved = r.get("status") == "approved"
            err = _validate_proxy_input(body, r)
            if err:
                return {"ok": False, "error": err}
            # 修改后回到待审态，需重新审核生效
            r["status"] = "pending"
            r["updated_time"] = int(time.time())
            if not _save_proxy_data(data):
                return {"ok": False, "error": "保存失败"}
            # 若原本已在 nginx 生效，修改后立即移出，避免改动未审核却仍被路由
            if was_approved:
                _regen_domain(domain)
            return {"ok": True, "item": _proxy_public(r)}
    return {"ok": False, "error": "记录不存在"}


def proxy_delete(domain: str, item_id: int) -> dict:
    data = _load_proxy_data()
    records = data.get(domain, [])
    removed = False
    for i, r in enumerate(records):
        if r.get("id") == item_id:
            records.pop(i)
            removed = True
            break
    if not removed:
        return {"ok": False, "error": "记录不存在"}
    if not _save_proxy_data(data):
        return {"ok": False, "error": "保存失败"}
    # 若删除的是已生效记录，重生成该域 nginx 配置
    _regen_domain(domain)
    return {"ok": True}


# ===== 管理后台：owner 审核所有域名的待审反代，审核通过才 nginx 生效 =====
def admin_list() -> dict:
    data = _load_proxy_data()
    out = []
    for domain, records in data.items():
        for r in records:
            out.append({"domain": domain, **{k: v for k, v in _proxy_public(r).items()}})
    out.sort(key=lambda x: x.get("updated_time", 0), reverse=True)
    return {"ok": True, "items": out}


def admin_approve(domain: str, item_id: int) -> dict:
    data = _load_proxy_data()
    for r in data.get(domain, []):
        if r.get("id") == item_id:
            r["status"] = "approved"
            r["approved_time"] = int(time.time())
            if not _save_proxy_data(data):
                return {"ok": False, "error": "保存失败"}
            approved = [x for x in data.get(domain, []) if x.get("status") == "approved"]
            ok, err = _apply_nginx(domain, approved)
            return {"ok": ok, "error": err} if not ok else {"ok": True}
    return {"ok": False, "error": "记录不存在"}


def admin_reject(domain: str, item_id: int) -> dict:
    data = _load_proxy_data()
    for r in data.get(domain, []):
        if r.get("id") == item_id:
            r["status"] = "rejected"
            r["updated_time"] = int(time.time())
            if not _save_proxy_data(data):
                return {"ok": False, "error": "保存失败"}
            return {"ok": True}
    return {"ok": False, "error": "记录不存在"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def check_auth(self):
        return TOKEN and self.headers.get("X-Auth-Token") == TOKEN

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Auth-Token, X-Card-Key, X-Card-Password")
        self.end_headers()

    def _get_card_info(self, force: bool = False):
        """从请求头读取卡密和密码，向 Go 后端验证，返回权限信息。force 时跳过会话缓存强制回询。"""
        card = self.headers.get("X-Card-Key", "")
        password = self.headers.get("X-Card-Password", "")
        if not card or not password:
            return None
        return validate_card(card, password, force=force)

    def _route(self):
        parsed = urllib.parse.urlparse(self.path)
        query_params = urllib.parse.parse_qs(parsed.query)
        segs = [s for s in parsed.path.split("/") if s]
        if not segs or segs[0] != "api":
            self.send_json({"ok": False, "error": "not found"}, 404)
            return
        action = segs[1] if len(segs) > 1 else ""
        rel = "/".join(segs[2:])

        # 卡密激活：无需鉴权
        if action == "activate":
            body = self.read_body() if self.command == "POST" else {}
            self.send_json(activate_card(body.get("card", ""), body.get("password", "")))
            return

        # 鉴权
        if not self.check_auth():
            self.send_json({"ok": False, "error": "鉴权失败"}, 401)
            return

        # 每次请求都验证卡密身份
        card_info = self._get_card_info()
        if card_info is None:
            # 非 2xx 状态：确保客户端把卡密失效当作错误拦截，而不是当成 200 成功。
            # 流式上传/下载只认状态码，不解析 body，因此必须用 401 结束请求。
            self.send_json({"ok": False, "error": "卡密已失效，请重新激活", "expired": True}, 401)
            return

        perm = card_info["perm"]
        quota = card_info["quota"]
        used = card_info["used"]

        # 每张卡管理自己绑定域名的站点目录：A 级进入系统根 "/"，B/C 级锁定在各自绑定域名对应的站点目录
        base = _card_base(card_info)

        # 流式上传：body 是原始文件字节，绝不能在前面的 read_body() 里被预先读走，
        # 否则图片等二进制内容被消费，handle_upload_stream 只能读到空 → 写出损坏的空文件。
        # 因此必须最先分发，直接读取原始 body 写入目标文件。
        if action == "upload-stream" and self.command == "POST":
            self.handle_upload_stream(base, query_params, perm)
            return

        body = self.read_body() if self.command == "POST" else {}
        if self.command == "GET" and query_params.get("path"):
            body["path"] = query_params["path"][0]

        # 默认启动目录（按卡存储）：GET 拉取，POST 保存覆盖。服务器是权威，避免客户端与本机打架。
        if action in ("default-path", "default_path"):
            card = self.headers.get("X-Card-Key", "")
            if self.command == "GET":
                self.send_json({"ok": True, "path": _get_default_path(base, card)})
            else:
                rel = (body.get("path", "") or "").strip().strip("/")
                try:
                    if rel:
                        safe_join(base, rel)
                except Exception:
                    self.send_json({"ok": False, "error": "路径越权"}, 403)
                    return
                _save_default_path(card, rel)
                self.send_json({"ok": True})
            return

        # ===== 反向代理：仅 A 级；数据按卡绑定域名隔离；owner 管理后台需二次口令 =====
        if action.startswith("proxy") or action.startswith("admin-"):
            card = self.headers.get("X-Card-Key", "")
            is_owner = _is_owner(card, card_info)
            if perm != "A":
                # B/C 级一律拒绝（入口都不该显示，这里作为兜底防线）
                self.send_json({"ok": False, "error": "无权限"}, 403)
                return
            # owner 顶层状态：App 据此决定是否显示"反向代理/管理后台"入口
            if action == "proxy-owner":
                self.send_json({"ok": True, "is_owner": is_owner, "perm": perm, "domain": card_info.get("domain", "")})
                return
            if action in ("admin-list", "admin-approve", "admin-reject"):
                if not is_owner:
                    self.send_json({"ok": False, "error": "无权限访问管理后台"}, 403)
                    return
                if (body.get("admin_password") or "") != ADMIN_PASSWORD:
                    self.send_json({"ok": False, "error": "管理口令错误"}, 403)
                    return
                if action == "admin-list":
                    self.send_json(admin_list())
                elif action == "admin-approve":
                    self.send_json(admin_approve(body.get("domain", ""), _dup_proxy_id(body.get("id"))))
                else:
                    self.send_json(admin_reject(body.get("domain", ""), _dup_proxy_id(body.get("id"))))
                return
            # 普通 A 级：严格锁定到自己绑定域名下的反代，看/改都越不过这一个站点
            scope_domain = (card_info.get("domain") or "").strip()
            if not scope_domain:
                self.send_json({"ok": False, "error": "未绑定域名"}, 400)
                return
            if action == "proxy-list":
                self.send_json(proxy_list(scope_domain))
            elif action == "proxy-add":
                self.send_json(proxy_add(scope_domain, body))
            elif action == "proxy-update":
                self.send_json(proxy_update(scope_domain, _dup_proxy_id(body.get("id")), body))
            elif action == "proxy-delete":
                self.send_json(proxy_delete(scope_domain, _dup_proxy_id(body.get("id"))))
            else:
                self.send_json({"ok": False, "error": "未知操作"}, 404)
            return

        try:
            # 前置统一越权校验：任何操作先验证路径归属身份，命中越权即时拒绝返回，绝不动文件。
            if action in ("list", "delete", "zip", "unzip", "mkdir", "read", "save", "upload", "download", "raw"):
                _guard_rel(base, rel or body.get("path", ""))
            elif action == "exec":
                _guard_rel(base, body.get("cwd", ""))
            elif action == "batch-delete":
                # 预守护也逐项容错：坏路径跳过不拦截，真正的逐项校验交给 batch_delete
                for _p in (body.get("paths", []) or []):
                    try:
                        _guard_rel(base, (_p or "").strip())
                    except PermissionError:
                        continue
            elif action == "rename":
                _guard_rel(base, rel or body.get("path", ""))
            elif action in ("copy", "move"):
                _guard_rel(base, body.get("src", ""))
                _guard_rel(base, body.get("dst", ""))

            if action == "exec":
                result, new_used = run_command(
                    base, body.get("command", ""), body.get("cwd", ""),
                    perm, quota, used, self.headers.get("X-Card-Key", "")
                )
                # 如果危险次数增加了，更新缓存
                if new_used != used:
                    _card_cache.get(self.headers.get("X-Card-Key", ""), {})["used"] = new_used
                self.send_json(result)
            elif action == "info":
                # root=该卡真实文件管理根目录：A级为系统根"/"，B/C级为绑定域名站点目录
                self.send_json({"ok": True, "perm": perm, "domain": card_info.get("domain", ""),
                               "root": _card_base(card_info)})
            elif action == "backup-list":
                # 强制回询 Go 拿最新备份余额，避免刚加的额度被 600 秒会话缓存挡成 0
                fresh = self._get_card_info(force=True) or card_info
                self.send_json(list_backups(fresh))
            elif action == "backup-create":
                fresh = self._get_card_info(force=True) or card_info
                self.send_json(create_backup(fresh))
            elif action == "backup-restore":
                self.send_json(restore_backup(card_info, body.get("name", "")))
            elif action == "backup-delete":
                self.send_json(delete_backup(card_info, body.get("name", "")))
            elif action == "backup-download":
                self.handle_backup_download(card_info, body.get("name", ""))
            elif action == "backend-restart":
                self.send_json(restart_backend(perm))
            elif action == "backend-cmd":
                self.send_json(run_backend_command(perm, body.get("cmd", "")))
            elif action == "diag":
                dom = (card_info.get("domain") or "").strip()
                b = _card_base(card_info)
                site_path = os.path.join(WWW_ROOT, dom) if dom else ""
                self.send_json({
                    "ok": True,
                    "card": self.headers.get("X-Card-Key", ""),
                    "perm": perm,
                    "domain": dom,
                    "routed_root": b,
                    "routed_exists": bool(b and os.path.isdir(b)),
                    "domain_dir_exists": bool(dom and os.path.isdir(site_path)),
                })
            elif action == "list":
                self.send_json(list_dir(base, rel or body.get("path", "")))
            elif action == "delete":
                self.send_json(delete(base, rel or body.get("path", ""))); _notify_change()
            elif action == "batch-delete":
                self.send_json(batch_delete(base, body.get("paths", []) or [])); _notify_change()
            elif action == "zip":
                self.send_json(zip_files(
                    base,
                    rel or body.get("path", ""),
                    body.get("items", []) or [],
                    body.get("name", "") or ""
                )); _notify_change()
            elif action == "unzip":
                self.send_json(unzip_file(
                    base,
                    rel or body.get("path", ""),
                    body.get("dest", "") or ""
                )); _notify_change()
            elif action == "rename":
                self.send_json(rename(base, rel or body.get("path", ""), body.get("newName", ""), perm)); _notify_change()
            elif action == "copy":
                self.send_json(duplicate(base, body.get("src", ""), body.get("dst", ""), perm)); _notify_change()
            elif action == "move":
                self.send_json(move(base, body.get("src", ""), body.get("dst", ""), perm)); _notify_change()
            elif action == "mkdir":
                self.send_json(mkdir(base, rel or body.get("path", ""), body.get("name", ""))); _notify_change()
            elif action == "read":
                self.send_json(read_file(base, rel or body.get("path", "")))
            elif action == "save":
                self.send_json(save_file(base, rel or body.get("path", ""), body.get("content", ""), perm)); _notify_change()
            elif action == "upload":
                self.send_json(upload(base, rel or body.get("path", ""), body.get("file", ""), body.get("data", ""), perm)); _notify_change()
            elif action == "download":
                self.send_json(download(base, rel or body.get("path", "")))
            elif action == "raw":
                self.send_raw(base, rel or body.get("path", ""))
            else:
                self.send_json({"ok": False, "error": "未知操作"}, 404)
        except PermissionError:
            self.send_json({"ok": False, "error": "操作失败"}, 403)
        except Exception:
            self.send_json({"ok": False, "error": "操作失败"}, 500)

    def send_raw(self, root, rel):
        try:
            target = safe_join(root, rel)
            if not os.path.isfile(target):
                self.send_json({"ok": False, "error": "文件不存在"}, 404)
                return
            size = os.path.getsize(target)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            try:
                with open(target, "rb") as f:
                    while True:
                        chunk = f.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass
        except Exception:
            try:
                self.send_json({"ok": False, "error": "操作失败"}, 500)
            except Exception:
                pass

    def handle_backup_download(self, info, name):
        """把某备份 zip 的原始字节流给客户端（下载到本地用）。备份存放在 BACKUP_ROOT/<域名>/。"""
        try:
            dom, _site = _resolve_site_domain(info)
        except PermissionError as e:
            self.send_json({"ok": False, "error": str(e)}, 403)
            return
        name = (name or "").strip().strip("/")
        base = os.path.basename(name)
        if not name or base != name or base.endswith("/"):
            self.send_json({"ok": False, "error": "非法备份名"}, 400)
            return
        target = _backup_abs(dom, name)
        if not os.path.isfile(target):
            self.send_json({"ok": False, "error": "备份文件不存在"}, 404)
            return
        try:
            size = os.path.getsize(target)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            with open(target, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            try:
                self.send_json({"ok": False, "error": "操作失败"}, 500)
            except Exception:
                pass

    def _iter_body(self):
        """逐块读取请求体，同时支持 Content-Length 与 Transfer-Encoding: chunked。
        BaseHTTPRequestHandler 原生不解析 chunked，而客户端在拿不到文件大小时会走 chunked；
        此时请求里没有 Content-Length，只读 Content-Length 会得到 0 字节并写出空文件，故这里显式支持。"""
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            while True:
                line = self.rfile.readline()
                if not line:
                    return
                line = line.strip()
                if not line:
                    continue
                try:
                    size = int(line.split(b";")[0], 16)
                except ValueError:
                    return
                if size == 0:
                    # 结束块：读取 trailer 直到空行
                    while True:
                        t = self.rfile.readline()
                        if not t or t in (b"\r\n", b"\n"):
                            break
                    return
                remaining = size
                while remaining > 0:
                    chunk = self.rfile.read(remaining)
                    if not chunk:
                        return
                    yield chunk
                    remaining -= len(chunk)
                self.rfile.read(2)  # 块尾 CRLF
        else:
            remaining = int(self.headers.get("Content-Length") or 0)
            while remaining > 0:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    return
                yield chunk
                remaining -= len(chunk)

    def handle_upload_stream(self, root, query_params, perm: str = "C"):
        rel_dir = query_params.get("path", [""])[0]
        file_name = query_params.get("file", [""])[0]
        if not file_name or "/" in file_name or "\\" in file_name:
            self.send_json({"ok": False, "error": "非法文件名"})
            return
        if not script_allowed(perm) and is_blocked_file(file_name):
            self.send_json({"ok": False, "error": "当前权限禁止脚本文件(C级)"})
            return
        try:
            target = safe_join(root, os.path.join(rel_dir or "", file_name))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            written = 0
            try:
                with open(target, "wb") as f:
                    for chunk in self._iter_body():
                        f.write(chunk)
                        written += len(chunk)
            except Exception:
                # 写入中断（网络断开等）：删掉残缺文件，避免留下 0B 的空壳
                try:
                    if os.path.exists(target):
                        os.remove(target)
                except Exception:
                    pass
                raise
            self.send_json({"ok": True, "path": os.path.relpath(target, root), "size": written})
            _notify_change()
        except PermissionError:
            self.send_json({"ok": False, "error": "操作失败"}, 403)
        except Exception:
            self.send_json({"ok": False, "error": "操作失败"}, 500)

    def do_GET(self):
        self._route()

    def do_POST(self):
        self._route()


HOST = "0.0.0.0"
PORT = 3001


def main():
    global ROOT, TOKEN, HOST, PORT, NGINX_CONF_DIR, NGINX_TEST_CMD, NGINX_RELOAD_CMD
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=3001)
    ap.add_argument("--root", default="/www/wwwroot/example.com")
    ap.add_argument("--token", default="change-me")
    ap.add_argument("--nginx-conf-dir", default="/etc/nginx/conf.d",
                    help="反向代理配置写入目录（每域名一个 aurora_proxy_<domain>.conf）")
    ap.add_argument("--nginx-test", default="nginx -t", help="nginx 配置校验命令（可空以跳过校验）")
    ap.add_argument("--nginx-reload", default="systemctl reload nginx", help="nginx 生效重载命令")
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    ROOT = parse_root_path(args.root)
    TOKEN = args.token
    HOST = args.host
    PORT = args.port
    # 反代 nginx 落地参数：命令字符串转 argv；为空则"只写文件不校验/不重载"（安全降级）。
    NGINX_CONF_DIR = (args.nginx_conf_dir or "").strip() or NGINX_CONF_DIR
    NGINX_TEST_CMD = (args.nginx_test or "").strip().split() if (args.nginx_test or "").strip() else []
    NGINX_RELOAD_CMD = (args.nginx_reload or "").strip().split() if (args.nginx_reload or "").strip() else []
    print(f"站点管理服务已启动: http://{HOST}:{PORT}")
    print(f"站点根目录: {ROOT}")
    print(f"反代 nginx: 目录={NGINX_CONF_DIR} 校验={' '.join(NGINX_TEST_CMD) or '跳过'} 重载={' '.join(NGINX_RELOAD_CMD) or '跳过'}")

    # 为 B/C 级创建低权执行账号（幂等）。非 root 或无 useradd 权限时仅告警，命令执行会明确报错。
    try:
        import grp
        pw = pwd.getpwnam(SITE_RUN_USER)
        print(f"低权账号 {SITE_RUN_USER} 已存在 (uid={pw.pw_uid})")
    except KeyError:
        try:
            subprocess.run(["useradd", "-r", "-M", "-s", "/sbin/nologin", SITE_RUN_USER], check=True)
            pw = pwd.getpwnam(SITE_RUN_USER)
            print(f"已创建低权账号 {SITE_RUN_USER} (uid={pw.pw_uid})")
        except Exception as e:
            pw = None
            print(f"[警告] 创建低权账号失败：{e} —— B/C 级命令执行将被拒绝")
    if pw is not None:
        try:
            os.chown(ROOT, pw.pw_uid, pw.pw_gid)
            print(f"已 chown 站点根目录 {ROOT} -> {SITE_RUN_USER}，低权账号可读写站点文件")
        except Exception as e:
            print(f"[警告] chown 站点根目录失败：{e} —— 低权账号可能无法写入站点文件")

    # 启动独立 TCP 推送服务（端口 3003）
    threading.Thread(target=_push_server, daemon=True).start()
    _wd_init()


if __name__ == "__main__":
    main()
    try:
        ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        pass
