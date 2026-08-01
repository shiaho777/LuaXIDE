package dev.luaxide.ui.shell

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.luaxide.ui.S

object OnboardingPrefs {
    private const val PREFS = "luaxide_onboarding"
    private const val KEY = "onboard_v1_done"

    fun isDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markDone(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .apply()
    }
}

@Composable
fun OnboardingDialog(
    onNewProject: () -> Unit,
    onOpenExampleAndRun: () -> Unit,
    onOpenBuild: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val steps = listOf(
        Triple(S.ONBOARD_STEP1_TITLE, S.ONBOARD_STEP1_BODY, S.ONBOARD_STEP1_CTA),
        Triple(S.ONBOARD_STEP2_TITLE, S.ONBOARD_STEP2_BODY, S.ONBOARD_STEP2_CTA),
        Triple(S.ONBOARD_STEP3_TITLE, S.ONBOARD_STEP3_BODY, S.ONBOARD_STEP3_CTA),
    )
    val current = steps[step]

    fun finish() {
        OnboardingPrefs.markDone(ctx)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { finish() },
        title = { Text(S.ONBOARD_TITLE) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${step + 1} / ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = current.first,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = current.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (step) {
                        0 -> onNewProject()
                        1 -> onOpenExampleAndRun()
                        else -> onOpenBuild()
                    }
                    if (step >= steps.lastIndex) finish() else step += 1
                },
            ) {
                Text(if (step >= steps.lastIndex) S.ONBOARD_DONE else current.third)
            }
        },
        dismissButton = {
            TextButton(onClick = { finish() }) {
                Text(S.ONBOARD_SKIP)
            }
        },
    )
}
