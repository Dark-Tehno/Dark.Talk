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
    "call_full" -> if (en) "The call is full" else "В звонке нет свободных мест"
    "not_in_call" -> if (en) "You are not in this call" else "Вы не в этом звонке"
    "declined_elsewhere" -> if (en) "Declined on another device" else "Отклонён на другом устройстве"
    "call_not_found" -> if (en) "The call has already ended" else "Звонок уже завершён"
    "already_in_call" -> if (en) "You are already in a call" else "Вы уже в звонке"
    else -> if (en) "Call ended" else "Звонок завершён"
}

/** Текст для системного сообщения-звонка в чате; null, если это не сообщение о звонке. */
fun describeCallMessage(message: Message, myId: Long, en: Boolean): String? {
    val call = message.metadata?.get("call") as? Map<*, *> ?: return null
    val status = call["status"] as? String ?: return null
    val video = call["media"] == "video"
    if (call["group"] == true) {
        // групповой звонок: {group: true, participants: [id...], duration, status}
        val groupKind = if (en) (if (video) "Group video call" else "Group call") else (if (video) "Групповой видеозвонок" else "Групповой звонок")
        val seconds = (call["duration"] as? Number)?.toInt() ?: 0
        return when {
            status == "ended" -> "$groupKind · ${seconds / 60}:${"%02d".format(seconds % 60)}"
            (call["caller_id"] as? Number)?.toLong() == myId -> if (en) "$groupKind: no answer" else "$groupKind: нет ответа"
            else -> if (en) "Missed ${groupKind.lowercase()}" else "Пропущенный ${groupKind.lowercase()}"
        }
    }
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
