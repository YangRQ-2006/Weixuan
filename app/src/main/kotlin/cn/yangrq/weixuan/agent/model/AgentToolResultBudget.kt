package cn.yangrq.weixuan.agent.model

import android.content.Context
import java.io.File

/**
 * 工具结果进入对话前的**字符预算 + 全文落盘**（2026-10-05 新增，借鉴 OkHuman 的做法）。
 *
 * ## 为什么需要
 * 手机端的上下文窗口只有 6144 token，而**工具 schema 本身已占约 4800 token**
 * （实测：57 个工具 = 19426 字节；`scripts/bench/agent_tools_full.json`）。
 * 留给「历史 + 工具结果」的空间只剩一千多 token。
 *
 * 而工具结果此前**没有任何长度上限**：`terminal`/`run_command` 执行一次 `dumpsys`、
 * 读一个日志文件、或 `observe_screen` 抓一棵深 UI 树，都可以产生上万字符。
 * 这些内容会原样写进下一轮的 prompt，后果是连锁的：
 * 1. 下一轮 prefill 变长（HTP 实测约 570~850 token/s）；
 * 2. decode 变慢（decode 随上下文线性下降：每 1000 token −1.4 t/s）；
 * 3. 持续满载 → 机身发热（实测电池 47~49°C、系统界面重载）；
 * 4. 极端情况直接撞满 ctx，触发上下文溢出。
 *
 * ## 处理策略（关键：**信息不丢**）
 * 保留头部（通常含关键结论）与尾部（通常含汇总/报错），中间截断，并**把完整结果写入文件**，
 * 在截断处给出**文件绝对路径**，提示模型用终端/文件工具按需取回（`cat` / `grep` / `tail`）。
 * 这样既压住了 prompt，又不会像"纯截断"那样把信息永久丢掉 —— 参考项目 OkHuman 正是这个做法
 * （其 bash 工具描述原文：输出超长被截断时全文已写入 log 文件、截断处会给出路径）。
 *
 * 落盘失败（异常/无权限/未配置目录）时自动退化为纯截断，并提示模型改用更精确的查询。
 *
 * ## 调参
 * [MAX_CHARS] 是唯一旋钮。2000 字符在中文结果下约 1000~2000 token、英文/JSON 下约 500 token。
 * 若将来把 ctx 调大或把工具集缩小，可以相应放宽。
 */
internal object AgentToolResultBudget {

    /** 工具结果进入对话前的字符上限。 */
    const val MAX_CHARS = 2_000

    private const val HEAD_CHARS = 1_500
    private const val TAIL_CHARS = 300

    /** 落盘目录最多保留的份数（防止长期使用堆积）。 */
    private const val SPILL_KEEP_FILES = 20

    /** 单个落盘文件的写入上限（防止一次超大 dump 把磁盘写爆）。 */
    private const val SPILL_MAX_CHARS = 1_000_000

    @Volatile
    private var spillDir: File? = null

    /** 由 App 启动时调用（与 `Prefs.initLocal(this)` 同处），配置全文落盘目录。 */
    fun configure(context: Context) {
        spillDir = runCatching {
            File(context.applicationContext.filesDir, "tool-results").apply { mkdirs() }
        }.getOrNull()
    }

    /** 仅供测试/诊断：查询当前落盘目录。 */
    fun spillDirectory(): File? = spillDir

    fun truncate(content: String, label: String? = null): String {
        if (content.length <= MAX_CHARS) return content

        val head = content.take(HEAD_CHARS)
        val tail = content.takeLast(TAIL_CHARS)
        val omitted = content.length - head.length - tail.length
        val path = spill(content, label)

        return buildString(content.length + 512) {
            append(head)
            append("\n…[结果过长：原 ")
            append(content.length)
            append(" 字符，此处省略中间 ")
            append(omitted)
            append(" 字符。")
            if (path != null) {
                append("全文已保存到 ")
                append(path)
                append("，需要细节时用终端或文件工具读取（例如 cat / grep / tail / sed -n），不要重复执行同样的查询。]")
            } else {
                append("如需完整内容，请改用更精确的命令或参数（限制行数、字段或范围）分批获取，不要重复执行同样的查询。]")
            }
            append('\n')
            append(tail)
        }
    }

    /** 把完整结果写入落盘目录，返回绝对路径；失败返回 null。 */
    private fun spill(content: String, label: String?): String? {
        val dir = spillDir ?: return null
        return runCatching {
            val safeLabel = (label ?: "result").replace(Regex("[^A-Za-z0-9_.-]"), "_").take(24)
            val file = File(dir, "tool-$safeLabel-${System.currentTimeMillis()}.txt")
            file.writeText(if (content.length > SPILL_MAX_CHARS) content.take(SPILL_MAX_CHARS) else content)
            prune(dir)
            file.absolutePath
        }.getOrNull()
    }

    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles() ?: return
            if (files.size <= SPILL_KEEP_FILES) return
            files.sortedByDescending { it.name }.drop(SPILL_KEEP_FILES).forEach { it.delete() }
        }
    }
}
