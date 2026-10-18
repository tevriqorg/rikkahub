package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
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
        val settingsStore = get<SettingsStore>()
        val remote = P14RemoteRepository(applicationContext, settingsStore, http)
        val sharedText = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getStringExtra(Intent.EXTRA_TEXT) else null
        @Suppress("DEPRECATION")
        val sharedFile = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
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
                    settingsStore = settingsStore,
                    initialSharedText = sharedText,
                    initialSharedFile = sharedFile,
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
    settingsStore: SettingsStore,
    initialSharedText: String?,
    initialSharedFile: Uri?,
    initialConversationId: String?,
    externalSessionId: String?,
    notificationId: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var conversationId by remember { mutableStateOf(initialConversationId) }
    var manifest by remember { mutableStateOf(fallbackP14Manifest()) }
    var notification by remember { mutableStateOf<P14NotificationEntity?>(null) }
    var interaction by remember { mutableStateOf<P14InteractionEntity?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val profileStore=remember(context) { P14ProfileStore(context) }
    var profileOpen by remember { mutableStateOf(false) }
    var profileYaml by remember { mutableStateOf(profileStore.exportProfile()) }
    var locationEnabled by remember { mutableStateOf(profileStore.locationEnabled()) }
    val recordLocation: () -> Unit = {
        scope.launch {
            status=runCatching {
                check(profileStore.locationEnabled()) { "Enable Location in your Profile first" }
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) { "Location permission is required" }
                val locationManager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                @Suppress("MissingPermission")
                val location=locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: error("No recent location fix available; open Maps or enable location services first")
                product.acceptLocationObservation(location.latitude,location.longitude,location.accuracy.toDouble())
                P14RemoteWork.enqueue(context)
                "Location saved as Observation (not an Entry)"
            }.getOrElse{it.message?:"Location capture failed"}
        }
    }
    val requestLocation=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recordLocation() else status="Location permission denied"
    }
    var captureOpen by remember { mutableStateOf(!initialSharedText.isNullOrBlank() || initialSharedFile!=null) }
    var captureText by remember { mutableStateOf(initialSharedText.orEmpty()) }
    var captureFiles by remember { mutableStateOf<List<Uri>>(listOfNotNull(initialSharedFile)) }
    var selectedView by remember { mutableStateOf("chat") }
    val chooseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        captureFiles = (captureFiles + it).distinct()
    }
    var directoryPage by remember { mutableStateOf(0) }
    var directory by remember { mutableStateOf<List<P14EntryDirectoryItem>>(emptyList()) }
    var directoryHasMore by remember { mutableStateOf(false) }
    var refreshContext by remember { mutableIntStateOf(0) }

    LaunchedEffect(initialConversationId, externalSessionId) {
        if (conversationId == null && externalSessionId != null) {
            conversationId = product.conversationForExternal(externalSessionId)?.conversationId
        }
        manifest = remote.manifestActive()
    }
    LaunchedEffect(notificationId, refreshContext) {
        notification = notificationId?.let { remote.notification(it) }
        interaction = notificationId?.let { remote.interaction(it) }
    }

    LaunchedEffect(conversationId,directoryPage,selectedView,refreshContext) {
        if (conversationId == null) {
            val page = product.directoryPage(directoryPage*20,view=selectedView)
            if (directoryPage==0) directory=page else directory=(directory+page).distinctBy{it.conversationId}
            directoryHasMore = page.size==20
        }
    }
    val productConversation = if (conversationId != null) product.conversationFlow(conversationId!!).collectAsState(initial = null).value else null
    val entry = if (productConversation != null) product.entryFlow(productConversation.entryId).collectAsState(initial = null).value else null
    val messages = if (conversationId != null) product.messages(conversationId!!).collectAsState(initial = emptyList()).value else emptyList()
    val attachments = if (conversationId != null) product.attachments(conversationId!!).collectAsState(initial = emptyList()).value else emptyList()
    val actions = if (conversationId != null) product.actions(conversationId!!).collectAsState(initial=emptyList()).value else emptyList()
    val viewMemberships = if (entry != null) product.entryViews(entry.entryId).collectAsState(initial=emptyList()).value else emptyList()
    val canText = "chat" in manifest.enabledViews && "text" in manifest.messageCapabilities
    val upgradeRequired = manifest.compatibility == "UPGRADE_REQUIRED"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (conversationId == null) "P14 Entries" else "P14 Entry") },
                actions = {
                    if(conversationId != null) TextButton(onClick={conversationId=null;directoryPage=0;refreshContext++}) { Text("All Entries") }
                    else {
                        TextButton(onClick={profileOpen=!profileOpen;profileYaml=profileStore.exportProfile()}) { Text("Profile") }
                        TextButton(onClick={captureOpen=true}) { Text("Capture") }
                    }
                },
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
        if (conversationId==null) {
            Column(Modifier.padding(padding).fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(profileOpen) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Profile / Capability (portable YAML; no secrets)",style=MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value=profileYaml,onValueChange={profileYaml=it},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=12)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={status=runCatching{profileStore.importProfile(profileYaml);locationEnabled=profileStore.locationEnabled();"Profile activated"}.getOrElse{it.message?:"Invalid Profile"}}) {Text("Activate")}
                                OutlinedButton(onClick={
                                    if (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) recordLocation()
                                    else requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                },enabled=locationEnabled) {Text("Record Location")}
                            }
                            Text("Location is off by default. No passive background collection; explicit permission and policy required.",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if(captureOpen) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Mixed Capture · Save first, process later",style=MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value=captureText,onValueChange={captureText=it},modifier=Modifier.fillMaxWidth(),label={Text("Text / URL / shared material")})
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={chooseFiles.launch(arrayOf("image/*","audio/*","video/*","application/*"))}) { Text("Add photo/audio/file") }
                                Text("${captureFiles.size} files")
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(enabled=captureText.isNotBlank()||captureFiles.isNotEmpty(),onClick={
                                    scope.launch {
                                        status=runCatching {
                                            val settings=settingsStore.awaitLoaded()
                                            val model=settings.providers.flatMap{it.models}.firstOrNull{it.modelId=="p14-hermes"}
                                                ?: error("Set up p14-hermes first")
                                            val assistant=settings.assistants.firstOrNull{
                                                settings.findModelById(it.chatModelId ?: settings.chatModelId)?.id==model.id
                                            } ?: error("Select a P14 assistant before Capture")
                                            val parts=buildList<UIMessagePart> {
                                                if(captureText.isNotBlank()) {
                                                    check(profileStore.captureEnabled("text")) { "Profile disabled text Capture" }
                                                    add(UIMessagePart.Text(captureText.trim()))
                                                }
                                                captureFiles.forEach { source ->
                                                    val mime=context.contentResolver.getType(source).orEmpty()
                                                    val durable=withContext(Dispatchers.IO) {
                                                        val dir=File(context.filesDir,"p14_capture").apply{mkdirs()}
                                                        val output=File(dir,UUID.randomUUID().toString())
                                                        context.contentResolver.openInputStream(source)?.use{input->output.outputStream().use{input.copyTo(it)}}
                                                            ?: error("Cannot import file")
                                                        Uri.fromFile(output).toString()
                                                    }
                                                    val capability=when {
                                                        mime.startsWith("image/")->"photo"
                                                        mime.startsWith("audio/")->"voice"
                                                        else->"file"
                                                    }
                                                    check(profileStore.captureEnabled(capability)) { "Profile disabled $capability Capture" }
                                                    add(when {
                                                        mime.startsWith("image/")->UIMessagePart.Image(durable)
                                                        mime.startsWith("audio/")->UIMessagePart.Audio(durable)
                                                        mime.startsWith("video/")->UIMessagePart.Video(durable)
                                                        else->UIMessagePart.Document(durable,source.lastPathSegment?:"file",mime.ifBlank{"application/octet-stream"})
                                                    })
                                                }
                                            }
                                            val localId=kotlin.uuid.Uuid.random()
                                            val result=product.acceptRikkaMessage(localId.toString(),localId.toString(),UIMessage(role=MessageRole.USER,parts=parts),assistant.id.toString(),model.id.toString())
                                            P14RemoteWork.enqueue(context)
                                            conversationId=result.conversationId
                                            captureOpen=false;captureText="";captureFiles=emptyList()
                                            "Entry durably saved"
                                        }.getOrElse{it.message?:"Save failed"}
                                    }
                                }) { Text("Save Entry") }
                                TextButton(onClick={captureOpen=false}) { Text("Cancel") }
                            }
                        }
                    }
                }
                status?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                Text("Entry history · server-synced and local",style=MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.fillMaxWidth()) {
                    listOf("chat","todo","idea","research","execute").filter{it in manifest.enabledViews}.forEach { view ->
                        TextButton(onClick={ selectedView=view;directoryPage=0;directory=emptyList() },
                            contentPadding=PaddingValues(horizontal=4.dp)) {
                            Text(if (view==selectedView) view.uppercase() else view)
                        }
                    }
                }
                LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.weight(1f)) {
                    items(directory,key={it.conversationId}) { item ->
                        OutlinedCard(onClick={conversationId=item.conversationId},modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.bodyText.take(150).ifBlank{"Entry ${item.entryId}"})
                                Text("${if(item.pinned) "Pinned · " else ""}${if(item.archived) "Archived" else "Active"}",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if(directoryHasMore) item { TextButton(onClick={directoryPage++}){Text("Load more")}}
                }
                OutlinedButton(onClick={directoryPage=0;refreshContext++}) {Text("Refresh Entry list")}
            }
        } else LazyColumn(
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
            entry?.let { currentEntry ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("Work surfaces (one Entry, multiple Views)",style=MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                listOf("todo","idea","research","execute").filter{it in manifest.enabledViews}.forEach { view ->
                                    val enabled=viewMemberships.any { it.viewName==view && it.enabled }
                                    TextButton(onClick={scope.launch {
                                        status=runCatching {
                                            product.assignEntryView(currentEntry.entryId,view,!enabled)
                                            P14RemoteWork.enqueue(context)
                                            "View assignment queued"
                                        }.getOrElse{it.message?:"View failed"}
                                    }},contentPadding=PaddingValues(horizontal=3.dp)) {
                                        Text("${if(enabled) "✓ " else "+ "}$view")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if(actions.isNotEmpty()) item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text("Actions / Results",style=MaterialTheme.typography.titleMedium)
                        actions.forEach { action ->
                            Text("${action.actionType} · ${action.status}",style=MaterialTheme.typography.titleSmall)
                            action.resultText?.let{Text(it)}
                            action.destinationRef?.let{Text("Destination: $it",style=MaterialTheme.typography.labelSmall)}
                        }
                    }
                }
            }
            productConversation?.let { state ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Conversation state", style = MaterialTheme.typography.titleSmall)
                            Text("rev ${state.stateRevision} · ${if(state.pinned) "Pinned" else "Not pinned"} · ${if(state.archived) "Archived" else "Active"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        status=runCatching {
                                            remote.setConversationStateActive(state.conversationId,state.stateRevision,pinned=!state.pinned)
                                            product.replayActive(); "Pin state updated"
                                        }.getOrElse { it.message ?: "Pin failed" }
                                    }
                                }) { Text(if(state.pinned) "Unpin" else "Pin") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        status=runCatching {
                                            remote.setConversationStateActive(state.conversationId,state.stateRevision,archived=!state.archived)
                                            product.replayActive(); "Archive state updated"
                                        }.getOrElse { it.message ?: "Archive failed" }
                                    }
                                }) { Text(if(state.archived) "Unarchive" else "Archive") }
                            }
                        }
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
                                            .fold({ "Quick Action queued: $actionType" }, { it.message ?: "Quick Action failed" })
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
