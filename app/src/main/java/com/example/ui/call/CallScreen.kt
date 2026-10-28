package com.example.ui.call

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.call.CallManager
import com.example.call.CallPhase
import com.example.call.CallService
import com.example.call.CallUiState
import com.example.ui.components.AvatarView
import com.example.util.EnTranslations
import com.example.util.LocalAppStrings
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallGreen = Color(0xFF2ECC71)
private val CallRed = Color(0xFFE5484D)

/** Подключается поверх навигации: полноэкранный звонок или плашка «вернуться к звонку». */
@Composable
fun CallHost(manager: CallManager) {
    val state by manager.state.collectAsState()
    var minimized by remember { mutableStateOf(false) }
    val requestPermissions = rememberCallPermissionRequester()

    LaunchedEffect(state.callId, state.phase == CallPhase.IDLE) {
        if (state.phase == CallPhase.INCOMING || state.phase == CallPhase.OUTGOING) minimized = false
    }
    if (state.phase == CallPhase.IDLE) return

    val canMinimize = state.phase == CallPhase.CONNECTING || state.phase == CallPhase.ACTIVE
    if (minimized && canMinimize) {
        ReturnToCallBanner(state) { minimized = false }
    } else {
        BackHandler(enabled = canMinimize) { minimized = true }
        CallScreen(
            manager = manager,
            state = state,
            onMinimize = if (canMinimize) ({ minimized = true }) else null,
            onAccept = { requestPermissions(state.isVideoCall) { manager.accept() } }
        )
    }
}

@Composable
private fun ReturnToCallBanner(state: CallUiState, onClick: () -> Unit) {
    val en = LocalAppStrings.current === EnTranslations
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(CallGreen)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (if (en) "Call in progress · " else "Идёт звонок · ") + (state.peer?.username ?: "") +
                (if (en) " · tap to return" else " · нажмите, чтобы вернуться"),
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
        )
    }
}

@Composable
fun CallScreen(
    manager: CallManager,
    state: CallUiState,
    onMinimize: (() -> Unit)?,
    onAccept: () -> Unit
) {
    val en = LocalAppStrings.current === EnTranslations
    val context = LocalContext.current
    val localTrack by manager.localVideoTrack.collectAsState()
    val remoteTrack by manager.remoteVideoTrack.collectAsState()
    val showRemote = state.remoteVideoOn && remoteTrack != null && state.phase != CallPhase.ENDED

    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) CallService.startScreenShare(context, data)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF16233F))))
    ) {
        if (showRemote) {
            WebRtcVideo(
                track = remoteTrack, eglContext = manager.eglBase.eglBaseContext,
                modifier = Modifier.fillMaxSize(), fit = state.remoteScreenShare
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AvatarView(avatarUrl = state.peer?.avatar, displayName = state.peer?.username ?: "?", size = 132.dp)
            }
        }

        // Верхняя панель: имя, статус, свернуть
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (onMinimize != null) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = Color.White)
                }
            } else Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.peer?.username ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(statusText(state, en), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                if (state.remoteScreenShare) {
                    Text(if (en) "Sharing screen" else "Показывает экран", color = CallGreen, fontSize = 12.sp)
                }
                if (state.remoteMuted) {
                    Text(if (en) "Microphone off" else "Микрофон выключен", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(48.dp))
        }

        // Своё видео (камера или экран)
        if (localTrack != null && state.phase != CallPhase.ENDED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 92.dp, end = 12.dp)
                    .size(width = 110.dp, height = 156.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            ) {
                WebRtcVideo(
                    track = localTrack, eglContext = manager.eglBase.eglBaseContext,
                    modifier = Modifier.fillMaxSize(), mirror = state.frontCamera && !state.screenSharing,
                    fit = state.screenSharing, overlay = true
                )
                if (state.cameraOn) {
                    IconButton(onClick = { manager.switchCamera() }, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip camera", tint = Color.White)
                    }
                }
            }
        }

        // Кнопки
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp, start = 16.dp, end = 16.dp)) {
            when (state.phase) {
                CallPhase.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    RoundButton(Icons.Default.CallEnd, CallRed, 68.dp, "Decline") { manager.reject() }
                    RoundButton(
                        if (state.isVideoCall) Icons.Default.Videocam else Icons.Default.Call,
                        CallGreen, 68.dp, "Accept", onClick = onAccept
                    )
                }
                CallPhase.ENDED -> Unit
                else -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToggleButton(
                        if (state.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        active = state.micMuted, description = "Mute"
                    ) { manager.toggleMute() }
                    ToggleButton(
                        if (state.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        active = state.cameraOn, description = "Camera",
                        enabled = state.phase != CallPhase.OUTGOING
                    ) { manager.toggleCamera() }
                    ToggleButton(
                        Icons.Default.ScreenShare, active = state.screenSharing, description = "Share screen",
                        enabled = state.phase == CallPhase.ACTIVE
                    ) {
                        if (state.screenSharing) manager.stopScreenShare()
                        else {
                            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenLauncher.launch(pm.createScreenCaptureIntent())
                        }
                    }
                    ToggleButton(
                        if (state.speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        active = state.speakerOn, description = "Speaker"
                    ) { manager.toggleSpeaker() }
                    RoundButton(Icons.Default.CallEnd, CallRed, 60.dp, "Hang up") { manager.hangUp() }
                }
            }
        }
    }
}

@Composable
private fun statusText(state: CallUiState, en: Boolean): String {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.connectedAtMs) {
        val start = state.connectedAtMs ?: return@LaunchedEffect
        while (true) {
            seconds = ((android.os.SystemClock.elapsedRealtime() - start) / 1000).toInt()
            delay(1000)
        }
    }
    return when (state.phase) {
        CallPhase.OUTGOING -> if (en) "Calling…" else "Вызов…"
        CallPhase.INCOMING -> if (state.isVideoCall) (if (en) "Incoming video call" else "Входящий видеозвонок")
        else (if (en) "Incoming call" else "Входящий звонок")
        CallPhase.CONNECTING -> if (en) "Connecting…" else "Соединение…"
        CallPhase.ACTIVE -> "%d:%02d".format(seconds / 60, seconds % 60)
        CallPhase.ENDED -> callEndReasonText(state.endReason, en)
        CallPhase.IDLE -> ""
    }
}

@Composable
private fun RoundButton(icon: ImageVector, color: Color, size: androidx.compose.ui.unit.Dp, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size / 2)) }
}

@Composable
private fun ToggleButton(icon: ImageVector, active: Boolean, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (active) Color.White else Color.White.copy(alpha = 0.16f)
    Box(
        Modifier
            .size(54.dp).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = description,
            tint = if (active) Color(0xFF0B1220) else Color.White.copy(alpha = if (enabled) 1f else 0.35f)
        )
    }
}

/** SurfaceViewRenderer внутри Compose. */
@Composable
fun WebRtcVideo(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fit: Boolean = false,
    overlay: Boolean = false
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(overlay)
            }.also { renderer = it }
        },
        update = {
            it.setMirror(mirror)
            it.setScalingType(
                if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL
            )
        }
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        val t = track
        if (t != null && r != null) runCatching { t.addSink(r) }
        onDispose { if (t != null && r != null) runCatching { t.removeSink(r) } }
    }
    DisposableEffect(Unit) { onDispose { runCatching { renderer?.release() } } }
}
