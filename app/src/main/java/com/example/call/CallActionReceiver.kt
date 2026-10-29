package com.example.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.DarkTalkApplication

/** Кнопки «Отклонить» и «Завершить» в уведомлениях звонка. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = DarkTalkApplication.instance.callManager
        when (intent.action) {
            CallNotifications.ACTION_DECLINE -> manager.reject()
            CallNotifications.ACTION_HANGUP -> manager.hangUp()
        }
    }
}
