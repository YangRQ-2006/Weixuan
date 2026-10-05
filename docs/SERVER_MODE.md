# 本地推理服务器模式（Local Inference Server）

> 一句话：**把微玄变成一台跑在你自己手机上的 OpenAI 兼容推理服务器 —— 数据不出设备，局域网内的其它设备 / 其它 App 都能调用。**

微玄内置的推理引擎是自建的 **llama.cpp `llama-server`** 子进程，它本身就**原生**支持
`/v1/chat/completions`、`/v1/completions`、`/v1/models`、`/v1/embeddings`、`/health`
以及 `--api-key` 鉴权。所以「服务器模式」不需要额外写服务，只是把过去**写死**的启动参数
（`--host 127.0.0.1 --port 18787 -np 1`）变成可配置项 + 一个设置界面。

---

## 1. 定位（Why）

- **私有**：模型、提示词、对话内容全程只在这台手机上，不经过任何云端。
- **兼容**：对外提供标准 OpenAI API，任何支持「自定义 base_url」的客户端都能直接接。
- **默认安全**：不开启时，引擎行为与以往**完全一致**（只绑 `127.0.0.1`、端口 `18787`、单槽、不鉴权）。

> English: The phone itself becomes a private, OpenAI-compatible inference server backed by the
> built-in `llama-server`. Nothing leaves the device unless you opt into LAN binding.

---

## 2. 开启步骤（How）

进入 **「设置 → 本地推理服务器」这个独立的二级页**（2026-10-05 起从「本地模型」页剥离，
入口与「本地模型」「模型市场」并列；「本地模型」页内也保留一个跳转入口）：

1. 打开 **「启用本地推理服务器」** 开关（默认关闭，opt-in）。
2. **绑定范围**：
   - `仅本机 127.0.0.1`（默认，最安全）——只有本机 App 能访问；
   - `局域网 0.0.0.0` —— 同 WiFi 的设备都能访问，**会自动要求并生成一个 API Key**。
3. （可选）**监听端口**：默认 `18787`，范围 `1024–65535`。
4. （可选）**并发槽数（-np）**：默认 `1`，范围 `1–4`。个人使用请保持 `1`（见第 4 节「能力边界」与第 4.1 节「并发槽数怎么选」）。
5. （可选）**API Key**：可一键生成 / 复制。局域网模式下**必配**。
6. 若引擎正在运行，点 **「重启引擎应用设置」**（或在上方先「卸载模型」再「加载模型」）——
   `llama-server` 的监听参数在进程启动时就固定了，改设置**必须重启引擎**才生效。
