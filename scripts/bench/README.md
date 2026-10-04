# 端侧推理基准：方法学与已验证结论

> 2026-10-02 建立。**先读方法学再动手** —— 这里的每一条都是用「错误结论」换来的。

## 为什么需要这个目录（血泪史）

同一件事（KV 量化、`-ub` 取值）在本项目里得出过**三次互相矛盾**的结论：

| 时期 | 协议缺陷 | 结论 |
|---|---|---|
| 2026-09-30 | 3.1k 上下文，协议未记录 | 「q8_0 KV 比 f16 慢 2.5×」 |
| 2026-10-02 上午 | 提示词让模型回 "OK" 就 EOS → **只采样到 1~2 个 token** | 「q8_0 KV 慢 1.7×」 |
| 2026-10-02 下午 | 同上（短样本），另加**变体顺序固定** | 「`-ub 1024` 快 7%」 |
| 2026-10-02 晚（修正协议） | 强制长输出 + `cache_prompt:false` + **同轮配对** | f16 与 q8_0 **只差 <2%**；跨轮绝对速度摆动 **2.3×** |
| 2026-10-02 晚（修正协议） | 同上 | `-ub` 512/1024/2048 **无差异** |

**教训：坏协议造出来的「优化收益」，比没有优化更糟** —— 你会为了一个不存在的收益去改默认参数。

---

## 四条铁律

### 1. `"cache_prompt": false` 必须带

llama-server 默认复用与上一条请求相同的前缀 KV。测量请求只要和预热请求共享前 300 个 token，
`prompt_n` 就只剩几十，而 `prompt_per_second = prompt_n / prompt_ms` ——
「prefill 速率」于是变成「几个 token 摊固定开销」的假数字。

**实测**：同一台机器同一模型，坏协议报 **155 tok/s**，好协议报 **~1000 tok/s**，差 6.4 倍。
`ab.sh` 会检查 `cache_n`，不为 0 直接告警。

### 2. 生成任务必须强制长输出

用「Reply with the single word OK」这类提示词，模型回 2 个 token 就 EOS ——
`predicted_per_second` 测的是**首 token 延迟**，噪声极大（±10% 起）。

**改用**：「count from 1 to 300, one number per line」+ `max_tokens: 400`。
`ab.sh` 会检查 `predicted_n`，小于 32 就告警。

### 3. 变体必须**同轮配对**，不能跨轮比较

这是最隐蔽的坑。实测设备会在两个状态之间跳变：

| 同轮内 | f16 | q8_0 |
|---|---|---|
| r1 | 16.45 | 16.22 |
| r2 | **7.14** | **7.25** |

同轮内两者差 <2%；跨轮却差 2.3×。**跨轮比较会把设备状态错误归因给你正在测的参数。**

`ab.sh` 按 A,B,C,A,B,C… 交替，但**真正的比较必须在同一轮内做**，跨轮只用来取中位数。

### 4. 样本数 ≥3，且要报出来

跑内方差实测 ±10%（受热漂移与 page cache 状态影响），比大多数参数调整的真实收益还大。
1 个样本的 A/B 不算结论。

---

## 已验证结论（本机 SM8850 / Adreno 840 / HTP v79，4B Q4_K_M，`--device HTP0 -t 4 -fa on -ngl 99`）

### 可信（协议干净或现象确定性）

| 项目 | 结论 | 数据 |
|---|---|---|
| **基线（快状态）** | prefill ≈ **1000 tok/s**，decode ≈ **16 tok/s** | 多轮同轮配对 |
| **基线（慢状态）** | decode ≈ **7.2 tok/s** | 同机同参数，跨轮摆动 2.3× |
| `--no-mmap`（PocketOrca 口味） | ❌ **进程直接死亡** | 2.5GB 模型 3/3 崩 |
| `-ctk/-ctv q4_0` | ❌ **进程直接死亡**，HTP 不支持 | 3/3 崩 |
| `--device none`（纯 CPU 对照） | prefill ≈ **5~9.5 tok/s**（热态偏低） | 与 NPU 差两个数量级 |
| PocketOrca 的 DSP skel | ❌ 无差异，可互换 | GenieX 17.44 vs PocketOrca 16.75（各 4 次均值） |
| GPU：Vulkan（Adreno 840） | ❌ 输出乱码 `@@@@` | prefill 4.3 tok/s |
| GPU：OpenCL | ❌ `platform IDs not available` | 无 sphal 命名空间 |

