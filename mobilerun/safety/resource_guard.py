"""
ResourceGuard — NPU Agent 资源守护系统
========================================

防止大模型推理把手机卡死的关键模块：
- 内存守卫：动态监控，防止 OOM / LMK 杀进程
- 温控管理：主动降频，避免热节流
- UI 保活：推理不阻塞界面
- 功耗管理：电量感知调度
- 自适应性能：根据设备状态动态调参
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Optional

logger = logging.getLogger("resource_guard")


class DeviceState(Enum):
    """设备状态"""
    COOL = "cool"          # 温度低，满性能
    WARM = "warm"          # 温度中等，轻微降频
    HOT = "hot"            # 温度高，降频
    CRITICAL = "critical"  # 温度极高，暂停推理
    LOW_BATTERY = "low_battery"
    LOW_MEMORY = "low_memory"


@dataclass
class ResourceMetrics:
    """资源指标"""
    timestamp: float = 0.0
    # 内存
    mem_total_mb: int = 0
    mem_available_mb: int = 0
    mem_used_mb: int = 0
    mem_usage_pct: float = 0.0
    # 温度
    cpu_temp: float = 0.0
    gpu_temp: float = 0.0
    npu_temp: float = 0.0
    battery_temp: float = 0.0
    # CPU
    cpu_usage_pct: float = 0.0
    cpu_freq_mhz: int = 0
    # GPU
    gpu_usage_pct: float = 0.0
    gpu_freq_mhz: int = 0
    # 电池
    battery_pct: int = 0
    is_charging: bool = False
    # 推理
    inference_active: bool = False
    tokens_per_second: float = 0.0
    kv_cache_mb: int = 0
    model_memory_mb: int = 0


# ============================================================
# 内存守卫
# ============================================================

class MemoryGuard:
    """
    内存守卫 — 防止 OOM 和 Android LMK 杀进程。

    核心策略：
    1. 预留安全水位（至少 500MB 可用内存）
    2. 动态 KV Cache 大小（内存紧张时自动缩小）
    3. 模型分片按需加载（不一次性加载全部权重）
    4. 紧急释放（内存告警时释放非关键缓存）
    """

    # 内存水位（MB）
    SAFE_MARGIN = 500        # 安全水位
    WARNING_LEVEL = 800      # 告警水位
    CRITICAL_LEVEL = 300     # 危险水位
    EMERGENCY_LEVEL = 150    # 紧急水位

    def __init__(self, total_mb: int = 16384):
        self._total = total_mb
        self._callbacks: dict[str, list[Callable]] = {
            "warning": [],
            "critical": [],
            "emergency": [],
        }
        self._model_mb = 0
        self._kv_cache_mb = 0
        self._kv_cache_max_mb = 2048  # 默认最大 KV Cache 2GB

    def check_memory(self) -> ResourceMetrics:
        """检查当前内存状态"""
        metrics = ResourceMetrics()
        metrics.timestamp = time.time()
        metrics.mem_total_mb = self._total

        try:
            with open("/proc/meminfo") as f:
                for line in f:
                    if line.startswith("MemAvailable:"):
                        metrics.mem_available_mb = int(line.split()[1]) // 1024
                    elif line.startswith("MemTotal:"):
                        metrics.mem_total_mb = int(line.split()[1]) // 1024
            metrics.mem_used_mb = metrics.mem_total_mb - metrics.mem_available_mb
            metrics.mem_usage_pct = metrics.mem_used_mb / metrics.mem_total_mb * 100
        except Exception as e:
            logger.warning(f"读取内存信息失败: {e}")

        return metrics

    def can_load_model(self, model_mb: int) -> bool:
        """检查是否可以加载模型"""
        metrics = self.check_memory()
        after_load = metrics.mem_available_mb - model_mb
        return after_load > self.SAFE_MARGIN

    def get_optimal_kv_cache_mb(self) -> int:
        """根据可用内存动态计算最优 KV Cache 大小"""
        metrics = self.check_memory()
        available = metrics.mem_available_mb - self.SAFE_MARGIN
        optimal = min(available // 2, self._kv_cache_max_mb)
        optimal = max(optimal, 128)  # 最小 128MB
        logger.info(f"📊 KV Cache 自适应: {optimal}MB (可用 {metrics.mem_available_mb}MB)")
        return optimal

    def get_optimal_context_window(self) -> int:
        """根据内存自适应计算上下文窗口大小"""
        kv_mb = self.get_optimal_kv_cache_mb()
        # 估算：每个 token 的 KV Cache ≈ 0.5MB (8B 模型) / 1.5MB (27B 模型)
        tokens = int(kv_mb / 1.5)  # 保守估计
        tokens = max(512, min(tokens, 8192))
        return tokens

    def trigger_callbacks(self, level: str):
        for cb in self._callbacks.get(level, []):
            try:
                cb()
            except Exception:
                pass

    def on(self, level: str, callback: Callable):
        self._callbacks[level].append(callback)

    def emergency_release(self):
        """紧急释放内存"""
        logger.warning("🚨 紧急释放内存！")
        self.trigger_callbacks("emergency")
        # 释放 KV Cache 缓存
        self._kv_cache_mb = 0


# ============================================================
# 温控管理
# ============================================================

class ThermalManager:
    """
    温控管理 — 主动降频，避免热节流。

    策略：
    1. 监控 CPU/GPU/NPU 温度
    2. 温度阈值触发推理降频
    3. 极端温度暂停推理
    4. 温度恢复后自动提速
    """

    # 温度阈值（摄氏度）
    TEMP_COOL = 40.0
    TEMP_WARM = 50.0
    TEMP_HOT = 60.0
    TEMP_CRITICAL = 70.0

    # 推理速度调节系数
    SPEED_COOL = 1.0      # 满速
    SPEED_WARM = 0.7      # 70% 速度
    SPEED_HOT = 0.4       # 40% 速度
    SPEED_CRITICAL = 0.0  # 暂停

    def __init__(self):
        self._current_speed_factor = 1.0
        self._callbacks: dict[str, list[Callable]] = {
            "warm": [], "hot": [], "critical": [], "cool": []
        }

    def get_temperatures(self) -> dict[str, float]:
        """读取设备温度（只读真实传感器，排除 trip point 阈值）"""
        temps = {"cpu": 0.0, "gpu": 0.0, "battery": 0.0, "npu": 0.0}
        try:
            thermal_zones = "/sys/class/thermal"
            if os.path.exists(thermal_zones):
                for zone in os.listdir(thermal_zones):
                    if zone.startswith("thermal_zone"):
                        zone_path = os.path.join(thermal_zones, zone)
                        try:
                            with open(os.path.join(zone_path, "type")) as f:
                                zone_type = f.read().strip().lower()
                            # 🔴 关键修复：排除 trip point（阈值，不是实际温度！）
                            if "trip" in zone_type or "hw-trip" in zone_type:
                                continue
                            with open(os.path.join(zone_path, "temp")) as f:
                                temp = int(f.read().strip()) / 1000.0  # 毫度→度
                            # 排除无效读数（-273°C = 绝对零度 = 传感器未连接）
                            if temp < -100 or temp > 150:
                                continue
                            
                            if "cpu" in zone_type or "cpullc" in zone_type:
                                temps["cpu"] = max(temps["cpu"], temp)
                            elif "gpu" in zone_type or "gpuss" in zone_type:
                                temps["gpu"] = max(temps["gpu"], temp)
                            elif "battery" in zone_type or "batt" in zone_type:
                                temps["battery"] = max(temps["battery"], temp)
                            elif "dsp" in zone_type or "npu" in zone_type or "hexagon" in zone_type or "nsphvx" in zone_type or "nsphmx" in zone_type or "qmx" in zone_type:
                                temps["npu"] = max(temps["npu"], temp)
                        except Exception:
                            pass
        except Exception as e:
            logger.warning(f"读取温度失败: {e}")
        return temps

    def get_state(self) -> tuple[DeviceState, float]:
        """获取温控状态和速度系数"""
        temps = self.get_temperatures()
        max_temp = max(temps.values())

        if max_temp >= self.TEMP_CRITICAL:
            state, factor = DeviceState.CRITICAL, self.SPEED_CRITICAL
        elif max_temp >= self.TEMP_HOT:
            state, factor = DeviceState.HOT, self.SPEED_HOT
        elif max_temp >= self.TEMP_WARM:
            state, factor = DeviceState.WARM, self.SPEED_WARM
        else:
            state, factor = DeviceState.COOL, self.SPEED_COOL

        self._current_speed_factor = factor
        return state, factor

    def get_speed_factor(self) -> float:
        """获取当前推理速度系数"""
        _, factor = self.get_state()
        return factor

    def should_pause(self) -> bool:
        """是否应该暂停推理"""
        state, _ = self.get_state()
        return state == DeviceState.CRITICAL

    def get_optimal_batch_size(self) -> int:
        """根据温度自适应批次大小"""
        factor = self.get_speed_factor()
        if factor >= 1.0:
            return 512   # 满速
        elif factor >= 0.7:
            return 256   # 降速
        elif factor >= 0.4:
            return 128   # 低速
        return 64        # 极低速

    def on(self, level: str, callback: Callable):
        self._callbacks[level].append(callback)


# ============================================================
# 功耗管理
# ============================================================

class PowerManager:
    """功耗管理 — 电量感知调度"""

    BATTERY_LOW = 20
    BATTERY_CRITICAL = 10

    def __init__(self):
        self._callbacks: dict[str, list[Callable]] = {
            "low": [], "critical": []
        }

    def get_battery_status(self) -> tuple[int, bool]:
        """获取电池状态 (电量%, 是否充电)"""
        try:
            with open("/sys/class/power_supply/battery/capacity") as f:
                pct = int(f.read().strip())
            with open("/sys/class/power_supply/battery/status") as f:
                status = f.read().strip().lower()
            return pct, status == "charging"
        except Exception:
            return 100, True  # 未知时假设有电

    def get_state(self) -> DeviceState:
        pct, charging = self.get_battery_status()
        if not charging:
            if pct <= self.BATTERY_CRITICAL:
                return DeviceState.CRITICAL
            elif pct <= self.BATTERY_LOW:
                return DeviceState.LOW_BATTERY
        return DeviceState.COOL

    def get_max_inference_time(self) -> float:
        """根据电量限制最大推理时间（秒）"""
        pct, charging = self.get_battery_status()
        if charging:
            return 3600  # 充电中不限
        if pct > 50:
            return 600   # 10 分钟
        elif pct > 20:
            return 180   # 3 分钟
        return 60        # 1 分钟

    def should_throttle(self) -> bool:
        """是否应该降频"""
        pct, charging = self.get_battery_status()
        return not charging and pct < 30


# ============================================================
# 自适应性能控制器
# ============================================================

class AdaptiveController:
    """
    自适应性能控制器 — 综合内存/温度/电量动态调参。

    核心思想：推理不是全有全无，而是根据设备状态动态调节。
    """

    def __init__(
        self,
        model_mb: int = 10000,  # 模型大小
    ):
        self.memory = MemoryGuard()
        self.thermal = ThermalManager()
        self.power = PowerManager()
        self._model_mb = model_mb
        self._inference_thread: Optional[threading.Thread] = None
        self._paused = False
        self._pause_event = threading.Event()

    def get_inference_config(self) -> dict[str, Any]:
        """
        根据当前设备状态返回最优推理配置。

        返回:
        {
            "can_infer": bool,       # 是否可以推理
            "max_tokens": int,       # 最大生成 token 数
            "batch_size": int,       # 批次大小
            "context_window": int,   # 上下文窗口
            "kv_cache_mb": int,      # KV Cache 大小
            "speed_factor": float,   # 速度系数 (0-1)
            "max_inference_sec": float,  # 最大推理时间
            "reason": str,           # 当前状态说明
        }
        """
        config = {}

        # 检查内存
        mem_metrics = self.memory.check_memory()
        config["mem_available_mb"] = mem_metrics.mem_available_mb

        # 检查温度
        thermal_state, speed_factor = self.thermal.get_state()
        config["speed_factor"] = speed_factor
        config["thermal_state"] = thermal_state.value

        # 检查电量
        power_state = self.power.get_state()
        config["power_state"] = power_state.value

        # 是否可以推理
        can_infer = (
            mem_metrics.mem_available_mb > self.memory.CRITICAL_LEVEL
            and not self.thermal.should_pause()
            and power_state != DeviceState.CRITICAL
        )
        config["can_infer"] = can_infer

        if not can_infer:
            reasons = []
            if mem_metrics.mem_available_mb <= self.memory.CRITICAL_LEVEL:
                reasons.append(f"内存不足 ({mem_metrics.mem_available_mb}MB)")
            if self.thermal.should_pause():
                reasons.append("温度过高")
            if power_state == DeviceState.CRITICAL:
                reasons.append("电量极低")
            config["reason"] = "; ".join(reasons)
        else:
            config["reason"] = f"温度:{thermal_state.value}, 内存:{mem_metrics.mem_available_mb}MB, 电量:{self.power.get_battery_status()[0]}%"

        # 自适应参数
        config["batch_size"] = self.thermal.get_optimal_batch_size()
        config["kv_cache_mb"] = self.memory.get_optimal_kv_cache_mb()
        config["context_window"] = self.memory.get_optimal_context_window()
        config["max_inference_sec"] = self.power.get_max_inference_time()
        config["max_tokens"] = int(1024 * speed_factor)

        return config

    def print_status(self):
        """打印当前设备状态"""
        config = self.get_inference_config()
        print(f"\n{'='*50}")
        print(f"📱 设备资源状态")
        print(f"{'='*50}")
        print(f"  可用内存: {config.get('mem_available_mb', 0)} MB")
        print(f"  温度状态: {config.get('thermal_state', '?')}")
        print(f"  电源状态: {config.get('power_state', '?')}")
        print(f"  速度系数: {config.get('speed_factor', 0):.1f}")
        print(f"  可推理: {'✅' if config.get('can_infer') else '❌'}")
        print(f"  批次大小: {config.get('batch_size', 0)}")
        print(f"  KV Cache: {config.get('kv_cache_mb', 0)} MB")
        print(f"  上下文窗口: {config.get('context_window', 0)} tokens")
        print(f"  最大推理时间: {config.get('max_inference_sec', 0):.0f}s")
        print(f"  状态: {config.get('reason', '?')}")
        print(f"{'='*50}")


# ============================================================
# UI 保活器
# ============================================================

class UIKeepAlive:
    """
    UI 保活器 — 确保推理不阻塞界面。

    策略：
    1. 推理始终在后台线程运行
    2. 渲染线程优先级最高
    3. 推理暂停时立即释放 CPU
    4. 流式输出避免 UI 卡顿
    """

    @staticmethod
    def run_inference_async(
        func: Callable,
        on_token: Optional[Callable] = None,
        on_done: Optional[Callable] = None,
        on_error: Optional[Callable] = None,
    ):
        """在后台线程运行推理，UI 保持响应"""
        def _worker():
            try:
                for token in func():
                    if on_token:
                        on_token(token)
                if on_done:
                    on_done()
            except Exception as e:
                if on_error:
                    on_error(e)

        thread = threading.Thread(target=_worker, daemon=True, name="inference")
        thread.start()
        return thread


# ============================================================
# ResourceGuard 主类
# ============================================================

class ResourceGuard:
    """
    ResourceGuard — 资源守护系统主入口。

    统一管理内存/温度/电量/UI，确保大模型推理不会卡死手机。
    """

    def __init__(self, model_mb: int = 10000):
        self.controller = AdaptiveController(model_mb)
        self.memory = self.controller.memory
        self.thermal = self.controller.thermal
        self.power = self.controller.power

    def pre_inference_check(self) -> tuple[bool, dict[str, Any]]:
        """推理前检查"""
        config = self.controller.get_inference_config()
        can_infer = config["can_infer"]
        if not can_infer:
            logger.warning(f"⛔ 暂停推理: {config['reason']}")
        return can_infer, config

    def get_status(self) -> dict[str, Any]:
        """获取完整状态"""
        return self.controller.get_inference_config()

    def print_status(self):
        self.controller.print_status()
