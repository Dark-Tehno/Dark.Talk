package com.example.ui.call

import com.example.data.model.Message

/** Подписи для экрана звонка и системных сообщений о звонках. */
fun callEndReasonText(reason: String?, en: Boolean): String = when (reason) {
    "busy" -> if (en) "User is busy" else "Абонент занят"
    "rejected" -> if (en) "Call declined" else "Звонок отклонён"
    "timeout", "missed" -> if (en) "No answer" else "Нет ответа"
    "cancelled" -> if (en) "Call cancelled" else "Звонок отменён"
    "answered_elsewhere" -> if (en) "Answered on another device" else "Принят на другом устройстве"
    "no_connection", "connection_lost", "error", "disconnected" -> if (en) "Connection lost" else "Соединение потеряно"
    "no_permission" -> if (en) "No microphone permission" else "Нет доступа к микрофону"
    "group_calls_unsupported" -> if (en) "Group calls are not supported" else "Групповые звонки не поддерживаются"
    "chat_not_found", "unavailable" -> if (en) "User unavailable" else "Абонент недоступен"
    "already_in_call" -> if (en) "You are already in a call" else "Вы уже в звонке"
    else -> if (en) "Call ended" else "Звонок завершён"
}

/** Текст для системного сообщения-звонка в чате; null, если это не сообщение о звонке. */
fun describeCallMessage(message: Message, myId: Long, en: Boolean): String? {
    val call = message.metadata?.get("call") as? Map<*, *> ?: return null
    val status = call["status"] as? String ?: return null
    val video = call["media"] == "video"
    val duration = (call["duration"] as? Number)?.toInt() ?: 0
    val outgoing = (call["caller_id"] as? Number)?.toLong() == myId
    val kind = if (en) (if (video) "Video call" else "Voice call") else (if (video) "Видеозвонок" else "Аудиозвонок")
    return when (status) {
        "ended" -> "$kind · ${duration / 60}:${"%02d".format(duration % 60)}"
        "rejected" -> if (en) "$kind declined" else "$kind отклонён"
        else -> when {
            outgoing -> if (en) "$kind: no answer" else "$kind: нет ответа"
            else -> if (en) "Missed ${kind.lowercase()}" else "Пропущенный ${kind.lowercase()}"
        }
    }
}
