package me.rerere.rikkahub.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.remote.p14.P14InboxRow
import me.rerere.rikkahub.data.remote.p14.P14ProductMessageEntity
import me.rerere.rikkahub.data.remote.p14.p14VisibleEntryMessages
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Reference: TickTick Android Inbox's quiet list hierarchy, row affordances and quick add.
// Product identity is P14 Entry, never a second TickTick task model or a new Hermes session.
private val inboxBlue = Color(0xFF4777D5)
private val inboxBackground = Color(0xFFF6F7FB)
private val inboxMuted = Color(0xFF8B92A1)
private val inboxInk = Color(0xFF252B36)

internal fun p14InboxTitle(body: String, entryId: String): String =
    body.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        ?.take(120) ?: "未命名条目"

internal fun p14InboxPreview(body: String): String =
    body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.drop(1)
        .take(2).joinToString(" ").take(150)

internal fun p14InboxFiltered(rows: List<P14InboxRow>, query: String): List<P14InboxRow> {
    val normalized = query.trim()
    return if (normalized.isBlank()) rows
    else rows.filter { it.bodyText.contains(normalized, ignoreCase = true) }
}

/** The original Capture message is always shown in its own, pinned Entry source area.
 * Do not remove a later user message merely because it repeats the same text.
 */
internal fun p14InboxConversationMessages(
    messages: List<P14ProductMessageEntity>,
    entryBody: String,
    entryCreatedAt: Long,
): List<P14ProductMessageEntity> {
    val visible = p14VisibleEntryMessages(messages)
    val source = visible.firstOrNull {
        it.role.equals("user", ignoreCase = true) &&
            it.bodyText.trim() == entryBody.trim() &&
            kotlin.math.abs(it.createdAtEpochMs - entryCreatedAt) <= 15_000L
    }
    return if (source == null) visible else visible.filterNot { it.messageId == source.messageId }
}

private fun inboxDate(epoch: Long): String =
    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(epoch))

