#!/system/bin/sh
# 崩溃事后取证（**只读**）—— 数据全部来自持久化区域，重启后仍在。
# 目的：判断上次事件到底是「热关机 / 内核 panic / 子系统重启(modem) / 电源跌落 / 用户态崩溃」。
# 用法：root 下执行  sh post_crash_forensics.sh

echo "############ 0. 重启原因 ############"
for p in ro.boot.bootreason persist.sys.boot.reason sys.boot.reason ro.boot.shutdown_reason \
         ro.boot.hardware.revision persist.vendor.boot.reason; do
  v=$(getprop "$p" 2>/dev/null); [ -n "$v" ] && echo "  $p = $v"
done
cat /proc/sys/kernel/boot_reason 2>/dev/null | sed 's/^/  boot_reason=/'
dmesg 2>/dev/null | grep -aiE "shutdown|reboot|panic" | tail -5 | sed 's/^/  dmesg: /'

echo "############ 1. pstore（上次重启前的内核尾巴，最关键）############"
ls -la /sys/fs/pstore/ 2>/dev/null | sed 's/^/  /'
for f in /sys/fs/pstore/*; do
  [ -f "$f" ] || continue
  echo "  ---- $f ----"
  grep -aiE "panic|watchdog|BUG:|hardware watchdog|thermal|shutdown|subsys|restart|modem|adsp|cdsp|qpnp|brownout|undervoltage|UVLO|OCP|reset" "$f" 2>/dev/null | tail -25 | sed 's/^/    /'
done

echo "############ 2. dropbox（system_server / systemui / ANR / 看门狗）############"
ls -lat /data/system/dropbox/ 2>/dev/null | head -20 | sed 's/^/  /'
for f in $(ls -t /data/system/dropbox/ 2>/dev/null | grep -aiE "system_server|systemui|watchdog|native_crash|crash" | head -5); do
  echo "  ==== $f ===="
  head -60 "/data/system/dropbox/$f" 2>/dev/null | sed 's/^/    /'
done

echo "############ 3. tombstones（native 崩溃）############"
ls -lat /data/tombstones/ 2>/dev/null | head -8 | sed 's/^/  /'

echo "############ 4. MIUI 日志 / 电源 ############"
ls -lat /data/miuilog/ 2>/dev/null | head -8 | sed 's/^/  /'
ls -lat /data/vendor/modem_logs /data/vendor/ramdump 2>/dev/null | head -6 | sed 's/^/  /'

echo "############ 5. 基带与 SIM 现状 ############"
getprop 2>/dev/null | grep -iE "gsm\.|modem|radio\.state|vendor.radio" | head -20 | sed 's/^/  /'

echo "############ 6. 热与电压历史（若有）############"
for n in /sys/class/thermal/thermal_zone*/temp; do
  printf "  %s = %s\n" "$n" "$(cat $n 2>/dev/null)"
done | head -12
cat /sys/class/power_supply/battery/{voltage_now,current_now,temp} 2>/dev/null | tr '\n' ' ' | sed 's/^/  battery(v,uA,tenthC)=/'
echo