### 测不出差异（旧结论已被证伪）

| 项目 | 旧结论 | 修正后 |
|---|---|---|
| `-ctk/-ctv q8_0` | 「慢 1.7~2.5×」 | **无差异**（同轮配对 <2%） |
| `-ub 1024`（PocketOrca 口味） | 「快 7%」 | **无差异**（512/1024/2048 中位数 15.32/15.79/14.91，跑内方差内） |
| `-t 2` / `-t 8` | — | 都不如 `-t 4`（旧协议，趋势待复核） |

**结论：本机 NPU 的参数空间已基本探索完，剩下的差异都在噪声里。**
再想提速只能动架构（把仍落在 CPU 上的算子推给 HTP）或换模型，不是调参。

---

## 待办：设备状态这个混淆变量必须控制住

「~16 t/s ↔ ~7.2 t/s」的 2.3× 摆动是整个项目最大的测量障碍，来源未确证，候选：
大核调度策略 / DSP 时钟档位 / 热态 / page cache 状态。

在控制住它之前，任何 <10% 的参数收益都无法判定。**已落地工具见下节 `state_forensics.sh`。**

---

## 工具二：`state_forensics.sh` —— 查清「状态摆动」

### 为什么必须有这个

传统 A/B（每轮重启 server + 跨轮比较）会**把这个摆动错误归因给被测参数** ——
这正是「`-ub 1024` 快 7%」「q8_0 KV 慢 1.7×」三次结论互相矛盾的真凶。

所以这个脚本不测参数，而是**把"状态"本身当观察对象**：

- **只启一次 server**，在同一进程内连续测 K 轮（状态可能在一次会话中途翻转）；
- 每轮带 `cache_prompt:false`，保证每次做**同样的活**；
- 每轮同步采协变量：各 cluster 当前频率、最高温 thermal zone、MemAvailable、Cached、
  **以及 server 进程的 `read_bytes` 增量**。

### 关键判别器：`io_read_bytes` 增量

| 现象 | 结论 | 怎么修 |
|---|---|---|
| 慢档读盘量比快档**高一个量级** | page cache 被挤出、权重每 token 重读闪存（**存储带宽瓶颈**） | 守住权重页预读；控制并发负载别挤 page cache |
| 两档读盘量都≈0 | 不是存储问题 → 看频率与 thermal zone（**降频/热节流**） | CPU governor / 钉核（`--pin`）/ 散热 |

脚本结尾会自动分档并打印这个判别结论。

### 用法

```sh
sh state_forensics.sh --model /sdcard/Download/xxx.gguf --iters 10 --ctx 2048 --gen 192
sh state_forensics.sh --model ... --pin 0-3      # 测试「钉核」能否稳定状态
```

结果落在 `/data/local/tmp/fo_result.csv`（原始数据 + 协变量，可直接拿去画图）。
闸门：跑前 `MemAvailable ≥ 模型×1.2+1GB` 且电池 ≤40°C；**每轮前复检温度，超限立即中止**；
`--iters` 硬上限 20。

---

## 工具三：系统提示 KV 的跨重启复用（已进 App 代码）

**背景**：Agent 每轮带 ~8k token 的「系统提示 + 工具定义」，实测 prefill ≈1000 tok/s
→ 冷启动光重新 prefill 就要 **~8 秒**。

**关键认知（别搞错）**：llama-server **在进程存活期间本来就复用同 slot 的公共前缀 KV**。
真正缺的只有**跨进程重启**那一段（App 重载模型 / 被 MIUI 杀掉后重启）。

**⚠️ 踩过的坑：不要用 `--prompt-cache`。** 它在 `common/arg.cpp:1869` 被
`.set_examples({LLAMA_EXAMPLE_COMPLETION})` 限死为 **llama-cli 专用**，server 侧没有任何消费
它的代码（`grep prompt_cache_file tools/server` 零命中），**传给 llama-server 会因"未知参数"直接退出**。

**正确机制**：`--slot-save-path <dir>`（`common/arg.cpp:3614` 明确
`.set_examples({LLAMA_EXAMPLE_SERVER})`）+ 路由
`POST /slots/:id_slot?action=save|restore|erase&filename=<name>`（`server-context.cpp:4791-4794`）。

