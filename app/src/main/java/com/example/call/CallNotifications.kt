package com.example.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.util.isEnglishLanguage

object CallNotifications {
    const val CHANNEL_INCOMING = "calls_incoming"
    const val CHANNEL_ONGOING = "calls_ongoing"
    const val RING_TIMEOUT_MS = 60_000L
    const val ID_INCOMING = 4101
    const val ID_ONGOING = 4102

    const val ACTION_ANSWER = "com.example.call.ACTION_ANSWER"
    const val ACTION_DECLINE = "com.example.call.ACTION_DECLINE"
    const val ACTION_HANGUP = "com.example.call.ACTION_HANGUP"
    const val ACTION_OPEN = "com.example.call.ACTION_OPEN"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // звук проигрывает CallSounds
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun immutable() = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun activityIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent, immutable())
    }

    private fun receiverIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, immutable())
    }

    private fun person(name: String) = Person.Builder().setName(name).setImportant(true).build()

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showIncoming(context: Context, state: CallUiState, language: String) {
        if (!canNotify(context)) return
        val en = isEnglishLanguage(language)
        val name = state.peer?.username ?: "?"
        val text = if (state.isVideoCall) {
            if (en) "Incoming video call" else "Входящий видеозвонок"
        } else {
            if (en) "Incoming call" else "Входящий звонок"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(activityIntent(context, ACTION_OPEN, 1))
            .setFullScreenIntent(activityIntent(context, ACTION_OPEN, 2), true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(name),
                    receiverIntent(context, ACTION_DECLINE, 3),
                    activityIntent(context, ACTION_ANSWER, 4)
                )
            )
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_INCOMING, notification) }
    }

    fun cancelIncoming(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_INCOMING) }
    }

    /** Уведомление для foreground-сервиса идущего звонка. */
    fun ongoing(context: Context, state: CallUiState, language: String): Notification {
        val en = isEnglishLanguage(language)
        val name = state.peer?.username ?: "?"
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(name)
            .setContentText(
                when {
                    state.screenSharing -> if (en) "Sharing your screen" else "Идёт демонстрация экрана"
                    state.phase == CallPhase.OUTGOING -> if (en) "Calling…" else "Вызов…"
                    else -> if (en) "Call in progress" else "Идёт звонок"
                }
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(activityIntent(context, ACTION_OPEN, 5))
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person(name),
                    receiverIntent(context, ACTION_HANGUP, 6)
                )
            )
        return builder.build()
    }
}
