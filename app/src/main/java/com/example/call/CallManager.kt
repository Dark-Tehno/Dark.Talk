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
 * Звонки 1-на-1 по WebRTC. Сервер только пересылает сигналинг (call_* по /ws/chats/),
 * аудио, видео и экран идут напрямую между устройствами.
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

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideo

    /** Нужен UI для SurfaceViewRenderer. */
    val eglBase: EglBase by lazy { EglBase.create() }

    private val sounds = CallSounds(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---- WebRTC ----
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null

    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null

    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null

    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var clientCallId: String? = null
    /** client_call_id отменённого до call_ringing звонка; не сбрасывается в finishLocally. */
    private var pendingCancelClientId: String? = null
    private var isCaller = false
    private var iceRestartDone = false
    private var disconnectWatchdog: Job? = null
    private var pingJob: Job? = null
    private var lostSocketInCall = false
    private var savedAudioMode: Int? = null

    private sealed interface Cmd {
        data class Start(val chatId: Long, val peer: CallPeer, val video: Boolean) : Cmd
        data object Accept : Cmd
        data object Reject : Cmd
        data object HangUp : Cmd
        data class Signal(val event: WsEvent.CallSignal) : Cmd
        data class IceState(val state: PeerConnection.IceConnectionState) : Cmd
        data class LocalCandidate(val candidate: IceCandidate) : Cmd
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
            is Cmd.IceState -> onIceState(cmd.state)
            is Cmd.LocalCandidate -> {
                val id = _state.value.callId ?: return
                send(
                    "call_ice", "call_id" to id,
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
                    // сеть могла смениться (Wi-Fi/LTE): перезапускаем ICE
                    if (isCaller && pc != null) sendOffer(iceRestart = true)
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

    private suspend fun onAccept() {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING || s.callId == null) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        sounds.stopAll()
        CallNotifications.cancelIncoming(context)
        isCaller = false
        _state.update { it.copy(phase = CallPhase.CONNECTING, speakerOn = it.isVideoCall) }
        beginAudioSession(s.isVideoCall)
        loadIceServers()
        buildPeerConnection(startCamera = s.isVideoCall && hasPermission(Manifest.permission.CAMERA))
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
                if (s.callId != null) send("call_end", "call_id" to s.callId)
                else pendingCancelClientId = clientCallId // call_ringing ещё не пришёл: отменим, как узнаем id
                finishLocally(if (s.phase == CallPhase.OUTGOING) "cancelled" else "hangup")
            }
        }
    }

    private suspend fun onSignal(e: WsEvent.CallSignal) {
        val s = _state.value
        when (e.type) {
            "call_incoming" -> {
                val callId = e.callId ?: return
                if (callId == s.callId) return // дубликат события
                if (s.phase == CallPhase.ENDED) _state.value = CallUiState()
                if (_state.value.phase != CallPhase.IDLE) {
                    send("call_reject", "call_id" to callId) // уже заняты на этом устройстве
                    return
                }
                val caller = e.json.optJSONObject("caller") ?: return
                val peer = CallPeer(
                    id = caller.optLong("id"),
                    username = caller.optString("username", "?"),
                    avatar = caller.optString("avatar").takeIf { it.isNotBlank() && it != "null" }
                )
                isCaller = false
                _state.value = CallUiState(
                    phase = CallPhase.INCOMING,
                    callId = callId,
                    chatId = e.chatId,
                    peer = peer,
                    isIncoming = true,
                    isVideoCall = e.json.optString("media") == "video"
                )
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
                if (s.phase == CallPhase.INCOMING) {
                    finishLocally("answered_elsewhere") // приняли на другом устройстве
                } else if (s.phase == CallPhase.OUTGOING && isCaller) {
                    sounds.stopAll()
                    _state.update { it.copy(phase = CallPhase.CONNECTING) }
                    CallService.sync(context)
                    startPing()
                    startWatchdog(40_000)
                    buildPeerConnection(startCamera = s.isVideoCall && hasPermission(Manifest.permission.CAMERA))
                    sendOffer(iceRestart = false)
                }
            }

            "call_offer" -> if (e.callId == s.callId) onRemoteOffer(e.json.optString("sdp"))
            "call_answer" -> if (e.callId == s.callId) onRemoteAnswer(e.json.optString("sdp"))
            "call_ice" -> if (e.callId == s.callId) onRemoteCandidate(e.json)

            "call_media_state" -> if (e.callId == s.callId) {
                _state.update {
                    it.copy(
                        remoteVideoOn = if (e.json.has("video")) e.json.optBoolean("video") else it.remoteVideoOn,
                        remoteScreenShare = if (e.json.has("screen_share")) e.json.optBoolean("screen_share") else it.remoteScreenShare,
                        remoteMuted = if (e.json.has("audio_muted")) e.json.optBoolean("audio_muted") else it.remoteMuted
                    )
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

    private fun buildPeerConnection(startCamera: Boolean) {
        val f = ensureFactory()
        if (iceServers.isEmpty()) {
            iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                if (p0 != null) inbox.trySend(Cmd.IceState(p0))
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(p0: IceCandidate?) {
                if (p0 != null) inbox.trySend(Cmd.LocalCandidate(p0))
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) _remoteVideo.value = track
            }
        }) ?: throw IllegalStateException("createPeerConnection returned null")
        pc = connection

        val source = f.createAudioSource(MediaConstraints())
        audioSource = source
        audioTrack = f.createAudioTrack("audio0", source).also {
            it.setEnabled(!_state.value.micMuted)
            connection.addTrack(it, listOf("call"))
        }
        // Звонящий создаёт видео-трансивер всегда (даже в аудиозвонке): включение камеры или экрана
        // потом делается подменой трека без повторного обмена SDP.
        // У принимающего трансивер, созданный через addTransceiver, WebRTC при применении чужого offer
        // НЕ переиспользует (только созданные через addTrack), поэтому он берёт трансивер из offer
        // (см. attachVideoSenderFromOffer).
        if (isCaller) {
            val transceiver = connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("call"))
            )
            videoSender = transceiver.sender
        }
        if (startCamera) startCameraCapture()
    }

    private suspend fun sendOffer(iceRestart: Boolean) {
        val connection = pc ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = connection.createOfferSuspend(constraints)
        connection.setLocalSuspend(offer)
        send("call_offer", "call_id" to _state.value.callId, "sdp" to offer.description)
    }

    private suspend fun onRemoteOffer(sdp: String) {
        val connection = pc ?: return
        if (sdp.isBlank()) return
        // Столкновение офферов: звонящий (impolite) игнорирует чужой, принимающий (polite) откатывается сам.
        if (connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER && isCaller) return
        connection.setRemoteSuspend(SessionDescription(SessionDescription.Type.OFFER, sdp))
        attachVideoSenderFromOffer(connection)
        drainCandidates()
        val answer = connection.createAnswerSuspend(MediaConstraints())
        connection.setLocalSuspend(answer)
        send("call_answer", "call_id" to _state.value.callId, "sdp" to answer.description)
        sendMediaState()
    }

    /** Принимающая сторона: берёт видео-трансивер из offer, разрешает отправку и подключает текущий локальный трек. */
    private fun attachVideoSenderFromOffer(connection: PeerConnection) {
        if (videoSender != null) return
        val transceiver = connection.transceivers.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        } ?: return
        transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        videoSender = transceiver.sender
        (screenTrack ?: cameraTrack)?.let { videoSender?.setTrack(it, false) }
    }

    private suspend fun onRemoteAnswer(sdp: String) {
        val connection = pc ?: return
        if (sdp.isBlank() || connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        connection.setRemoteSuspend(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        drainCandidates()
        sendMediaState()
    }

    private fun onRemoteCandidate(json: JSONObject) {
        val line = json.optString("candidate")
        if (line.isBlank()) return
        val candidate = IceCandidate(
            json.optString("sdp_mid").takeIf { it.isNotBlank() && it != "null" },
            json.optInt("sdp_mline_index", 0),
            line
        )
        val connection = pc
        if (connection == null || connection.remoteDescription == null) pendingRemoteCandidates += candidate
        else connection.addIceCandidate(candidate)
    }

    private fun drainCandidates() {
        val connection = pc ?: return
        pendingRemoteCandidates.forEach { connection.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private suspend fun onIceState(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                disconnectWatchdog?.cancel()
                iceRestartDone = false
                if (_state.value.phase == CallPhase.CONNECTING) {
                    _state.update { it.copy(phase = CallPhase.ACTIVE, connectedAtMs = SystemClock.elapsedRealtime()) }
                    sendMediaState()
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> startWatchdog()
            PeerConnection.IceConnectionState.FAILED -> {
                startWatchdog()
                if (isCaller && !iceRestartDone) {
                    iceRestartDone = true
                    sendOffer(iceRestart = true)
                }
            }
            else -> Unit
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
        videoSender?.setTrack(track, false)
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
            videoSender?.setTrack(null, false)
            _localVideo.value = null
        }
        cameraTrack?.dispose(); cameraTrack = null
        cameraSource?.dispose(); cameraSource = null
        cameraHelper?.dispose(); cameraHelper = null
        if (hadCamera) _state.update { it.copy(cameraOn = false) }
    }

    private fun onToggleCamera() {
        if (pc == null) return
        if (_state.value.cameraOn) {
            stopCameraCapture()
            sendMediaState()
        } else startCameraCapture()
    }

    // ---- демонстрация экрана ----

    private fun onStartScreen(permissionData: Intent) {
        val connection = pc ?: return
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
        videoSender?.setTrack(track, false)
        // для скринкаста важнее чёткость, чем частота кадров
        videoSender?.let { sender ->
            val params = sender.parameters
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            sender.parameters = params
        }
        _localVideo.value = track
        _state.update { it.copy(screenSharing = true) }
        sendMediaState()
        if (connection.connectionState() == PeerConnection.PeerConnectionState.CLOSED) stopScreen()
    }

    private fun stopScreen(restoreCamera: Boolean = true) {
        if (screenCapturer == null) return
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose(); screenCapturer = null
        if (_localVideo.value === screenTrack) {
            videoSender?.setTrack(null, false)
            _localVideo.value = null
        }
        screenTrack?.dispose(); screenTrack = null
        screenSource?.dispose(); screenSource = null
        screenHelper?.dispose(); screenHelper = null
        _state.update { it.copy(screenSharing = false) }
        CallService.sync(context)
        if (restoreCamera && _state.value.isVideoCall && pc != null) startCameraCapture() else sendMediaState()
    }

    // ================= Вспомогательное =================

    private fun sendMediaState() {
        val s = _state.value
        val id = s.callId ?: return
        if (pc == null) return
        send(
            "call_media_state", "call_id" to id,
            "video" to (s.cameraOn || s.screenSharing),
            "screen_share" to s.screenSharing,
            "audio_muted" to s.micMuted
        )
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

    private fun showEnded(reason: String, chatId: Long?, peer: CallPeer?) {
        _state.value = CallUiState(phase = CallPhase.ENDED, chatId = chatId, peer = peer, endReason = reason)
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
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        videoSender = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        pendingRemoteCandidates.clear()

        savedAudioMode?.let {
            setSpeaker(false)
            @Suppress("DEPRECATION")
            audioManager.mode = it
        }
        savedAudioMode = null
        clientCallId = null
        iceRestartDone = false

        CallService.stop(context)
        showEnded(reason, before.chatId, before.peer)
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
