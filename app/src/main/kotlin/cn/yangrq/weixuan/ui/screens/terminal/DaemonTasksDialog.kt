package cn.yangrq.weixuan.ui.screens.terminal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yangrq.weixuan.R
import cn.yangrq.weixuan.agent.terminal.TerminalEnvironment
import cn.yangrq.weixuan.ui.app.DaemonTaskUi
import cn.yangrq.weixuan.ui.components.EtaTextButton
import cn.yangrq.weixuan.ui.components.EtaWindowDialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 守护任务列表面板；终端块视图与控制台视图共用。 */
@Composable
internal fun DaemonTasksDialog(
    tasks: List<DaemonTaskUi>,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.terminal_daemon_tasks),
        onDismissRequest = onDismiss,
    ) {
        if (tasks.isEmpty()) {
            Text(
                text = stringResource(R.string.terminal_daemon_empty),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = tasks, key = { it.id }) { task ->
                    DaemonTaskRow(task = task, onStop = onStop, onLoadLogs = onLoadLogs)
                }
            }
        }
    }
}

@Composable
private fun DaemonTaskRow(
    task: DaemonTaskUi,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    var logsExpanded by remember(task.id) { mutableStateOf(false) }
    var logs by remember(task.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = task.command,
            style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = daemonMeta(task),
                style = MiuixTheme.textStyles.footnote2,
                color = if (task.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            EtaTextButton(
                text = stringResource(
                    if (logsExpanded) R.string.terminal_daemon_hide_logs else R.string.terminal_daemon_view_logs
                ),
                onClick = {
                    logsExpanded = !logsExpanded
                    if (logsExpanded && logs == null) {
                        scope.launch { logs = onLoadLogs(task.id) }
                    }
                },
            )
            EtaTextButton(
                text = stringResource(R.string.terminal_stop),
                onClick = { onStop(task.id) },
            )
        }
        if (logsExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                if (logs == null) {
                    InfiniteProgressIndicator(size = 14.dp)
                } else {
                    Text(
                        text = logs.orEmpty(),
                        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun daemonMeta(task: DaemonTaskUi): String {
    val environmentLabel = when (task.environment) {
        TerminalEnvironment.ANDROID -> "Android"
        TerminalEnvironment.ALPINE -> "Alpine"
        TerminalEnvironment.DEBIAN -> "Debian"
    }
    val stateLabel = stringResource(
        if (task.running) R.string.terminal_daemon_running else R.string.terminal_daemon_exited
    )
    return "$environmentLabel · ${task.identity} · $stateLabel"
}
