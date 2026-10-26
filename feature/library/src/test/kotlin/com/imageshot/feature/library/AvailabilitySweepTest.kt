package com.imageshot.feature.library

import com.imageshot.core.model.Asset
import com.imageshot.core.storage.UriAccessibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2（PR-30）: 表示時 URI 失効検査の方針を JVM で検証する。
 *
 * ContentResolver / Room / provider には触れない（実機・androidTest の領域）。
 * ここで固定するのは「どの資産を検査するか」「どの結果を DB へ書き戻すか」の判断だけ。
 */
class AvailabilitySweepTest {

    private fun entry(id: String, availability: String) = AvailabilitySweep.Entry(
        id = id,
        sourceUri = "content://test/$id",
        availability = availability,
    )

    @Test
    fun `表示時検査はokの資産だけを対象にし失効済みは除外する`() {
        val plan = AvailabilitySweep.plan(
            listOf(
                entry("a", Asset.AVAILABILITY_OK),
                entry("b", Asset.AVAILABILITY_REVOKED),
                entry("c", Asset.AVAILABILITY_DISCONNECTED),
                entry("d", Asset.AVAILABILITY_OK),
            ),
            includeUnavailable = false,
        )

        assertEquals(listOf("a", "d"), plan.targets.map { it.id })
        assertEquals(listOf("b", "c"), plan.skipped.map { it.id })
    }

    @Test
    fun `明示的全走査は失効済みも対象に含める`() {
        val plan = AvailabilitySweep.plan(
            listOf(
                entry("a", Asset.AVAILABILITY_OK),
                entry("b", Asset.AVAILABILITY_REVOKED),
                entry("c", Asset.AVAILABILITY_DISCONNECTED),
            ),
            includeUnavailable = true,
        )

        assertEquals(listOf("a", "b", "c"), plan.targets.map { it.id })
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `okの資産がSecurityException相当ならrevokedへ遷移する`() {
        val transitions = AvailabilitySweep.transitions(
            listOf(entry("a", Asset.AVAILABILITY_OK) to UriAccessibility.REVOKED),
        )

        assertEquals(1, transitions.size)
        assertEquals("a", transitions[0].id)
        assertEquals(Asset.AVAILABILITY_OK, transitions[0].from)
        assertEquals(Asset.AVAILABILITY_REVOKED, transitions[0].to)
    }

    @Test
    fun `okの資産がFileNotFound相当ならdisconnectedへ遷移する`() {
        val transitions = AvailabilitySweep.transitions(
            listOf(entry("a", Asset.AVAILABILITY_OK) to UriAccessibility.DISCONNECTED),
        )

        assertEquals(1, transitions.size)
        assertEquals(Asset.AVAILABILITY_DISCONNECTED, transitions[0].to)
    }

    @Test
    fun `変化しない観測結果はDB書き戻しの対象にしない`() {
        val transitions = AvailabilitySweep.transitions(
            listOf(
                entry("a", Asset.AVAILABILITY_OK) to UriAccessibility.OK,
                entry("b", Asset.AVAILABILITY_REVOKED) to UriAccessibility.REVOKED,
                entry("c", Asset.AVAILABILITY_DISCONNECTED) to UriAccessibility.DISCONNECTED,
            ),
        )

        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `明示的全走査では回復も遷移として検出する`() {
        val transitions = AvailabilitySweep.transitions(
            listOf(
                entry("a", Asset.AVAILABILITY_REVOKED) to UriAccessibility.OK,
                entry("b", Asset.AVAILABILITY_DISCONNECTED) to UriAccessibility.DISCONNECTED,
            ),
        )

        assertEquals(1, transitions.size)
        assertEquals("a", transitions[0].id)
        assertEquals(Asset.AVAILABILITY_OK, transitions[0].to)
    }

    @Test
    fun `probe結果の写像はcore-modelのavailability定数と一致する`() {
        assertEquals(Asset.AVAILABILITY_OK, AvailabilitySweep.toAvailability(UriAccessibility.OK))
        assertEquals(Asset.AVAILABILITY_REVOKED, AvailabilitySweep.toAvailability(UriAccessibility.REVOKED))
        assertEquals(
            Asset.AVAILABILITY_DISCONNECTED,
            AvailabilitySweep.toAvailability(UriAccessibility.DISCONNECTED),
        )
    }

    @Test
    fun `Assetから検査対象を取り出す`() {
        val asset = Asset(
            id = "x",
            sourceUri = "content://test/x",
            displayName = "x.jpg",
            mimeGuess = "image/jpeg",
            sniffedFormat = "jpeg",
            width = 1,
            height = 1,
            orientation = 1,
            dateTaken = null,
            rawCameraId = null,
            colorProfileHash = null,
            imageFingerprint = null,
            availability = Asset.AVAILABILITY_OK,
            importedAt = 0L,
        )

        assertEquals(
            AvailabilitySweep.Entry("x", "content://test/x", Asset.AVAILABILITY_OK),
            AvailabilitySweep.entryOf(asset),
        )
    }
}
