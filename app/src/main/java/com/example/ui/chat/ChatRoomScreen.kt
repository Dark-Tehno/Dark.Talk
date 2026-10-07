package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.example.ui.components.AvatarView
import com.example.ui.components.CyberButton
import com.example.ui.components.CyberTextField
import com.example.ui.components.DarkTalkTopBar
import com.example.ui.theme.*
import com.example.util.MediaUrlUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    chatTitle: String,
    viewModel: ChatRoomViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var highlightedMessageId by remember { mutableStateOf<Long?>(null) }

    // Chat / Profile info sheet state
    var isChatInfoOpen by remember { mutableStateOf(false) }

    // Full screen image preview state
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var fullScreenImageName by remember { mutableStateOf<String?>(null) }

    // Voice recording state
    var isRecordingVoice by remember { mutableStateOf(false) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceOutputFile by remember { mutableStateOf<File?>(null) }
    var recordingTimerSeconds by remember { mutableIntStateOf(0) }

    // Permission launcher for voice recording
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val file = context.cacheDir.resolve("voice_${System.currentTimeMillis()}.m4a")
            voiceOutputFile = file
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            runCatching {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                mediaRecorder = recorder
                isRecordingVoice = true
            }
        }
    }

    // Voice Recording Timer Effect
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordingTimerSeconds = 0
            while (isRecordingVoice) {
                delay(1000)
                recordingTimerSeconds++
            }
        }
    }

    // Synchronize edit text
    LaunchedEffect(uiState.editingMessage) {
        if (uiState.editingMessage != null) {
            inputText = uiState.editingMessage?.text.orEmpty()
        }
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Jump to replied message function
    val onJumpToMessage: (Long) -> Unit = { targetId ->
        val targetIndex = uiState.messages.indexOfFirst { it.id == targetId }
        if (targetIndex != -1) {
            coroutineScope.launch {
                listState.animateScrollToItem(targetIndex)
                highlightedMessageId = targetId
                delay(1500)
                highlightedMessageId = null
            }
        }
    }

    // Media picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val fileName = "img_${System.currentTimeMillis()}.jpg"
                    if (bytes != null) {
                        viewModel.sendAttachment(bytes, fileName, mimeType)
                    }
                }
            }
        }
    }

    var isAddParticipantOpen by remember { mutableStateOf(false) }
    var participantSearchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var selectedParticipants by remember { mutableStateOf<List<User>>(emptyList()) }

    val typingSubtitle = remember(uiState.typingUserIds) {
        if (uiState.typingUserIds.isNotEmpty()) {
            "typing..."
        } else {
            null
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            DarkTalkTopBar(
                title = chatTitle,
                subtitle = typingSubtitle,
                onBackClick = onBackClick,
                onTitleClick = { isChatInfoOpen = true },
                wsState = uiState.roomWsState,
                actions = {
                    IconButton(onClick = { isChatInfoOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Chat Info",
                            tint = CyanAccent
                        )
                    }
                    if (uiState.chat?.chatType == "group") {
                        IconButton(onClick = { isAddParticipantOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = "Add Participants",
                                tint = CyanAccent
                            )
                        }
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
            // Load more messages indicator
            if (uiState.hasMore) {
                TextButton(
                    onClick = { viewModel.loadMoreMessages() },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                ) {
                    Text("Load earlier messages", color = CyanAccent, fontSize = 12.sp)
                }
            }

            // Messages LazyColumn
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.isLoading && uiState.messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = CyanAccent)
                    }
                } else if (uiState.messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages yet. Say hello!",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            val isOwn = message.sender?.id == viewModel.currentUserId
                            val replyTarget = remember(message.replyTo, uiState.messages) {
                                message.replyTo?.let { repId ->
                                    uiState.messages.firstOrNull { it.id == repId }
                                }
                            }

                            MessageBubble(
                                message = message,
                                isOwn = isOwn,
                                isHighlighted = message.id == highlightedMessageId,
                                replyMessage = replyTarget,
                                viewModel = viewModel,
                                onDoubleTap = { contextMenuMessage = message },
                                onPhotoClick = { url, name ->
                                    fullScreenImageUrl = url
                                    fullScreenImageName = name
                                },
                                onReactionClick = { emoji ->
                                    viewModel.toggleReaction(message, emoji)
                                },
                                onJumpToMessage = onJumpToMessage
                            )
                        }
                    }
                }
            }

            // Replying Banner
            AnimatedVisibility(visible = uiState.replyingTo != null) {
                Surface(
                    color = DarkSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Replying",
                            tint = CyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${uiState.replyingTo?.sender?.username ?: "message"}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = uiState.replyingTo?.text.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { viewModel.setReplyingTo(null) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = TextMuted)
                        }
                    }
                }
            }

            // Editing Banner
            AnimatedVisibility(visible = uiState.editingMessage != null) {
                Surface(
                    color = DarkSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editing",
                            tint = VioletAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Editing message",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = VioletAccent,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            viewModel.setEditingMessage(null)
                            inputText = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel edit", tint = TextMuted)
                        }
                    }
                }
            }

            // Voice Recording Banner
            AnimatedVisibility(visible = isRecordingVoice) {
                Surface(
                    color = DarkSurfaceElevated,
                    border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Recording",
                            tint = CrimsonError,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val mins = recordingTimerSeconds / 60
                        val secs = recordingTimerSeconds % 60
                        Text(
                            text = String.format(Locale.US, "Recording... %02d:%02d", mins, secs),
                            color = CrimsonError,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f)
                        )

                        // Cancel Recording Button
                        IconButton(onClick = {
                            runCatching {
                                mediaRecorder?.stop()
                                mediaRecorder?.release()
                                voiceOutputFile?.delete()
                            }
                            mediaRecorder = null
                            voiceOutputFile = null
                            isRecordingVoice = false
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel Recording", tint = TextMuted)
                        }

                        // Send Voice Message Button
                        IconButton(onClick = {
                            runCatching {
                                mediaRecorder?.stop()
                                mediaRecorder?.release()
                                val file = voiceOutputFile
                                if (file != null && file.exists()) {
                                    val bytes = file.readBytes()
                                    if (bytes.isNotEmpty()) {
                                        viewModel.sendAttachment(
                                            bytes = bytes,
                                            fileName = "voice_${System.currentTimeMillis()}.m4a",
                                            mimeType = "audio/m4a",
                                            overrideMessageType = "voice_message"
                                        )
                                    }
                                }
                            }
                            mediaRecorder = null
                            voiceOutputFile = null
                            isRecordingVoice = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Send Voice", tint = CyanAccent)
                        }
                    }
                }
            }

            // Bottom Input Bar
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = GlassSurface,
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment button
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("attach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Send attachment",
                            tint = CyanAccent
                        )
                    }

                    // Input Text Field
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            viewModel.onTypingInput()
                        },
                        placeholder = { Text("Write a message...", color = TextMuted, fontSize = 14.sp) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceElevated,
                            unfocusedContainerColor = DarkSurfaceElevated,
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = BubbleBorder,
                            cursorColor = CyanAccent,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Send or Mic Button
                    if (inputText.isNotBlank()) {
                        FloatingActionButton(
                            onClick = {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            },
                            containerColor = CyanAccent,
                            contentColor = Color(0xFF001F28),
                            shape = CircleShape,
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.editingMessage != null) Icons.Default.Check else Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        // Voice Mic Button
                        FloatingActionButton(
                            onClick = {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasPermission) {
                                    val file = context.cacheDir.resolve("voice_${System.currentTimeMillis()}.m4a")
                                    voiceOutputFile = file
                                    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        MediaRecorder(context)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        MediaRecorder()
                                    }
                                    runCatching {
                                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                        recorder.setOutputFile(file.absolutePath)
                                        recorder.prepare()
                                        recorder.start()
                                        mediaRecorder = recorder
                                        isRecordingVoice = true
                                    }
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            containerColor = DarkSurfaceElevated,
                            contentColor = CyanAccent,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("voice_record_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record Voice Message",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // Message Actions Bottom Sheet / Context Menu (Opened ONLY on double tap)
        if (contextMenuMessage != null) {
            val msg = contextMenuMessage!!
            val isOwn = msg.sender?.id == viewModel.currentUserId
            val reactionEmojis = remember { viewModel.getCustomReactionEmojis() }

            ModalBottomSheet(
                onDismissRequest = { contextMenuMessage = null },
                containerColor = DarkSurfaceElevated
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    // Quick Emoji Reactions
                    Text(
                        text = "React",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        reactionEmojis.forEach { emoji ->
                            Surface(
                                shape = CircleShape,
                                color = DarkSurface,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
                                        viewModel.toggleReaction(msg, emoji)
                                        contextMenuMessage = null
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = emoji, fontSize = 22.sp)
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = BubbleBorder,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )

                    // Reply option
                    ListItem(
                        headlineContent = { Text("Reply", color = TextPrimary) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = CyanAccent) },
                        modifier = Modifier
                            .clickable {
                                viewModel.setReplyingTo(msg)
                                contextMenuMessage = null
                            }
                            .testTag("action_reply")
                    )

                    // Edit option (if own message and not deleted)
                    if (isOwn && !msg.isDeleted) {
                        ListItem(
                            headlineContent = { Text("Edit", color = TextPrimary) },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null, tint = VioletAccent) },
                            modifier = Modifier
                                .clickable {
                                    viewModel.setEditingMessage(msg)
                                    contextMenuMessage = null
                                }
                                .testTag("action_edit")
                        )
                    }

                    // Delete option (if own message and not deleted)
                    if (isOwn && !msg.isDeleted) {
                        ListItem(
                            headlineContent = { Text("Delete", color = CrimsonError) },
                            leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = CrimsonError) },
                            modifier = Modifier
                                .clickable {
                                    viewModel.deleteMessage(msg.id)
                                    contextMenuMessage = null
                                }
                                .testTag("action_delete")
                        )
                    }
                }
            }
        }

        // Chat / Profile Info Bottom Sheet
        if (isChatInfoOpen) {
            ChatInfoModalBottomSheet(
                chat = uiState.chat,
                otherUserProfile = uiState.otherUserProfile,
                currentUserId = viewModel.currentUserId,
                onDismiss = { isChatInfoOpen = false },
                onAddMembersClick = {
                    isChatInfoOpen = false
                    isAddParticipantOpen = true
                },
                onRemoveParticipant = { userId ->
                    viewModel.removeParticipant(userId)
                }
            )
        }

        // Full Screen Zoomable Image Preview Dialog
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

        // Add Participants Dialog
        if (isAddParticipantOpen) {
            Dialog(onDismissRequest = { isAddParticipantOpen = false }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Add Participants to Group",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        CyberTextField(
                            value = participantSearchQuery,
                            onValueChange = {
                                participantSearchQuery = it
                                viewModel.searchUsers(it) { list -> searchResults = list }
                            },
                            label = "Search User",
                            placeholder = "Type username...",
                            leadingIcon = { Icon(Icons.Default.PersonSearch, contentDescription = null, tint = CyanAccent) }
                        )

                        if (selectedParticipants.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                selectedParticipants.forEach { user ->
                                    InputChip(
                                        selected = true,
                                        onClick = { selectedParticipants = selectedParticipants.filterNot { it.id == user.id } },
                                        label = { Text(user.username, fontSize = 12.sp) },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                    )
                                }
                            }
                        }

                        if (searchResults.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 150.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(searchResults, key = { it.id }) { user ->
                                    val isSel = selectedParticipants.any { it.id == user.id }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSel) CyanAccentContainer else DarkSurfaceElevated,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedParticipants = if (isSel) {
                                                    selectedParticipants.filterNot { it.id == user.id }
                                                } else {
                                                    selectedParticipants + user
                                                }
                                            }
                                            .padding(2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AvatarView(avatarUrl = user.avatar, displayName = user.username, size = 32.dp)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(user.username, color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            Icon(
                                                imageVector = if (isSel) Icons.Default.CheckCircle else Icons.Default.Add,
                                                contentDescription = null,
                                                tint = CyanAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { isAddParticipantOpen = false }) {
                                Text("Cancel", color = TextSecondary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            CyberButton(
                                text = "Add",
                                onClick = {
                                    val usernames = selectedParticipants.map { it.username }
                                    viewModel.addParticipants(usernames)
                                    isAddParticipantOpen = false
                                    selectedParticipants = emptyList()
                                    participantSearchQuery = ""
                                }
                            )
                        }
                    }
                }
            }
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
    onRemoveParticipant: (Long) -> Unit
) {
    if (chat == null) return

    val isGroup = chat.chatType == "group"
    val otherPart = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
    val otherUser = otherUserProfile ?: otherPart
    val chatTitle = if (isGroup) (chat.title ?: "Group Chat") else (otherUser?.username ?: chat.title ?: "User Profile")

    val currentUserRole = remember(chat.participants) {
        chat.participants.firstOrNull { it.user?.id == currentUserId }?.role?.lowercase()
    }
    val isGroupAdminOrOwner = remember(currentUserRole) {
        currentUserRole == "owner" || currentUserRole == "admin" || currentUserRole == "moderator"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Profile Header
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AvatarView(
                    avatarUrl = if (isGroup) chat.avatar else otherUser?.avatar,
                    displayName = chatTitle,
                    size = 72.dp,
                    isOnline = if (!isGroup) otherUser?.isOnline else null
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = chatTitle,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )

                if (isGroup) {
                    Text(
                        text = "Group • ${chat.participants.size} participants",
                        style = MaterialTheme.typography.bodyMedium.copy(color = CyanAccent)
                    )
                } else if (otherUser != null) {
                    Text(
                        text = if (otherUser.isOnline == true) "Online" else "Offline",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (otherUser.isOnline == true) EmeraldSuccess else TextMuted
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (!isGroup && otherUser != null) {
                // Direct User Profile Details
                Text(
                    text = "User Information",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, BubbleBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (!otherUser.email.isNullOrBlank()) {
                            ProfileDetailRow(icon = Icons.Default.Email, label = "Email", value = otherUser.email)
                            HorizontalDivider(color = BubbleBorder, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        if (!otherUser.info.isNullOrBlank()) {
                            ProfileDetailRow(icon = Icons.Default.Info, label = "Bio", value = otherUser.info)
                            HorizontalDivider(color = BubbleBorder, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        if (!otherUser.language.isNullOrBlank()) {
                            ProfileDetailRow(icon = Icons.Default.Language, label = "Language", value = otherUser.language)
                        }
                        if (!otherUser.dateOfBirth.isNullOrBlank()) {
                            HorizontalDivider(color = BubbleBorder, modifier = Modifier.padding(vertical = 8.dp))
                            ProfileDetailRow(icon = Icons.Default.Cake, label = "Date of Birth", value = otherUser.dateOfBirth)
                        }
                    }
                }
            } else if (isGroup) {
                // Group Info & Participant Management
                if (!chat.description.isNullOrBlank()) {
                    Text(
                        text = "Group Description",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = DarkSurface,
                        border = BorderStroke(1.dp, BubbleBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = chat.description,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Participants (${chat.participants.size})",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                    )

                    if (isGroupAdminOrOwner) {
                        TextButton(onClick = onAddMembersClick) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Member", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chat.participants.forEach { part ->
                        val pUser = part.user
                        if (pUser != null) {
                            val roleStr = part.role?.lowercase() ?: "member"
                            val isOwner = roleStr == "owner"
                            val isAdmin = roleStr == "admin" || roleStr == "moderator"

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = DarkSurface,
                                border = BorderStroke(1.dp, BubbleBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AvatarView(
                                        avatarUrl = pUser.avatar,
                                        displayName = pUser.username,
                                        size = 38.dp,
                                        isOnline = pUser.isOnline
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pUser.username,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                        Text(
                                            text = if (pUser.id == currentUserId) "You" else (if (pUser.isOnline == true) "Online" else "Offline"),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (pUser.isOnline == true) EmeraldSuccess else TextMuted
                                            )
                                        )
                                    }

                                    // Role Badge
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = when {
                                            isOwner -> AmberWarning.copy(alpha = 0.2f)
                                            isAdmin -> VioletContainer
                                            else -> DarkSurfaceElevated
                                        }
                                    ) {
                                        Text(
                                            text = roleStr.replaceFirstChar { it.uppercase() },
                                            color = when {
                                                isOwner -> AmberWarning
                                                isAdmin -> VioletAccent
                                                else -> TextSecondary
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    // Kick / Remove button for Admins/Owners
                                    if (isGroupAdminOrOwner && pUser.id != currentUserId && !isOwner) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { onRemoveParticipant(pUser.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PersonRemove,
                                                contentDescription = "Remove member",
                                                tint = CrimsonError,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun ProfileDetailRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.labelSmall.copy(color = TextMuted))
            Text(text = value, style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold))
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

    val resolvedUrl = remember(imageUrl) {
        MediaUrlUtils.resolveUrl(imageUrl)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
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
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(resolvedUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = fileName ?: "Full Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )

            // Top bar with close button
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
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceElevated.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    isHighlighted: Boolean = false,
    replyMessage: Message?,
    viewModel: ChatRoomViewModel,
    onDoubleTap: () -> Unit,
    onPhotoClick: (String?, String?) -> Unit,
    onReactionClick: (String) -> Unit,
    onJumpToMessage: (Long) -> Unit
) {
    val bubbleColor = if (isOwn) BubbleSelf else BubbleOther
    val alignment = if (isOwn) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isOwn) 22.dp else 6.dp,
                bottomEnd = if (isOwn) 6.dp else 22.dp
            ),
            color = bubbleColor,
            border = BorderStroke(
                width = if (isHighlighted) 2.dp else 1.dp,
                color = if (isHighlighted) CyanAccent else BubbleBorder
            ),
            modifier = Modifier
                .widthIn(max = 310.dp)
                .pointerInput(message.id) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() }
                    )
                }
                .testTag("message_bubble_${message.id}")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Sender name if other user
                if (!isOwn && message.sender != null) {
                    Text(
                        text = message.sender.username,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Reply preview
                if (replyMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToMessage(replyMessage.id) }
                            .padding(bottom = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(28.dp)
                                    .background(CyanAccent, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = replyMessage.sender?.username ?: "Message",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = CyanAccent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                                Text(
                                    text = replyMessage.text.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = TextSecondary),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Attachment or Voice Message
                if (message.messageType == "voice_message" && !message.attachment.isNullOrBlank()) {
                    VoiceMessagePlayer(
                        messageId = message.id,
                        rawAudioUrl = message.attachment,
                        viewModel = viewModel
                    )
                } else if (!message.attachment.isNullOrBlank()) {
                    val resolvedAttachmentUrl = remember(message.attachment) {
                        MediaUrlUtils.resolveUrl(message.attachment)
                    }
                    if (message.messageType == "image") {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(resolvedAttachmentUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = message.attachmentName ?: "Image attachment",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onPhotoClick(resolvedAttachmentUrl, message.attachmentName)
                                }
                                .padding(bottom = 6.dp)
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = "File",
                                    tint = CyanAccent
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = message.attachmentName ?: "File",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Message Text
                if (message.isDeleted) {
                    Text(
                        text = "Message was deleted",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextMuted,
                            fontStyle = FontStyle.Italic,
                            fontSize = 13.sp
                        )
                    )
                } else if (!message.text.isNullOrBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    )
                }

                // Metadata: edited, timestamp, read checkmark
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited && !message.isDeleted) {
                        Text(
                            text = "edited",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextMuted,
                                fontSize = 9.sp
                            ),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }

                    val formattedTime = remember(message.createdAt) {
                        MediaUrlUtils.formatMessageTime(message.createdAt)
                    }
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    )

                    if (isOwn) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val isRead = message.readBy.any { it != message.sender?.id }
                        Icon(
                            imageVector = if (isRead) Icons.Default.DoneAll else Icons.Default.Check,
                            contentDescription = if (isRead) "Read" else "Sent",
                            tint = if (isRead) CyanAccent else TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Reactions list under message bubble
        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val grouped = message.reactions.groupBy { it.emoji }
                grouped.forEach { (emoji, reactions) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceElevated,
                        border = BorderStroke(1.dp, BubbleBorder),
                        modifier = Modifier
                            .clickable { onReactionClick(emoji) }
                            .padding(vertical = 1.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = emoji, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = reactions.size.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatAudioTime(ms: Int): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

@Composable
fun VoiceMessagePlayer(
    messageId: Long,
    rawAudioUrl: String?,
    viewModel: ChatRoomViewModel
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isLoadingAudio by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying && mediaPlayer != null) {
                runCatching {
                    currentPositionMs = mediaPlayer?.currentPosition ?: 0
                }
                delay(200)
            }
        }
    }

    DisposableEffect(messageId) {
        onDispose {
            runCatching {
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Pause / Loading Button
            IconButton(
                onClick = {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (rawAudioUrl.isNullOrBlank()) return@IconButton
                        if (mediaPlayer != null) {
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    mediaPlayer?.playbackParams = mediaPlayer?.playbackParams?.setSpeed(playbackSpeed) ?: PlaybackParams()
                                }
                                mediaPlayer?.start()
                                isPlaying = true
                            }
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
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                playbackParams = playbackParams.setSpeed(playbackSpeed)
                                            }
                                            start()
                                            setOnCompletionListener {
                                                isPlaying = false
                                                currentPositionMs = durationMs
                                            }
                                        }
                                        isPlaying = true
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
                    CircularProgressIndicator(strokeWidth = 2.dp, color = CyanAccent, modifier = Modifier.size(22.dp))
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = "Play/Pause Voice",
                        tint = CyanAccent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Audio Progress Slider
            val maxProgress = durationMs.coerceAtLeast(1).toFloat()
            val currentProgress = currentPositionMs.coerceIn(0, durationMs.coerceAtLeast(1)).toFloat()

            Slider(
                value = currentProgress,
                onValueChange = { newPos ->
                    currentPositionMs = newPos.toInt()
                    runCatching {
                        mediaPlayer?.seekTo(newPos.toInt())
                    }
                },
                valueRange = 0f..maxProgress,
                colors = SliderDefaults.colors(
                    thumbColor = CyanAccent,
                    activeTrackColor = CyanAccent,
                    inactiveTrackColor = BubbleBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Playback Speed Button (1x -> 1.5x -> 2x)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceElevated,
                    modifier = Modifier
                        .clickable {
                            playbackSpeed = when (playbackSpeed) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            runCatching {
                                mediaPlayer?.playbackParams = mediaPlayer?.playbackParams?.setSpeed(playbackSpeed) ?: PlaybackParams()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (playbackSpeed == 1.0f) "1x" else if (playbackSpeed == 1.5f) "1.5x" else "2x",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        // Time Indicator Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val timeText = if (durationMs > 0) {
                if (isPlaying || currentPositionMs > 0) {
                    "${formatAudioTime(currentPositionMs)} / ${formatAudioTime(durationMs)}"
                } else {
                    formatAudioTime(durationMs)
                }
            } else {
                "00:00"
            }

            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            )

            Text(
                text = "Voice",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextMuted,
                    fontSize = 10.sp
                )
            )
        }
    }
}
