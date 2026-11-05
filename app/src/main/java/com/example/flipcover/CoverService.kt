package com.example.flipcover

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * 端末が閉じられてカバー画面だけが点灯したとき、カバー画面の上に FlipCover の表示を重ねる。
 * （「重ねて表示＋常駐」は Google Play プロテクトに有害と判定されるが、持ち主の了承のうえで使う。インストール時は
 * 「インストールする」を選ぶ）
 *
 * ユーザー補助（アクセシビリティ）は使わない。PayPay や銀行のアプリは、ユーザー補助のサービスが有効だと
 * 決済などを止めるため。代わりに「他のアプリの上に重ねて表示」の許可で重ね、常駐サービス（通知欄に小さく出る）として動く。
 *
 * - アプリのボタンを押したら、そのアプリをカバー画面で開き、少し待ってから表示を消す
 * - 消した表示は、カバー画面がいったん消えて点き直したとき（または開いて閉じ直したとき）に出し直す
 *   （ユーザー補助を使わないので、アプリを閉じた瞬間はわからない）
 * - ダブルタップで消すこともできる
 */
class CoverService : Service(), DisplayManager.DisplayListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: View? = null
    private var overlayWm: WindowManager? = null
    private var dismissedUntilReopen = false
    private var coverDisplayId = Display.INVALID_DISPLAY

    private var folded = false

    /** カバー画面が点いていたか（消えて点き直したら、消していた表示を出し直す）。 */
    private var coverWasOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotice()
        prefs = Prefs(this)
        prefs.registerListener(this)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, handler)
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 設定でオフにされていたり、重ねて表示の許可がなければ止まる
        if (!prefs.enabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        update()
        return START_STICKY
    }

    /** 常駐していることを、通知欄に目立たない通知で出す（Android の決まり）。 */
    private fun startForegroundWithNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "カバー画面の表示", NotificationManager.IMPORTANCE_MIN).apply {
            description = "閉じたときにカバー画面へ表示するために動いていることを示します"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("FlipCover")
            .setContentText("閉じたときにカバー画面へ表示します")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onDestroy() {
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(this)
        if (::prefs.isInitialized) prefs.unregisterListener(this)
        hide()
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) = update()

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        // 設定変更を即座に反映するため作り直す
        hide()
        if (!prefs.enabled) { stopSelf(); return }
        update()
    }

    private fun update() {
        val cover = findCoverDisplay(displayManager)
        val main = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        folded = cover != null && cover.state == Display.STATE_ON &&
            main?.state != Display.STATE_ON

        val coverOn = cover != null && cover.state == Display.STATE_ON
        // カバー画面が消えて点き直したら、アプリを開くなどで消していた表示を出し直す
        if (coverOn && !coverWasOn) dismissedUntilReopen = false
        coverWasOn = coverOn

        if (!folded) {
            dismissedUntilReopen = false
            handler.removeCallbacks(fadeOut)
            hide()
            return
        }
        if (prefs.enabled && Settings.canDrawOverlays(this) && !dismissedUntilReopen && overlay == null) show(cover!!)
    }

    private fun show(display: Display) {
        val ctx = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val view = CoverContent.create(ctx, prefs, CoverContent.coverSize(display), ::launch, ::onWidgetTapped)

        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                dismissedUntilReopen = true
                hide()
                return true
            }
        })
        view.setOnTouchListener { _, e -> detector.onTouchEvent(e) }

        // 重ねて表示の窓は、何も指定しないと下のバー（ジェスチャーの棒）などの分を除いた大きさになり、下が見切れる。
        // カバー画面の実寸を指定し、バーの分も除かないようにする
        val size = CoverContent.coverSize(display)
        val lp = WindowManager.LayoutParams(
            size.width,
            size.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            fitInsetsTypes = 0
            fitInsetsSides = 0
            // カメラ穴の部分まで描画する
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        runCatching { wm.addView(view, lp) }.onSuccess {
            overlay = view
            overlayWm = wm
            coverDisplayId = display.displayId
            // 表示している間だけ、ウィジェットの更新（残高の変化など）を受け取る
            if (prefs.widgetIds.isNotEmpty()) runCatching { WidgetHost.get(this).startListening() }
        }
    }

    /**
     * アプリをカバー画面上で起動する。
     * すぐに表示を消すと、アプリが出るまでの間に元のカバー画面が見えてしまうので、少し待ってからフェードアウトして消す。
     */
    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(coverDisplayId)
        runCatching { startActivity(intent, options.toBundle()) }
            .onSuccess {
                dismissedUntilReopen = true
                handler.removeCallbacks(fadeOut)
                handler.postDelayed(fadeOut, LAUNCH_FADE_MS)
            }
            .onFailure { Toast.makeText(this, "起動できませんでした: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /**
     * ウィジェットがタップされたとき。モバイルSuica のように、残高を読むために自分のアプリの画面を開く
     * ウィジェットがあるが、この表示はいつも一番上にあるので、そのままでは画面が下に隠れてしまう。
     * ユーザー補助を使わないので開いたかはわからないため、少し待ってから表示を消す（次にカバー画面が点いたら出し直す）。
     */
    private fun onWidgetTapped(@Suppress("UNUSED_PARAMETER") packageName: String) {
        dismissedUntilReopen = true
        handler.removeCallbacks(fadeOut)
        handler.postDelayed(fadeOut, WIDGET_FADE_MS)
    }

    private fun hide() {
        if (overlay != null) runCatching { WidgetHost.get(this).stopListening() }
        overlay?.let { v -> runCatching { overlayWm?.removeView(v) } }
        overlay = null
        overlayWm = null
    }

    private val fadeOut = Runnable {
        val v = overlay ?: return@Runnable
        v.animate().alpha(0f).setDuration(150).withEndAction { if (overlay === v) hide() }.start()
    }

    companion object {
        /** アプリを開いてから表示を消すまでの時間（アプリの画面が出るのを待つ）。 */
        private const val LAUNCH_FADE_MS = 700L

        /** ウィジェットをタップしてから表示を消すまでの時間。 */
        private const val WIDGET_FADE_MS = 900L

        private const val CHANNEL = "cover"
        private const val NOTICE_ID = 1

        /** 設定がオンで、重ねて表示が許可されていれば、常駐サービスを動かす（オフなら止める）。 */
        fun sync(context: Context) {
            val intent = Intent(context, CoverService::class.java)
            if (Prefs(context).enabled && Settings.canDrawOverlays(context)) runCatching { context.startForegroundService(intent) }
            else context.stopService(intent)
        }

        fun findCoverDisplay(dm: DisplayManager): Display? = CoverContent.findCoverDisplay(dm)
    }
}

/** 電源を入れたときと、FlipCover を更新したときに、常駐サービスを動かし直す。 */
class CoverBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) CoverService.sync(context)
    }
}
