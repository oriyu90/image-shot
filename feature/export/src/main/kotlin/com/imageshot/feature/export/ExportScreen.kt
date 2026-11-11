package com.imageshot.feature.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ExportProgress
import com.imageshot.core.ui.theme.spacing
import com.imageshot.feature.export.R as ExportR

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
    val spacing = MaterialTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            // 広い画面では「設定」と「ジョブ」を左右に分け、行長を読み物幅に制限する。
            // 高さも要する: 横画面スマホ（914×411dp）で左右に分けると設定カードが縦に押し出され、
            // 主操作「書き出しを開始」が折りたたみ下に隠れる。縦短い画面は 1 カラム+スクロールに戻す。
            val sideBySide = maxWidth >= EXPANDED_MIN_WIDTH && maxHeight >= EXPANDED_MIN_HEIGHT
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Column(modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) {
                Text(
                    text = stringResource(ExportR.string.export_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(spacing.xs))
                TargetDocumentRow(settings.document, settings.documentError)
                Spacer(Modifier.size(spacing.lg))

                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        ExportSettingsCard(
                            settings = settings,
                            onFormatSelected = onFormatSelected,
                            onQualityChanged = onQualityChanged,
                            onDestinationPicked = onDestinationPicked,
                            onEnqueue = onEnqueue,
                            modifier = Modifier.weight(1f),
                        )
                        JobsSection(jobs = jobs, modifier = Modifier.weight(1f))
                    }
                } else {
                    ExportSettingsCard(
                        settings = settings,
                        onFormatSelected = onFormatSelected,
                        onQualityChanged = onQualityChanged,
                        onDestinationPicked = onDestinationPicked,
                        onEnqueue = onEnqueue,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(spacing.xl))
                    JobsSection(jobs = jobs, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.size(spacing.xxl))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.size(spacing.md))
                Text(
                    text = stringResource(ExportR.string.export_pipeline_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = MaterialTheme.spacing.readingMaxWidth),
                )
                Spacer(Modifier.size(spacing.xs))
                Text(
                    text = stringResource(ExportR.string.export_support_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
        }
    }
}

/** 出力設定（形式・品質・保存先・実行）。1 枚の面にまとめ、操作順を上から下へ揃える。 */
@Composable
private fun ExportSettingsCard(
    settings: ExportViewModel.ExportSettingsUi,
    onFormatSelected: (ExportFormat) -> Unit,
    onQualityChanged: (Int) -> Unit,
    onDestinationPicked: (String) -> Unit,
    onEnqueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        FormatSelector(format = settings.format, onFormatSelected = onFormatSelected)
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
        Button(
            onClick = onEnqueue,
            enabled = settings.destinationUri != null &&
                settings.document != null &&
                !settings.enqueuing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (settings.enqueuing) ExportR.string.export_enqueuing else ExportR.string.export_button_enqueue,
                ),
            )
        }
    }
}

/** 対象文書の表示（documentId ≠ assetId: EditStore が assetId から正本文書を解決する）。 */
@Composable
private fun TargetDocumentRow(document: ExportViewModel.DocumentInfo?, error: String?) {
    when {
        error != null -> {
            Text(stringResource(ExportR.string.export_document_error_head), style = MaterialTheme.typography.bodySmall)
            Text(error, style = MaterialTheme.typography.labelSmall)
        }
        document == null -> Text(stringResource(ExportR.string.export_document_loading), style = MaterialTheme.typography.bodySmall)
        else -> {
            // 文言は res（export_document_summary）。連結部（hasCurve 抜けなし）も res 参照のみ。
            val summary = stringResource(
                ExportR.string.export_document_summary,
                document.documentId.take(8),
                document.recipeRevision,
                document.nonDefaultSliderCount,
                if (document.hasCurve) stringResource(ExportR.string.export_document_has_curve) else "",
            )
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
        Text(stringResource(ExportR.string.export_format), style = MaterialTheme.typography.labelLarge)
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
            Text(stringResource(ExportR.string.export_quality), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(ExportR.string.export_quality_png_note), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(ExportR.string.export_quality_value, quality), style = MaterialTheme.typography.labelLarge)
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
                text = stringResource(
                    if (destinationUri == null) ExportR.string.export_destination_none else ExportR.string.export_destination,
                    destinationUri?.let { Uri.parse(it).lastPathSegment } ?: "",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { launcher.launch(suggestedName) }) {
                Text(stringResource(ExportR.string.export_destination_pick))
            }
        }
    }
}

@Composable
private fun JobsSection(jobs: List<ExportProgress>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(ExportR.string.export_jobs_title), style = MaterialTheme.typography.labelLarge)
        if (jobs.isEmpty()) {
            Text(stringResource(ExportR.string.export_jobs_empty), style = MaterialTheme.typography.bodySmall)
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
                    job.errorCode?.let { code ->
                        Text(
                            text = stringResource(ExportR.string.export_job_error, exportErrorReason(code)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.padding(2.dp))
                }
            }
        }
    }
}

/**
 * G2（PR-30）: 書き出し失敗理由の表示文言。URI 失効は ExportWorker が
 * `availability != ok` を弾く経路（§8.1）なので、原因と復旧導線を日本語で明示する。
 * 未対応コードは列挙名のまま出す（失敗を黙って握り潰さない・ADR-014）。
 */
@Composable
private fun exportErrorReason(code: ErrorCode): String = when (code) {
    ErrorCode.URI_REVOKED -> stringResource(ExportR.string.export_error_uri_revoked)
    else -> code.name
}

/** SAF 提案名（選択はユーザー側で上書き可否を含め最終決定・OS は新規文書のみ作成）。 */
private fun suggestedName(settings: ExportViewModel.ExportSettingsUi): String {
    val doc = settings.document
    val base = ExportNaming.sanitizeBaseName(
        doc?.sourceAsset?.displayName?.substringBeforeLast('.') ?: "photo",
    )
    return "${base}_rev${doc?.recipeRevision ?: 0L}.${settings.format.fileExtension}"
}

/** 設定とジョブを左右に並べる最小幅（material3 expanded 相当）。 */
private val EXPANDED_MIN_WIDTH = 840.dp

/** 広い画面でも内容が間延びしない上限幅（DeX / タブレット横向き）。 */
private val CONTENT_MAX_WIDTH = 1080.dp

/** 設定/ジョブを左右に並べるのに必要な最小高さ。横画面スマホ（411dp）は 1 カラムに戻す。 */
private val EXPANDED_MIN_HEIGHT = 600.dp