实现见 `local/LlamaServerProcess.kt`：

| 点 | 做法 |
|---|---|
| 缓存文件名 | 绑定**模型指纹**（路径+大小+mtime 的 hash）→ 换模型自动换文件，杜绝 A 模型 KV 恢复到 B 模型 |
| 保存时机 | `stop()` 里先 `save` 再 destroy（**best-effort**；被强杀时不会有这一步，属已知限制） |
| 恢复时机 | 置 `READY` **之前** `restore`（避免 Agent 抢先发请求占住 slot，restore 会返回 busy） |
| 失败处理 | 一律只记日志，绝不影响推理（首次运行没缓存文件 = restore 必然失败，正常） |
| 磁盘 | `filesDir/slotcache/`，启动时 `pruneStaleSlots` 删掉非当前模型的缓存 |

---

## 已被实测证伪、不要再试的（重要）

### ⚠️ KV 量化定案：**q8_0 不可用，慢 3.4×**（2026-10-04，`kv_ctx_ab.sh`）

| 日期 | 协议 | 当时结论 | 现在怎么看 |
|---|---|---|---|
| 2026-09-30 | 协议未记录 | q8_0 慢 2.5× | **方向对**（实测 3.4×） |
| 2026-10-02 上午 | 提示词让模型回 "OK" 就 EOS → 只采到 1~2 个 token | q8_0 慢 1.7× | 样本无效 |
| 2026-10-02 晚 | 强制长输出 + 同轮配对 | 「测不出差异」<2% | **❌ 错，已推翻** |
| **2026-10-04** | 同 prompt(2997 token) + `cache_prompt:false` + 组内 3 次 + 协变量 | **q8_0 慢 3.4×（6.49 → 1.93 t/s）** | ✅ **定案** |

**为什么旧结论会错**：旧测试的 prompt 短（345~2000 token）→ KV 短 → 量化代价小。
本机用 `-nkvo`（KV 留在 CPU 算注意力），**q8_0 每次注意力都要反量化，代价随 KV 长度增长**，
所以短 KV 测不出，而 3k token（真实 Agent 量级）下直接掉 70%。

**同日同时测出、同样重要**：`ctx 6144 → 12288` 只慢 **12.4%**（旧结论说的 −26% 太悲观）。
⇒ **要更多上下文只能用 f16 + 更大 ctx**（代价转移到内存：f16 @8192=1152MB、@12288=1728MB），
**不能用 KV 量化换**（省一半内存赔 70% 速度）。

| 方案 | 实测结论 |
|---|---|
| `--no-mmap`（PocketOrca 口味） | 2.5GB 模型 **3/3 进程必崩** |
| `-ctk/-ctv q4_0` | **3/3 进程必崩**，HTP 无该算子 |
| `-ub 1024`（PocketOrca 口味） | **无收益**（512/1024/2048 中位数 15.32/15.79/14.91） |
| KV q8_0 vs f16 | ❌ **旧结论「测不出差异（同轮配对 <2%）」已被 2026-10-04 实测推翻** → 见下方定案 |
| PocketOrca 的 DSP skel | 无差异，可互换 |
| **推测解码（draft 0.6B）** | **无收益**：decode 11.0 vs 12.0，**prefill 腰斩**（107.8 vs 254，draft 抢 NPU 算力），多占 380MB |
| hybrid（NPU+GPU+CPU） | 比纯 NPU 慢 2~3 倍（跨设备张量拷贝） |
| MoE 专家流式 | 139GB 读取 / 0 token（长 prompt 触发全专家扫描） |

---

## 用法

```sh
sh scripts/bench/ab.sh --list       # 列出现有安装的 nativeLibraryDir

sh scripts/bench/ab.sh \
  --model /sdcard/Download/xxx.gguf \
  --dev HTP0 --rounds 3 --ctx 2048 --gen 400 \
  --variant "基线" \
  --variant "+ub1024:-ub 1024" \
  --variant "q8_0KV:-ctk q8_0 -ctv q8_0"
```

`--variant "<标签>:<追加参数>"`，冒号后可写任意 llama-server 参数。

