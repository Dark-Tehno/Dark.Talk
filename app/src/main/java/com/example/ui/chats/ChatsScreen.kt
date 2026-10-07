package com.example.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import com.example.data.model.Chat
import com.example.data.model.User
import com.example.ui.components.AvatarView
import com.example.ui.components.CyberButton
import com.example.ui.components.CyberTextField
import com.example.ui.components.DarkTalkTopBar
import com.example.ui.theme.*
import com.example.util.MediaUrlUtils

@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    currentUserId: Long,
    onChatClick: (chatId: Long, title: String) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var isSearchExpanded by remember { mutableStateOf(false) }
    var isFabMenuOpen by remember { mutableStateOf(false) }

    // Dialog inputs
    var directUsername by remember { mutableStateOf("") }
    var groupTitle by remember { mutableStateOf("") }
    var groupMemberQuery by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }

    // Navigate to created chat if any
    LaunchedEffect(uiState.createdChatId) {
        uiState.createdChatId?.let { id ->
            val chat = uiState.chats.firstOrNull { it.id == id }
            val title = chat?.title ?: "Chat #$id"
            viewModel.clearCreatedChatId()
            onChatClick(id, title)
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
            val matchesSearch = uiState.searchQuery.isBlank() ||
                    chatName.contains(uiState.searchQuery, ignoreCase = true) ||
                    (chat.lastMessage?.text?.contains(uiState.searchQuery, ignoreCase = true) == true)

            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            DarkTalkTopBar(
                title = "Dark.Talk",
                subtitle = "Chats",
                wsState = uiState.wsState,
                actions = {
                    IconButton(
                        onClick = { isSearchExpanded = !isSearchExpanded },
                        modifier = Modifier.testTag("search_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = CyanAccent
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(visible = isFabMenuOpen) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabMenuOpen = false
                                viewModel.openDirectDialog()
                            },
                            containerColor = DarkSurfaceElevated,
                            contentColor = CyanAccent,
                            icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                            text = { Text("New Direct Chat") },
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .testTag("fab_new_direct")
                        )

                        ExtendedFloatingActionButton(
                            onClick = {
                                isFabMenuOpen = false
                                viewModel.openGroupDialog()
                            },
                            containerColor = DarkSurfaceElevated,
                            contentColor = VioletAccent,
                            icon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
                            text = { Text("New Group Chat") },
                            modifier = Modifier.testTag("fab_new_group")
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { isFabMenuOpen = !isFabMenuOpen },
                    containerColor = CyanAccent,
                    contentColor = Color(0xFF001F28),
                    shape = CircleShape,
                    modifier = Modifier.testTag("main_chat_fab")
                ) {
                    Icon(
                        imageVector = if (isFabMenuOpen) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Create Chat"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            AnimatedVisibility(visible = isSearchExpanded) {
                Surface(
                    color = DarkSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CyberTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        label = "Search chats or messages",
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanAccent) },
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
            }

            // Filter Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatFilter.entries.forEach { filter ->
                    val isSelected = uiState.activeFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onFilterChanged(filter) },
                        label = {
                            Text(
                                text = when (filter) {
                                    ChatFilter.ALL -> "All"
                                    ChatFilter.DIRECT -> "Direct"
                                    ChatFilter.GROUPS -> "Groups"
                                    ChatFilter.UNREAD -> "Unread"
                                },
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanAccentContainer,
                            selectedLabelColor = CyanAccent,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) CyanAccent else BubbleBorder
                        ),
                        modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                    )
                }
            }

            // Chat List or Empty State
            if (uiState.isLoading && uiState.chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyanAccent)
                }
            } else if (filteredChats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = DarkSurfaceElevated,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No matching chats found" else "No conversations yet",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the + button to search users and start a chat.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextSecondary
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredChats, key = { it.id }) { chat ->
                        ChatItemRow(
                            chat = chat,
                            currentUserId = currentUserId,
                            onClick = {
                                val otherUser = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
                                val title = chat.title ?: otherUser?.username ?: "Chat #${chat.id}"
                                onChatClick(chat.id, title)
                            },
                            onDelete = { viewModel.deleteChat(chat.id) }
                        )
                        HorizontalDivider(
                            color = BubbleBorder.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 76.dp)
                        )
                    }
                }
            }
        }

        // CREATE DIRECT CHAT DIALOG WITH LIVE USER SEARCH
        if (uiState.isDirectDialogOpen) {
            Dialog(onDismissRequest = { viewModel.closeDirectDialog() }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "New Direct Chat",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Search users by username:",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        CyberTextField(
                            value = directUsername,
                            onValueChange = {
                                directUsername = it
                                viewModel.searchUsers(it)
                            },
                            label = "User Search",
                            placeholder = "Search username...",
                            leadingIcon = { Icon(Icons.Default.PersonSearch, contentDescription = null, tint = CyanAccent) },
                            testTag = "direct_username_input"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // User Search Results List
                        if (uiState.isSearchingUsers) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(24.dp))
                            }
                        } else if (uiState.userSearchResults.isNotEmpty()) {
                            Text(
                                text = "Search Results:",
                                style = MaterialTheme.typography.labelSmall.copy(color = CyanAccent)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(uiState.userSearchResults, key = { it.id }) { user ->
                                    UserSearchResultRow(
                                        user = user,
                                        onSelect = {
                                            directUsername = user.username
                                            viewModel.createDirectChat(user.username)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { viewModel.closeDirectDialog() },
                                modifier = Modifier.testTag("cancel_direct_chat_button")
                            ) {
                                Text("Cancel", color = TextSecondary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            CyberButton(
                                text = "Start Chat",
                                onClick = {
                                    viewModel.createDirectChat(directUsername)
                                    directUsername = ""
                                },
                                isLoading = uiState.isLoading,
                                testTag = "create_direct_chat_button"
                            )
                        }
                    }
                }
            }
        }

        // CREATE GROUP CHAT DIALOG WITH USER SEARCH & CHIPS
        if (uiState.isGroupDialogOpen) {
            Dialog(onDismissRequest = { viewModel.closeGroupDialog() }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, VioletAccent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "New Group Chat",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        CyberTextField(
                            value = groupTitle,
                            onValueChange = { groupTitle = it },
                            label = "Group Title",
                            placeholder = "e.g. Project Team",
                            leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = VioletAccent) },
                            testTag = "group_title_input"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        CyberTextField(
                            value = groupDescription,
                            onValueChange = { groupDescription = it },
                            label = "Description (Optional)",
                            placeholder = "Group topic or rules",
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, tint = VioletAccent) },
                            testTag = "group_description_input"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Search & Add Participants",
                            style = MaterialTheme.typography.labelMedium.copy(color = VioletAccent)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        CyberTextField(
                            value = groupMemberQuery,
                            onValueChange = {
                                groupMemberQuery = it
                                viewModel.searchUsers(it)
                            },
                            label = "Find User",
                            placeholder = "Type username...",
                            leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, tint = VioletAccent) },
                            testTag = "group_participants_input"
                        )

                        // Selected Member Chips
                        if (uiState.selectedGroupMembers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.selectedGroupMembers.forEach { member ->
                                    InputChip(
                                        selected = true,
                                        onClick = { viewModel.removeGroupMember(member) },
                                        label = { Text(member.username, fontSize = 12.sp) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = TextMuted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        },
                                        colors = InputChipDefaults.inputChipColors(
                                            selectedContainerColor = VioletContainer,
                                            selectedLabelColor = VioletAccent
                                        )
                                    )
                                }
                            }
                        }

                        // Search Results for Group
                        if (uiState.userSearchResults.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(uiState.userSearchResults, key = { it.id }) { user ->
                                    val isSelected = uiState.selectedGroupMembers.any { it.id == user.id }
                                    UserSearchResultRow(
                                        user = user,
                                        isSelected = isSelected,
                                        onSelect = { viewModel.toggleSelectGroupMember(user) }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { viewModel.closeGroupDialog() },
                                modifier = Modifier.testTag("cancel_group_chat_button")
                            ) {
                                Text("Cancel", color = TextSecondary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            CyberButton(
                                text = "Create Group",
                                onClick = {
                                    val usernamesStr = uiState.selectedGroupMembers
                                        .map { it.username }
                                        .ifEmpty { groupMemberQuery.split(",").map { it.trim() } }
                                        .joinToString(",")

                                    viewModel.createGroupChat(usernamesStr, groupTitle, groupDescription)
                                    groupTitle = ""
                                    groupMemberQuery = ""
                                    groupDescription = ""
                                },
                                isLoading = uiState.isLoading,
                                testTag = "create_group_chat_button"
                            )
                        }
                    }
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
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) VioletContainer else DarkSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) VioletAccent else BubbleBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarView(
                avatarUrl = user.avatar,
                displayName = user.username,
                size = 36.dp,
                isOnline = user.isOnline
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.username,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
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
                tint = if (isSelected) VioletAccent else CyanAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ChatItemRow(
    chat: Chat,
    currentUserId: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isGroup = chat.chatType == "group"
    val otherUser = chat.participants.firstOrNull { it.user?.id != currentUserId }?.user
    val chatTitle = if (isGroup) {
        chat.title ?: "Group #${chat.id}"
    } else {
        otherUser?.username ?: chat.title ?: "Chat #${chat.id}"
    }

    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("chat_item_${chat.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        val avatarUrl = if (isGroup) chat.avatar else (otherUser?.avatar ?: chat.avatar)
        AvatarView(
            avatarUrl = avatarUrl,
            displayName = chatTitle,
            size = 50.dp,
            isOnline = if (!isGroup) otherUser?.isOnline else null
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Title and Last Message
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chatTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isGroup) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = VioletContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Group",
                            color = VioletAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val lastMsgText = chat.lastMessage?.let { msg ->
                val senderPrefix = if (isGroup && msg.sender != null) "${msg.sender.username}: " else ""
                if (msg.isDeleted) {
                    "Message deleted"
                } else if (msg.messageType == "voice_message") {
                    "${senderPrefix}🎙 Voice message"
                } else if (msg.messageType == "image" || msg.attachment?.endsWith(".jpg", ignoreCase = true) == true || msg.attachment?.endsWith(".png", ignoreCase = true) == true) {
                    "${senderPrefix}📷 Photo"
                } else if (!msg.attachment.isNullOrBlank()) {
                    val fileName = msg.attachmentName?.takeIf { it.isNotBlank() } ?: "File"
                    "${senderPrefix}📁 File: $fileName"
                } else if (!msg.text.isNullOrBlank()) {
                    "${senderPrefix}${msg.text}"
                } else {
                    chat.description?.takeIf { it.isNotBlank() } ?: "No messages yet"
                }
            } ?: (chat.description?.takeIf { it.isNotBlank() } ?: "No messages yet")

            Text(
                text = lastMsgText,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (chat.unreadCount > 0) TextPrimary else TextSecondary,
                    fontWeight = if (chat.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Time and Unread Count
        Column(
            horizontalAlignment = Alignment.End
        ) {
            val timeSource = chat.lastMessage?.createdAt ?: chat.updatedAt ?: chat.createdAt
            val formattedTime = MediaUrlUtils.formatMessageTime(timeSource)
            if (formattedTime.isNotBlank()) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(CyanAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                        color = Color(0xFF001F28),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // More Options Dropdown
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkSurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete Chat", color = CrimsonError) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = CrimsonError)
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
