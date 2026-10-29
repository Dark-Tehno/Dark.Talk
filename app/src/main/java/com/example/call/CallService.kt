package com.example.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.DarkTalkApplication

/**
 * Foreground-сервис идущего звонка: не даёт системе убить процесс и разрешает
 * доступ к микрофону/камере/записи экрана в фоне.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = DarkTalkApplication.instance.callManager
        val state = manager.state.value
        if (state.phase == CallPhase.IDLE || state.phase == CallPhase.ENDED || state.phase == CallPhase.INCOMING) {
            // startForegroundService обязывает вызвать startForeground, даже если сразу останавливаемся
            runCatching { promote(state, withProjection = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        val screenData = if (intent?.action == ACTION_START_SCREEN) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        } else null

        try {
            promote(state, withProjection = screenData != null)
        } catch (e: Exception) {
            // Нет разрешений на нужный тип сервиса: звонок остаётся, но без защиты от убийства процесса
            stopSelf()
            return START_NOT_STICKY
        }
        // MediaProjection разрешено получать только после startForeground с типом mediaProjection.
        if (screenData != null) manager.startScreenShare(screenData)
        return START_NOT_STICKY
    }

    private fun promote(state: CallUiState, withProjection: Boolean) {
        val language = DarkTalkApplication.instance.preferencesManager.userLanguage
        val notification = CallNotifications.ongoing(this, state, language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (granted(Manifest.permission.CAMERA) && (state.cameraOn || state.isVideoCall)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (withProjection || state.screenSharing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(CallNotifications.ID_ONGOING, notification, type)
        } else {
            startForeground(CallNotifications.ID_ONGOING, notification)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(CallNotifications.ID_ONGOING)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SCREEN = "com.example.call.START_SCREEN"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        /** Запустить/обновить сервис (вызывается, когда приложение на экране). */
        fun sync(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            }
        }

        /** Пользователь разрешил запись экрана: поднимаем сервис с типом mediaProjection и стартуем захват. */
        fun startScreenShare(context: Context, data: Intent) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallService::class.java).apply {
                        action = ACTION_START_SCREEN
                        putExtra(EXTRA_PROJECTION_DATA, data)
                    }
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
