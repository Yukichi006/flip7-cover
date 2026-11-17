package com.example.flipcover

import org.junit.Assert.assertEquals
import org.junit.Test

/** フェーダーの働き（音量・明るさ・アプリを開くバネ）ごとの表示のテスト。 */
class FaderTest {
    @Test
    fun 働きごとに引き手の下の文字が変わる() {
        assertEquals("VOL", ZipperFader.labelFor("volume"))
        assertEquals("BRT", ZipperFader.labelFor("brightness"))
        assertEquals("APP", ZipperFader.labelFor("app"))
    }

    @Test
    fun 部品の説明に明るさが入っている() {
        assertEquals(true, ModuleType.SLIDER.note.contains("明るさ"))
    }
}
