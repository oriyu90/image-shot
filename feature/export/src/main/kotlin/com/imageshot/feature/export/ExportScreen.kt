package com.imageshot.feature.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.ExportProgress

/**
 * 書き出し画面（初期骨格）。
 * - 未完了ジョブは QUEUED のまま明示表示。RENDERING 以降は未実装であることを偽らない（ADR-014）。
 */
@Composable
fun ExportScreen(
    jobs: List<ExportProgress>,
    onEnqueueDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Text("書き出し", style = MaterialTheme.typography.titleLarge)
        Text(
            "WorkManager 連携は Sprint C。現状はキュー登録と観測のみ。",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(Modifier.padding(vertical = 8.dp)) {
            Button(onClick = onEnqueueDemo) { Text("キューに登録（デモ）") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(jobs, key = { it.jobId }) { job ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(job.jobId.take(8) + "…  " + job.state.name, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    job.errorCode?.let {
                        Text("エラー: $it", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.padding(2.dp))
                }
            }
        }
    }
}
