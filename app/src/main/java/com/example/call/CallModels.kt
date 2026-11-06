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

/** Состояние WebRTC-соединения с одним участником группового звонка. */
enum class LinkState { CONNECTING, CONNECTED, LOST }

/** Другой участник звонка (в группе их несколько): что он показывает и есть ли с ним связь. */
data class CallMember(
    val id: Long,
    val username: String,
    val avatar: String? = null,
    val videoOn: Boolean = false,
    val screenShare: Boolean = false,
    val muted: Boolean = false,
    val link: LinkState = LinkState.CONNECTING
)

/** Идущий групповой звонок в чате (кнопка «Присоединиться»). */
data class GroupCallInfo(
    val callId: String,
    val chatId: Long,
    val media: String,
    val participants: List<Long>,
    val maxParticipants: Int
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
    val endReason: String? = null,
    /** Групповой звонок (mesh): [members] — остальные участники, [chatTitle] — название чата. */
    val isGroup: Boolean = false,
    val chatTitle: String = "",
    val members: List<CallMember> = emptyList(),
    val maxParticipants: Int = 6
)
