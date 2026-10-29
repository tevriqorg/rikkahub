package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.media.MediaRecorder
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import androidx.compose.ui.platform.testTag
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
        enableEdgeToEdge()
        if(Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
        if (intent.getBooleanExtra(EXTRA_FROM_LAUNCHER, false)) {
            // Back at the record root exits the app. The explicit settings action
            // still finishes only this surface and reveals the existing shell.
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { finishAffinity() }
            })
        }
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
        const val EXTRA_FROM_LAUNCHER = "p14_from_launcher"
        const val EXTRA_CONVERSATION_ID = "p14_conversation_id"
        const val EXTRA_EXTERNAL_SESSION_ID = "p14_external_session_id"
        const val EXTRA_NOTIFICATION_ID = "p14_notification_id"
        const val EXTRA_INTERACTION_RESPONSE = "p14_interaction_response"
    }
}

/** Older Entry roots are server-keyset paged even when current Room pages are shorter. */
internal fun p14InboxShouldOfferMore(localCount:Int,limit:Int,serverHasOlder:Boolean):Boolean =
    serverHasOlder || localCount >= limit

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
    var conversationId by rememberSaveable { mutableStateOf(initialConversationId) }
    var manifest by remember { mutableStateOf(fallbackP14Manifest()) }
    var notification by remember { mutableStateOf<P14NotificationEntity?>(null) }
    var interaction by remember { mutableStateOf<P14InteractionEntity?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var sendingContinuation by remember { mutableStateOf(false) }
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
    val draftStore=remember(context) { P14CaptureDraftStore(context) }
    var savedDraft by remember { mutableStateOf(draftStore.read()) }
    var captureOpen by remember { mutableStateOf(!initialSharedText.isNullOrBlank() || initialSharedFile!=null) }
    var captureText by remember { mutableStateOf(initialSharedText ?: savedDraft.text) }
    var captureFiles by remember { mutableStateOf(savedDraft.materials.map { Uri.parse(it.locator) }) }
    var recordedVoiceDurations by remember { mutableStateOf(savedDraft.materials.mapNotNull { m -> m.voiceDurationMs?.let { Uri.parse(m.locator) to it } }.toMap()) }
    var scanUris by remember { mutableStateOf(savedDraft.materials.filter { it.scan }.map { Uri.parse(it.locator) }.toSet()) }
    var savingCapture by remember { mutableStateOf(false) }
    var importsPending by remember { mutableIntStateOf(0) }
    fun persistText(value:String) {
        val changed=savedDraft.copy(text=value)
        runCatching { draftStore.save(changed) }
            .onSuccess { savedDraft=changed; captureText=value }
            .onFailure { status=it.message ?: "Draft not saved" }
    }
    fun importMaterial(source:Uri, scan:Boolean=false, voiceMs:Long?=null) {
        importsPending++
        scope.launch {
            runCatching {
                val item=withContext(Dispatchers.IO) { draftStore.import(source,scan,voiceMs) }
                val next=savedDraft.copy(materials=savedDraft.materials+item)
                withContext(Dispatchers.IO) { draftStore.save(next) }
                savedDraft=next
                captureFiles=next.materials.map { Uri.parse(it.locator) }
                recordedVoiceDurations=next.materials.mapNotNull { m -> m.voiceDurationMs?.let { Uri.parse(m.locator) to it } }.toMap()
                scanUris=next.materials.filter { it.scan }.map { Uri.parse(it.locator) }.toSet()
            }.onFailure { status=it.message ?: "Failed to preserve Capture source" }
            importsPending--
        }
    }
    LaunchedEffect(initialSharedText,initialSharedFile) {
        if(!initialSharedText.isNullOrBlank()) persistText(initialSharedText)
        if(initialSharedFile!=null) importMaterial(initialSharedFile)
    }
    var activeRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingStart by remember { mutableLongStateOf(0L) }
    var scanTarget by remember { mutableStateOf<Uri?>(null) }
    var scanFile by remember { mutableStateOf<File?>(null) }
    val scanCamera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok ->
        val uri=scanTarget
        if(ok && uri!=null) {
            importMaterial(uri,scan=true)
        }
        else scanFile?.delete()
        scanTarget=null;scanFile=null
    }
    val startVoice: () -> Unit = {
        val file=File(context.filesDir,"p14_voice_${UUID.randomUUID()}.m4a")
        runCatching {
            @Suppress("DEPRECATION")
            val recorder=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare();recorder.start()
            activeRecorder=recorder;recordingFile=file;recordingStart=SystemClock.elapsedRealtime()
        }.onFailure {file.delete();status=it.message ?: "Mic unavailable"}
    }
    val requestMicrophone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted) startVoice() else status="Microphone permission denied"
    }
    val requestCamera=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted){
            val file=File(context.cacheDir,"p14_scan_${UUID.randomUUID()}.jpg")
            scanFile=file
            scanTarget=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
            scanCamera.launch(scanTarget!!)
        }else status="Camera permission denied"
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { activeRecorder?.stop() };runCatching { activeRecorder?.release() };recordingFile?.takeIf{activeRecorder!=null}?.delete() }
    }
    // Keep the original full workbench behind Advanced; Inbox is the default Entry-first home.
    var legacyWorkbench by remember { mutableStateOf(false) }
    var inboxArchived by rememberSaveable { mutableStateOf(false) }
    var inboxQuery by rememberSaveable { mutableStateOf("") }
    var inboxView by rememberSaveable { mutableStateOf("") }
    var inboxLimit by remember { mutableIntStateOf(60) }
    var inboxLoadingMore by remember { mutableStateOf(false) }
    val inboxRows by remember(inboxArchived, inboxLimit,inboxQuery,inboxView) {
        product.inboxRows(inboxArchived, inboxLimit,inboxQuery,inboxView)
    }.collectAsState(initial = emptyList())
    var selectedView by remember { mutableStateOf("chat") }
    var workItems by remember { mutableStateOf<List<P14WorkItem>>(emptyList()) }
    var workError by remember { mutableStateOf<String?>(null) }
    var supplementActionId by remember { mutableStateOf<String?>(null) }
    var supplementInput by remember { mutableStateOf("") }
    val chooseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { importMaterial(it) }
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

    LaunchedEffect(selectedView,directoryPage,refreshContext) {
        if(selectedView!="chat") {
            runCatching { remote.workItems(selectedView,directoryPage*20) }
                .onSuccess { workItems=if(directoryPage==0) it else (workItems+it).distinctBy{item->item.source+item.sourceId} ;workError=null }
                .onFailure {workError=it.message ?: "Work view unavailable"}
        }
    }
    LaunchedEffect(conversationId,directoryPage,selectedView,refreshContext) {
        if (conversationId == null) {
            val page = product.directoryPage(directoryPage*20,view=selectedView)
            if (directoryPage==0) directory=page else directory=(directory+page).distinctBy{it.conversationId}
            directoryHasMore = page.size==20
        }
    }
    var historyLimit by remember(conversationId) { mutableIntStateOf(50) }
    LaunchedEffect(conversationId,historyLimit) {
        val id=conversationId ?: return@LaunchedEffect
        runCatching {
            val entryId=product.ensureConversationForId(id)?.entryId ?: error("Entry context is unavailable")
            for (offset in 0 until historyLimit step 100) {
                val count=minOf(100,historyLimit-offset)
                val loaded=product.loadEntryHistory(entryId,offset,count)
                if(loaded<count) break
            }
            product.ensureEntrySource(id)
            product.hydrateEntryState(entryId,id)
        }.onFailure { status=it.message ?: "Entry history unavailable" }
    }
    val productConversation = if (conversationId != null) product.conversationFlow(conversationId!!).collectAsState(initial = null).value else null
    val entry = if (productConversation != null) product.entryFlow(productConversation.entryId).collectAsState(initial = null).value else null
    val messages = if (conversationId != null) product.messages(conversationId!!,historyLimit).collectAsState(initial = emptyList()).value else emptyList()
    val attachments = if (conversationId != null) product.attachments(conversationId!!,historyLimit).collectAsState(initial = emptyList()).value else emptyList()
    val actions = if (conversationId != null) product.actions(conversationId!!).collectAsState(initial=emptyList()).value else emptyList()
    var nearbyObservations by remember(entry?.entryId,refreshContext) { mutableStateOf<List<P14ObservationEntity>>(emptyList()) }
    var linkedObservationIds by remember(entry?.entryId,refreshContext) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(entry?.entryId,refreshContext) {
        if(entry != null) linkedObservationIds=runCatching{product.linkedObservationIds(entry.entryId)}.getOrDefault(emptyList())
        if(entry != null && profileStore.locationEnabled()) {
            nearbyObservations=product.recentObservations().filter {
                kotlin.math.abs(it.capturedAtEpochMs-entry.createdAtEpochMs)<=2*60*60*1000L
            }
        }
    }
    val sourceMessage = if(conversationId!=null) remember(conversationId) {product.sourceMessage(conversationId!!)}.collectAsState(initial=null).value else null
    val editConflicts = if(conversationId!=null) remember(conversationId) {product.editConflicts(conversationId!!)}.collectAsState(initial=emptyList()).value else emptyList()
    val viewMemberships = if (entry != null) product.entryViews(entry.entryId).collectAsState(initial=emptyList()).value else emptyList()
    val canText = "chat" in manifest.enabledViews && "text" in manifest.messageCapabilities
    val upgradeRequired = manifest.compatibility == "UPGRADE_REQUIRED"

    fun saveInboxCapture() {
        if(savingCapture||importsPending>0||(captureText.isBlank()&&captureFiles.isEmpty())) return
        savingCapture=true
        scope.launch {
            status=runCatching {
                                            val settings=settingsStore.awaitLoaded()
                                            val model=settings.providers.flatMap{it.models}.firstOrNull{it.modelId=="p14-hermes"}
                                            val assistant=model?.let { target -> settings.assistants.firstOrNull{
                                                settings.findModelById(it.chatModelId ?: settings.chatModelId)?.id==target.id
                                            } }
                                            val parts=buildList<UIMessagePart> {
                                                if(!initialSharedText.isNullOrBlank() || initialSharedFile!=null) {
                                                    check(profileStore.captureEnabled("share")) { "Profile disabled Share Capture" }
                                                }
                                                if(captureText.isNotBlank()) {
                                                    check(profileStore.captureEnabled("text")) { "Profile disabled text Capture" }
                                                    add(UIMessagePart.Text(captureText.trim()))
                                                }
                                                captureFiles.forEach { source ->
                                                    val originalMaterial=savedDraft.materials.firstOrNull { it.locator==source.toString() }
                                                    val mime=originalMaterial?.mime ?: context.contentResolver.getType(source).orEmpty()
                                                    val durable=withContext(Dispatchers.IO) {
                                                        val dir=File(context.filesDir,"p14_capture").apply{mkdirs()}
                                                        val extension=source.lastPathSegment?.substringAfterLast('.',"")
                                                            ?.takeIf{it.length in 1..8 && it.all(Char::isLetterOrDigit)}.orEmpty()
                                                        val output=File(dir,UUID.randomUUID().toString() + if(extension.isBlank()) "" else ".$extension")
                                                        context.contentResolver.openInputStream(source)?.use{input->output.outputStream().use{input.copyTo(it)}}
                                                            ?: error("Cannot import file")
                                                        Uri.fromFile(output).toString()
                                                    }
                                                    val effectiveMime=if(source in recordedVoiceDurations) "audio/mp4" else mime
                                                    val capability=when {
                                                        source in scanUris -> "scan"
                                                        effectiveMime.startsWith("image/")->"photo"
                                                        effectiveMime.startsWith("audio/")->"voice"
                                                        else->"file"
                                                    }
                                                    check(profileStore.captureEnabled(capability)) { "Profile disabled $capability Capture" }
                                                    add(when {
                                                        effectiveMime.startsWith("image/")->UIMessagePart.Image(durable,
                                                            if(source in scanUris) buildJsonObject {put("p14_media_role","scan")} else null)
                                                        effectiveMime.startsWith("audio/")->UIMessagePart.Audio(durable,
                                                            recordedVoiceDurations[source]?.let{duration-> buildJsonObject {
                                                                put("p14_media_role","voice_message");put("p14_duration_ms",duration);put("p14_mime_type","audio/mp4")
                                                            }})
                                                        effectiveMime.startsWith("video/")->UIMessagePart.Video(durable)
                                                        else->UIMessagePart.Document(durable,source.lastPathSegment?:"file",mime.ifBlank{"application/octet-stream"})
                                                    })
                                                }
                                            }
                                            // Stable draft identities prevent creating another Entry if an OS kill
                                            // happens after Room commit but before draft-clear/UI navigation.
                                            val localId=kotlin.uuid.Uuid.parse(savedDraft.draftId)
                                            val userMessage=UIMessage(id=kotlin.uuid.Uuid.parse(savedDraft.messageId),role=MessageRole.USER,parts=parts)
                                            val result=product.acceptRikkaMessage(localId.toString(),localId.toString(),userMessage,assistant?.id?.toString(),model?.id?.toString(),deferAi=true,initialViews=savedDraft.views.toSet())
                                            runCatching { withContext(Dispatchers.IO) { draftStore.clearAfterAccept() } }
                                            savedDraft=P14CaptureDraft()
                                            conversationId=null
                                            legacyWorkbench=false
                                            captureOpen=false;captureText="";captureFiles=emptyList();recordedVoiceDurations=emptyMap();scanUris=emptySet()
                                            P14RemoteWork.enqueue(context)
                                            "已保存到收件箱"
            }.getOrElse { it.message ?: "保存失败，草稿已保留" }
            savingCapture=false
        }
    }
    fun toggleInboxVoice() {
        if(activeRecorder==null) {
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startVoice()
            else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val file=recordingFile;val duration=SystemClock.elapsedRealtime()-recordingStart
            val stopped=runCatching {activeRecorder?.stop();activeRecorder?.release()}.isSuccess
            activeRecorder=null;recordingFile=null
            if(stopped&&file?.isFile==true&&file.length()>0L&&duration>=300L)importMaterial(Uri.fromFile(file),voiceMs=duration)
            else file?.delete()
        }
    }
    fun loadThumbnail(id:String) {scope.launch {runCatching {product.materializeAttachment(id)}}}
    fun organize(id:String,pin:Boolean?=null,archive:Boolean?=null) {scope.launch {
        status=runCatching {product.acceptOrganization(id,pin,archive);P14RemoteWork.enqueue(context);"整理已保存"}.getOrElse {it.message ?: "整理失败"}
    }}
    // A single low-friction inbox is the new home; the existing Entry detail, Hermes
    // continuation, mixed-material Capture and diagnostics remain reachable and unchanged.
    if (!legacyWorkbench && !profileOpen && !captureOpen) {
        if(conversationId!=null&&(productConversation==null||entry==null)) {
            androidx.activity.compose.BackHandler {conversationId=null}
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
                Text(status ?: "正在恢复记录…")
                TextButton(onClick={conversationId=null;status=null}){Text("返回收件箱")}
            }
            return
        }
        if (conversationId == null) {
            P14TickTickInboxScreen(
                rows = inboxRows,
                archived = inboxArchived,
                onShowArchived = { inboxArchived = it; inboxLimit = 60 },
                draftText = captureText,
                onDraftChange = { persistText(it) },
                saving = savingCapture,
                status = status,
                onQuickSave = {saveInboxCapture()},
                onRichCapture = {chooseFiles.launch(arrayOf("*/*"))},
                draftMaterials = savedDraft.materials,
                draftViews = savedDraft.views.toSet(),
                onDraftView = {view ->
                    val selected=savedDraft.views.toMutableSet();if(!selected.add(view))selected.remove(view)
                    val next=savedDraft.copy(views=selected.toList());draftStore.save(next);savedDraft=next
                },
                onRemoveDraftMaterial = { material ->
                    val next=savedDraft.copy(materials=savedDraft.materials.filterNot {it.locator==material.locator})
                    draftStore.save(next);savedDraft=next;captureFiles=next.materials.map {Uri.parse(it.locator)}
                },
                onRecordVoice = {toggleInboxVoice()},recording = activeRecorder!=null,
                onTogglePin = {row -> organize(row.conversationId,pin=!row.pinned)},
                onLoadThumbnail = {loadThumbnail(it)},
                managedSearch = true,onSearchChange = {inboxQuery=it;inboxLimit=60},
                activeView = inboxView,onViewFilter = {inboxView=it;inboxLimit=60},
                availableViews = listOf("todo","idea","research","execute").filter {it in manifest.enabledViews},
                onOpen = { conversationId = it; input = ""; status = null },
                onToggleArchive = {row -> organize(row.conversationId,archive=!row.archived)},
                onMore = { legacyWorkbench = true; status = null },
                onLoadMore = {
                    if (!inboxLoadingMore) {
                        inboxLoadingMore = true
                        scope.launch {
                            runCatching {
                                // The oldest ~50 server Entry roots are bootstrapped first.
                                // Page at the current boundary to fetch older keyset pages
                                // before increasing the observable Room projection limit.
                                product.directoryPage(
                                    offset = (inboxLimit - 10).coerceAtLeast(0),
                                    limit = 20,
                                    view = "chat",
                                )
                                inboxLimit += 60
                            }.onFailure { status = it.message ?: "加载历史条目失败" }
                            inboxLoadingMore = false
                        }
                    }
                },
                hasMore = p14InboxShouldOfferMore(
                    inboxRows.size, inboxLimit, product.inboxHasOlderServerEntries(),
                ),
                onBack = onBack,
                onSettings = {
                    context.startActivity(Intent(context, me.rerere.rikkahub.RouteActivity::class.java)
                        .setAction(me.rerere.rikkahub.P14_ACTION_CONNECTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                },
                resumeQuickDraft = savedDraft.text.isNotBlank()||savedDraft.materials.isNotEmpty(),
            )
        } else {
            P14TickTickEntryDetailScreen(
                entryBody = entry?.bodyText,
                entryId = entry?.entryId ?: conversationId!!,
                displayBody = sourceMessage?.bodyText,
                sourceMessageId = sourceMessage?.messageId,
                sourceRevision = sourceMessage?.revision ?: 1,
                editConflict = editConflicts.isNotEmpty(),
                onResolveConflict = {keep -> scope.launch {
                    status=runCatching {product.resolveConflicts(conversationId!!,keep);P14RemoteWork.enqueue(context);"冲突已处理"}.getOrElse {it.message ?: "本机修改仍已保留，请重试"}
                } },
                onCommitEdit = { draft,done -> scope.launch {
                    runCatching {
                        product.acceptEntryRevision(conversationId!!,draft.text,draft.baseRevision)
                        P14RemoteWork.enqueue(context)
                    }.onSuccess {status="修改已保存";done(true)}
                        .onFailure {status=it.message ?: "编辑草稿已保留，请重试";done(false)}
                } },
                pinned = productConversation?.pinned ?: false,
                archived = productConversation?.archived ?: false,
                onToggleArchive = {productConversation?.let {organize(it.conversationId,archive=!it.archived)}},
                onTogglePin = {productConversation?.let {organize(it.conversationId,pin=!it.pinned)}},
                onLoadThumbnail = {loadThumbnail(it)},
                createdAt = entry?.createdAtEpochMs,
                conversationId = conversationId!!,
                messages = messages,
                availableViews = listOf("todo", "idea", "research", "execute").filter { it in manifest.enabledViews },
                selectedViews = viewMemberships.filter { it.enabled }.map { it.viewName }.toSet(),
                onToggleView = { view -> entry?.let { current -> scope.launch {
                    status = runCatching {
                        product.assignEntryView(current.entryId, view, !viewMemberships.any { it.viewName == view && it.enabled })
                        P14RemoteWork.enqueue(context)
                        "分类已保存"
                    }.getOrElse { it.message ?: "分类保存失败" }
                } } },
                attachments = attachments,
                onOpenAttachment = { attachment -> scope.launch {
                    status = runCatching {
                        val file = withContext(Dispatchers.IO) { product.materializeAttachment(attachment.localAttachmentId) }
                        val content = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(requireNotNull(file.path)))
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(content, attachment.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        "已打开 ${attachment.fileName}"
                    }.getOrElse { it.message ?: "附件暂不可用，请联网后重试" }
                } },
                input = input,
                onInputChange = { input = it },
                sending = sendingContinuation,
                canChat = canText && !upgradeRequired,
                status = status,
                onSend = {
                    if (!sendingContinuation && input.isNotBlank() && canText && !upgradeRequired) {
                        val id = conversationId!!
                        val text = input
                        sendingContinuation = true
                        scope.launch {
                            try {
                                product.acceptEntryContinuation(id, text)
                                input = ""
                                P14RemoteWork.enqueue(context)
                            } catch (e: Exception) {
                                status = e.message ?: "消息尚未发送，请重试"
                            } finally {
                                sendingContinuation = false
                            }
                        }
                    }
                },
                onBack = { conversationId = null; status = null; inboxArchived = false },
                onAdvanced = { legacyWorkbench = true },
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (conversationId == null) "P14 Entries" else "P14 Entry") },
                actions = {
                    TextButton(onClick={legacyWorkbench=false;captureOpen=false;profileOpen=false;conversationId=null;status=null}) { Text("收件箱") }
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
                        modifier = Modifier.weight(1f).testTag("p14-entry-message-input"),
                        enabled = canText && !upgradeRequired,
                        placeholder = { Text(if (upgradeRequired) "Client upgrade required" else "Continue this Entry") },
                    )
                    Button(
                        enabled = input.isNotBlank() && canText && !upgradeRequired && !sendingContinuation,
                        onClick = {
                            if(sendingContinuation) return@Button
                            val id = conversationId ?: return@Button
                            val text = input
                            sendingContinuation=true
                            scope.launch {
                                try {
                                    product.acceptEntryContinuation(id, text)
                                    input=""  // Only discard input after P14 transaction commits.
                                    P14RemoteWork.enqueue(context)
                                } catch(error:Exception) {
                                    status=error.message ?: "Message remains unsent; retry"
                                } finally { sendingContinuation=false }
                            }
                        },
                    ) { Text("Send") }
                }
            }
        },
    ) { padding ->
        if (conversationId==null) {
            LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("p14-entry-list"),
                contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(profileOpen) item(key="profile-panel") {
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
                            Text("Effective capabilities",style=MaterialTheme.typography.titleSmall)
                            profileStore.effectiveCapabilities().forEach { capability ->
                                Text("${capability.id}: ${capability.state} · ${capability.reason}",style=MaterialTheme.typography.labelSmall)
                            }
                            Text("Location is off by default. No passive background collection; explicit permission and policy required.",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if(captureOpen) item(key="capture-panel") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("Mixed Capture · Save first, process later",style=MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value=captureText,onValueChange={persistText(it)},modifier=Modifier.fillMaxWidth(),label={Text("Text / URL / shared material")})
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={chooseFiles.launch(arrayOf("image/*","audio/*","video/*","application/*"))}) { Text("Add photo/audio/file") }
                                Text("${captureFiles.size} files")
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={
                                    if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
                                        val file=File(context.cacheDir,"p14_scan_${UUID.randomUUID()}.jpg")
                                        scanFile=file
                                        scanTarget=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                                        scanCamera.launch(scanTarget!!)
                                    } else requestCamera.launch(Manifest.permission.CAMERA)
                                }){Text("Scan photo")}
                                OutlinedButton(onClick={
                                    if(activeRecorder==null) {
                                        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startVoice()
                                        else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                    }else{
                                        val file=recordingFile
                                        val duration=SystemClock.elapsedRealtime()-recordingStart
                                        val stopped=runCatching{activeRecorder?.stop();activeRecorder?.release()}.isSuccess
                                        activeRecorder=null;recordingFile=null
                                        if(stopped && file?.isFile==true && file.length()>0L && duration>=300L) {
                                            val uri=Uri.fromFile(file)
                                            importMaterial(uri,voiceMs=duration)
                                        }else file?.delete()
                                    }
                                }){Text(if(activeRecorder!=null) "Stop voice" else "Record voice")}
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(enabled=(captureText.isNotBlank()||captureFiles.isNotEmpty()) && !savingCapture && importsPending==0,onClick={
                                    if(savingCapture || importsPending>0) return@Button
                                    savingCapture=true
                                    scope.launch {
                                        status=runCatching {
                                            val settings=settingsStore.awaitLoaded()
                                            val model=settings.providers.flatMap{it.models}.firstOrNull{it.modelId=="p14-hermes"}
                                            val assistant=model?.let { target -> settings.assistants.firstOrNull{
                                                settings.findModelById(it.chatModelId ?: settings.chatModelId)?.id==target.id
                                            } }
                                            val parts=buildList<UIMessagePart> {
                                                if(!initialSharedText.isNullOrBlank() || initialSharedFile!=null) {
                                                    check(profileStore.captureEnabled("share")) { "Profile disabled Share Capture" }
                                                }
                                                if(captureText.isNotBlank()) {
                                                    check(profileStore.captureEnabled("text")) { "Profile disabled text Capture" }
                                                    add(UIMessagePart.Text(captureText.trim()))
                                                }
                                                captureFiles.forEach { source ->
                                                    val originalMaterial=savedDraft.materials.firstOrNull { it.locator==source.toString() }
                                                    val mime=originalMaterial?.mime ?: context.contentResolver.getType(source).orEmpty()
                                                    val durable=withContext(Dispatchers.IO) {
                                                        val dir=File(context.filesDir,"p14_capture").apply{mkdirs()}
                                                        val extension=source.lastPathSegment?.substringAfterLast('.',"")
                                                            ?.takeIf{it.length in 1..8 && it.all(Char::isLetterOrDigit)}.orEmpty()
                                                        val output=File(dir,UUID.randomUUID().toString() + if(extension.isBlank()) "" else ".$extension")
                                                        context.contentResolver.openInputStream(source)?.use{input->output.outputStream().use{input.copyTo(it)}}
                                                            ?: error("Cannot import file")
                                                        Uri.fromFile(output).toString()
                                                    }
                                                    val effectiveMime=if(source in recordedVoiceDurations) "audio/mp4" else mime
                                                    val capability=when {
                                                        source in scanUris -> "scan"
                                                        effectiveMime.startsWith("image/")->"photo"
                                                        effectiveMime.startsWith("audio/")->"voice"
                                                        else->"file"
                                                    }
                                                    check(profileStore.captureEnabled(capability)) { "Profile disabled $capability Capture" }
                                                    add(when {
                                                        effectiveMime.startsWith("image/")->UIMessagePart.Image(durable,
                                                            if(source in scanUris) buildJsonObject {put("p14_media_role","scan")} else null)
                                                        effectiveMime.startsWith("audio/")->UIMessagePart.Audio(durable,
                                                            recordedVoiceDurations[source]?.let{duration-> buildJsonObject {
                                                                put("p14_media_role","voice_message");put("p14_duration_ms",duration);put("p14_mime_type","audio/mp4")
                                                            }})
                                                        effectiveMime.startsWith("video/")->UIMessagePart.Video(durable)
                                                        else->UIMessagePart.Document(durable,source.lastPathSegment?:"file",mime.ifBlank{"application/octet-stream"})
                                                    })
                                                }
                                            }
                                            // Stable draft identities prevent creating another Entry if an OS kill
                                            // happens after Room commit but before draft-clear/UI navigation.
                                            val localId=kotlin.uuid.Uuid.parse(savedDraft.draftId)
                                            val userMessage=UIMessage(id=kotlin.uuid.Uuid.parse(savedDraft.messageId),role=MessageRole.USER,parts=parts)
                                            val result=product.acceptRikkaMessage(localId.toString(),localId.toString(),userMessage,assistant?.id?.toString(),model?.id?.toString(),deferAi=true)
                                            runCatching { withContext(Dispatchers.IO) { draftStore.clearAfterAccept() } }
                                            savedDraft=P14CaptureDraft()
                                            conversationId=result.conversationId
                                            legacyWorkbench=false
                                            captureOpen=false;captureText="";captureFiles=emptyList();recordedVoiceDurations=emptyMap();scanUris=emptySet()
                                            P14RemoteWork.enqueue(context)
                                            "Entry durably saved"
                                        }.getOrElse{it.message?:"Save failed"}
                                        savingCapture=false
                                    }
                                }) { Text("Save Entry") }
                                TextButton(onClick={captureOpen=false}) { Text("Cancel") }
                            }
                        }
                    }
                }
                status?.let { message -> item(key="status") { Text(message,style=MaterialTheme.typography.bodySmall) } }
                item(key="history-title") { Text("Entry history · server-synced and local",style=MaterialTheme.typography.titleMedium) }
                item(key="view-filter") {
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.fillMaxWidth()) {
                        listOf("chat","todo","idea","research","execute").filter{it in manifest.enabledViews}.forEach { view ->
                            TextButton(onClick={ selectedView=view;directoryPage=0;directory=emptyList();workItems=emptyList();workError=null },
                                contentPadding=PaddingValues(horizontal=4.dp)) {
                                Text(if (view==selectedView) view.uppercase() else view)
                            }
                        }
                    }
                }
                workError?.let { message -> item(key="work-error") { Text(message,style=MaterialTheme.typography.labelSmall) } }
                if(selectedView!="chat") items(directory,key={"filed:${selectedView}:${it.conversationId}"}) { item ->
                    OutlinedCard(onClick={conversationId=item.conversationId},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(item.bodyText.take(150).ifBlank{"Entry ${item.entryId}"})
                            Text("Entry · ${selectedView.uppercase()}",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if(selectedView!="chat") items(
                    p14VisibleWorkItems(workItems,directory.map { it.entryId }.toSet()),
                    key={it.stableListKey()}
                ) { item ->
                    OutlinedCard(onClick={scope.launch {
                        val cid=item.conversationId ?: return@launch
                        product.seedWorkEntry(item.entryId,cid,item.bodyText.orEmpty())
                        conversationId=cid
                    }},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${item.kind} · ${item.status}",style=MaterialTheme.typography.titleSmall)
                            item.phase?.let { Text(it) }
                            item.progressPercent?.let { Text("$it%") }
                            item.resultText?.let { Text(it) }
                        }
                    }
                }
                if(selectedView=="chat") items(directory,key={"conversation:${it.conversationId}"}) { item ->
                    OutlinedCard(onClick={conversationId=item.conversationId},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.bodyText.take(150).ifBlank{"Entry ${item.entryId}"})
                            Text("${if(item.pinned) "Pinned · " else ""}${if(item.archived) "Archived" else "Active"}",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if(directoryHasMore || (selectedView!="chat" && workItems.size==20)) item {
                    TextButton(onClick={directoryPage++}){Text("Load more")}
                }
                item(key="refresh") { OutlinedButton(onClick={directoryPage=0;refreshContext++}) {Text("Refresh Entry list")} }
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
                        Text(entry?.bodyText ?: "Waiting for authoritative Entry replay…", modifier=Modifier.testTag("p14-entry-body"))
                        Text(
                            "Manifest: ${manifest.compatibility} · ${manifest.source}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            entry?.let { currentEntry ->
                if(nearbyObservations.isNotEmpty()) item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Nearby context · opt-in Location",style=MaterialTheme.typography.titleSmall)
                            Text("Context is separate from user-authored Entry")
                            if(linkedObservationIds.isNotEmpty()) Text("Linked Location observations: ${linkedObservationIds.size}")
                            nearbyObservations.take(3).forEach { observation ->
                                TextButton(onClick={scope.launch {
                                    status=runCatching {
                                        remote.queueObservationContextLink(currentEntry.entryId,observation.observationId)
                                        "Location Context link queued"
                                    }.getOrElse{it.message?:"Context link failed"}
                                }}){Text("Attach location at ${observation.capturedAtEpochMs}")}
                            }
                        }
                    }
                }
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
                                            refreshContext++
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
                            if(action.status in setOf("accepted","needs_reassessment")) {
                                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                    listOf("confirm" to "Confirm","supplement" to "Supplement","skip" to "Skip","cancel" to "Cancel")
                                        .forEach { (decision,label) ->
                                            TextButton(onClick={
                                                if(decision=="supplement") supplementActionId=action.actionId
                                                else scope.launch { status=runCatching {
                                                    remote.queueActionDecision(action.actionId,decision)
                                                    "Decision queued · ${action.actionId}"
                                                }.getOrElse{it.message?:"Decision failed"} }
                                            },contentPadding=PaddingValues(horizontal=2.dp)) { Text(label) }
                                        }
                                }
                                if(supplementActionId==action.actionId) {
                                    OutlinedTextField(value=supplementInput,onValueChange={supplementInput=it},label={Text("Additional context")})
                                    Button(enabled=supplementInput.isNotBlank(),onClick={scope.launch {
                                        status=runCatching {
                                            remote.queueActionDecision(action.actionId,"supplement",supplementInput)
                                            supplementActionId=null;supplementInput=""
                                            "Context queued"
                                        }.getOrElse{it.message?:"Save failed"}
                                    }}) { Text("Submit supplement") }
                                }
                            }
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
            if(messages.size>=historyLimit) item { OutlinedButton(onClick={historyLimit=(historyLimit+50).coerceAtMost(10000)}) {Text("Load older messages")} }
            items(p14VisibleEntryMessages(messages), key = { it.messageId }) { message ->
                val count = attachments.count { it.messageId == message.messageId }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(message.role.uppercase(), style = MaterialTheme.typography.labelSmall)
                        Text(if (message.tombstone) "[deleted]" else message.bodyText)
                        if (count > 0) Text("$count attachment(s)", style = MaterialTheme.typography.labelSmall)
                        attachments.filter { it.messageId==message.messageId }.forEach { attachment ->
                            TextButton(onClick={ scope.launch {
                                status=runCatching {
                                    val file=withContext(Dispatchers.IO) { product.materializeAttachment(attachment.localAttachmentId) }
                                    val content=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",File(requireNotNull(file.path)))
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(content,attachment.mimeType)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    "Opened ${attachment.fileName}"
                                }.getOrElse { it.message ?: "Media unavailable; retry when connected" }
                            } }) { Text("Open ${attachment.kind.lowercase()}: ${attachment.fileName}") }
                        }
                        Text("${message.deliveryState} · rev ${message.revision}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
