package com.imageshot.core.storage

import android.net.Uri
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import com.imageshot.core.model.ImportProbe

/**
 * 取り込み契約（02_実装定義書.md §5 ImageImporter）。
 * - inspect は unsupported でも例外を投げない。
 * - register は原本を一切変更せず、参照 + 選択的stagingのみ行う（ADR-009）。
 */
interface ImportRepository {
    /** ヘッダー/サイズ/対応可能性の検査。ネットワークアクセスは行わない。 */
    suspend fun inspect(uri: Uri): ImageOpResult<ImportProbe>

    /** ライブラリ登録。原本複製なし、fingerprint は sha-256（または検証済み比較）。 */
    suspend fun register(uri: Uri, policy: ImportPolicy): ImageOpResult<String>
}