@Composable
private fun InboxGroupTitle(name: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 7.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color(0xFF697181), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), color = inboxMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InboxItemRow(
    row: P14InboxRow,
    archived: Boolean,
    onOpen: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 10.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
            .testTag("p14-inbox-item-${row.entryId}"),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(
            onClick = onToggleArchive,
            modifier = Modifier.size(36.dp).testTag("p14-inbox-toggle-${row.entryId}"),
        ) {
            Box(
                Modifier.size(21.dp).background(
                    color = if (archived) inboxBlue else Color.Transparent,
                    shape = RoundedCornerShape(5.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (archived) Text("✓", color = Color.White, fontSize = 18.sp)
                else Box(
                    Modifier.size(19.dp).background(Color.Transparent, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // TickTick-style unfilled completion square. In P14 this explicitly means archive.
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFADB3BF)),
                        modifier = Modifier.size(18.dp),
                    ) {}
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = p14InboxTitle(row.bodyText, row.entryId),
                color = if (archived) inboxMuted else inboxInk,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 23.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val preview = p14InboxPreview(row.bodyText)
            if (preview.isNotBlank()) Text(
                preview, color = inboxMuted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(inboxDate(row.createdAtEpochMs), color = inboxBlue, style = MaterialTheme.typography.labelSmall)
                if (row.pinned) {
                    Spacer(Modifier.width(9.dp))
                    Text("置顶", color = inboxMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun InboxGroup(
    title: String,
    rows: List<P14InboxRow>,
    archived: Boolean,
    onOpen: (String) -> Unit,
    onToggleArchive: (P14InboxRow) -> Unit,
) {
    if (rows.isEmpty()) return
    InboxGroupTitle(title, rows.size)
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { index, row ->
                InboxItemRow(
                    row = row, archived = archived,
                    onOpen = { onOpen(row.conversationId) },
                    onToggleArchive = { onToggleArchive(row) },
                )
                if (index < rows.lastIndex) HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = Color(0xFFF0F1F5),
                    thickness = 0.7.dp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun P14TickTickInboxScreen(
    rows: List<P14InboxRow>,
    archived: Boolean,
    onShowArchived: (Boolean) -> Unit,
    draftText: String,
    onDraftChange: (String) -> Unit,
    saving: Boolean,
    status: String?,
    onQuickSave: () -> Unit,
    onRichCapture: () -> Unit,
    onOpen: (String) -> Unit,
    onToggleArchive: (P14InboxRow) -> Unit,
    onMore: () -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    onBack: () -> Unit,
    resumeQuickDraft: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(resumeQuickDraft) }
    val display = p14InboxFiltered(rows, query)
    val pinned = display.filter { it.pinned }
    val remaining = display.filterNot { it.pinned }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(26.dp))
                Text("P14 Entry", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.headlineSmall)
                NavigationDrawerItem(
                    label = { Text("收件箱") }, selected = !archived,
                    onClick = { onShowArchived(false); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("已归档") }, selected = archived,
                    onClick = { onShowArchived(true); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                NavigationDrawerItem(
                    label = { Text("高级功能 / 旧入口") }, selected = false,
                    onClick = { scope.launch { drawer.close() }; onMore() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            containerColor = inboxBackground,
            topBar = {
                TopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = inboxBackground,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Text("☰", color = inboxInk, fontSize = 22.sp)
                        }
                    },
                    title = {
                        Text(
                            if (archived) "已归档" else "收件箱",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = inboxInk,
                        )
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Text(if (searchOpen) "×" else "⌕", color = inboxInk, fontSize = 25.sp)
                        }
                        IconButton(onClick = onMore) {
                            Text("⋮", color = inboxInk, fontSize = 25.sp)
                        }
                    },
                )
            },
            floatingActionButton = {
                if (!archived) FloatingActionButton(
                    onClick = { addOpen = true },
                    containerColor = inboxBlue, contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.testTag("p14-inbox-add"),
                ) { Text("+", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Light) }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (searchOpen) OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("p14-inbox-search"),
                    placeholder = { Text("搜索收件箱") }, singleLine = true,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("p14-inbox-list"),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp, top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (status != null) item(key = "inbox-status") {
                        Text(status, color = inboxBlue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (display.isEmpty()) item(key = "inbox-empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 100.dp), contentAlignment = Alignment.Center) {
                            Text(if (archived) "还没有已归档的条目" else "收件箱是空的，点 + 随时记录想法",
                                color = inboxMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (pinned.isNotEmpty()) item(key = "pinned") {
                        InboxGroup("置顶", pinned, archived, onOpen, onToggleArchive)
                    }
                    if (remaining.isNotEmpty()) item(key = "items") {
                        InboxGroup(if (archived) "已归档" else "未整理", remaining, archived, onOpen, onToggleArchive)
                    }
                    if (hasMore && query.isBlank()) item(key = "more") {
                        TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                    }
                }
            }
        }
    }
    if (addOpen && !archived) ModalBottomSheet(onDismissRequest = { addOpen = false }) {
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("添加到收件箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = draftText, onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().testTag("p14-inbox-quick-input"),
                placeholder = { Text("有什么想记录的？") },
                minLines = 2, maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draftText.isNotBlank() && !saving) onQuickSave()
                }),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { addOpen = false; onRichCapture() }) { Text("添加图片、语音或文件") }
                Button(
                    enabled = draftText.isNotBlank() && !saving,
                    onClick = { onQuickSave(); addOpen = false },
                    modifier = Modifier.testTag("p14-inbox-quick-save"),
                ) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun P14TickTickEntryDetailScreen(
    entryBody: String?,
    createdAt: Long?,
    conversationId: String,
    messages: List<P14ProductMessageEntity>,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    canChat: Boolean,
    status: String?,
    onBack: () -> Unit,
    onAdvanced: () -> Unit,
) {
    val source = entryBody.orEmpty()
    val visibleMessages = p14InboxConversationMessages(messages, source, createdAt ?: 0L)
    Scaffold(
        containerColor = inboxBackground,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = inboxBackground),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", color = inboxInk, fontSize = 32.sp)
                    }
                },
                title = { Text("条目详情", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onAdvanced) { Text("⋮", color = inboxInk, fontSize = 25.sp) }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = input, onValueChange = onInputChange,
                        modifier = Modifier.weight(1f).testTag("p14-entry-message-input"),
                        placeholder = { Text("围绕这条内容问 AI…") },
                        enabled = canChat, maxLines = 4,
                    )
                    IconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() && !sending && canChat,
                        modifier = Modifier.size(52.dp).background(
                            if (input.isNotBlank() && canChat) inboxBlue else Color(0xFFE9ECF2),
                            CircleShape,
                        ).testTag("p14-inbox-ai-send"),
                    ) {
                        Text("➤", color = if (input.isNotBlank() && canChat) Color.White else inboxMuted,
                            fontSize = 22.sp)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("p14-inbox-detail"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            item(key = "source-card") {
                Surface(
                    color = Color.White, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("p14-entry-body"),
                ) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("原始内容", color = inboxBlue, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium)
                        Text(source.ifBlank { "正在恢复原始 Entry…" }, color = inboxInk,
                            style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                        if (createdAt != null) Text(inboxDate(createdAt),
                            style = MaterialTheme.typography.labelSmall, color = inboxMuted)
                    }
                }
            }
            item(key = "conversation-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI 对话", color = inboxInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("此 Entry 专属上下文", color = inboxMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (visibleMessages.isEmpty()) item(key = "conversation-empty") {
                Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("从这里继续讨论", style = MaterialTheme.typography.titleMedium, color = inboxInk)
                        Text("AI 会围绕上方的原始内容和此 Entry 的历史对话回复。",
                            style = MaterialTheme.typography.bodySmall, color = inboxMuted)
                    }
                }
            }
            items(visibleMessages, key = { it.messageId }) { message ->
                val user = message.role.equals("user", ignoreCase = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (user) Color(0xFFE8F0FF) else Color.White,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(if (user) 0.88f else 0.94f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (user) "我" else "AI",
                                color = if (user) inboxBlue else inboxMuted,
                                style = MaterialTheme.typography.labelSmall)
                            Text(if (message.tombstone) "消息已删除" else message.bodyText,
                                color = inboxInk, style = MaterialTheme.typography.bodyMedium)
                            if (message.deliveryState != "SYNCED") Text(message.deliveryState,
                                color = inboxMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (status != null) item(key = "detail-status") {
                Text(status, color = inboxBlue, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
