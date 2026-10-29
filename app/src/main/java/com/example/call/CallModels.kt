package com.example.call

enum class CallPhase {
    IDLE,

    /** Мы звоним: вызов отправлен, ждём ответа. */
    OUTGOING,

    /** Нам звонят: идёт вызов. */
    INCOMING,

    /** Ответ получен, устанавливается WebRTC-соединение. */
    CONNECTING,

    /** Соединение установлено. */
    ACTIVE,

    /** Звонок закончен (показываем причину пару секунд). */
    ENDED
}

data class CallPeer(
    val id: Long,
    val username: String,
    val avatar: String? = null
)

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val chatId: Long? = null,
    val peer: CallPeer? = null,
    val isIncoming: Boolean = false,
    /** Звонок начат как видеозвонок. */
    val isVideoCall: Boolean = false,
    val micMuted: Boolean = false,
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    val screenSharing: Boolean = false,
    val speakerOn: Boolean = false,
    val remoteVideoOn: Boolean = false,
    val remoteScreenShare: Boolean = false,
    val remoteMuted: Boolean = false,
    /** Время установки соединения (elapsedRealtime), для таймера. */
    val connectedAtMs: Long? = null,
    /** Код причины для экрана «Звонок завершён» (busy, rejected, timeout, ...). */
    val endReason: String? = null
)
