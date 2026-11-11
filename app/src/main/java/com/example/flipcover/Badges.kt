package com.example.flipcover

/**
 * アプリごとの通知の件数（FlipHome と同じ描き方の部品が使う窓口）。
 * FlipCover は「通知へのアクセス」を使わないので、件数はいつも 0（通知の印は出ない）。
 */
object Badges {
    fun count(@Suppress("UNUSED_PARAMETER") pkg: String): Int = 0
    fun addListener(@Suppress("UNUSED_PARAMETER") l: () -> Unit) {}
    fun removeListener(@Suppress("UNUSED_PARAMETER") l: () -> Unit) {}
}
