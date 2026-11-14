package com.gleohealth.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.gleohealth.app.MainActivity
import com.gleohealth.app.R
import com.gleohealth.app.engine.Event
import com.gleohealth.app.voice.Voice

/**
 * 백그라운드 기록 서비스 (P2). 화면을 닫아도 1분마다 기록하고 음성 안내를 한다.
 * 자원 규칙(Pleos 성능 가이드): 정차가 30분 넘게 이어지고 화면도 안 보이면 스스로 멈춘다(CPU·메모리 0).
 * 외부에 열려 있지 않다(exported=false).
 */
class HealthService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var voice: Voice
    private var idleMin = 0

    private val onEvent: (Event) -> Unit = { e -> voice.speakOnce(e.text) }

    private val ticker = object : Runnable {
        override fun run() {
            HealthCore.tick(1)
            idleMin = if (HealthCore.signal(0).moving) 0 else idleMin + 1
            if (idleMin >= IDLE_STOP_MIN && !HealthCore.uiVisible) { stopSelf(); return }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        HealthCore.init(this)
        voice = Voice(this)
        HealthCore.addEventListener(onEvent)
        startForegroundCompat()
        HealthCore.startSource(this)
        handler.postDelayed(ticker, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        idleMin = 0
        return START_NOT_STICKY // 차가 꺼졌다 켜지면 사용자가 앱을 열 때 다시 시작한다
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        HealthCore.removeEventListener(onEvent)
        HealthCore.stopSource()
        voice.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val CHANNEL = "gleo_health_trip"
        private const val NOTI_ID = 7
        private const val IDLE_STOP_MIN = 30

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HealthService::class.java))
        }
    }
}
