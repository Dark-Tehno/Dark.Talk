package com.example.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.data.model.Chat
import com.example.data.model.User
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.LocalAppStrings
import com.example.util.MediaUrlUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    currentUserId: Long,
    onChatClick: (chatId: Long, title: String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var isFabMenuOpen by remember { mutableStateOf(false) }

    var directUsername by remember { mutableStateOf("") }
    var groupTitle by remember { mutableStateOf("") }
    var groupMemberQuery by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }

    val accent = CyanAccent
    val onAccent = LocalAppThemeColors.current.buttonContent
    val strings = LocalAppStrings.current
    val anyDialogOpen = uiState.isDirectDialogOpen || uiState.isGroupDialogOpen

    // Возвращаемся из чата -> подтягиваем актуальные unread / последние сообщения
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.loadChats(isBackground = true) }

    LaunchedEffect(uiState.createdChatId) {
        uiState.createdChatId?.let { id ->
            val title = uiState.createdChatTitle
                ?: uiState.chats.firstOrNull { it.id == id }?.title
                ?: "Chat #$id"
            viewModel.clearCreatedChatId()
            onChatClick(id, title)
        }
    }

    LaunchedEffect(uiState.isDirectDialogOpen) { if (!uiState.isDirectDialogOpen) directUsername = "" }
    LaunchedEffect(uiState.isGroupDialogOpen) {
        if (!uiState.isGroupDialogOpen) {
            groupTitle = ""; groupMemberQuery = ""; groupDescription = ""
        }
    }

    // Раньше errorMessage вообще нигде не показывался.
    LaunchedEffect(uiState.errorMessage, anyDialogOpen) {
        val msg = uiState.errorMessage
        if (msg != null && !anyDialogOpen) {
            snackbar.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    val filteredChats = remember(uiState.chats, uiState.searchQuery, uiState.activeFilter) {
        uiState.chats.filter { chat ->
            val matchesFilter = when (uiState.activeFilter) {
                ChatFilter.ALL -> true
                ChatFilter.DIRECT -> chat.chatType == "direct"
                ChatFilter.GROUPS -> chat.chatType == "group"
                ChatFilter.UNREAD -> chat.unreadCount > 0
            }
            val otherUser = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
            val chatName = chat.title ?: otherUser?.username ?: "Chat #${chat.id}"
            val q = uiState.searchQuery
            val matchesSearch = q.isBlank() ||
                    chatName.contains(q, ignoreCase = true) ||
                    (chat.lastMessage?.text?.contains(q, ignoreCase = true) == true)
            matchesFilter && matchesSearch
        }
    }
    val unreadChatsCount = remember(uiState.chats) { uiState.chats.count { it.unreadCount > 0 } }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            DarkTalkTopBar(
                title = "Dark.Talk",
                subtitle = strings.chatsTitle,
                wsState = uiState.wsState,
                actions = {
                    IconButton(
                        onClick = { isSearchExpanded = !isSearchExpanded; if (!isSearchExpanded) viewModel.onSearchQueryChanged("") },
                        modifier = Modifier.testTag("search_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = accent)
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(visible = isFabMenuOpen) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 12.dp)) {
                        ExtendedFloatingActionButton(
                            onClick = { isFabMenuOpen = false; viewModel.openDirectDialog() },
                            containerColor = DarkSurfaceElevated,
                            contentColor = accent,
                            icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                            text = { Text(strings.newDirectChat) },
                            modifier = Modifier.padding(bottom = 8.dp).testTag("fab_new_direct")
                        )
                        ExtendedFloatingActionButton(
                            onClick = { isFabMenuOpen = false; viewModel.openGroupDialog() },
                            containerColor = DarkSurfaceElevated,
                            contentColor = VioletAccent,
                            icon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
                            text = { Text(strings.newGroupChat) },
                            modifier = Modifier.testTag("fab_new_group")
                        )
                    }
                }
                FloatingActionButton(
                    onClick = { isFabMenuOpen = !isFabMenuOpen },
                    containerColor = accent,
                    contentColor = onAccent,
                    shape = CircleShape,
                    modifier = Modifier.testTag("main_chat_fab")
                ) {
                    Icon(
                        imageVector = if (isFabMenuOpen) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = "Create Chat"
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = isSearchExpanded) {
                CyberTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    label = strings.searchChatsPlaceholder,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = accent) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    testTag = "chat_search_input"
                )
            }

            // Папки в стиле Telegram – стеклянные «пилюли»
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatFilter.entries.forEach { filter ->
                    val selected = uiState.activeFilter == filter
                    val pill = RoundedCornerShape(50)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .then(
                                if (selected) {
                                    Modifier
                                        .clip(pill)
                                        .background(accent.copy(alpha = 0.22f))
                                        .border(1.dp, accent.copy(alpha = 0.7f), pill)
                                } else Modifier.glass(pill, strength = 0.7f, baseAlpha = 0.35f)
                            )
                            .clickable { viewModel.onFilterChanged(filter) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("filter_chip_${filter.name.lowercase()}")
                    ) {
                        Text(
                            text = when (filter) {
                                ChatFilter.ALL -> "All"
                                ChatFilter.DIRECT -> "Direct"
                                ChatFilter.GROUPS -> "Groups"
                                ChatFilter.UNREAD -> "Unread"
                            },
                            color = if (selected) accent else TextSecondary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        if (filter == ChatFilter.UNREAD && unreadChatsCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(accent)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(unreadChatsCount.toString(), color = onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading && uiState.chats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            } else if (filteredChats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(84.dp).glass(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = accent, modifier = Modifier.size(38.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No matching chats found" else "No conversations yet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the pencil button to find users and start a chat.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(filteredChats, key = { it.id }) { chat ->
                        ChatItemRow(
                            chat = chat,
                            currentUserId = currentUserId,
                            onClick = {
                                val other = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
                                val title = chat.title ?: other?.username ?: "Chat #${chat.id}"
                                onChatClick(chat.id, title)
                            },
                            onDelete = { viewModel.deleteOrLeaveChat(chat) }
                        )
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.07f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 82.dp)
                        )
                    }
                }
            }
        }

        // ---------- Новый личный чат ----------
        if (uiState.isDirectDialogOpen) {
            GlassDialog(onDismiss = { viewModel.closeDirectDialog() }, accent = accent) {
                Text(strings.newDirectChat, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                Spacer(modifier = Modifier.height(6.dp))
                Text("Search users by username", style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
                Spacer(modifier = Modifier.height(14.dp))

                CyberTextField(
                    value = directUsername,
                    onValueChange = { directUsername = it; viewModel.searchUsers(it) },
                    label = "User Search",
                    placeholder = "Search username...",
                    leadingIcon = { Icon(Icons.Default.PersonSearch, contentDescription = null, tint = accent) },
                    testTag = "direct_username_input"
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (uiState.isSearchingUsers) {
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp))
                    }
                } else if (uiState.userSearchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.userSearchResults, key = { it.id }) { user ->
                            UserSearchResultRow(user = user, onSelect = { viewModel.createDirectChat(user.username) })
                        }
                    }
                }

                if (!uiState.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(uiState.errorMessage.orEmpty(), color = CrimsonError, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.closeDirectDialog() }, modifier = Modifier.testTag("cancel_direct_chat_button")) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    CyberButton(
                        text = "Start Chat",
                        onClick = { viewModel.createDirectChat(directUsername) },
                        enabled = directUsername.isNotBlank(),
                        isLoading = uiState.isLoading,
                        testTag = "create_direct_chat_button"
                    )
                }
            }
        }

        // ---------- Новая группа ----------
        if (uiState.isGroupDialogOpen) {
            GlassDialog(onDismiss = { viewModel.closeGroupDialog() }, accent = VioletAccent) {
                Text(strings.newGroupChat, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                Spacer(modifier = Modifier.height(14.dp))

                CyberTextField(
                    value = groupTitle,
                    onValueChange = { groupTitle = it },
                    label = "Group Title",
                    placeholder = "e.g. Project Team",
                    leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = VioletAccent) },
                    testTag = "group_title_input"
                )
                Spacer(modifier = Modifier.height(10.dp))
                CyberTextField(
                    value = groupDescription,
                    onValueChange = { groupDescription = it },
                    label = "Description (Optional)",
                    placeholder = "Group topic or rules",
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, tint = VioletAccent) },
                    testTag = "group_description_input"
                )
                Spacer(modifier = Modifier.height(10.dp))
                CyberTextField(
                    value = groupMemberQuery,
                    onValueChange = { groupMemberQuery = it; viewModel.searchUsers(it) },
                    label = "Find participants",
                    placeholder = "Type username...",
                    leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, tint = VioletAccent) },
                    testTag = "group_participants_input"
                )

                if (uiState.selectedGroupMembers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // Раньше чипы не прокручивались и вылезали за экран
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        uiState.selectedGroupMembers.forEach { member ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.removeGroupMember(member) },
                                label = { Text(member.username, fontSize = 12.sp) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(14.dp))
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = VioletContainer,
                                    selectedLabelColor = VioletAccent
                                )
                            )
                        }
                    }
                }

                if (uiState.userSearchResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.userSearchResults, key = { it.id }) { user ->
                            UserSearchResultRow(
                                user = user,
                                isSelected = uiState.selectedGroupMembers.any { it.id == user.id },
                                onSelect = { viewModel.toggleSelectGroupMember(user) }
                            )
                        }
                    }
                }

                if (!uiState.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(uiState.errorMessage.orEmpty(), color = CrimsonError, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.closeGroupDialog() }, modifier = Modifier.testTag("cancel_group_chat_button")) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    CyberButton(
                        text = "Create Group",
                        onClick = {
                            val names = uiState.selectedGroupMembers.map { it.username }
                                .ifEmpty { groupMemberQuery.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
                                .joinToString(",")
                            viewModel.createGroupChat(names, groupTitle, groupDescription)
                        },
                        isLoading = uiState.isLoading,
                        testTag = "create_group_chat_button"
                    )
                }
            }
        }
    }
}

