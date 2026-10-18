package com.imageshot.feature.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ExportNaming（§8.2: 既存と同名は新名生成・上書きはこのモジュールでは提供しない）の
 * 生成規則テスト（純Kotlin）。
 */
class ExportNamingTest {

    @Test
    fun sanitize_removesIllegalAndControlCharacters() {
        assertEquals("photo A", ExportNaming.sanitizeBaseName("photo: A\\*?\"<>|"))
        assertEquals("tabremoved", ExportNaming.sanitizeBaseName("tab\u0007removed"))
        assertEquals(100, ExportNaming.sanitizeBaseName("x".repeat(500)).length)
    }

    @Test
    fun sanitize_blankFallsBackToDefaultName() {
        assertEquals("photo", ExportNaming.sanitizeBaseName("   "))
        assertEquals("photo", ExportNaming.sanitizeBaseName("..."))
    }

    @Test
    fun composeUnique_firstNoSuffix_thenNumbered() {
        assertEquals("photo.jpg", ExportNaming.composeUniqueFileName("photo", "jpg", emptySet()))
        assertEquals("photo (2).jpg", ExportNaming.composeUniqueFileName("photo", "jpg", setOf("photo.jpg")))
        assertEquals(
            "photo (3).jpg",
            ExportNaming.composeUniqueFileName("photo", "jpg", setOf("photo.jpg", "photo (2).jpg")),
        )
    }

    @Test
    fun composeUnique_treatsCaseInsensitiveExtensionsAndSuffixesAsExisting() {
        assertEquals(
            "Photo (2).jpg",
            ExportNaming.composeUniqueFileName("Photo", "jpg", setOf("PHOTO.JPG")),
        )
    }

    @Test
    fun noSuffixName_isSanitizedProposalWithoutCollisionNumber() {
        // 上書き選択は別の明示承認（§8.2）。この関数は衝突回避番号を持たない提案名のみ返す。
        assertEquals("IMG_0001.jpg.jpg", ExportNaming.noSuffixName("IMG_0001.jpg", "jpg"))
        assertEquals("photo_rev0.jpg", ExportNaming.noSuffixName("photo_rev0", "jpg"))
    }
}