7. （强烈建议）在「**保活（后台存活）**」分节点 **「关闭电池优化」**，把微玄设为不优化/无限制；
   小米 / 澎湃用户还需**手动**给微玄加自启动白名单（见 [4.3 保活与后台存活](#43-保活与后台存活2026-10-05-新增)）。

> English: Local Model page → "Local inference server" section → toggle on → choose bind scope →
> (optional) port / slots / API key → restart the engine to apply.

---

## 3. 客户端接入（Client usage）

### 3.1 base_url

| 场景 | base_url |
|------|----------|
| 本机（同一台手机上的其它 App） | `http://127.0.0.1:18787/v1` |
| 局域网（另一台设备 / 电脑） | `http://<手机IP>:18787/v1` |

> `<手机IP>`：在手机 **设置 → 关于手机 → 状态信息** 里看 IP（形如 `192.168.x.x`）。
> 也可以用客户端先扫一遍局域网，或在本机终端执行 `ip addr` 查看 `wlan0` 地址。

### 3.2 api_key

- 服务器模式**仅本机**且未设 Key 时，`api_key` 可留空。
- **局域网必须配 Key**：设置页会生成一个 32 位十六进制 Key，客户端在
  `Authorization: Bearer <key>` 里携带。
- 未携带或错误的 Key，服务端返回 `401`。

### 3.3 模型名

`llama-server` 一次只加载一个模型，模型名以服务端 `/v1/models` 返回的 `id` 为准
（通常是所加载 `.gguf` 的文件路径/基名）。**先用自检命令确认**，再把返回的 `id` 填进客户端：

```bash
# 自检：确认服务在跑、拿到可用的模型名（id）
curl -s http://127.0.0.1:18787/v1/models

# 带鉴权（局域网）时：
curl -s -H "Authorization: Bearer <key>" http://192.168.1.23:18787/v1/models

# 健康检查（无需鉴权）
curl -s http://127.0.0.1:18787/health
```

一个最小的对话调用示例：

```bash
curl http://127.0.0.1:18787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <key>" \
  -d '{
    "model": "<从 /v1/models 拿到的 id>",
    "messages": [{"role":"user","content":"你好"}],
    "stream": false
  }'
```

> English: point any OpenAI-compatible client at `http://<phone-ip>:18787/v1`, set the API key
> from the settings page, and read the model id from `GET /v1/models`.

---

## 4. 能力边界（**务必读完，别把它当服务器级服务**）

请对这台「服务器」建立正确预期：

- **它是单/少槽的个人推理服务，不是高并发后端。**
  llama.cpp 的 `-np` 是**并行槽位**，不是吞吐倍增器：`-np 4` **不等于 4 倍吞吐**。
- **单流速度参考（4B 量化模型、本机实测）**：约 **9–17 tok/s**（随上下文长度、温度、是否解码等波动）。
- **多槽并发**：多个请求同时进入不同槽时，**每个槽的速度都会明显下降**
  （共享同一份权重与算力），且 KV cache 内存按槽数**倍增**——`-np` 越大越吃内存，可能直接加载失败。
- **适合场景**：个人 / 低频调用、局域网内自己或家人偶尔用、给同机其它 App 提供私有模型接口。
- **不适合场景**：多人同时高强度调用、需要稳定 QPS 的服务器级负载、实时流式大批量生产任务。
- 首字延迟：冷启动要加载模型（数秒~数十秒）；即便已加载，长 prompt 的 prefill 也需时间。

> English: It is a single/few-slot service for personal, low-frequency use. `-np 4` is **not** 4×
> throughput; each slot slows down under concurrency and KV memory multiplies. 4B single-stream is
> roughly 9–17 tok/s. Not suitable for server-grade QPS.

---

## 4.1 并发槽数怎么选（实测）

**默认值保持 `1`（`LocalServerPrefs.DEFAULT_SLOTS = 1`），没有改动任何已有用户的行为。**
下面的实测说明只是帮你决定「要不要手动升」。

**实测现象（本机，自建 llama.cpp `llama-server`，默认 `-np 1`）：**

- 第一个客户端正常调用时，**第二个客户端会排队等待**；
- 具体到「打 `/v1/models`」这个最轻的探活请求：单槽被占满时，第二个客户端拿到的**是空返回**
  （请求进不了槽，探活拿不到模型列表）——这正是「感觉服务器像挂了」的根因，而不是服务没起来。
- 也就是说：`-np 1` 时服务是**串行**的，任何第二个并发请求都在等第一个跑完。

**升到多槽的取舍（实测）：**

| 槽数 | 现象 | 代价 |
|------|------|------|
| `1`（默认） | 单客户端正常；第二个客户端排队，探活可能空返回 | KV 内存最省 |
| `2~4` | 少数客户端可并行，不再排队 | **KV cache 内存按槽数约翻倍**；多请求并发时每槽速度明显下降；`-np 4` ≠ 4 倍吞吐 |

**怎么选：**

- **只有你一个人 / 偶尔用** → 保持 `1`，不必升级（排队对低频场景影响很小）。
- **同机多个 App、或局域网内确实有 2~3 个客户端会同时打** → 升到 `2`（够用又不至于太吃内存），
  设置页在「服务器模式开启且槽数为 1」时会给出告警并提供**「一键升到 2 槽」**。
- **别盲目上 `4`**：4B 模型在 4 槽下 KV 内存约 4 倍，容易直接加载失败，且单槽速度下降得更狠。
- 改完槽数记得点「重启引擎应用设置」（`-np` 在进程启动时固定）。

> English: Default stays `-np 1`. Measured: with a single slot, a **second client queues**, and a
> second `/v1/models` probe can return **empty** (the request never gets a slot) — which looks like
> "the server is down" but isn't. Raise to `2` only if you genuinely have concurrent clients; KV
> memory roughly doubles per extra slot and `-np 4` is not 4× throughput.

---

## 4.2 功耗与热保护（85 / 70 热断路器，2026-10-05 新增）

**为什么服务器模式需要单独的热保护：** 服务器模式和「聊天」不是一种负载。聊天是**突发**的
（一问一答、有间隙）；服务器模式是**持续**的——模型常驻内存、请求随时可能进来，甚至被同一
WiFi 的设备连续调用。长时间满载会把 SoC 顶到高温、触发系统级热节流，严重时热失控。因此服务器
模式自带一个**热断路器**：温度过高就暂停本地推理服务（卸载模型 → 立刻止热），降下来再自动恢复。

| 偏好键 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `local_server_thermal_guard` | Boolean | `true` | 热保护开关（**仅在服务器模式开启时生效**） |
| `local_server_temp_pause_c` | Int | `85` | 暂停阈值（°C）：温度**高于**它即卸载模型止热 |
| `local_server_temp_resume_c` | Int | `70` | 恢复阈值（°C）：暂停后温度**低于**它才自动重新拉起引擎 |

**行为**

- 看门狗每 **~15 秒**采一次 SoC 温度（复用现有 `/sys/class/thermal` 读取逻辑，排除 trip-point 假温度）；
- 温度 **> 85°C** → 暂停服务（`stop()` 卸载模型），日志
  `温度保护：已暂停本地推理服务（XX°C > 85°C），降至 70°C 以下自动恢复`；
- 温度 **< 70°C** → 用**上次的模型路径**自动重新加载，日志 `温度保护：已恢复本地推理服务（XX°C < 70°C）`；
- **迟滞（hysteresis）**：暂停与恢复用两个不同阈值，中间 15°C 是**死区**；
- **温度读取失败不暂停**：探测不到温度时**宁可暂不保护，也绝不因探测异常把服务停掉**（避免传感器抖动误停）；
- 状态（正常 / 已暂停因高温）与实时温度在设置页「功耗与热保护」分节可见。

**为什么必须用迟滞（而不是单阈值）**

单阈值下温度会在阈值附近抖动，导致引擎「暂停 → 恢复 → 暂停」反复开关；而每次恢复都要重新加载
**GB 级模型**（数秒~数十秒 + 峰值内存），代价极高，反复开关既伤体验也可能把系统压垮。85/70 的
15°C 死区让状态切换在时间上互相隔开，一次高温只会触发一次暂停。

**暂停时客户端会看到什么**

暂停是**主动卸载模型**（不是崩溃），所以客户端表现为：

- 正在进行的请求：失败（连接中断 / 返回错误）；
- 新的请求：**连接被拒**（`Connection refused`）或拿到 `503`（服务未就绪）；
- 恢复后（温度 < 70°C）服务会自动回来，客户端**重试即可**成功。

**什么时候该把阈值调低**

- **室温高**（夏天、无空调、被太阳直晒）时手机更容易升温，可把暂停阈值从 `85` 调到 **`80`**
  （设置页可改；记得恢复阈值也要相应下调，始终满足 `恢复阈值 < 暂停阈值` 的死区）。
- 环境很凉爽、或希望尽量不停服务，也可以把暂停阈值上调（不要超过 `120`）。
- 想在过热时**完全不暂停**：关掉「高温自动暂停（热保护）」开关——但**不建议**，服务器是持续
  负载，长期高温会加速电池老化并触发系统级降频。

> English: Server mode is a *sustained* load, so it ships a **thermal circuit breaker**: > 85°C →
> pause (unload the model to cool down), < 70°C → auto-resume with the previous model. The 15°C gap
> is deliberate hysteresis (it avoids flapping, since every resume reloads a multi-GB model).
> Temperature-read failures never pause the service. While paused, clients see connection-refused/503.
> Drop the pause threshold to `80` in a hot room. **This protection is only active while server mode
> is enabled** — with server mode off, behaviour is unchanged, byte for byte.

---

## 4.3 保活与后台存活（2026-10-05 新增）

服务器模式的价值在于「**手机随时都是一台可用的私有 AI 服务器**」，所以它最怕的不是慢，而是
**被系统在后台掐掉**：llama-server 常驻 GB 级模型，冷启动一次要数十秒；被 MIUI / 澎湃
（HyperOS）的「一键清理」或 LMK（低内存杀手）杀掉之后，用户下次调用就会直接连不上。

因此服务器模式做了一套**双轨保活**：**无 root 也能用大部分，有 root 再多几层**。

> ⚠️ **一切保活行为只在「服务器模式」开启（`local_server_enabled = true`）时生效。**
> 未开启服务器模式的用户：**不写新键、不起新服务、不持锁**，行为与旧版本逐字节一致。
> 保活也**不引入任何新的偏好键**——它完全跟随服务器模式开关。

### 无 root 可用（默认就有）

| 手段 | 作用 | 克制点 |
|------|------|--------|
| **前台服务 + 常驻通知** | 复用 App 已有的前台执行服务（`specialUse` 类型），进入保活模式后常驻前台，通知显示「**微玄推理服务器运行中 · 127.0.0.1:18787**」。前台服务是防冻结、防回收最有效的无 root 手段 | 仅在服务器模式开启且引擎就绪时启动；引擎停止即收起 |
| **克制的 PartialWakeLock** | 锁住 CPU，避免深度休眠时网络/请求处理被挂起 | **只在「服务器模式开启 + 引擎已加载」时持有**，引擎停止（卸载 / 热断路器暂停）**立即释放**；且**带 1 小时超时自动到期**，由看门狗续期——**不做无条件 24/7 持锁** |
| **轻量看门狗** | 每 **~20 秒**检查引擎是否还活着；若被系统杀掉但服务器模式仍开着 → **自动拉起** | **指数退避**重拉（5s→10s→…→最多 120s），**连续失败 5 次后停止重试并通知用户**，避免疯狂重拉耗电发热 |

**为什么 wakelock 要克制**：无条件 24/7 持 PartialWakeLock 会让 CPU 永远不休眠，**持续耗电并加剧
发热**——而发热正是本项目已知的核心痛点（见上一节的热断路器）。所以这里的策略是：**只在真正需要
它的时候（引擎在跑、服务器在服务）持锁**，引擎一停就放；并设 1 小时超时兜底，即使逻辑出岔子也会
自动解锁。换句话说，**保活优先靠前台服务与看门狗，唤醒锁只是补充**。

**暂停 / 恢复的配合**：看门狗在热断路器处于「已暂停（因高温）」时**不会**去重拉引擎——否则会与
「降温自动恢复」打架、把温度再顶上去。热断路器恢复时由它自己 `start()`，保活随后自然重新武装。

### 有 root 才多做的事（`rootAvailable` 为真时才执行，任一步失败仅记日志、绝不影响功能）

| 手段 | 作用 |
|------|------|
| 把 App 与 `llama-server` 子进程的 `oom_score_adj` 调到 **-800** | 显著降低被 LMK 优先回收的概率 |
| `dumpsys deviceidle whitelist +cn.yangrq.weixuan` | 加入 Doze / 电池优化白名单 |
| 内核 wakelock（`/sys/power/wake_lock`） | 在 App 未声明 `WAKE_LOCK` 权限时，用 root 侧持续唤醒兜底 |

> root 加固在**重启后会被系统重置**（`oom_score_adj` 会回到默认、deviceidle 白名单通常保留），
> 这属正常现象；看门狗每次重新武装时会**重新施加**。每条 root 命令都有独立日志 tag
> `LsKeepAliveRoot`，可用 `logcat -s LsKeepAliveRoot` 诊断；保活主流程日志 tag 为 `LsKeepAlive`。

### 小米 / 澎湃（HyperOS）需要**手动**设置什么

厂商的省电与自启动管理是**系统级策略，应用无法用代码自动完成**（这也是为什么必须在设置页给一段
说明而不是给自己加白名单）。建议逐项设置：

1. **省电策略**：设置 → 应用设置 → 应用管理 → 微玄 → **省电策略 → 无限制**；
2. **自启动**：同上页 → **自启动 → 允许**；
3. **最近任务加锁**：在最近任务卡片上给微玄**下拉加锁**（锁定后台），避免「一键清理」误杀；
4. **电池优化**：设置 → 电池 → **应用智能省电 → 微玄 → 无限制**（设置页里「关闭电池优化」按钮
   会直接打开系统的电池优化设置页，把微玄设为「不优化」）。

设置页「**保活（后台存活）**」分节会实时显示：前台服务 / 唤醒锁 / 看门狗是否活跃、已自动拉起几次、
root 加固是否施加，并提供「关闭电池优化」的一键跳转按钮。

> English: Server mode ships a **two-track keepalive**. Without root: a **foreground service with a
> persistent notification** ("推理服务器运行中 · 127.0.0.1:18787"), a **restrained PartialWakeLock**
> (held *only* while the server mode is on *and* the engine is loaded — released the moment the engine
> stops, and self-expiring after 1h) and a **lightweight watchdog** that auto-restarts a killed engine
> with exponential backoff (gives up after 5 consecutive failures and notifies you). With root: lowers
> `oom_score_adj` for the app and the llama-server child, adds the app to the Doze whitelist, and can
> hold a kernel wake lock. **Why the wake lock is restrained:** an unconditional 24/7 CPU lock drains
> battery and adds heat, which is this project's known pain point — so keepalive leans on the
> foreground service and the watchdog first. On Xiaomi / HyperOS you must **manually** set battery
> policy to *no restrictions*, allow **autostart**, and lock the app in Recents — those are vendor
> policies an app cannot change programmatically. **All of this is active only when server mode is on.**

---

## 5. 安全提示（Security）

- **默认仅本机**（`127.0.0.1`）：不开服务器模式、或保持「仅本机」，外部一律访问不到。
- **局域网绑定必须配 API Key**：一旦绑 `0.0.0.0`，同一个 WiFi 下**任何人都能访问你的端口**——
  不设 Key 就等于把你的手机算力、电量、模型都敞开门让人用。设置页在开启局域网时会**自动生成**一个 Key。
- API Key 等同于密码：**不要**把它贴到公开的地方；分享给他人时可随时「重新生成」。
- 端口尽量避开常见保留段；若发现异常占用，换个端口并重启引擎。
- 用完记得关掉服务器模式开关（或把绑定范围调回「仅本机」）。

> English: LAN binding without an API key lets anyone on the same Wi-Fi burn your battery and use
> your model. Keep the default (loopback only) unless you really need LAN access, and always set a key.

---

## 6. 常见问题

| 现象 | 原因 / 处理 |
|------|-------------|
| 改了端口/范围没生效 | 引擎启动参数已固定，**重启引擎**（或重新加载模型）后生效 |
| 客户端 401 | 局域网模式必须带 `Authorization: Bearer <key>`；Key 在设置页查看 |
| 其它设备连不上 | 确认绑定了「局域网 0.0.0.0」、在同 WiFi、IP 填对、防火墙未拦 |
| 返回 404 | base_url 要带 `/v1`；确认客户端打的是 `/v1/chat/completions` |
| 加载失败 / 变卡 | `-np` 调大了导致 KV 内存倍增；改回 `1` 并重启引擎 |
| 客户端突然连不上（之后又自己好了） | 可能是**热保护暂停**：SoC 温度 > 85°C 时服务会主动卸载模型止热，降到 70°C 以下自动恢复。可在设置页「功耗与热保护」看当前温度与断路器状态；室温高时可把暂停阈值调到 80 |
| 断路器一直显示「已暂停」不恢复 | 温度没降到恢复阈值以下（或温度读不到）。检查是否仍在充电 + 满载、环境是否太热；必要时重启 App。恢复阈值必须低于暂停阈值 |
| 服务隔一会儿就断了 / 锁屏后调用失败 | 后台被系统冻结或回收。到设置页「保活」分节确认**前台服务 / 看门狗活跃**，并**关闭电池优化**；小米 / 澎湃还需手动加自启动白名单 + 最近任务加锁（见 [4.3](#43-保活与后台存活2026-10-05-新增)）。看门狗会自动重拉，但厂商「无限制」省电策略只有你能设 |
| 通知栏一直显示「微玄推理服务器运行中」 | 这是**保活前台服务**的常驻通知（服务器模式开启且引擎就绪时才有）。想彻底去掉：关闭服务器模式开关，或在通知上点「停止服务器保活」 |
| 重启手机后要重新设置吗 | 不需要。偏好持久保存；`oom_score_adj` 之类的 root 加固会被系统重置属正常，看门狗重新武装时会自动再施加 |

---

## 7. 相关实现（开发者）

- 启动参数：`app/src/main/kotlin/cn/yangrq/weixuan/local/LlamaServerProcess.kt`
  （`serverMode` 分支负责读取设置并派生 `--host/--port/-np/--api-key`）。
- 偏好契约：`app/src/main/kotlin/cn/yangrq/weixuan/config/Prefs.kt` 的 `LocalServerPrefs`
  （**集中定义键名与默认值**，默认值保证与旧写死参数逐字节一致）。
- 读写实现：`app/src/main/kotlin/cn/yangrq/weixuan/local/LocalSettings.kt`
  （`localServerEnabled / localServerLan / localServerPort / localServerSlots / localServerApiKey`）。
- 设置界面：`app/src/main/kotlin/cn/yangrq/weixuan/ui/pages/local/LocalServerScreen.kt`
  （独立的「本地推理服务器」二级页；入口在设置页，`LocalModelScreen` 只保留跳转入口）。
  路由 `AppRoute.LocalServer`，标题与 entry 见 `ui/app/AgentAppShell.kt` / `ui/app/AgentAppRoot.kt`。
- 热保护看门狗：`app/src/main/kotlin/cn/yangrq/weixuan/local/LlamaServerProcess.kt`
  （`ensureThermalWatchdog` / `readSocTemperatureC` / `ThermalGuardState`；**只在
  `LocalSettings.localServerEnabled` 且热保护开关开启时才启动协程**，未开启服务器模式的用户
  不启动任何轮询任务）。读温复用
  `app/src/main/kotlin/cn/yangrq/weixuan/local/LocalResourceGuard.kt` 的 `maxTemperatureC()`。
- **保活加固**（2026-10-05）：`app/src/main/kotlin/cn/yangrq/weixuan/local/LocalServerKeepAlive.kt`
  （前台服务 + 克制 wakelock + 退避看门狗 + root 加固；**门控在 `onEngineReady` 第一行：
  `if (!LocalSettings.localServerEnabled) return`**，未开启服务器模式不写键 / 不起服务 / 不持锁）；
  前台服务复用 `app/src/main/kotlin/cn/yangrq/weixuan/agent/runtime/AgentExecutionService.kt`
  新增的「服务器保活」模式（`acquireServerKeepAlive` / `releaseServerKeepAlive`，
  **不新增任何 Service 声明**）。日志 tag：主流程 `LsKeepAlive`，root 加固 `LsKeepAliveRoot`。
  **保活不引入任何新的偏好键**——它完全跟随 `local_server_enabled`。
  > ⚠️ 已知限制：`AndroidManifest.xml` **未声明 `android.permission.WAKE_LOCK`**，因此 App 内的
  > `PartialWakeLock` 会被系统拒绝（代码里已静默降级，只记日志）。补上该权限后唤醒锁即自动生效
  > （无需改代码）；在此之前，无 root 的持续唤醒由**前台服务**承担，root 用户由内核 wakelock 承担。

| 偏好键 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `local_server_enabled` | Boolean | `false` | 服务器模式开关（opt-in） |
| `local_server_lan` | Boolean | `false` | false=仅本机 `127.0.0.1`；true=绑 `0.0.0.0` |
| `local_server_port` | Int | `18787` | 监听端口 |
| `local_server_slots` | Int | `1` | 并发槽数（`-np`），范围 1..4 |
| `local_server_api_key` | String | `""` | 非空时追加 `--api-key`；局域网为空则自动生成 |
| `local_server_thermal_guard` | Boolean | `true` | 热保护开关（**仅服务器模式开启时生效**） |
| `local_server_temp_pause_c` | Int | `85` | 暂停阈值（°C）；高于它卸载模型止热 |
| `local_server_temp_resume_c` | Int | `70` | 恢复阈值（°C）；低于它自动重新拉起引擎（须 < 暂停阈值） |
