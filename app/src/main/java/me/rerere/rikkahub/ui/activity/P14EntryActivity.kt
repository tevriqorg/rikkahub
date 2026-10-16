package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.p14.*
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import okhttp3.OkHttpClient
import org.koin.android.ext.android.get

class P14EntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val http = get<OkHttpClient>()
        val product = P14ProductRepository(applicationContext, http)
        val remote = P14RemoteRepository(applicationContext, get<SettingsStore>(), http)
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        val interactionResponse = intent.getStringExtra(EXTRA_INTERACTION_RESPONSE)
        if (notificationId != null) lifecycleScope.launch {
            remote.queueState(notificationId, "OPENED")
            if (!interactionResponse.isNullOrBlank()) remote.queueInteraction(notificationId, interactionResponse)
            P14RemoteWork.enqueue(this@P14EntryActivity)
        }
        setContent {
            RikkahubTheme {
                P14EntryScreen(
                    product = product,
                    remote = remote,
                    initialConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                    externalSessionId = intent.getStringExtra(EXTRA_EXTERNAL_SESSION_ID),
                    notificationId = notificationId,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "p14_conversation_id"
        const val EXTRA_EXTERNAL_SESSION_ID = "p14_external_session_id"
        const val EXTRA_NOTIFICATION_ID = "p14_notification_id"
        const val EXTRA_INTERACTION_RESPONSE = "p14_interaction_response"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P14EntryScreen(
    product: P14ProductRepository,
    remote: P14RemoteRepository,
    initialConversationId: String?,
    externalSessionId: String?,
    notificationId: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var conversationId by remember { mutableStateOf(initialConversationId) }
    var entry by remember { mutableStateOf<P14EntryEntity?>(null) }
    var manifest by remember { mutableStateOf(fallbackP14Manifest()) }
    var notification by remember { mutableStateOf<P14NotificationEntity?>(null) }
    var interaction by remember { mutableStateOf<P14InteractionEntity?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshContext by remember { mutableIntStateOf(0) }

    LaunchedEffect(initialConversationId, externalSessionId) {
        if (conversationId == null && externalSessionId != null) {
            conversationId = product.conversationForExternal(externalSessionId)?.conversationId
        }
        conversationId?.let { id ->
            entry = product.conversation(id)?.let { product.entry(it.entryId) }
        }
        manifest = remote.manifestActive()
    }
    LaunchedEffect(notificationId, refreshContext) {
        notification = notificationId?.let { remote.notification(it) }
        interaction = notificationId?.let { remote.interaction(it) }
    }

    val messages = if (conversationId != null) product.messages(conversationId!!).collectAsState(initial = emptyList()).value else emptyList()
    val attachments = if (conversationId != null) product.attachments(conversationId!!).collectAsState(initial = emptyList()).value else emptyList()
    val canText = "chat" in manifest.enabledViews && "text" in manifest.messageCapabilities
    val upgradeRequired = manifest.compatibility == "UPGRADE_REQUIRED"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("P14 Entry") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        bottomBar = {
            if (conversationId != null) Column(Modifier.fillMaxWidth().padding(12.dp)) {
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        enabled = canText && !upgradeRequired,
                        placeholder = { Text(if (upgradeRequired) "Client upgrade required" else "Continue this Entry") },
                    )
                    Button(
                        enabled = input.isNotBlank() && canText && !upgradeRequired,
                        onClick = {
                            val id = conversationId ?: return@Button
                            val text = input
                            input = ""
                            scope.launch {
                                runCatching {
                                    product.acceptEntryContinuation(id, text)
                                    P14RemoteWork.enqueue(context)
                                }.onFailure { status = it.message }
                            }
                        },
                    ) { Text("Send") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Entry", style = MaterialTheme.typography.titleMedium)
                        Text(entry?.bodyText ?: "Waiting for authoritative Entry replay…")
                        Text(
                            "Manifest: ${manifest.compatibility} · ${manifest.source}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            notification?.let { n ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(n.title, style = MaterialTheme.typography.titleMedium)
                            Text(n.body)
                            interaction?.let { i ->
                                Text(i.prompt)
                                if (i.actionable && i.status == "PENDING" && "notification_interaction" in manifest.messageCapabilities) {
                                    val options = runCatching { Json.decodeFromString<List<P14InteractionOption>>(i.optionsJson) }.getOrDefault(emptyList())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        options.take(3).forEach { option ->
                                            Button(onClick = {
                                                scope.launch {
                                                    remote.queueInteraction(n.notificationId, option.id)
                                                    P14RemoteWork.enqueue(context)
                                                    refreshContext++
                                                }
                                            }) { Text(option.label) }
                                        }
                                    }
                                } else Text(i.status, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (manifest.quickActionTypes.isNotEmpty() && conversationId != null) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Quick Actions", style = MaterialTheme.typography.titleSmall)
                            manifest.quickActionTypes.forEach { actionType ->
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        status = runCatching { remote.quickActionActive(conversationId!!, actionType) }
                                            .fold({ "Quick Action accepted: $actionType" }, { it.message ?: "Quick Action failed" })
                                    }
                                }) { Text(actionType) }
                            }
                        }
                    }
                }
            }
            items(messages, key = { it.messageId }) { message ->
                val count = attachments.count { it.messageId == message.messageId }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(message.role.uppercase(), style = MaterialTheme.typography.labelSmall)
                        Text(if (message.tombstone) "[deleted]" else message.bodyText)
                        if (count > 0) Text("$count attachment(s)", style = MaterialTheme.typography.labelSmall)
                        Text("${message.deliveryState} · rev ${message.revision}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
