package com.sbro.emucorec.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.core.Ps3CorePerformancePresets
import com.sbro.emucorec.core.Ps3CorePerformancePresets.Preset
import com.sbro.emucorec.ui.common.SectionCard
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** KyotoByte Edition: "Accurate" (original defaults) vs "Performance" one-tap preset. */
@Composable
fun PerformancePresetSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Preset?>(null) }
    var busy by remember { mutableStateOf(false) }
    val appliedMsg = stringResource(R.string.kb_preset_applied)
    val failedMsg = stringResource(R.string.kb_preset_partial)

    LaunchedEffect(Unit) {
        current = withContext(Dispatchers.IO) { Ps3CorePerformancePresets.current(context) }
    }

    fun choose(preset: Preset) {
        if (busy || preset == current) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Ps3CorePerformancePresets.apply(context, preset) }
            current = preset
            busy = false
            Toast.makeText(context, if (ok) appliedMsg else failedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    SectionCard(
        title = stringResource(R.string.kb_preset_title),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.kb_preset_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    shape = neonChipShape(),
                    selected = current == Preset.ACCURATE,
                    enabled = !busy,
                    onClick = { choose(Preset.ACCURATE) },
                    label = { Text(stringResource(R.string.kb_preset_accurate)) },
                )
                FilterChip(
                    shape = neonChipShape(),
                    selected = current == Preset.PERFORMANCE,
                    enabled = !busy,
                    onClick = { choose(Preset.PERFORMANCE) },
                    label = { Text(stringResource(R.string.kb_preset_performance)) },
                )
            }
            Text(
                text = stringResource(
                    if (current == Preset.PERFORMANCE) R.string.kb_preset_performance_help
                    else R.string.kb_preset_accurate_help
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