@Composable
fun UserSearchResultRow(
    user: User,
    isSelected: Boolean = false,
    onSelect: () -> Unit
) {
    val accent = CyanAccent
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (isSelected) accent else Color.White.copy(alpha = 0.12f), shape)
            .clickable { onSelect() }
            .padding(10.dp)
    ) {
        AvatarView(avatarUrl = user.avatar, displayName = user.username, size = 36.dp, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.username, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
            if (!user.info.isNullOrBlank()) {
                Text(
                    text = user.info,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Add,
            contentDescription = "Select",
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun chatPreview(chat: Chat, currentUserId: Long, isGroup: Boolean): String {
    val fallback = chat.description?.takeIf { it.isNotBlank() } ?: "No messages yet"
    val msg = chat.lastMessage ?: return fallback
    val prefix = when {
        msg.sender?.id != null && msg.sender.id == currentUserId -> "You: "
        isGroup && msg.sender != null && msg.sender.username.isNotBlank() -> "${msg.sender.username}: "
        else -> ""
    }
    val attachment = msg.attachment
    return when {
        msg.isDeleted -> "Message deleted"
        msg.messageType == "voice_message" -> "${prefix}🎙 Voice message"
        msg.messageType == "image" ||
                attachment?.endsWith(".jpg", true) == true ||
                attachment?.endsWith(".jpeg", true) == true ||
                attachment?.endsWith(".png", true) == true -> "${prefix}📷 Photo"
        msg.messageType == "video" -> "${prefix}🎞 Video"
        !attachment.isNullOrBlank() -> "${prefix}📁 ${msg.attachmentName?.takeIf { it.isNotBlank() } ?: "File"}"
        !msg.text.isNullOrBlank() -> "$prefix${msg.text}"
        else -> fallback
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItemRow(
    chat: Chat,
    currentUserId: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = CyanAccent
    val onAccent = LocalAppThemeColors.current.buttonContent
    val isGroup = chat.chatType == "group"
    val otherUser = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
    val chatTitle = if (isGroup) chat.title ?: "Group #${chat.id}" else otherUser?.username ?: chat.title ?: "Chat #${chat.id}"
    val myRole = chat.participants.firstOrNull { it.user?.id == currentUserId }?.role?.lowercase()
    val canDelete = !isGroup || myRole == "owner" || myRole == "admin"
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                .padding(horizontal = 16.dp, vertical = 11.dp)
                .testTag("chat_item_${chat.id}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarView(
                avatarUrl = if (isGroup) chat.avatar else (otherUser?.avatar ?: chat.avatar),
                displayName = chatTitle,
                size = 54.dp,
                isOnline = if (!isGroup) otherUser?.isOnline else null
            )
            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isGroup) {
                        Icon(Icons.Default.Group, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = chatTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = TextPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = chatPreview(chat, currentUserId, isGroup),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (chat.unreadCount > 0) TextPrimary else TextSecondary,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                val time = MediaUrlUtils.formatChatListTime(chat.lastMessage?.createdAt ?: chat.updatedAt ?: chat.createdAt)
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (chat.unreadCount > 0) accent else TextMuted,
                        fontSize = 12.sp
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (chat.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                            color = onAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(22.dp))
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(Color(0xF2141C2E))
        ) {
            DropdownMenuItem(
                text = { Text(if (canDelete) "Delete chat" else "Leave group", color = CrimsonError) },
                leadingIcon = {
                    Icon(
                        if (canDelete) Icons.Default.Delete else Icons.Default.ExitToApp,
                        contentDescription = null,
                        tint = CrimsonError
                    )
                },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}
