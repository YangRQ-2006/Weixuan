"""
SafetyGuard — NPU Agent 安全框架核心模块
==========================================

提供权限分级、危险检测、快照回滚、审计日志、紧急停止等安全机制，
确保 Agent 在操作手机系统时不会造成不可逆的损害。

作者: NPU Agent Team
版本: 1.0.0
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import time
from dataclasses import dataclass, field
from enum import IntEnum
from pathlib import Path
from typing import Any, Callable, Optional

logger = logging.getLogger("safety_guard")


# ============================================================
# 权限分级
# ============================================================

class PermissionLevel(IntEnum):
    """5 级权限体系"""
    READ_ONLY = 0      # 只读（读文件、查看设置、截图）
    UI_OPERATION = 1   # UI 操作（点击、滑动、输入文字）
    FILE_WRITE = 2     # 文件操作（创建/修改用户空间文件）
    SYSTEM_CHANGE = 3  # 系统设置（修改系统设置、安装/卸载用户应用）
    ROOT_ACCESS = 4    # Root 级（修改系统分区、系统应用）
    DANGEROUS = 5      # 危险级（格式化、分区、刷机）→ 永远需要确认

    @property
    def label(self) -> str:
        labels = {
            0: "只读", 1: "UI操作", 2: "文件写入",
            3: "系统变更", 4: "Root级", 5: "危险级"
        }
        return labels.get(self.value, "未知")

    @property
    def color(self) -> str:
        colors = {
            0: "🟢", 1: "🟢", 2: "🟡",
            3: "🟠", 4: "🔴", 5: "⛔"
        }
        return colors.get(self.value, "⚪")


# ============================================================
# 操作记录
# ============================================================

@dataclass
class Operation:
    """一次 Agent 操作的完整记录"""
    id: str
    timestamp: float
    tool_name: str
    command: str
    permission_level: PermissionLevel
    status: str = "pending"  # pending/approved/executed/rolled_back/denied
    risk_score: float = 0.0
    user_confirmed: bool = False
    snapshot_id: Optional[str] = None
    result: Optional[str] = None
    error: Optional[str] = None


@dataclass
class Snapshot:
    """操作快照（支持回滚）"""
    id: str
    operation_id: str
    timestamp: float
    affected_files: dict[str, str] = field(default_factory=dict)  # path -> backup_path
    system_settings: dict[str, str] = field(default_factory=dict)
    installed_packages: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)


# ============================================================
# 危险检测器
# ============================================================

class DangerDetector:
    """危险命令检测器（规则 + 语义分析）"""

    # Level 5: DANGEROUS — 永远拒绝
    DANGEROUS_PATTERNS = [
        r'rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*)\s+(/|/system|/data|/vendor|/boot|/recovery|/etc|/bin|/sbin)',
        r'rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*)\s+~|/\$HOME',
        r'rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*)\s+\*\s*$',
        r'mkfs\.',
        r'dd\s+.*of=/dev/(block|sd|mmcblk|boot|recovery)',
        r'fdisk|parted|sgdisk|cfdisk',
        r'fastboot\s+(flash|erase|boot)',
        r'mount\s+.*-o\s+.*rw.*/system',
        r'>\s*/(system|vendor|boot|recovery|etc/passwd)',
        r'chmod\s+(-R\s+)?777\s+/',
        r'chown\s+(-R\s+)?\S+\s+/',
        r'wipe\s+data|factory[._]reset',
        r'shutdown|reboot\s+recovery|reboot\s+bootloader',
        r'iptables\s+(-F|-X|--flush)',
        r'selinux\w*\s+(0|disable)',
        r'>\s*/dev/(block|sd|mmcblk)',
    ]

    # Level 4: ROOT_ACCESS — 需要确认
    ROOT_PATTERNS = [
        r'pm\s+uninstall\s+.*(--user\s+\d+\s+)?(com\.android|com\.google|android|system)',
        r'pm\s+(disable|hide)\s+(com\.android|com\.google)',
        r'settings\s+put\s+\w+\s+(lock|password|pin|adb|usb)',
        r'content\s+.*--method\s+\w+',
        r'rm\s+(-rf?)\s+/data/(data|app|system)',
        r'mv\s+\S+\s+/system/',
        r'cp\s+.*\s+/system/',
        r'sed\s+-i.*(/system|/vendor|/boot)',
        r'echo\s+.*>\s*/system/',
        r'setprop\s+(ro\.|persist\.system)',
        r'cmd\s+package\s+(install|uninstall|disable)',
        r'cmd\s+device_config\s+put',
        r'am\s+start.*--ei.*admin',
        r'input\s+keyevent\s+(KEYCODE_POWER|KEYCODE_SLEEP)',
    ]

    # Level 3: SYSTEM_CHANGE — 需要确认
    SYSTEM_PATTERNS = [
        r'pm\s+install',
        r'pm\s+uninstall\s+(?!com\.android|com\.google|android|system)',
        r'pm\s+(disable|enable|clear)',
        r'settings\s+put\s+',
        r'svc\s+(wifi|data|bluetooth|nfc|power)',
        r'cmd\s+(wifi|bluetooth|telephony|connectivity)',
        r'am\s+start.*com\.android\.(settings|contacts|mms|camera)',
        r'dumpsys\s+.*clear',
        r'content\s+insert',
        r'am\s+broadcast',
        r'input\s+text',
    ]

    def __init__(self):
        self._dangerous = [re.compile(p, re.IGNORECASE) for p in self.DANGEROUS_PATTERNS]
        self._root = [re.compile(p, re.IGNORECASE) for p in self.ROOT_PATTERNS]
        self._system = [re.compile(p, re.IGNORECASE) for p in self.SYSTEM_PATTERNS]

    def classify(self, command: str) -> tuple[PermissionLevel, float]:
        """
        分类命令的权限等级和风险分数。
        Returns: (PermissionLevel, risk_score 0.0-1.0)
        """
        cmd = command.strip()

        # 检查 Level 5 (DANGEROUS)
        for pattern in self._dangerous:
            if pattern.search(cmd):
                return PermissionLevel.DANGEROUS, 1.0

        # 检查 Level 4 (ROOT_ACCESS)
        for pattern in self._root:
            if pattern.search(cmd):
                return PermissionLevel.ROOT_ACCESS, 0.8

        # 检查 Level 3 (SYSTEM_CHANGE)
        for pattern in self._system:
            if pattern.search(cmd):
                return PermissionLevel.SYSTEM_CHANGE, 0.5

        # 文件写入检测
        if self._is_file_write(cmd):
            return PermissionLevel.FILE_WRITE, 0.3

        # UI 操作检测
        if self._is_ui_operation(cmd):
            return PermissionLevel.UI_OPERATION, 0.1

        # 默认只读
        return PermissionLevel.READ_ONLY, 0.0

    def _is_file_write(self, cmd: str) -> bool:
        write_patterns = [
            r'touch\s+', r'mkdir\s+', r'cp\s+', r'mv\s+',
            r'>\s*(?!/dev|/proc)', r'>>\s*(?!/dev|/proc)',
            r'tee\s+', r'sed\s+-i', r'cat\s+.*>\s*',
        ]
        return any(re.search(p, cmd) for p in write_patterns)

    def _is_ui_operation(self, cmd: str) -> bool:
        ui_patterns = [
            r'input\s+(tap|swipe|keyevent|text)',
            r'screencap', r'screenrecord',
            r'am\s+start', r'am\s+startservice',
        ]
        return any(re.search(p, cmd) for p in ui_patterns)


# ============================================================
# 权限控制器
# ============================================================

class PermissionController:
    """权限控制器 — 管理会话级权限授权"""

    def __init__(self, max_level: PermissionLevel = PermissionLevel.ROOT_ACCESS):
        self._max_level = max_level
        self._session_grants: set[PermissionLevel] = set()
        self._tool_grants: dict[str, PermissionLevel] = {}  # tool -> max level

    def check(self, level: PermissionLevel) -> bool:
        """检查是否有权限执行该等级的操作"""
        if level > self._max_level:
            return False
        if level in self._session_grants:
            return True
        return level <= PermissionLevel.UI_OPERATION  # 低级操作默认允许

    def grant_session(self, level: PermissionLevel, duration: float = 3600):
        """授予会话级权限（有时效）"""
        self._session_grants.add(level)
        logger.info(f"🔐 授予权限: {level.label} (持续 {duration}s)")

    def revoke_session(self, level: PermissionLevel):
        """回收会话级权限"""
        self._session_grants.discard(level)
        logger.info(f"🔒 回收权限: {level.label}")

    def needs_confirmation(self, level: PermissionLevel) -> bool:
        """判断是否需要用户确认"""
        return level >= PermissionLevel.SYSTEM_CHANGE


# ============================================================
# 快照管理器
# ============================================================

class SnapshotManager:
    """快照管理器 — 操作前备份，支持一键回滚"""

    def __init__(self, backup_dir: str = "/data/local/tmp/agent_snapshots"):
        self._backup_dir = Path(backup_dir)
        self._backup_dir.mkdir(parents=True, exist_ok=True)
        self._snapshots: list[Snapshot] = []

    def create_snapshot(self, operation: Operation, affected_paths: list[str] | None = None) -> Snapshot:
        """操作前创建快照"""
        snap = Snapshot(
            id=f"snap_{int(time.time() * 1000)}",
            operation_id=operation.id,
            timestamp=time.time(),
        )

        # 备份受影响的文件
        if affected_paths:
            for path in affected_paths:
                p = Path(path)
                if p.exists() and p.is_file():
                    backup_path = self._backup_dir / snap.id / p.name
                    backup_path.parent.mkdir(parents=True, exist_ok=True)
                    try:
                        shutil.copy2(str(p), str(backup_path))
                        snap.affected_files[path] = str(backup_path)
                    except Exception as e:
                        logger.warning(f"备份失败 {path}: {e}")

        # 备份系统设置
        try:
            import subprocess
            result = subprocess.run(
                ["settings", "list", "system"], capture_output=True, text=True, timeout=5
            )
            snap.system_settings["_raw"] = result.stdout
        except Exception:
            pass

        # 备包列表
        try:
            import subprocess
            result = subprocess.run(
                ["pm", "list", "packages"], capture_output=True, text=True, timeout=5
            )
            snap.installed_packages = result.stdout.strip().split("\n")
        except Exception:
            pass

        self._snapshots.append(snap)
        operation.snapshot_id = snap.id

        # 限制快照数量
        if len(self._snapshots) > 50:
            old = self._snapshots.pop(0)
            self._cleanup_snapshot(old)

        logger.info(f"📸 创建快照: {snap.id} (备份 {len(snap.affected_files)} 个文件)")
        return snap

    def rollback(self, snapshot_id: str) -> bool:
        """回滚到指定快照"""
        snap = next((s for s in self._snapshots if s.id == snapshot_id), None)
        if not snap:
            logger.error(f"快照不存在: {snapshot_id}")
            return False

        logger.info(f"⏪ 回滚到快照: {snap.id}")

        # 恢复文件
        for original_path, backup_path in snap.affected_files.items():
            try:
                if os.path.exists(backup_path):
                    shutil.copy2(backup_path, original_path)
                    logger.info(f"  ✅ 恢复: {original_path}")
            except Exception as e:
                logger.error(f"  ❌ 恢复失败 {original_path}: {e}")

        logger.info(f"⏪ 回滚完成")
        return True

    def timeline(self) -> list[Snapshot]:
        """查看操作时间线"""
        return self._snapshots.copy()

    def _cleanup_snapshot(self, snap: Snapshot):
        """清理旧快照"""
        snap_dir = self._backup_dir / snap.id
        if snap_dir.exists():
            shutil.rmtree(str(snap_dir), ignore_errors=True)


# ============================================================
# 审计日志
# ============================================================

class AuditLogger:
    """审计日志 — 全量操作记录"""

    def __init__(self, log_file: str = "/data/local/tmp/agent_audit.log"):
        self._log_file = Path(log_file)
        self._log_file.parent.mkdir(parents=True, exist_ok=True)
        self._operations: list[Operation] = []

    def log(self, operation: Operation):
        """记录操作"""
        self._operations.append(operation)
        entry = {
            "id": operation.id,
            "timestamp": operation.timestamp,
            "tool": operation.tool_name,
            "command": operation.command,
            "level": operation.permission_level.label,
            "status": operation.status,
            "confirmed": operation.user_confirmed,
            "snapshot": operation.snapshot_id,
            "result": operation.result,
            "error": operation.error,
        }
        with open(str(self._log_file), "a") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def get_operations(self, limit: int = 100) -> list[Operation]:
        return self._operations[-limit:]


# ============================================================
# 紧急停止
# ============================================================

class EmergencyStop:
    """紧急停止 — 一键冻结 Agent"""

    def __init__(self, max_ops_per_minute: int = 30):
        self._frozen = False
        self._max_ops_per_minute = max_ops_per_minute
        self._recent_ops: list[float] = []
        self._callbacks: list[Callable] = []

    @property
    def is_frozen(self) -> bool:
        return self._frozen

    def trigger(self, reason: str = "manual"):
        """触发紧急停止"""
        self._frozen = True
        logger.warning(f"🚨 紧急停止触发! 原因: {reason}")
        for cb in self._callbacks:
            try:
                cb(reason)
            except Exception:
                pass

    def unfreeze(self):
        """解除冻结"""
        self._frozen = False
        logger.info("✅ 解除冻结")

    def check_operation(self) -> bool:
        """检查是否允许执行操作"""
        if self._frozen:
            return False

        now = time.time()
        self._recent_ops = [t for t in self._recent_ops if now - t < 60]

        if len(self._recent_ops) >= self._max_ops_per_minute:
            self.trigger(f"操作频率超限 ({self._max_ops_per_minute}/min)")
            return False

        self._recent_ops.append(now)
        return True

    def on_freeze(self, callback: Callable):
        self._callbacks.append(callback)


# ============================================================
# SafetyGuard 主类
# ============================================================

class SafetyGuard:
    """
    SafetyGuard — 安全防护中间件

    所有 Agent 操作都必须经过 SafetyGuard 检查后才能执行。
    """

    def __init__(
        self,
        max_permission: PermissionLevel = PermissionLevel.ROOT_ACCESS,
        backup_dir: str = "/data/local/tmp/agent_snapshots",
        audit_file: str = "/data/local/tmp/agent_audit.log",
    ):
        self.detector = DangerDetector()
        self.permissions = PermissionController(max_permission)
        self.snapshots = SnapshotManager(backup_dir)
        self.audit = AuditLogger(audit_file)
        self.emergency = EmergencyStop()

    def check_operation(
        self,
        tool_name: str,
        command: str,
        affected_paths: list[str] | None = None,
    ) -> Operation:
        """
        检查操作安全性。返回 Operation 对象，包含：
        - permission_level: 操作权限等级
        - risk_score: 风险分数
        - 是否需要用户确认
        """
        op = Operation(
            id=f"op_{int(time.time() * 1000)}",
            timestamp=time.time(),
            tool_name=tool_name,
            command=command,
            permission_level=PermissionLevel.READ_ONLY,
        )

        # 紧急停止检查
        if not self.emergency.check_operation():
            op.status = "denied"
            op.error = "Agent 已被冻结"
            self.audit.log(op)
            return op

        # 危险检测
        level, risk = self.detector.classify(command)
        op.permission_level = level
        op.risk_score = risk

        # 权限检查
        if not self.permissions.check(level):
            op.status = "denied"
            op.error = f"权限不足: 需要 {level.label}，当前最高 {self.permissions._max_level.label}"
            self.audit.log(op)
            return op

        # 自动快照（高危操作前）
        if level >= PermissionLevel.SYSTEM_CHANGE:
            snap = self.snapshots.create_snapshot(op, affected_paths)
            op.snapshot_id = snap.id

        # 是否需要确认
        if self.permissions.needs_confirmation(level):
            op.status = "pending"
        else:
            op.status = "approved"
            op.user_confirmed = False

        self.audit.log(op)
        return op

    def confirm(self, operation_id: str) -> bool:
        """用户确认操作"""
        ops = self.audit.get_operations()
        op = next((o for o in ops if o.id == operation_id), None)
        if op:
            op.status = "approved"
            op.user_confirmed = True
            self.audit.log(op)
            return True
        return False

    def deny(self, operation_id: str) -> bool:
        """用户拒绝操作"""
        ops = self.audit.get_operations()
        op = next((o for o in ops if o.id == operation_id), None)
        if op:
            op.status = "denied"
            self.audit.log(op)
            return True
        return False

    def rollback(self, snapshot_id: str) -> bool:
        """回滚操作"""
        return self.snapshots.rollback(snapshot_id)

    def get_status(self) -> dict[str, Any]:
        """获取安全状态"""
        return {
            "frozen": self.emergency.is_frozen,
            "max_permission": self.permissions._max_level.label,
            "recent_operations": len(self.emergency._recent_ops),
            "total_operations": len(self.audit._operations),
        }
