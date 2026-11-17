package com.example.flipcover

import android.app.Activity
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** フェーダー（音量・明るさ・アプリを開くバネ）のテスト。 */
@RunWith(RobolectricTestRunner::class)
class FaderTest {
    @Test
    fun 働きごとに引き手の下の文字が変わる() {
        assertEquals("VOL", ZipperFader.labelFor("volume"))
        assertEquals("BRT", ZipperFader.labelFor("brightness"))
        assertEquals("APP", ZipperFader.labelFor("app"))
    }

    @Test
    fun 部品の説明に明るさが入っている() {
        assertTrue(ModuleType.SLIDER.note.contains("明るさ"))
    }

    private fun faderOn(activity: Activity): ZipperFader {
        val fader = ZipperFader(activity, "brightness", "BRT")
        activity.setContentView(FrameLayout(activity).apply { addView(fader, FrameLayout.LayoutParams(200, 600)) })
        fader.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        fader.layout(0, 0, 200, 600)
        return fader
    }

    /** 引き手を上から下まで動かす（下ほど暗い）。 */
    private fun drag(fader: View, fromY: Float, toY: Float) {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, y: Float) = MotionEvent.obtain(t, t + 10, action, 100f, y, 0).also { fader.onTouchEvent(it); it.recycle() }
        ev(MotionEvent.ACTION_DOWN, fromY)
        for (i in 1..10) ev(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * i / 10)
        ev(MotionEvent.ACTION_UP, toY)
    }

    /**
     * 7.2.0 の不具合：明るさのフェーダーが、カバー画面ではなく、開いたときの画面（端末全体の設定）の明るさを変えていた。
     * カバー画面の明るさは、FlipCover の画面（ウィンドウ）の明るさとして変える。端末全体の設定は変えない。
     */
    @Test
    fun 明るさのフェーダーは端末全体の設定ではなく自分の画面の明るさを変える() {
        T.app.getSharedPreferences("home", 0).edit().clear().commit()
        Settings.System.putInt(T.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 200)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val fader = faderOn(activity)

        drag(fader, 300f, 590f)

        val window = activity.window.attributes.screenBrightness
        assertTrue("画面の明るさが暗くなっていない: $window", window in 0f..0.2f)
        assertEquals(200, Settings.System.getInt(T.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test
    fun 選んだ明るさは次に開いたときも使い_タップで端末の設定どおりに戻せる() {
        T.app.getSharedPreferences("home", 0).edit().clear().commit()
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        drag(faderOn(first), 300f, 590f)

        // 次に開いた画面にも、同じ明るさを当てる
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        CoverBrightness.apply(second)
        assertTrue(second.window.attributes.screenBrightness in 0f..0.2f)

        // タップで「端末の設定どおり（AUTO）」に戻す
        val fader = faderOn(second)
        val t = SystemClock.uptimeMillis()
        for (a in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) MotionEvent.obtain(t, t, a, 100f, 300f, 0).also { fader.onTouchEvent(it); it.recycle() }
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, second.window.attributes.screenBrightness)
        assertEquals(null, CoverBrightness.saved(second))
    }
}
