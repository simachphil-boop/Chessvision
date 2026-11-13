package com.chessvision.ai.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.chessvision.ai.ChessVisionApp
import com.chessvision.ai.MainActivity
import com.chessvision.ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the floating analysis window. Running as a foreground
 * service keeps the window (and the analysis) alive while the app's activity is in the background.
 */
class FloatingOverlayService : Service() {
    private var scope: CoroutineScope? = null
    private var manager: FloatingOverlayManager? = null
    private var vm: OverlayViewModel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelfSafely(); return START_NOT_STICKY }
        if (!OverlayPermissionManager.canDraw(this)) {
            Toast.makeText(this, "Нет разрешения «Поверх других приложений»", Toast.LENGTH_LONG).show()
            stopSelfSafely(); return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException etc.
            Log.e("Overlay", "startForeground failed", e)
            stopSelf(); return START_NOT_STICKY
        }
        val g = (application as ChessVisionApp).graph
        if (manager?.isShowing != true) {
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val model = OverlayViewModel(g, sc)
            val m = FloatingOverlayManager(this)
            val ok = m.show(model, onClose = { stopSelfSafely() }, onOpenApp = { openApp() })
            if (!ok) {
                sc.cancel()
                Toast.makeText(this, "Не удалось показать плавающее окно", Toast.LENGTH_LONG).show()
                stopSelfSafely(); return START_NOT_STICKY
            }
            scope = sc; vm = model; manager = m
            g.overlay.setRunning(true)
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Плавающее окно анализа", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_overlay)
            .setContentTitle("ChessVision: плавающее окно")
            .setContentText("Учебная доска и анализ Stockfish")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Закрыть", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "analysis_from_overlay"))
        } catch (e: Exception) { Log.w("Overlay", "open app failed", e) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        manager?.clampToScreen()
    }

    private fun stopSelfSafely() {
        cleanup()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun cleanup() {
        vm?.close()
        manager?.hide()
        scope?.cancel()
        vm = null; manager = null; scope = null
        runCatching { (application as ChessVisionApp).graph.overlay.setRunning(false) }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.chessvision.ai.overlay.STOP"

        /** Starts the overlay; returns false if the permission is missing. */
        fun start(context: Context): Boolean {
            if (!OverlayPermissionManager.canDraw(context)) return false
            val i = Intent(context, FloatingOverlayService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
                true
            } catch (e: Exception) { Log.e("Overlay", "start failed", e); false }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, FloatingOverlayService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
