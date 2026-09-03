package com.debdroid.app.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.debdroid.app.R
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.rootfs.RootfsInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 安装向导（FR-W1~W4）：旋涡 logo → 步骤指示 → 进度卡 → 主按钮。
 * 状态覆盖：安装中（进度）/ 失败（错误卡 + 重试）/ 完成（进入终端）。
 */
@Composable
fun WizardScreen(
    settings: AppSettings,
    install: suspend (AppSettings, (RootfsInstaller.Progress) -> Unit) -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var progress by remember { mutableFloatStateOf(0f) }
    val installingLabel = stringResource(R.string.wizard_installing)
    var stage by remember { mutableStateOf(installingLabel) }
    var error by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme

    fun runInstall() {
        scope.launch {
            phase = Phase.INSTALLING
            error = null
            runCatching {
                install(settings) { p ->
                    progress = p.fraction
                    stage = p.stage
                }
            }.onFailure { e ->
                error = e.message ?: "未知错误"
                phase = Phase.FAILED
                return@launch
            }
            phase = Phase.DONE
            onFinished()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(scheme.surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 旋涡 logo（蓝→绿渐变，签名元素）
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            for ((i, r) in listOf(48f, 36f, 26f, 16f, 6f).withIndex()) {
                Box(
                    modifier = Modifier
                        .size((r * 2).dp)
                        .background(
                            color = if (i % 2 == 0) scheme.primary else scheme.secondary,
                            shape = CircleShape,
                        )
                        .rotate(i * 45f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "DebDroid",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(16.dp))

        // 步骤指示：3 点
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true, phase != Phase.IDLE, phase == Phase.DONE).forEach { done ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (done) scheme.secondary else scheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        when (phase) {
            Phase.IDLE -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stage, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            Phase.INSTALLING -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stage, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Phase.FAILED -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "⚠ ${stringResource(R.string.wizard_error_title)}",
                        color = scheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error ?: "未知错误",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Phase.DONE -> Unit
        }

        Spacer(Modifier.height(20.dp))

        when (phase) {
            Phase.IDLE -> Button(
                onClick = { scope.launch { runCatching { runInstall() } } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.wizard_install)) }

            Phase.FAILED -> OutlinedButton(
                onClick = { phase = Phase.IDLE },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.wizard_retry)) }

            else -> Unit
        }
    }
}

private enum class Phase { IDLE, INSTALLING, FAILED, DONE }