**必须在 Android 宿主上跑**（PRoot 沙箱里没有 `/system/bin/linker64`）：

```sh
/opt/taixu/bin/taixu-host shell "setsid nohup sh /sdcard/Download/ab.sh ... >/data/local/tmp/out 2>&1 &"
```

### ⚠️ 安全边界（2026-09-25 事故 + **2026-10-02 失联事故**后立的铁律，压测同样适用）

**2026-10-02 事故经过**：跑完一轮基准（6~9 次 2.5GB 模型加载）后，**手机移动网络失联**，
显示「仅可拨打紧急电话」，重启后恢复。当时 CPU0 **99.7°C**、电池 **51.8°C**；
本机 MemTotal 15.37GB 但 **MemAvailable 只有 5.83GB**（日常使用更低）。

**为什么内存比温度更可能是真凶**：脚本每轮 `kill` 完服务器只 `sleep 4` 就起下一轮，
上一轮的 page cache / DMA 缓冲根本来不及回收，于是反复 2.5GB 加载把 MemAvailable 反复打到底。
候选机制（均未证实，但都由压测负载触发）：
1. 内存骤降 → lmkd 连带杀掉电话栈进程；
2. Qualcomm **ION/dmabuf/DSP carveout** 被反复开合的 HTP 会话耗尽或碎片化（modem/RF 共用同一批共享内存池）；
3. 极端发热导致基带进入异常态。

**铁律（逐条照做）**：

| # | 规则 |
|---|---|
| ① | **串行执行**，严禁并发或快速接续加载；每轮之间必须确认 `MemAvailable` 回到基线（≥1GB 余量），不足则等 |
| ② | **单次会话 ≤ 3 轮**；轮间间隔 **≥30s**，且先确认上一轮 `libllama-server` 已退出 |
| ③ | 跑前记录 `MemAvailable` 与电池温度；**电池 >40°C 立即停止** |
| ④ | 跑完**健康检查三件套**：`libllama-server` 进程清零、`gradle --stop`、**确认移动网络/电话栈正常** |
| ⑤ | 出现失联/无服务：先冷却 → 飞行模式 → 仍不行则**重启**（2026-10-02 实测重启即恢复） |
| ⑥ | 出问题先取证：`/data/system/dropbox`（**跨重启保留**）+ `dumpsys telephony.registry`，再下结论 |

**已知踩过的坑**：`pkill -f '<pattern>'` 会匹配到自己这条 `su -c` 的命令行并**把自己的 shell 杀掉**
（2026-10-02 实测，报 `proot info: vpid 1: terminated with signal 15`）。要用 `pkill -f 'pat[t]ern'`
这类括号技巧，或先 `ps` 取 PID 再按 PID 杀。

**⚠️ 温度闸门怎么读（2026-10-03 修正）**：不要只看 `dumpsys battery` 的 temperature——
它滞后于 SoC，且日常 39~42°C 很常见，单看它会误拦正常操作。请用**双条件 + thermalservice**：
```sh
dumpsys battery | grep -iE 'AC powered|USB powered|status|temperature'   # status:3=放电
dumpsys thermalservice | grep 'Temperature{'                              # CPUx / GPUx / battery 分区温度
```
停手线：**任意 CPU zone > 85°C** 或 **电池 > 42°C**。
（事故当时：CPU0 99.7°C + 电池 51.8°C；2026-10-03 正常态：CPU 62~65°C + 电池 39.9~42.2°C。）
另：**不要**用别的 App 打印的日志行当系统状态——必须读上面的权威源。


### 诊断速查（无服务时）

```sh
dumpsys telephony.registry | grep -m1 mServiceState      # OUT_OF_SERVICE? mIsEmergencyOnly?
logcat -d -b radio -t 300 | grep -a REGISTRATION_STATE   # regState / reasonForDenial
getprop gsm.sim.state; getprop gsm.sim.operator.numeric  # SIM 是否加载、是哪家运营商
settings get global airplane_mode_on; settings get global mobile_data1
```
判读要点：`NOT_REG_MT_SEARCHING_OP` + `reasonForDenial=0` = **搜网中未注册、未被拒绝**
（不是欠费/换机锁定）；若驻留 PLMN 与 SIM 的 `sim.operator.numeric` 不一致，说明停在了别家网上。

