package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Chat
import com.example.data.model.Message
import com.example.data.model.User
import com.example.ui.chats.UserSearchResultRow
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.LocalAppStrings
import com.example.util.MediaUrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

private sealed class RoomItem {
    data class DayHeader(val key: String, val label: String) : RoomItem()
    data class Msg(val message: Message, val showSender: Boolean) : RoomItem()
}

private val senderPalette = listOf(
    Color(0xFFFF7A85), Color(0xFFFFB454), Color(0xFFB388FF), Color(0xFF5EE6A8),
    Color(0xFF4FD8FF), Color(0xFF6FB7FF), Color(0xFFFF8FD0)
)

private fun senderColor(name: String): Color = senderPalette[abs(name.hashCode()) % senderPalette.size]

// Только один голосовой плеер играет одновременно
private var activeVoiceStopper: (() -> Unit)? = null

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatRoomScreen(
    chatTitle: String,
    viewModel: ChatRoomViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val accent = CyanAccent
    val onAccent = LocalAppThemeColors.current.buttonContent
    val myId = viewModel.currentUserId

    var inputText by remember { mutableStateOf("") }
    var actionsMessage by remember { mutableStateOf<Message?>(null) }
    var highlightedMessageId by remember { mutableStateOf<Long?>(null) }
    var isChatInfoOpen by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var fullScreenImageName by remember { mutableStateOf<String?>(null) }
    var isAddParticipantOpen by remember { mutableStateOf(false) }
    var participantSearchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var selectedParticipants by remember { mutableStateOf<List<User>>(emptyList()) }
    var initialScrolled by remember { mutableStateOf(false) }

    // ---- данные о чате ----
    val chat = uiState.chat
    val isGroup = chat?.chatType == "group"
    val otherUser = uiState.otherUserProfile ?: chat?.participants?.firstOrNull { it.user?.id != myId }?.user
    val myRole = chat?.participants?.firstOrNull { it.user?.id == myId }?.role?.lowercase()
    val canModerate = isGroup && (myRole == "owner" || myRole == "admin")
    val headerTitle = if (isGroup) (chat?.title ?: chatTitle) else (otherUser?.username ?: chatTitle)
    val subtitle: String? = when {
        uiState.typingUserIds.isNotEmpty() -> "typing…"
        isGroup && chat != null && chat.participants.size > 1 -> "${chat.participants.size} members"
        isGroup -> null
        otherUser?.isOnline == true -> "online"
        otherUser != null -> MediaUrlUtils.formatLastSeen(otherUser.lastOnline)
        else -> null
    }

    // ---- голосовая запись ----
    var isRecordingVoice by remember { mutableStateOf(false) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceOutputFile by remember { mutableStateOf<File?>(null) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    fun startVoiceRecording() {
        val dir = context.cacheDir.resolve("voice_cache").apply { mkdirs() }
        val file = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val ok = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
        }.isSuccess
        if (ok) {
            mediaRecorder = recorder
            voiceOutputFile = file
            isRecordingVoice = true
        } else {
            runCatching { recorder.release() }
            file.delete()
        }
    }

    // recorder всегда освобождается (раньше при исключении stop() release() не вызывался)
    fun finishVoiceRecording(send: Boolean) {
        val recorder = mediaRecorder
        val file = voiceOutputFile
        mediaRecorder = null
        voiceOutputFile = null
        isRecordingVoice = false
        val stopped = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.release() }
        if (send && stopped && file != null && file.exists() && file.length() > 0) {
            viewModel.sendAttachment(
                bytes = file.readBytes(),
                fileName = "voice_${System.currentTimeMillis()}.m4a",
                mimeType = "audio/mp4",
                overrideMessageType = "voice_message"
            )
        }
        file?.delete()
    }

    DisposableEffect(Unit) {
        onDispose { if (isRecordingVoice) finishVoiceRecording(false) }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording()
    }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordingSeconds = 0
            while (isRecordingVoice) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                    if (bytes != null) viewModel.sendAttachment(bytes, "img_${System.currentTimeMillis()}.$ext", mime)
                }
            }
        }
    }

    // ---- эффекты ----
    LaunchedEffect(uiState.editingMessage) {
        uiState.editingMessage?.let { inputText = it.text.orEmpty() }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // ---- список с разделителями дней ----
    val listItems = remember(uiState.messages, isGroup, myId) {
        val out = mutableListOf<RoomItem>()
        var lastDay: String? = null
        var prev: Message? = null
        uiState.messages.forEach { m ->
            val day = MediaUrlUtils.localDayKey(m.createdAt)
            if (day != null && day != lastDay) {
                out += RoomItem.DayHeader(day, MediaUrlUtils.formatDayLabel(m.createdAt))
                lastDay = day
                prev = null
            }
            val showSender = isGroup && m.sender?.id != myId && prev?.sender?.id != m.sender?.id
            out += RoomItem.Msg(m, showSender)
            prev = m
        }
        out
    }
    val messagesById = remember(uiState.messages) { uiState.messages.associateBy { it.id } }

    // Прокрутка вниз только при НОВОМ последнем сообщении (раньше срабатывала и при подгрузке истории)
    val lastMsgId = uiState.messages.lastOrNull()?.id
    LaunchedEffect(lastMsgId) {
        if (listItems.isEmpty()) return@LaunchedEffect
        val lastIndex = listItems.lastIndex
        if (!initialScrolled) {
            listState.scrollToItem(lastIndex)
            initialScrolled = true
        } else {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val lastIsOwn = uiState.messages.lastOrNull()?.sender?.id == myId
            if (lastIsOwn || lastVisible >= lastIndex - 3) listState.animateScrollToItem(lastIndex)
        }
    }

    // Автоподгрузка истории при прокрутке вверх; позиция сохраняется благодаря key у элементов
    LaunchedEffect(listState, initialScrolled) {
        if (!initialScrolled) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .filter { it <= 2 }
            .collect {
                if (uiState.hasMore && !uiState.isLoadingMore) viewModel.loadMoreMessages()
            }
    }

    val onJumpToMessage: (Long) -> Unit = { targetId ->
        val idx = listItems.indexOfFirst { it is RoomItem.Msg && it.message.id == targetId }
        if (idx != -1) {
            scope.launch {
                listState.animateScrollToItem(idx)
                highlightedMessageId = targetId
                delay(1500)
                highlightedMessageId = null
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            DarkTalkTopBar(
                title = headerTitle,
                subtitle = subtitle,
                onBackClick = onBackClick,
                onTitleClick = { isChatInfoOpen = true },
                wsState = uiState.roomWsState,
                titleLeading = {
                    AvatarView(
                        avatarUrl = if (isGroup) chat?.avatar else otherUser?.avatar,
                        displayName = headerTitle,
                        size = 38.dp,
                        isOnline = if (!isGroup) otherUser?.isOnline else null
                    )
                },
                actions = {
                    if (canModerate) {
                        IconButton(onClick = { isAddParticipantOpen = true }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Add Participants", tint = accent)
                        }
                    }
                    IconButton(onClick = { isChatInfoOpen = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Chat Info", tint = accent)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    uiState.isLoading && uiState.messages.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = accent)
                        }
                    }
                    uiState.messages.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No messages yet. Say hello!",
                                color = TextSecondary,
                                modifier = Modifier.glass(RoundedCornerShape(16.dp), strength = 0.7f).padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = listItems,
                                key = { item ->
                                    when (item) {
                                        is RoomItem.DayHeader -> "d_${item.key}"
                                        is RoomItem.Msg -> "m_${item.message.id}"
                                    }
                                }
                            ) { item ->
                                when (item) {
                                    is RoomItem.DayHeader -> {
                                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = item.label,
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier
                                                    .glass(RoundedCornerShape(50), strength = 0.7f, baseAlpha = 0.4f)
                                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    is RoomItem.Msg -> {
                                        val m = item.message
                                        MessageBubble(
                                            message = m,
                                            isOwn = m.sender?.id == myId,
                                            showSender = item.showSender,
                                            isHighlighted = m.id == highlightedMessageId,
                                            replyMessage = m.replyTo?.let { messagesById[it] },
                                            myId = myId,
                                            viewModel = viewModel,
                                            onActions = { if (!m.isDeleted) actionsMessage = m },
                                            onPhotoClick = { url, name ->
                                                fullScreenImageUrl = url
                                                fullScreenImageName = name
                                            },
                                            onReactionClick = { emoji -> viewModel.toggleReaction(m, emoji) },
                                            onJumpToMessage = onJumpToMessage
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- композер ----
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                AnimatedVisibility(visible = uiState.replyingTo != null) {
                    ComposerBanner(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        tint = accent,
                        title = "Replying to ${uiState.replyingTo?.sender?.username ?: "message"}",
                        subtitle = uiState.replyingTo?.let { messagePreview(it) }.orEmpty(),
                        onClose = { viewModel.setReplyingTo(null) }
                    )
                }
                AnimatedVisibility(visible = uiState.editingMessage != null) {
                    ComposerBanner(
                        icon = Icons.Default.Edit,
                        tint = VioletAccent,
                        title = "Editing message",
                        subtitle = uiState.editingMessage?.text.orEmpty(),
                        onClose = {
                            viewModel.setEditingMessage(null)
                            inputText = ""
                        }
                    )
                }

                if (isRecordingVoice) {
                    val pulse = rememberInfiniteTransition(label = "rec")
                    val dotAlpha by pulse.animateFloat(
                        initialValue = 0.3f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recAlpha"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .glass(RoundedCornerShape(26.dp), tint = CrimsonError, baseAlpha = 0.6f)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(CrimsonError.copy(alpha = dotAlpha)))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", recordingSeconds / 60, recordingSeconds % 60),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { finishVoiceRecording(false) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel Recording", tint = TextSecondary)
                        }
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(accent)
                                .clickable { finishVoiceRecording(true) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Voice", tint = onAccent, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).glass(RoundedCornerShape(26.dp), baseAlpha = 0.6f),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            IconButton(
                                onClick = {
                                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                modifier = Modifier.testTag("attach_button")
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Send attachment", tint = TextSecondary)
                            }
                            TextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    if (it.isNotBlank() && uiState.editingMessage == null) viewModel.onTypingInput()
                                },
                                placeholder = { Text(strings.writeMessagePlaceholder, color = TextMuted, fontSize = 15.sp) },
                                maxLines = 5,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    cursorColor = accent,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.weight(1f).testTag("chat_message_input")
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        val hasText = inputText.isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(if (hasText) accent else Color.White.copy(alpha = 0.12f))
                                .clickable {
                                    if (hasText) {
                                        viewModel.sendMessage(inputText)
                                        inputText = ""
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) startVoiceRecording()
                                        else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                .testTag(if (hasText) "send_message_button" else "voice_record_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    !hasText -> Icons.Default.Mic
                                    uiState.editingMessage != null -> Icons.Default.Check
                                    else -> Icons.AutoMirrored.Filled.Send
                                },
                                contentDescription = if (hasText) "Send" else "Record Voice Message",
                                tint = if (hasText) onAccent else accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---------- Действия над сообщением ----------
        actionsMessage?.let { msg ->
            val isOwn = msg.sender?.id == myId
            val reactionEmojis = remember { viewModel.getCustomReactionEmojis() }
            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

            ModalBottomSheet(
                onDismissRequest = { actionsMessage = null },
                containerColor = Color(0xF2141C2E),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        reactionEmojis.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .glass(CircleShape, strength = 0.8f)
                                    .clickable {
                                        viewModel.toggleReaction(msg, emoji)
                                        actionsMessage = null
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))

                    ListItem(
                        headlineContent = { Text("Reply", color = TextPrimary) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, null, tint = accent) },
                        colors = itemColors,
                        modifier = Modifier.clickable {
                            if (uiState.editingMessage != null) inputText = ""
                            viewModel.setReplyingTo(msg)
                            actionsMessage = null
                        }.testTag("action_reply")
                    )
                    if (!msg.text.isNullOrBlank()) {
                        ListItem(
                            headlineContent = { Text("Copy", color = TextPrimary) },
                            leadingContent = { Icon(Icons.Default.ContentCopy, null, tint = accent) },
                            colors = itemColors,
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString(msg.text))
                                actionsMessage = null
                            }
                        )
                    }
                    if (isOwn && msg.messageType == "text") {
                        ListItem(
                            headlineContent = { Text("Edit", color = TextPrimary) },
                            leadingContent = { Icon(Icons.Default.Edit, null, tint = VioletAccent) },
                            colors = itemColors,
                            modifier = Modifier.clickable {
                                viewModel.setEditingMessage(msg)
                                actionsMessage = null
                            }.testTag("action_edit")
                        )
                    }
                    // Admin/owner группы может удалять чужие сообщения (README: HTTP DELETE)
                    if (isOwn || canModerate) {
                        ListItem(
                            headlineContent = { Text("Delete", color = CrimsonError) },
                            leadingContent = { Icon(Icons.Default.Delete, null, tint = CrimsonError) },
                            colors = itemColors,
                            modifier = Modifier.clickable {
                                viewModel.deleteMessage(msg)
                                actionsMessage = null
                            }.testTag("action_delete")
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (isChatInfoOpen) {
            ChatInfoModalBottomSheet(
                chat = uiState.chat,
                otherUserProfile = uiState.otherUserProfile,
                currentUserId = myId,
                onDismiss = { isChatInfoOpen = false },
                onAddMembersClick = {
                    isChatInfoOpen = false
                    isAddParticipantOpen = true
                },
                onRemoveParticipant = { userId -> viewModel.removeParticipant(userId) },
                onLeaveGroup = {
                    isChatInfoOpen = false
                    viewModel.leaveChat { onBackClick() }
                }
            )
        }

        if (!fullScreenImageUrl.isNullOrBlank()) {
            FullImagePreviewDialog(
                imageUrl = fullScreenImageUrl,
                fileName = fullScreenImageName,
                onDismiss = {
                    fullScreenImageUrl = null
                    fullScreenImageName = null
                }
            )
        }

        if (isAddParticipantOpen) {
            val existingIds = chat?.participants?.mapNotNull { it.user?.id }.orEmpty().toSet()
            GlassDialog(
                onDismiss = {
                    isAddParticipantOpen = false
                    selectedParticipants = emptyList()
                    participantSearchQuery = ""
                    searchResults = emptyList()
                },
                accent = accent
            ) {
                Text("Add Participants", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                Spacer(Modifier.height(12.dp))
                CyberTextField(
                    value = participantSearchQuery,
                    onValueChange = {
                        participantSearchQuery = it
                        viewModel.searchUsers(it) { list -> searchResults = list }
                    },
                    label = "Search User",
                    placeholder = "Type username...",
                    leadingIcon = { Icon(Icons.Default.PersonSearch, null, tint = accent) }
                )
                if (selectedParticipants.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedParticipants.forEach { user ->
                            InputChip(
                                selected = true,
                                onClick = { selectedParticipants = selectedParticipants.filterNot { it.id == user.id } },
                                label = { Text(user.username, fontSize = 12.sp) },
                                trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp)) }
                            )
                        }
                    }
                }
                val visibleResults = searchResults.filter { it.id != myId && it.id !in existingIds }
                if (visibleResults.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(visibleResults, key = { it.id }) { user ->
                            val isSel = selectedParticipants.any { it.id == user.id }
                            UserSearchResultRow(
                                user = user,
                                isSelected = isSel,
                                onSelect = {
                                    selectedParticipants = if (isSel) selectedParticipants.filterNot { it.id == user.id } else selectedParticipants + user
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { isAddParticipantOpen = false }) { Text("Cancel", color = TextSecondary) }
                    Spacer(Modifier.width(8.dp))
                    CyberButton(
                        text = "Add",
                        enabled = selectedParticipants.isNotEmpty(),
                        onClick = {
                            viewModel.addParticipants(selectedParticipants.map { it.username })
                            isAddParticipantOpen = false
                            selectedParticipants = emptyList()
                            participantSearchQuery = ""
                            searchResults = emptyList()
                        }
                    )
                }
            }
        }
    }
}

private fun messagePreview(m: Message): String = when {
    m.isDeleted -> "Message deleted"
    !m.text.isNullOrBlank() -> m.text
    m.messageType == "voice_message" -> "🎙 Voice message"
    m.messageType == "image" -> "📷 Photo"
    !m.attachment.isNullOrBlank() -> "📁 ${m.attachmentName ?: "File"}"
    else -> ""
}

@Composable
private fun ComposerBanner(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .glass(RoundedCornerShape(18.dp), strength = 0.8f, baseAlpha = 0.55f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(2.dp).height(30.dp).background(tint, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoModalBottomSheet(
    chat: Chat?,
    otherUserProfile: User?,
    currentUserId: Long,
    onDismiss: () -> Unit,
    onAddMembersClick: () -> Unit,
    onRemoveParticipant: (Long) -> Unit,
    onLeaveGroup: () -> Unit = {}
) {
    if (chat == null) return
    val accent = CyanAccent
    val isGroup = chat.chatType == "group"
    val otherPart = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
    val otherUser = otherUserProfile ?: otherPart
    val chatTitle = if (isGroup) (chat.title ?: "Group Chat") else (otherUser?.username ?: chat.title ?: "User Profile")
    val myRole = chat.participants.firstOrNull { it.user?.id == currentUserId }?.role?.lowercase()
    val isAdminOrOwner = myRole == "owner" || myRole == "admin"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF2141C2E),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarView(
                    avatarUrl = if (isGroup) chat.avatar else otherUser?.avatar,
                    displayName = chatTitle,
                    size = 80.dp,
                    isOnline = if (!isGroup) otherUser?.isOnline else null
                )
                Spacer(Modifier.height(12.dp))
                Text(chatTitle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                if (isGroup) {
                    Text("Group • ${chat.participants.size} participants", color = accent, fontSize = 14.sp)
                } else if (otherUser != null) {
                    Text(
                        text = if (otherUser.isOnline == true) "online" else MediaUrlUtils.formatLastSeen(otherUser.lastOnline),
                        color = if (otherUser.isOnline == true) EmeraldSuccess else TextMuted,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            if (!isGroup && otherUser != null) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).padding(16.dp)) {
                    var any = false
                    if (!otherUser.info.isNullOrBlank()) {
                        ProfileDetailRow(Icons.Default.Info, "Bio", otherUser.info); any = true
                    }
                    if (!otherUser.language.isNullOrBlank()) {
                        if (any) HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
                        ProfileDetailRow(Icons.Default.Language, "Language", otherUser.language); any = true
                    }
                    if (!otherUser.dateOfBirth.isNullOrBlank()) {
                        if (any) HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
                        ProfileDetailRow(Icons.Default.Cake, "Date of Birth", otherUser.dateOfBirth); any = true
                    }
                    if (!any) Text("No additional information", color = TextMuted, fontSize = 13.sp)
                }
            } else if (isGroup) {
                if (!chat.description.isNullOrBlank()) {
                    Text(chat.description, color = TextPrimary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), strength = 0.7f).padding(14.dp))
                    Spacer(Modifier.height(16.dp))
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Participants (${chat.participants.size})", color = TextSecondary, fontWeight = FontWeight.Bold)
                    if (isAdminOrOwner) {
                        TextButton(onClick = onAddMembersClick) {
                            Icon(Icons.Default.PersonAdd, null, tint = accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chat.participants.forEach { part ->
                        val pUser = part.user ?: return@forEach
                        val role = part.role?.lowercase() ?: "member"
                        val isOwner = role == "owner"
                        val isAdmin = role == "admin" || role == "moderator"
                        Row(
                            modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), strength = 0.6f, baseAlpha = 0.35f).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarView(pUser.avatar, pUser.username, size = 40.dp, isOnline = pUser.isOnline)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pUser.username, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (pUser.id == currentUserId) "You" else if (pUser.isOnline == true) "online" else "offline",
                                    color = if (pUser.isOnline == true) EmeraldSuccess else TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            if (isOwner || isAdmin) {
                                Text(
                                    text = role.replaceFirstChar { it.uppercase() },
                                    color = if (isOwner) AmberWarning else VioletAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background((if (isOwner) AmberWarning else VioletAccent).copy(alpha = 0.18f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            // README: admin не может удалить owner'а; owner может всех
                            if (isAdminOrOwner && pUser.id != currentUserId && !isOwner) {
                                IconButton(onClick = { onRemoveParticipant(pUser.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.PersonRemove, "Remove member", tint = CrimsonError, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                if (myRole != "owner") {
                    Spacer(Modifier.height(16.dp))
                    CyberButton(
                        text = "Leave group",
                        onClick = onLeaveGroup,
                        isSecondary = true,
                        icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = CrimsonError) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun ProfileDetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = TextMuted, fontSize = 11.sp)
            Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun FullImagePreviewDialog(
    imageUrl: String?,
    fileName: String?,
    onDismiss: () -> Unit
) {
    if (imageUrl.isNullOrBlank()) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val resolvedUrl = remember(imageUrl) { MediaUrlUtils.resolveUrl(imageUrl) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else scale = 2.5f
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(resolvedUrl).crossfade(true).build(),
                contentDescription = fileName ?: "Full Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fileName ?: "Image Preview",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).glass(CircleShape)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    showSender: Boolean,
    isHighlighted: Boolean = false,
    replyMessage: Message?,
    myId: Long,
    viewModel: ChatRoomViewModel,
    onActions: () -> Unit,
    onPhotoClick: (String?, String?) -> Unit,
    onReactionClick: (String) -> Unit,
    onJumpToMessage: (Long) -> Unit
) {
    val accent = CyanAccent
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOwn) 18.dp else 4.dp,
        bottomEnd = if (isOwn) 4.dp else 18.dp
    )
    val navy = Color(0xFF0A1B3D)
    val ownBrush = Brush.linearGradient(listOf(lerp(accent, navy, 0.25f), lerp(accent, navy, 0.55f)))
    val bubbleModifier = if (isOwn) {
        Modifier
            .clip(shape)
            .background(ownBrush)
            .border(
                if (isHighlighted) 2.dp else 1.dp,
                if (isHighlighted) Color.White else Color.White.copy(alpha = 0.22f),
                shape
            )
    } else {
        Modifier
            .glass(shape, strength = 1.1f, baseAlpha = 0.45f)
            .then(if (isHighlighted) Modifier.border(2.dp, accent, shape) else Modifier)
    }
    val contentTint = if (isOwn) Color.White else accent

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = bubbleModifier
                .widthIn(max = 310.dp)
                .pointerInput(message.id) {
                    detectTapGestures(onDoubleTap = { onActions() }, onLongPress = { onActions() })
                }
                .testTag("message_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
                if (showSender && message.sender != null) {
                    Text(
                        text = message.sender.username,
                        color = senderColor(message.sender.username),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                if (replyMessage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.18f))
                            .clickable { onJumpToMessage(replyMessage.id) }
                            .padding(6.dp)
                    ) {
                        Box(Modifier.width(3.dp).height(30.dp).background(contentTint, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(replyMessage.sender?.username ?: "Message", color = contentTint, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            Text(messagePreview(replyMessage), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                if (!message.isDeleted && !message.attachment.isNullOrBlank()) {
                    if (message.messageType == "voice_message") {
                        VoiceMessagePlayer(
                            messageId = message.id,
                            rawAudioUrl = message.attachment,
                            viewModel = viewModel,
                            tint = contentTint
                        )
                    } else if (message.messageType == "image") {
                        val url = remember(message.attachment) { MediaUrlUtils.resolveUrl(message.attachment) }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                            contentDescription = message.attachmentName ?: "Image attachment",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPhotoClick(url, message.attachmentName) }
                        )
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.18f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, "File", tint = contentTint)
                            Spacer(Modifier.width(8.dp))
                            Text(message.attachmentName ?: "File", color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (message.isDeleted) {
                    Text("Message was deleted", color = TextSecondary, fontStyle = FontStyle.Italic, fontSize = 14.sp)
                } else if (!message.text.isNullOrBlank()) {
                    Text(message.text, color = TextPrimary, fontSize = 15.sp)
                }

                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited && !message.isDeleted) {
                        Text("edited", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                    }
                    val time = remember(message.createdAt) { MediaUrlUtils.formatMessageTime(message.createdAt) }
                    Text(time, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                    if (isOwn) {
                        Spacer(Modifier.width(4.dp))
                        val isRead = message.readBy.any { it != message.sender?.id }
                        Icon(
                            imageVector = if (isRead) Icons.Default.DoneAll else Icons.Default.Check,
                            contentDescription = if (isRead) "Read" else "Sent",
                            tint = if (isRead) Color.White else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.reactions.groupBy { it.emoji }.forEach { (emoji, reactions) ->
                    val mine = reactions.any { it.userId == myId }
                    val chip = RoundedCornerShape(12.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(chip)
                            .background(if (mine) accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f))
                            .border(1.dp, if (mine) accent else Color.White.copy(alpha = 0.18f), chip)
                            .clickable { onReactionClick(emoji) }
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(emoji, fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(reactions.size.toString(), color = TextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

fun formatAudioTime(ms: Int): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", totalSecs / 60, totalSecs % 60)
}

@Composable
fun VoiceMessagePlayer(
    messageId: Long,
    rawAudioUrl: String?,
    viewModel: ChatRoomViewModel,
    tint: Color = CyanAccent
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isLoadingAudio by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    val stopSelf = remember {
        {
            runCatching { if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause() }
            isPlaying = false
        }
    }

    fun applySpeed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                mediaPlayer?.let { it.playbackParams = (it.playbackParams ?: PlaybackParams()).setSpeed(playbackSpeed) }
            }
        }
    }

    fun beginPlayback() {
        activeVoiceStopper?.takeIf { it !== stopSelf }?.invoke()
        activeVoiceStopper = stopSelf
        runCatching {
            if (currentPositionMs >= durationMs - 100) currentPositionMs = 0
            mediaPlayer?.start()
            applySpeed()
            isPlaying = true
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            runCatching { currentPositionMs = mediaPlayer?.currentPosition ?: 0 }
            delay(200)
        }
    }

    DisposableEffect(messageId) {
        onDispose {
            if (activeVoiceStopper === stopSelf) activeVoiceStopper = null
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    Column(Modifier.fillMaxWidth().widthIn(min = 220.dp).padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (rawAudioUrl.isNullOrBlank()) return@IconButton
                        if (mediaPlayer != null) {
                            beginPlayback()
                        } else {
                            isLoadingAudio = true
                            viewModel.downloadVoiceFile(rawAudioUrl, messageId) { localFile ->
                                isLoadingAudio = false
                                if (localFile != null && localFile.exists()) {
                                    runCatching {
                                        mediaPlayer = MediaPlayer().apply {
                                            setDataSource(localFile.absolutePath)
                                            prepare()
                                            durationMs = duration
                                            setOnCompletionListener {
                                                isPlaying = false
                                                currentPositionMs = 0
                                            }
                                        }
                                        beginPlayback()
                                    }
                                }
                            }
                        }
                    }
                },
                enabled = !isLoadingAudio,
                modifier = Modifier.size(38.dp)
            ) {
                if (isLoadingAudio) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = tint, modifier = Modifier.size(22.dp))
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = "Play/Pause Voice",
                        tint = tint,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            val maxProgress = durationMs.coerceAtLeast(1).toFloat()
            Slider(
                value = currentPositionMs.coerceIn(0, durationMs.coerceAtLeast(1)).toFloat(),
                onValueChange = { pos ->
                    currentPositionMs = pos.toInt()
                    runCatching { mediaPlayer?.seekTo(pos.toInt()) }
                },
                valueRange = 0f..maxProgress,
                colors = SliderDefaults.colors(
                    thumbColor = tint,
                    activeTrackColor = tint,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier.weight(1f).height(24.dp)
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Text(
                    text = if (playbackSpeed == 1.0f) "1x" else if (playbackSpeed == 1.5f) "1.5x" else "2x",
                    color = tint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable {
                            playbackSpeed = when (playbackSpeed) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            // На паузе не трогаем playbackParams – на ряде устройств это запускает воспроизведение
                            if (isPlaying) applySpeed()
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Text(
            text = if (durationMs > 0) {
                if (isPlaying || currentPositionMs > 0) "${formatAudioTime(currentPositionMs)} / ${formatAudioTime(durationMs)}"
                else formatAudioTime(durationMs)
            } else "Voice message",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 44.dp)
        )
    }
}
