package me.rerere.rikkahub.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import me.rerere.rikkahub.data.remote.p14.P14ProductAttachmentEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
// Visual baseline approved by the user: a restrained Inbox, readable detail, optional AI,
// and system-following light/dark appearances. No invented task completion or tag data.
private data class InboxPalette(
    val blue: Color,
    val background: Color,
    val surface: Color,
    val muted: Color,
    val ink: Color,
    val divider: Color,
    val userBubble: Color,
    val disabled: Color,
)
private val inboxLight = InboxPalette(
    blue = Color(0xFF4777D5),
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    muted = Color(0xFF747F90),
    ink = Color(0xFF252B36),
    divider = Color(0xFFF0F1F5),
    userBubble = Color(0xFFE8F0FF),
    disabled = Color(0xFFE9ECF2),
)
private val inboxDark = InboxPalette(
    blue = Color(0xFF78A2FF),
    background = Color(0xFF0E1727),
    surface = Color(0xFF182438),
    muted = Color(0xFF9CAAC0),
    ink = Color(0xFFF2F5FA),
    divider = Color(0xFF28374A),
    userBubble = Color(0xFF233E67),
    disabled = Color(0xFF314054),
)
@Composable
private fun inboxColors(): InboxPalette = if (isSystemInDarkTheme()) inboxDark else inboxLight

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
    val colors = inboxColors()
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 7.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = colors.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), color = colors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InboxItemRow(
    row: P14InboxRow,
    archived: Boolean,
    onOpen: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = inboxColors()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 10.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
            .testTag("p14-inbox-item-${row.entryId}"),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(
            onClick = onToggleArchive,
            modifier = Modifier.size(48.dp).testTag("p14-inbox-toggle-${row.entryId}")
                .semantics { contentDescription = if (archived) "移回收件箱" else "归档记录" },
        ) {
            Box(
                Modifier.size(21.dp).background(
                    color = if (archived) colors.blue else Color.Transparent,
                    shape = RoundedCornerShape(5.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (archived) Text("↶", color = Color.White, fontSize = 18.sp)
                else Box(
                    Modifier.size(19.dp).background(Color.Transparent, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // P14 archive is never a task completion state.
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
                color = if (archived) colors.muted else colors.ink,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 23.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val preview = p14InboxPreview(row.bodyText)
            if (preview.isNotBlank()) Text(
                preview, color = colors.muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(inboxDate(row.createdAtEpochMs), color = colors.blue, style = MaterialTheme.typography.labelSmall)
                if (row.pinned) {
                    Spacer(Modifier.width(9.dp))
                    Text("置顶", color = colors.muted, style = MaterialTheme.typography.labelSmall)
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
    val colors = inboxColors()
    if (rows.isEmpty()) return
    InboxGroupTitle(title, rows.size)
    Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { index, row ->
                InboxItemRow(
                    row = row, archived = archived,
                    onOpen = { onOpen(row.conversationId) },
                    onToggleArchive = { onToggleArchive(row) },
                )
                if (index < rows.lastIndex) HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colors.divider,
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
    val colors = inboxColors()
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
            containerColor = colors.background,
            topBar = {
                TopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Text("☰", color = colors.ink, fontSize = 22.sp)
                        }
                    },
                    title = {
                        Text(
                            if (archived) "已归档" else "收件箱",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = colors.ink,
                        )
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Text(if (searchOpen) "×" else "⌕", color = colors.ink, fontSize = 25.sp)
                        }
                        IconButton(onClick = onMore) {
                            Text("⋮", color = colors.ink, fontSize = 25.sp)
                        }
                    },
                )
            },
            floatingActionButton = {
                if (!archived) FloatingActionButton(
                    onClick = { addOpen = true },
                    containerColor = colors.blue, contentColor = Color.White,
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
                // Archive is not task completion. Expose only states P14 actually persists.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterChip(
                        selected = !archived,
                        onClick = { onShowArchived(false) },
                        label = { Text("全部") },
                        modifier = Modifier.testTag("p14-inbox-all-tab"),
                    )
                    FilterChip(
                        selected = archived,
                        onClick = { onShowArchived(true) },
                        label = { Text("已归档") },
                        modifier = Modifier.testTag("p14-inbox-archived-tab"),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("p14-inbox-list"),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp, top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (status != null) item(key = "inbox-status") {
                        Text(status, color = colors.blue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (display.isEmpty()) item(key = "inbox-empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 100.dp), contentAlignment = Alignment.Center) {
                            Text(if (archived) "还没有已归档的条目" else "收件箱是空的，点 + 随时记录想法",
                                color = colors.muted, style = MaterialTheme.typography.bodyMedium)
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
    attachments: List<P14ProductAttachmentEntity> = emptyList(),
    onOpenAttachment: (P14ProductAttachmentEntity) -> Unit = {},
    availableViews: List<String> = emptyList(),
    selectedViews: Set<String> = emptySet(),
    onToggleView: (String) -> Unit = {},
) {
    val colors = inboxColors()
    // A saved Entry is useful on its own. Conversation is an explicit secondary choice.
    var aiOpen by rememberSaveable(conversationId) { mutableStateOf(false) }
    var categoriesOpen by rememberSaveable(conversationId) { mutableStateOf(false) }
    BackHandler { if (categoriesOpen) categoriesOpen = false else if (aiOpen) aiOpen = false else onBack() }
    if (categoriesOpen) {
        Scaffold(containerColor = colors.background, topBar = {
            TopAppBar(title = { Text("标签与分类", color = colors.ink) },
                navigationIcon = { TextButton(onClick = { categoriesOpen = false }) { Text("返回") } })
        }) { padding ->
            Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("分类视图", color = colors.ink, style = MaterialTheme.typography.titleMedium)
                Text("同一记录可以出现在多个视图中。", color = colors.muted)
                availableViews.forEach { view ->
                    FilterChip(selected = view in selectedViews, onClick = { onToggleView(view) },
                        label = { Text(when(view) { "todo" -> "待办"; "idea" -> "想法"; "research" -> "研究"; "execute" -> "执行"; else -> view }) },
                        modifier = Modifier.fillMaxWidth().testTag("p14-entry-view-$view"))
                }
                if (availableViews.isEmpty()) Text("当前配置没有可用分类视图", color = colors.muted)
                HorizontalDivider()
                Text("自定义标签、日历和提醒：待实现", color = colors.muted)
                Text("仅展示当前协议支持的分类能力。", color = colors.muted)
            }
        }
        return
    }
    val source = entryBody.orEmpty()
    val visibleMessages = p14InboxConversationMessages(messages, source, createdAt ?: 0L)
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                navigationIcon = {
                    IconButton(
                        onClick = { if (aiOpen) aiOpen = false else onBack() },
                        modifier = Modifier.testTag("p14-entry-back"),
                    ) {
                        Text("‹", color = colors.ink, fontSize = 32.sp)
                    }
                },
                title = { Text(if (aiOpen) "与 AI 讨论" else "条目详情", color = colors.ink,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (!aiOpen) IconButton(onClick = onAdvanced) {
                        Text("⋮", color = colors.ink, fontSize = 25.sp)
                    }
                },
            )
        },
        bottomBar = {
            if (!aiOpen) {
                Surface(shadowElevation = 4.dp, color = colors.surface) {
                    Button(
                        onClick = { aiOpen = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("p14-inbox-open-ai"),
                    ) { Text("与 AI 讨论（可选）") }
                }
            } else Surface(shadowElevation = 8.dp, color = colors.surface) {
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
                            if (input.isNotBlank() && canChat) colors.blue else colors.disabled,
                            CircleShape,
                        ).testTag("p14-inbox-ai-send"),
                    ) {
                        Text("➤", color = if (input.isNotBlank() && canChat) Color.White else colors.muted,
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
                    color = colors.surface, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("p14-entry-body"),
                ) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (aiOpen) "当前记录 · AI 上下文" else "原始内容",
                            color = colors.blue, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium)
                        if (!aiOpen) {
                            Text(p14InboxTitle(source, conversationId), color = colors.ink,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        // A one-line Entry is a title, not two identical paragraphs.
                        val detailText = if (aiOpen) source else
                            source.lineSequence().drop(1).joinToString("\n").trim()
                        if (detailText.isNotBlank() || source.isBlank()) Text(
                            if (aiOpen) detailText.take(220)
                            else detailText.ifBlank { "正在恢复原始 Entry…" },
                            color = colors.ink, style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 25.sp, maxLines = if (aiOpen) 5 else Int.MAX_VALUE,
                        )
                        if (createdAt != null) Text(inboxDate(createdAt),
                            style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    }
                }
            }
            if (!aiOpen && attachments.isNotEmpty()) item(key = "entry-attachments") {
                Surface(color = colors.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("附件", color = colors.ink, style = MaterialTheme.typography.titleMedium)
                        attachments.forEach { attachment ->
                            TextButton(onClick = { onOpenAttachment(attachment) },
                                modifier = Modifier.testTag("p14-entry-attachment-${attachment.localAttachmentId}")) {
                                Text("${attachment.fileName} · ${attachment.kind.lowercase()}")
                            }
                        }
                    }
                }
            }
            if (!aiOpen) item(key = "entry-organization") {
                TextButton(onClick = { categoriesOpen = true }, modifier = Modifier.testTag("p14-entry-categories")) {
                    Text("标签与分类")
                }
            }
            if (!aiOpen) item(key = "detail-independent") {
                Text(
                    "这条记录已独立保存。你可以直接返回收件箱，也可以选择与 AI 讨论。",
                    color = colors.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                )
            }
            if (aiOpen) item(key = "conversation-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI 对话", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("此 Entry 专属上下文", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (aiOpen && visibleMessages.isEmpty()) item(key = "conversation-empty") {
                Surface(color = colors.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("从这里继续讨论", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        Text("AI 会围绕上方的原始内容和此 Entry 的历史对话回复。",
                            style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
            }
            if (aiOpen) items(visibleMessages, key = { it.messageId }) { message ->
                val user = message.role.equals("user", ignoreCase = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (user) colors.userBubble else colors.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(if (user) 0.88f else 0.94f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (user) "我" else "AI",
                                color = if (user) colors.blue else colors.muted,
                                style = MaterialTheme.typography.labelSmall)
                            Text(if (message.tombstone) "消息已删除" else message.bodyText,
                                color = colors.ink, style = MaterialTheme.typography.bodyMedium)
                            if (message.deliveryState != "SYNCED") Text(message.deliveryState,
                                color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (status != null && aiOpen) item(key = "detail-status") {
                Text(status, color = colors.blue, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
