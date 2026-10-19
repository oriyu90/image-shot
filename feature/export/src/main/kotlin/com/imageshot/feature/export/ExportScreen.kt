package com.imageshot.feature.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.ExportProgress

/**
 * 書き出し画面（M1）。
 * - 出力先は SAF ACTION_CREATE_DOCUMENT のみ。OS は常に新しい文書を作成するため既存の
 *   上書きは起こらない（§8.2「上書き選択を別の明示承認にする」。M1 では上書き承認経路未提供）。
 *   MediaStore 書き出し時の実名衝突回避（一意名生成）は Worker 側 ExportNaming が担う。
 * - 実処理は WorkManager（ADR-010）。QUEUED 登録後は §8.2 二段階コミット
 *   （QUEUED → PREPARING → RENDERING → ENCODING → VERIFYING → PUBLISHING → COMPLETE）。
 *   部分出力は COMPLETE にしない（T-010）。
 * - 未対応の項目は ADR-014 に従い画面でも明示する。
 */
@Composable
fun ExportScreen(
    jobs: List<ExportProgress>,
    settings: ExportViewModel.ExportSettingsUi,
    onFormatSelected: (ExportFormat) -> Unit,
    onQualityChanged: (Int) -> Unit,
    onDestinationPicked: (String) -> Unit,
    onEnqueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text("書き出し", style = MaterialTheme.typography.titleLarge)
        Text(
            // 旧文言「WorkManager 連携は Sprint C」は実働済みのため撤去。現状を正確に。
            "ジョブの実処理は WorkManager（ADR-010）。登録後は QUEUED → PREPARING → " +
                "RENDERING → ENCODING → VERIFYING → PUBLISHING → COMPLETE の二段階コミットで進行し、" +
                "部分出力は完了扱いされません。",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "※ 未対応（ADR-014）: EXIF pass-through 未実装（入力 EXIF は書き込まない。出力 ICC は " +
                "platform エンコーダが付与: 2026-10-19 実機実測）。RAW/TIFF 書き出しは非対応。" +
                "ジョブ設定正本はキャッシュ領域の request ファイルのため、キャッシュ消去で" +
                "FAILED_PERMANENT になります（再設定が必要）。",
            style = MaterialTheme.typography.labelSmall,
        )

        TargetDocumentRow(settings.document, settings.documentError)

        FormatSelector(
            format = settings.format,
            onFormatSelected = onFormatSelected,
        )

        QualitySection(
            format = settings.format,
            quality = settings.quality,
            onQualityChanged = onQualityChanged,
        )

        DestinationSection(
            mime = settings.format.mime,
            suggestedName = suggestedName(settings),
            destinationUri = settings.destinationUri,
            onPicked = onDestinationPicked,
        )

        Row(Modifier.padding(vertical = 8.dp)) {
            Button(
                onClick = onEnqueue,
                enabled = settings.destinationUri != null &&
                    settings.document != null &&
                    !settings.enqueuing,
            ) {
                Text(if (settings.enqueuing) "登録中…" else "書き出しを開始")
            }
        }

        JobsSection(jobs)
    }
}

/** 対象文書の表示（documentId ≠ assetId: EditStore が assetId から正本文書を解決する）。 */
@Composable
private fun TargetDocumentRow(document: ExportViewModel.DocumentInfo?, error: String?) {
    when {
        error != null -> {
            Text("編集文書の読み込みに失敗", style = MaterialTheme.typography.bodySmall)
            Text(error, style = MaterialTheme.typography.labelSmall)
        }
        document == null -> Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
        else -> {
            val summary = buildString {
                append("文書 ")
                append(document.documentId.take(8))
                append("… rev")
                append(document.recipeRevision)
                append(" / スライダー変更 ")
                append(document.nonDefaultSliderCount)
                append("件")
                if (document.hasCurve) append(" / トーンカーブあり")
            }
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FormatSelector(
    format: ExportFormat,
    onFormatSelected: (ExportFormat) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("形式", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportFormat.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == format,
                    onClick = { onFormatSelected(candidate) },
                    label = { Text(candidate.name) },
                )
            }
        }
    }
}

@Composable
private fun QualitySection(
    format: ExportFormat,
    quality: Int,
    onQualityChanged: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        if (format === ExportFormat.PNG) {
            Text("画質", style = MaterialTheme.typography.labelLarge)
            Text("PNG（可逆）では画質設定は N/A", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("画質: $quality", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = quality.toFloat(),
                onValueChange = { onQualityChanged(it.toInt()) },
                valueRange = ExportFormatValidator.MIN_QUALITY.toFloat()..ExportFormatValidator.MAX_QUALITY.toFloat(),
                steps = ExportFormatValidator.MAX_QUALITY - ExportFormatValidator.MIN_QUALITY - 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 出力先選択（SAF ACTION_CREATE_DOCUMENT）。
 * 提案名は `${元ファイル名(去拡張子)_rev<改訂番号>`.拡張子。OS が新規文書のみ作成する
 * （既存名を提案して上書きに進まない仕様により §8.2「上書き選択を別の明示承認にする」）。
 */
@Composable
private fun DestinationSection(
    mime: String,
    suggestedName: String,
    destinationUri: String?,
    onPicked: (String) -> Unit,
) {
    // CreateDocument の mime は contract 生成時に固定されるため、形式変更で launcher を組み直す。
    key(mime) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(mime),
            onResult = { uri -> uri?.toString()?.let(onPicked) },
        )
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                text = when (val shown = destinationUri?.let { Uri.parse(it).lastPathSegment }) {
                    null -> "出力先: 未選択"
                    else -> "出力先: $shown"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { launcher.launch(suggestedName) }) {
                Text("出力先を選択…")
            }
        }
    }
}

@Composable
private fun JobsSection(jobs: List<ExportProgress>) {
    Column(Modifier.padding(top = 4.dp)) {
        Text("ジョブ一覧", style = MaterialTheme.typography.labelLarge)
        if (jobs.isEmpty()) {
            Text("ジョブはありません", style = MaterialTheme.typography.bodySmall)
        }
        if (jobs.isNotEmpty()) {
            // 縦スクロール可能な親 Column 内のため LazyColumn は不可（無限高さ制約違反）。
            // ジョブ数は M1 ライブラリ規模で小さいため通常 Column 展開とする（M2: ウィンドウ分割で再検討）。
            jobs.forEach { job ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        job.jobId.take(8) + "…  " + job.state.name,
                        style = MaterialTheme.typography.bodySmall,
                    )
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

/** SAF 提案名（選択はユーザー側で上書き可否を含め最終決定・OS は新規文書のみ作成）。 */
private fun suggestedName(settings: ExportViewModel.ExportSettingsUi): String {
    val doc = settings.document
    val base = ExportNaming.sanitizeBaseName(
        doc?.sourceAsset?.displayName?.substringBeforeLast('.') ?: "photo",
    )
    return "${base}_rev${doc?.recipeRevision ?: 0L}.${settings.format.fileExtension}"
}
