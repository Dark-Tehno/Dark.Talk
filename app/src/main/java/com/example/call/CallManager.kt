package com.example.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.PreferencesManager
import com.example.data.model.IceServerDto
import com.example.data.model.WsEvent
import com.example.data.network.DarkTalkWebSocketManager
import com.example.data.network.WsConnectionState
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Звонки по WebRTC: 1-на-1 и групповые. Сервер только пересылает сигналинг (call_* по /ws/chats/),
 * аудио, видео и экран идут напрямую между устройствами.
 *
 * Групповой звонок это mesh: по одному PeerConnection ([Link]) на каждого другого участника.
 * Offer создаёт тот, кто присоединился ПОЗЖЕ (список приходит в call_accepted.participants),
 * поэтому встречных offer не бывает. В группе каждый SDP/ICE уходит с `to_user_id`.
 * Личный звонок это та же машинерия с одним Link и без `to_user_id`.
 *
 * Все изменения состояния и вызовы WebRTC идут последовательно через один «почтовый ящик»
 * ([inbox]) на отдельном потоке, поэтому гонок между событиями сокета и действиями пользователя нет.
 */
class CallManager(
    private val context: Context,
    private val repository: DarkTalkRepository,
    private val webSocketManager: DarkTalkWebSocketManager,
    private val prefs: PreferencesManager
) {
    private val tag = "CallManager"

    private val rtcDispatcher = Executors.newSingleThreadExecutor { Thread(it, "CallRtc") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + rtcDispatcher)
    private val inbox = Channel<Cmd>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideo

    /** Видео собеседника в личном звонке. */
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideo

    /** Видео участников группового звонка: userId -> трек. */
    private val _remoteVideos = MutableStateFlow<Map<Long, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<Long, VideoTrack>> = _remoteVideos

    /** Идущие групповые звонки по чатам (для баннера «Присоединиться»). */
    private val _groupCalls = MutableStateFlow<Map<Long, GroupCallInfo>>(emptyMap())
    val groupCalls: StateFlow<Map<Long, GroupCallInfo>> = _groupCalls

    /** Нужен UI для SurfaceViewRenderer. */
    val eglBase: EglBase by lazy { EglBase.create() }

    private val sounds = CallSounds(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Одно WebRTC-соединение с одним участником. */
    private class Link(val id: Long, val offerer: Boolean) {
        var pc: PeerConnection? = null
        var videoSender: RtpSender? = null
        val pending = mutableListOf<IceCandidate>()
        var iceRestartDone = false
        var timer: Job? = null
    }

    // ---- WebRTC ----
    private var factory: PeerConnectionFactory? = null
    private val links = LinkedHashMap<Long, Link>()
    private val known = HashMap<Long, CallPeer>()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null

    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null

    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private var clientCallId: String? = null
    /** client_call_id отменённого до call_ringing звонка; не сбрасывается в finishLocally. */
    private var pendingCancelClientId: String? = null
    private var isCaller = false
    private var disconnectWatchdog: Job? = null
    private var pingJob: Job? = null
    private var lostSocketInCall = false
    private var savedAudioMode: Int? = null

    private val myId: Long get() = prefs.userId

    private sealed interface Cmd {
        data class Start(val chatId: Long, val peer: CallPeer, val video: Boolean) : Cmd
        data class StartGroup(val chatId: Long, val title: String, val video: Boolean, val users: List<CallPeer>) : Cmd
        data class JoinGroup(val chatId: Long, val title: String, val users: List<CallPeer>) : Cmd
        data object Accept : Cmd
        data object Reject : Cmd
        data object HangUp : Cmd
        data class Signal(val event: WsEvent.CallSignal) : Cmd
        data class IceState(val linkId: Long, val state: PeerConnection.IceConnectionState) : Cmd
        data class LocalCandidate(val linkId: Long, val candidate: IceCandidate) : Cmd
        data object ToggleMute : Cmd
        data object ToggleCamera : Cmd
        data object SwitchCamera : Cmd
        data object ToggleSpeaker : Cmd
        data class StartScreen(val data: Intent) : Cmd
        data object StopScreen : Cmd
        data object SocketLost : Cmd
        data object SocketBack : Cmd
        data object ClearEnded : Cmd
    }

    init {
        scope.launch { webSocketManager.events.collect { if (it is WsEvent.CallSignal) inbox.trySend(Cmd.Signal(it)) } }
        scope.launch {
            var wasConnected = false
            var lost = false
            webSocketManager.connectionState.collect { s ->
                if (s == WsConnectionState.CONNECTED) {
                    if (lost) inbox.trySend(Cmd.SocketBack)
                    wasConnected = true
                    lost = false
                } else if (wasConnected) {
                    wasConnected = false
                    lost = true
                    inbox.trySend(Cmd.SocketLost)
                }
            }
        }
        scope.launch {
            for (cmd in inbox) {
                try {
                    handle(cmd)
                } catch (e: Exception) {
                    Log.e(tag, "Call command failed: $cmd", e)
                    val cur = _state.value
                    if (cur.phase != CallPhase.IDLE && cur.phase != CallPhase.ENDED) {
                        cur.callId?.let { send(if (cur.phase == CallPhase.INCOMING) "call_reject" else "call_end", "call_id" to it) }
                        finishLocally("error")
                    }
                }
            }
        }
    }

    // ================= Публичное API (потокобезопасно) =================

    fun startCall(chatId: Long, peer: CallPeer, video: Boolean) { inbox.trySend(Cmd.Start(chatId, peer, video)) }

    /** Групповой звонок из группового чата; [users] — участники чата (для имён и аватарок). */
    fun startGroupCall(chatId: Long, title: String, video: Boolean, users: List<CallPeer>) {
        inbox.trySend(Cmd.StartGroup(chatId, title, video, users))
    }

    /** Присоединиться к идущему групповому звонку чата (баннер в чате). */
    fun joinGroupCall(chatId: Long, title: String, users: List<CallPeer>) { inbox.trySend(Cmd.JoinGroup(chatId, title, users)) }

    /** Узнать у сервера, идёт ли в чате групповой звонок (при открытии чата). */
    fun refreshGroupCall(chatId: Long) {
        scope.launch {
            val r = repository.getActiveCall(chatId) ?: return@launch
            applyGroupState(chatId, r.active, r.callId, r.media, r.participants, r.maxParticipants)
        }
    }

    fun accept() { inbox.trySend(Cmd.Accept) }
    fun reject() { inbox.trySend(Cmd.Reject) }
    fun hangUp() { inbox.trySend(Cmd.HangUp) }
    fun toggleMute() { inbox.trySend(Cmd.ToggleMute) }
    fun toggleCamera() { inbox.trySend(Cmd.ToggleCamera) }
    fun switchCamera() { inbox.trySend(Cmd.SwitchCamera) }
    fun toggleSpeaker() { inbox.trySend(Cmd.ToggleSpeaker) }
    fun stopScreenShare() { inbox.trySend(Cmd.StopScreen) }

    /** Вызывается из [CallService] после startForeground(mediaProjection). */
    fun startScreenShare(permissionData: Intent) { inbox.trySend(Cmd.StartScreen(permissionData)) }

    // ================= Обработка команд =================

    private suspend fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Start -> onStart(cmd)
            is Cmd.StartGroup -> onStartGroup(cmd)
            is Cmd.JoinGroup -> onJoinGroup(cmd)
            Cmd.Accept -> onAccept()
            Cmd.Reject -> {
                val s = _state.value
                if (s.phase == CallPhase.INCOMING && s.callId != null) {
                    send("call_reject", "call_id" to s.callId)
                    finishLocally("rejected")
                }
            }
            Cmd.HangUp -> onHangUp()
            is Cmd.Signal -> onSignal(cmd.event)
            is Cmd.IceState -> onIceState(cmd.linkId, cmd.state)
            is Cmd.LocalCandidate -> {
                val link = links[cmd.linkId] ?: return
                if (_state.value.callId == null) return
                sendTo(
                    link, "call_ice",
                    "candidate" to cmd.candidate.sdp,
                    "sdp_mid" to cmd.candidate.sdpMid,
                    "sdp_mline_index" to cmd.candidate.sdpMLineIndex
                )
            }
            Cmd.ToggleMute -> {
                val muted = !_state.value.micMuted
                audioTrack?.setEnabled(!muted)
                _state.update { it.copy(micMuted = muted) }
                sendMediaState()
            }
            Cmd.ToggleCamera -> onToggleCamera()
            Cmd.SwitchCamera -> cameraCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) { _state.update { it.copy(frontCamera = isFront) } }
                override fun onCameraSwitchError(error: String?) { Log.w(tag, "switchCamera: $error") }
            })
            Cmd.ToggleSpeaker -> {
                val on = !_state.value.speakerOn
                setSpeaker(on)
                _state.update { it.copy(speakerOn = on) }
            }
            is Cmd.StartScreen -> onStartScreen(cmd.data)
            Cmd.StopScreen -> stopScreen()
            Cmd.SocketLost -> {
                val phase = _state.value.phase
                if (phase == CallPhase.OUTGOING || phase == CallPhase.INCOMING) {
                    // Сервер отменяет вызов при обрыве сокета звонящего; входящий вернётся при переподключении.
                    finishLocally("connection_lost")
                } else if (phase == CallPhase.CONNECTING || phase == CallPhase.ACTIVE) {
                    lostSocketInCall = true // идущий звонок пережидает обрыв: медиа идёт напрямую
                }
            }
            Cmd.SocketBack -> {
                val phase = _state.value.phase
                if (lostSocketInCall && (phase == CallPhase.CONNECTING || phase == CallPhase.ACTIVE)) {
                    lostSocketInCall = false
                    sendPing()
                    // сеть могла смениться (Wi-Fi/LTE): перезапускаем ICE на тех соединениях, где offer создаём мы
                    for (link in links.values.toList()) if (link.offerer) sendOfferSafe(link, iceRestart = true)
                    if (_state.value.isGroup) resyncGroup()
                }
            }
            Cmd.ClearEnded -> if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        }
    }

    private suspend fun onStart(cmd: Cmd.Start) {
        if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        if (_state.value.phase != CallPhase.IDLE) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showEnded("no_permission", cmd.chatId, cmd.peer)
            return
        }
        known[cmd.peer.id] = cmd.peer
        clientCallId = UUID.randomUUID().toString()
        pendingCancelClientId = null
        isCaller = true
        _state.value = CallUiState(
            phase = CallPhase.OUTGOING,
            chatId = cmd.chatId,
            peer = cmd.peer,
            isIncoming = false,
            isVideoCall = cmd.video,
            speakerOn = cmd.video
        )
        val ok = send(
            "call_invite", "chat_id" to cmd.chatId,
            "media" to if (cmd.video) "video" else "audio",
            "client_call_id" to clientCallId
        )
        if (!ok) {
            finishLocally("no_connection")
            return
        }
        beginAudioSession(cmd.video)
        sounds.startRingback()
        CallService.sync(context)
        loadIceServers()
    }

    private suspend fun onStartGroup(cmd: Cmd.StartGroup) {
        if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        if (_state.value.phase != CallPhase.IDLE) return
        seed(cmd.users)
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showEnded("no_permission", cmd.chatId, null)
            return
        }
        clientCallId = UUID.randomUUID().toString()
        pendingCancelClientId = null
        isCaller = true
        _state.value = CallUiState(
            phase = CallPhase.OUTGOING,
            chatId = cmd.chatId,
            isIncoming = false,
            isVideoCall = cmd.video,
            speakerOn = cmd.video,
            isGroup = true,
            chatTitle = cmd.title
        )
        val ok = send(
            "call_invite", "chat_id" to cmd.chatId,
            "media" to if (cmd.video) "video" else "audio",
            "client_call_id" to clientCallId
        )
        if (!ok) {
            finishLocally("no_connection")
            return
        }
        beginAudioSession(cmd.video)
        sounds.startRingback()
        CallService.sync(context)
        loadIceServers()
        ensureAudioTrack()
        if (cmd.video && hasPermission(Manifest.permission.CAMERA)) startCameraCapture()
    }

    private suspend fun onJoinGroup(cmd: Cmd.JoinGroup) {
        if (_state.value.phase == CallPhase.ENDED) _state.value = CallUiState()
        if (_state.value.phase != CallPhase.IDLE) return
        val info = _groupCalls.value[cmd.chatId] ?: return
        seed(cmd.users)
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showEnded("no_permission", cmd.chatId, null)
            return
        }
        val video = info.media == "video"
        isCaller = false
        _state.value = CallUiState(
            phase = CallPhase.CONNECTING,
            callId = info.callId,
            chatId = cmd.chatId,
            isIncoming = false,
            isVideoCall = video,
            speakerOn = video,
            isGroup = true,
            chatTitle = cmd.title,
            maxParticipants = info.maxParticipants
        )
        enterGroupCall(info.callId, video)
    }

    /** Вход в групповой звонок: подготовить звук/камеру и отправить call_accept. Соединения создаст call_accepted. */
    private suspend fun enterGroupCall(callId: String, video: Boolean) {
        beginAudioSession(video)
        loadIceServers()
        ensureAudioTrack()
        if (video && hasPermission(Manifest.permission.CAMERA)) startCameraCapture()
        send("call_accept", "call_id" to callId)
        CallService.sync(context)
        startPing()
    }

    private suspend fun onAccept() {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING || s.callId == null) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        sounds.stopAll()
        CallNotifications.cancelIncoming(context)
        isCaller = false
        _state.update { it.copy(phase = CallPhase.CONNECTING, speakerOn = it.isVideoCall) }
        if (s.isGroup) {
            enterGroupCall(s.callId, s.isVideoCall)
            return
        }
        beginAudioSession(s.isVideoCall)
        loadIceServers()
        val peerId = s.peer?.id ?: return
        makeLink(peerId, offerer = false)
        if (s.isVideoCall && hasPermission(Manifest.permission.CAMERA)) startCameraCapture()
        send("call_accept", "call_id" to s.callId)
        CallService.sync(context)
        startPing()
        startWatchdog(40_000)
    }

    private fun onHangUp() {
        val s = _state.value
        when (s.phase) {
            CallPhase.IDLE, CallPhase.ENDED -> Unit
            CallPhase.INCOMING -> {
                send("call_reject", "call_id" to s.callId)
                finishLocally("rejected")
            }
            else -> {
                // В группе call_end значит «выйти»: остальные продолжают разговор.
                if (s.callId != null) send("call_end", "call_id" to s.callId)
                else pendingCancelClientId = clientCallId // call_ringing ещё не пришёл: отменим, как узнаем id
                finishLocally(if (s.phase == CallPhase.OUTGOING) "cancelled" else "hangup")
            }
        }
    }

    // ================= Участники (групповые плитки) =================

    private fun parsePeer(o: JSONObject) = CallPeer(
        id = o.optLong("id"),
        username = o.optString("username", "?"),
        avatar = o.optString("avatar").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun parsePeers(json: JSONObject, key: String): List<CallPeer> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<CallPeer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue // в call_participant_joined здесь числа, не объекты
            out += parsePeer(o)
        }
        return out
    }

    private fun seed(users: List<CallPeer>) {
        for (u in users) known[u.id] = u
    }

    private fun lookup(id: Long): CallPeer = known[id] ?: CallPeer(id, "#$id")

    private fun addMember(id: Long, info: CallPeer? = null) {
        if (id == myId) return
        if (info != null) known[id] = info
        if (_state.value.members.any { it.id == id }) return
        val p = lookup(id)
        _state.update { it.copy(members = it.members + CallMember(p.id, p.username, p.avatar)) }
        if (p.username.startsWith("#")) resolveNames()
    }

    private fun patchMember(id: Long, f: (CallMember) -> CallMember) {
        if (_state.value.members.none { it.id == id }) return
        _state.update { s -> s.copy(members = s.members.map { if (it.id == id) f(it) else it }) }
    }

    private fun dropMember(id: Long) {
        closeLink(id)
        _state.update { s -> s.copy(members = s.members.filterNot { it.id == id }) }
    }

    /** Имена тех, кого мы ещё не знаем: берём из карточки чата. */
    private fun resolveNames() {
        val chatId = _state.value.chatId ?: return
        scope.launch {
            val r = repository.getChatDetails(chatId)
            if (r is Resource.Success) {
                for (p in r.data.participants) {
                    val u = p.user ?: continue
                    known[u.id] = CallPeer(u.id, u.username, u.avatar)
                }
            }
            _state.update { s ->
                s.copy(members = s.members.map { m -> lookup(m.id).let { p -> m.copy(username = p.username, avatar = p.avatar) } })
            }
        }
    }

    private fun applyGroupState(chatId: Long, active: Boolean, callId: String?, media: String?, participants: List<Long>, max: Int?) {
        _groupCalls.update { cur ->
            if (active && callId != null) cur + (chatId to GroupCallInfo(callId, chatId, media ?: "audio", participants, max ?: 6))
            else cur - chatId
        }
    }

    /** После обрыва сокета мы могли пропустить входы/выходы: сверяем список с сервером. */
    private suspend fun resyncGroup() {
        val s = _state.value
        val chatId = s.chatId ?: return
        val callId = s.callId ?: return
        val r = repository.getActiveCall(chatId) ?: return
        if (_state.value.callId != callId) return
        if (!r.active || r.callId != callId) return // call_ended / call_error сообщат сами
        val ids = r.participants.filter { it != myId }.toSet()
        for (id in links.keys.toList()) if (id !in ids) dropMember(id)
        for (id in ids) {
            if (links.containsKey(id)) continue
            // вошёл, пока нас не было на связи: его offer мог потеряться, предлагаем сами
            addMember(id)
            val link = makeLink(id, offerer = true) ?: continue
            sendOfferSafe(link, iceRestart = false)
        }
    }

    private fun linkFor(e: WsEvent.CallSignal, create: Boolean): Link? {
        val s = _state.value
        if (!s.isGroup) return links.values.firstOrNull()
        val id = e.userId ?: return null
        links[id]?.let { return it }
        if (!create) return null
        // offer от того, чей call_participant_joined мы ещё не видели: создаём соединение на лету
        addMember(id)
        return makeLink(id, offerer = false)
    }

    // ================= Сигналы сервера =================

    private suspend fun onSignal(e: WsEvent.CallSignal) {
        val s = _state.value
        when (e.type) {
            "call_group_state" -> {
                val chatId = e.chatId ?: return
                val ids = ArrayList<Long>()
                e.json.optJSONArray("participants")?.let { arr -> for (i in 0 until arr.length()) ids += arr.optLong(i) }
                applyGroupState(
                    chatId, e.json.optBoolean("active", false), e.callId,
                    e.json.optString("media").takeIf { it.isNotBlank() }, ids,
                    if (e.json.has("max_participants")) e.json.optInt("max_participants", 6) else null
                )
            }

            "call_incoming" -> {
                val callId = e.callId ?: return
                if (callId == s.callId) return // дубликат события
                if (s.phase == CallPhase.ENDED) _state.value = CallUiState()
                val group = e.json.optBoolean("group", false)
                if (_state.value.phase != CallPhase.IDLE) {
                    if (!group) send("call_reject", "call_id" to callId) // уже заняты на этом устройстве
                    return
                }
                val caller = e.json.optJSONObject("caller")?.let { parsePeer(it) }
                if (group) {
                    val inCall = parsePeers(e.json, "participants")
                    seed(inCall)
                    if (caller != null) known[caller.id] = caller
                    isCaller = false
                    _state.value = CallUiState(
                        phase = CallPhase.INCOMING,
                        callId = callId,
                        chatId = e.chatId,
                        peer = caller,
                        isIncoming = true,
                        isVideoCall = e.json.optString("media") == "video",
                        isGroup = true,
                        chatTitle = e.json.optString("chat_title"),
                        members = inCall.filter { it.id != myId }.map { CallMember(it.id, it.username, it.avatar) },
                        maxParticipants = e.json.optInt("max_participants", 6)
                    )
                } else {
                    if (caller == null) return
                    known[caller.id] = caller
                    isCaller = false
                    _state.value = CallUiState(
                        phase = CallPhase.INCOMING,
                        callId = callId,
                        chatId = e.chatId,
                        peer = caller,
                        isIncoming = true,
                        isVideoCall = e.json.optString("media") == "video"
                    )
                }
                sounds.startRingtone()
                CallNotifications.showIncoming(context, _state.value, prefs.userLanguage)
            }

            "call_ringing" -> {
                val cid = e.json.optString("client_call_id")
                if (pendingCancelClientId != null && cid == pendingCancelClientId) {
                    pendingCancelClientId = null
                    e.callId?.let { send("call_end", "call_id" to it) }
                    return
                }
                if (s.phase == CallPhase.OUTGOING && s.callId == null && e.json.optString("client_call_id") == clientCallId) {
                    _state.update { it.copy(callId = e.callId) }
                }
            }

            "call_accepted" -> {
                if (e.callId != s.callId) return
                if (s.isGroup) {
                    if (s.phase == CallPhase.INCOMING) {
                        finishLocally("answered_elsewhere") // вошли с другого устройства
                    } else if (s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE) {
                        // Мы вошли: соединяемся со всеми, кто уже в звонке; offer создаём мы.
                        if (e.json.has("max_participants")) {
                            val max = e.json.optInt("max_participants", 6)
                            _state.update { it.copy(maxParticipants = max) }
                        }
                        val existing = parsePeers(e.json, "participants")
                        for (u in existing) {
                            addMember(u.id, u)
                            val link = makeLink(u.id, offerer = true) ?: continue
                            sendOfferSafe(link, iceRestart = false)
                        }
                        if (existing.isEmpty() && _state.value.phase == CallPhase.CONNECTING) {
                            // в звонке больше никого: ждём остальных, таймер пошёл
                            _state.update { it.copy(phase = CallPhase.ACTIVE, connectedAtMs = SystemClock.elapsedRealtime()) }
                        }
                    }
                    return
                }
                if (s.phase == CallPhase.INCOMING) {
                    finishLocally("answered_elsewhere") // приняли на другом устройстве
                } else if (s.phase == CallPhase.OUTGOING && isCaller) {
                    sounds.stopAll()
                    _state.update { it.copy(phase = CallPhase.CONNECTING) }
                    CallService.sync(context)
                    startPing()
                    startWatchdog(40_000)
                    val peerId = s.peer?.id ?: return
                    val link = makeLink(peerId, offerer = true) ?: return
                    if (s.isVideoCall && hasPermission(Manifest.permission.CAMERA)) startCameraCapture()
                    sendOffer(link, iceRestart = false)
                }
            }

            "call_participant_joined" -> {
                if (e.callId != s.callId || !s.isGroup) return
                val id = e.userId ?: return
                if (id == myId) return
                if (s.phase == CallPhase.OUTGOING) {
                    sounds.stopAll()
                    _state.update { it.copy(phase = CallPhase.CONNECTING) }
                    CallService.sync(context)
                    startPing()
                }
                addMember(id)
                makeLink(id, offerer = false) // новичок создаёт offer, мы отвечаем
            }

            "call_participant_left" -> {
                if (e.callId != s.callId || !s.isGroup) return
                val id = e.userId ?: return
                if (id != myId) dropMember(id)
            }

            "call_declined" -> {
                if (e.callId == s.callId && s.phase == CallPhase.INCOMING && e.userId == myId) {
                    finishLocally("declined_elsewhere") // отказались на другом нашем устройстве
                }
            }

            "call_offer" -> if (e.callId == s.callId) {
                val link = linkFor(e, create = true)
                if (link != null) onRemoteOffer(link, e.json.optString("sdp"))
            }
            "call_answer" -> if (e.callId == s.callId) {
                val link = linkFor(e, create = false)
                if (link != null) onRemoteAnswer(link, e.json.optString("sdp"))
            }
            "call_ice" -> if (e.callId == s.callId) {
                val link = linkFor(e, create = false)
                if (link != null) onRemoteCandidate(link, e.json)
            }

            "call_media_state" -> if (e.callId == s.callId) {
                if (s.isGroup) {
                    val id = e.userId ?: return
                    patchMember(id) { m ->
                        m.copy(
                            videoOn = if (e.json.has("video")) e.json.optBoolean("video") else m.videoOn,
                            screenShare = if (e.json.has("screen_share")) e.json.optBoolean("screen_share") else m.screenShare,
                            muted = if (e.json.has("audio_muted")) e.json.optBoolean("audio_muted") else m.muted
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            remoteVideoOn = if (e.json.has("video")) e.json.optBoolean("video") else it.remoteVideoOn,
                            remoteScreenShare = if (e.json.has("screen_share")) e.json.optBoolean("screen_share") else it.remoteScreenShare,
                            remoteMuted = if (e.json.has("audio_muted")) e.json.optBoolean("audio_muted") else it.remoteMuted
                        )
                    }
                }
            }

            "call_ended" -> {
                if (e.callId != null && e.callId == s.callId && s.phase != CallPhase.IDLE && s.phase != CallPhase.ENDED) {
                    val reason = when (e.json.optString("status")) {
                        "ended" -> "hangup"
                        else -> e.json.optString("reason").ifBlank { e.json.optString("status") }
                    }
                    finishLocally(reason)
                }
            }

            "call_error" -> {
                val code = e.json.optString("code")
                // Ошибка нашей попытки позвонить (busy, group_calls_unsupported, ...)
                if (s.phase == CallPhase.OUTGOING && s.callId == null) finishLocally(code)
                // Не смогли войти в групповой звонок (мест нет, звонок уже закончился, заняты другим)
                else if (s.isGroup && s.phase == CallPhase.CONNECTING && links.isEmpty() && e.callId == s.callId &&
                    (code == "call_full" || code == "call_not_found" || code == "already_in_call")
                ) finishLocally(code)
                // Сервер считает звонок завершённым (например, мы слишком долго были без связи)
                else if (code == "call_not_found" && e.callId != null && e.callId == s.callId &&
                    (s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE)
                ) finishLocally("connection_lost")
            }
        }
    }

    // ================= WebRTC =================

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private suspend fun loadIceServers() {
        val fetched: List<IceServerDto> = repository.getIceServers()
        val list = fetched.filter { it.urls.isNotEmpty() }.map {
            val b = PeerConnection.IceServer.builder(it.urls)
            if (!it.username.isNullOrBlank()) b.setUsername(it.username)
            if (!it.credential.isNullOrBlank()) b.setPassword(it.credential)
            b.createIceServer()
        }
        iceServers = list.ifEmpty {
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
    }

    /** Один микрофонный трек на все соединения звонка. */
    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let { return it }
        val f = ensureFactory()
        val source = f.createAudioSource(MediaConstraints())
        audioSource = source
        return f.createAudioTrack("audio0", source).also {
            it.setEnabled(!_state.value.micMuted)
            audioTrack = it
        }
    }

    /** Создаёт соединение с участником [id]. [offerer] = true, если offer создаём мы. */
    private fun makeLink(id: Long, offerer: Boolean): Link? {
        links[id]?.let { return it }
        val f = ensureFactory()
        if (iceServers.isEmpty()) {
            iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val link = Link(id, offerer)
        val connection = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                if (p0 != null) inbox.trySend(Cmd.IceState(id, p0))
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(p0: IceCandidate?) {
                if (p0 != null) inbox.trySend(Cmd.LocalCandidate(id, p0))
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    _remoteVideos.update { it + (id to track) }
                    if (!_state.value.isGroup) _remoteVideo.value = track
                }
            }
        }) ?: throw IllegalStateException("createPeerConnection returned null")
        link.pc = connection
        links[id] = link

        connection.addTrack(ensureAudioTrack(), listOf("call"))
        // Offerer всегда создаёт видео-трансивер (даже в аудиозвонке): включение камеры или экрана
        // потом делается подменой трека без повторного обмена SDP.
        // У принимающего трансивер, созданный через addTransceiver, WebRTC при применении чужого offer
        // НЕ переиспользует (только созданные через addTrack), поэтому он берёт трансивер из offer
        // (см. attachVideoSenderFromOffer).
        if (offerer) {
            val transceiver = connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("call"))
            )
            link.videoSender = transceiver.sender
            (screenTrack ?: cameraTrack)?.let { link.videoSender?.setTrack(it, false) }
        }
        // группа: соединение, которое так и не поднялось, помечаем «нет связи» (остальные продолжают)
        if (_state.value.isGroup) startLinkTimer(link, 40_000)
        return link
    }

    private fun closeLink(id: Long) {
        val link = links.remove(id) ?: return
        link.timer?.cancel()
        runCatching { link.pc?.close() }
        runCatching { link.pc?.dispose() }
        link.pc = null
        link.videoSender = null
        link.pending.clear()
        _remoteVideos.update { it - id }
    }

    private suspend fun sendOfferSafe(link: Link, iceRestart: Boolean) {
        try {
            sendOffer(link, iceRestart)
        } catch (e: Exception) {
            Log.w(tag, "offer to ${link.id} failed", e) // одна неудача не должна рвать весь групповой звонок
        }
    }

    private suspend fun sendOffer(link: Link, iceRestart: Boolean) {
        val connection = link.pc ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = connection.createOfferSuspend(constraints)
        connection.setLocalSuspend(offer)
        sendTo(link, "call_offer", "sdp" to offer.description)
    }

    /** Столкновение офферов: в личном звонке «неучтивый» звонящий, в группе — тот, у кого меньше id. */
    private fun isImpolite(link: Link): Boolean = if (_state.value.isGroup) myId < link.id else isCaller

    private suspend fun onRemoteOffer(link: Link, sdp: String) {
        val connection = link.pc ?: return
        if (sdp.isBlank()) return
        if (connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER && isImpolite(link)) return
        connection.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, sdp))
        attachVideoSenderFromOffer(link, connection)
        drainCandidates(link)
        val answer = connection.createAnswerSuspend(MediaConstraints())
        connection.setLocalSuspend(answer)
        sendTo(link, "call_answer", "sdp" to answer.description)
        sendMediaState()
    }

    /** Принимающая сторона: берёт видео-трансивер из offer, разрешает отправку и подключает текущий локальный трек. */
    private fun attachVideoSenderFromOffer(link: Link, connection: PeerConnection) {
        if (link.videoSender != null) return
        val transceiver = connection.transceivers.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        } ?: return
        transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        link.videoSender = transceiver.sender
        (screenTrack ?: cameraTrack)?.let { link.videoSender?.setTrack(it, false) }
    }

    private suspend fun onRemoteAnswer(link: Link, sdp: String) {
        val connection = link.pc ?: return
        if (sdp.isBlank() || connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        connection.setRemoteSuspend(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        drainCandidates(link)
        sendMediaState()
    }

    private fun onRemoteCandidate(link: Link, json: JSONObject) {
        val line = json.optString("candidate")
        if (line.isBlank()) return
        val candidate = IceCandidate(
            json.optString("sdp_mid").takeIf { it.isNotBlank() && it != "null" },
            json.optInt("sdp_mline_index", 0),
            line
        )
        val connection = link.pc
        if (connection == null || connection.remoteDescription == null) link.pending += candidate
        else connection.addIceCandidate(candidate)
    }

    private fun drainCandidates(link: Link) {
        val connection = link.pc ?: return
        link.pending.forEach { connection.addIceCandidate(it) }
        link.pending.clear()
    }

    private suspend fun onIceState(linkId: Long, state: PeerConnection.IceConnectionState) {
        val link = links[linkId] ?: return
        val group = _state.value.isGroup
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                disconnectWatchdog?.cancel()
                link.timer?.cancel()
                link.iceRestartDone = false
                if (group) patchMember(linkId) { it.copy(link = LinkState.CONNECTED) }
                if (_state.value.phase == CallPhase.CONNECTING) {
                    _state.update { it.copy(phase = CallPhase.ACTIVE, connectedAtMs = SystemClock.elapsedRealtime()) }
                }
                sendMediaState()
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                if (group) startLinkTimer(link, 25_000) else startWatchdog()
            }
            PeerConnection.IceConnectionState.FAILED -> {
                if (group) startLinkTimer(link, 25_000) else startWatchdog()
                if (link.offerer && !link.iceRestartDone) {
                    link.iceRestartDone = true
                    sendOfferSafe(link, iceRestart = true)
                }
            }
            else -> Unit
        }
    }

    /** Группа: соединение, которое остаётся лежать, помечается «нет связи»; звонок и остальные соединения живут. */
    private fun startLinkTimer(link: Link, timeoutMs: Long) {
        link.timer?.cancel()
        link.timer = scope.launch {
            delay(timeoutMs)
            val st = link.pc?.iceConnectionState()
            if (st == PeerConnection.IceConnectionState.CONNECTED || st == PeerConnection.IceConnectionState.COMPLETED) return@launch
            patchMember(link.id) { it.copy(link = LinkState.LOST) }
        }
    }

    /** Сигнал «я на связи»: по нему сервер понимает, что звонок жив, даже если сокет переподключался. */
    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                sendPing()
                delay(8_000)
            }
        }
    }

    private fun sendPing() {
        _state.value.callId?.let { send("call_ping", "call_id" to it) }
    }

    private fun startWatchdog(timeoutMs: Long = 25_000) {
        if (_state.value.isGroup) return // в группе соединения отслеживаются по отдельности, быть одному в звонке нормально
        disconnectWatchdog?.cancel()
        disconnectWatchdog = scope.launch {
            delay(timeoutMs)
            if (_state.value.phase == CallPhase.ACTIVE || _state.value.phase == CallPhase.CONNECTING) {
                _state.value.callId?.let { send("call_end", "call_id" to it) }
                finishLocally("connection_lost")
            }
        }
    }

    // ---- камера ----

    private fun setVideoTrackOnAll(track: VideoTrack?) {
        for (link in links.values) link.videoSender?.setTrack(track, false)
    }

    private fun startCameraCapture() {
        if (cameraCapturer != null) return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        stopScreen(restoreCamera = false)
        val f = ensureFactory()
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        val capturer = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("CameraThread", eglBase.eglBaseContext)
        val source = f.createVideoSource(false)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val track = f.createVideoTrack("camera0", source)
        cameraCapturer = capturer
        cameraHelper = helper
        cameraSource = source
        cameraTrack = track
        setVideoTrackOnAll(track)
        _localVideo.value = track
        _state.update { it.copy(cameraOn = true, frontCamera = enumerator.isFrontFacing(name)) }
        CallService.sync(context)
        sendMediaState()
    }

    private fun stopCameraCapture() {
        val hadCamera = cameraCapturer != null
        runCatching { cameraCapturer?.stopCapture() }
        cameraCapturer?.dispose(); cameraCapturer = null
        if (_localVideo.value === cameraTrack) {
            setVideoTrackOnAll(null)
            _localVideo.value = null
        }
        cameraTrack?.dispose(); cameraTrack = null
        cameraSource?.dispose(); cameraSource = null
        cameraHelper?.dispose(); cameraHelper = null
        if (hadCamera) _state.update { it.copy(cameraOn = false) }
    }

    /** Можно ли сейчас включать камеру/экран: идёт соединение, а в группе ещё и пока мы ждём остальных. */
    private fun inMedia(): Boolean {
        val s = _state.value
        return s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE || (s.isGroup && s.phase == CallPhase.OUTGOING)
    }

    private fun onToggleCamera() {
        if (!inMedia()) return
        if (_state.value.cameraOn) {
            stopCameraCapture()
            sendMediaState()
        } else startCameraCapture()
    }

    // ---- демонстрация экрана ----

    private fun onStartScreen(permissionData: Intent) {
        if (!inMedia()) return
        if (_state.value.screenSharing) return
        stopCameraCapture()
        val f = ensureFactory()
        val capturer = ScreenCapturerAndroid(permissionData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { inbox.trySend(Cmd.StopScreen) } // пользователь остановил запись из шторки
        })
        val helper = SurfaceTextureHelper.create("ScreenThread", eglBase.eglBaseContext)
        val source = f.createVideoSource(true)
        capturer.initialize(helper, context, source.capturerObserver)
        val metrics = context.resources.displayMetrics
        val scale = 1280f / maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
        val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
        capturer.startCapture(w, h, 15)
        val track = f.createVideoTrack("screen0", source)
        screenCapturer = capturer
        screenHelper = helper
        screenSource = source
        screenTrack = track
        setVideoTrackOnAll(track)
        // для скринкаста важнее чёткость, чем частота кадров
        for (link in links.values) {
            val sender = link.videoSender ?: continue
            val params = sender.parameters
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            sender.parameters = params
        }
        _localVideo.value = track
        _state.update { it.copy(screenSharing = true) }
        sendMediaState()
    }

    private fun stopScreen(restoreCamera: Boolean = true) {
        if (screenCapturer == null) return
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose(); screenCapturer = null
        if (_localVideo.value === screenTrack) {
            setVideoTrackOnAll(null)
            _localVideo.value = null
        }
        screenTrack?.dispose(); screenTrack = null
        screenSource?.dispose(); screenSource = null
        screenHelper?.dispose(); screenHelper = null
        _state.update { it.copy(screenSharing = false) }
        CallService.sync(context)
        if (restoreCamera && _state.value.isVideoCall && inMedia()) startCameraCapture() else sendMediaState()
    }

    // ================= Вспомогательное =================

    private fun sendMediaState() {
        val s = _state.value
        val id = s.callId ?: return
        if (links.isEmpty()) return
        // без to_user_id: в группе сервер разошлёт состояние всем остальным участникам
        send(
            "call_media_state", "call_id" to id,
            "video" to (s.cameraOn || s.screenSharing),
            "screen_share" to s.screenSharing,
            "audio_muted" to s.micMuted
        )
    }

    /** Сигнал конкретному участнику: в группе добавляет to_user_id, в личном звонке нет. */
    private fun sendTo(link: Link, type: String, vararg fields: Pair<String, Any?>): Boolean {
        val s = _state.value
        val all = ArrayList<Pair<String, Any?>>()
        all.add("call_id" to s.callId)
        if (s.isGroup) all.add("to_user_id" to link.id)
        all.addAll(fields)
        return send(type, *all.toTypedArray())
    }

    private fun send(type: String, vararg fields: Pair<String, Any?>): Boolean {
        val json = JSONObject().put("type", type)
        fields.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        return webSocketManager.sendCallEvent(json)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun beginAudioSession(video: Boolean) {
        if (savedAudioMode == null) savedAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(video)
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
    }

    private fun showEnded(reason: String, chatId: Long?, peer: CallPeer?, isGroup: Boolean = false, title: String = "") {
        _state.value = CallUiState(phase = CallPhase.ENDED, chatId = chatId, peer = peer, endReason = reason, isGroup = isGroup, chatTitle = title)
        scope.launch { delay(2500); inbox.trySend(Cmd.ClearEnded) }
    }

    /** Освобождает всё WebRTC/аудио и показывает причину завершения. */
    private fun finishLocally(reason: String) {
        val before = _state.value
        disconnectWatchdog?.cancel()
        pingJob?.cancel()
        lostSocketInCall = false
        sounds.stopAll()
        CallNotifications.cancelIncoming(context)

        stopScreen(restoreCamera = false)
        stopCameraCapture()
        _localVideo.value = null
        _remoteVideo.value = null
        for (id in links.keys.toList()) closeLink(id)
        links.clear()
        _remoteVideos.value = emptyMap()
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null

        savedAudioMode?.let {
            setSpeaker(false)
            @Suppress("DEPRECATION")
            audioManager.mode = it
        }
        savedAudioMode = null
        clientCallId = null

        CallService.stop(context)
        showEnded(reason, before.chatId, before.peer, before.isGroup, before.chatTitle)
    }
}

// ---- suspend-обёртки над SdpObserver ----

private class SdpResult(private val onOk: (SessionDescription?) -> Unit, private val onFail: (String?) -> Unit) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) = onOk(sdp)
    override fun onSetSuccess() = onOk(null)
    override fun onCreateFailure(error: String?) = onFail(error)
    override fun onSetFailure(error: String?) = onFail(error)
}

private suspend fun PeerConnection.createOfferSuspend(c: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(SdpResult({ cont.resume(it!!) }, { cont.resumeWithException(IllegalStateException(it)) }), c)
    }

private suspend fun PeerConnection.createAnswerSuspend(c: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(SdpResult({ cont.resume(it!!) }, { cont.resumeWithException(IllegalStateException(it)) }), c)
    }

private suspend fun PeerConnection.setLocalSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(SdpResult({ cont.resume(Unit) }, { cont.resumeWithException(IllegalStateException(it)) }), sdp)
    }

private suspend fun PeerConnection.setRemoteSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(SdpResult({ cont.resume(Unit) }, { cont.resumeWithException(IllegalStateException(it)) }), sdp)
    }
