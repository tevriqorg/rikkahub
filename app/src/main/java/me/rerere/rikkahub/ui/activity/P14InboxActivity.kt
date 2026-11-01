package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.P14_EXTRA_NATIVE_ENTRY_CHAT
import me.rerere.rikkahub.data.remote.p14.*
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import okhttp3.OkHttpClient
import org.koin.android.ext.android.get

class P14InboxActivity:ComponentActivity() {
    private lateinit var repo:P14RemoteRepository
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore=get<SettingsStore>()
        val nativeConversations=get<ConversationRepository>()
        val product=P14ProductRepository(applicationContext,get<OkHttpClient>())
        repo=P14RemoteRepository(applicationContext,settingsStore,get<OkHttpClient>())
        val initialNotificationId=intent.getStringExtra("notification_id")
        lifecycleScope.launch {
            runCatching {
                repo.refreshActive()
                initialNotificationId?.let { id ->
                    val item=repo.notification(id)
                    if(item?.state in setOf("UNREAD","READ")) repo.queueState(id,"OPENED")
                }
            }
        }
        setContent {
            val preferences = remember { getSharedPreferences("p14-push",Context.MODE_PRIVATE) }
            var pushStatus by remember { mutableStateOf(P14Push.registrationStatus(this)) }
            var pushBusy by remember { mutableStateOf(false) }
            var discussingId by remember { mutableStateOf<String?>(null) }
            var discussionError by remember { mutableStateOf<String?>(null) }
            DisposableEffect(preferences) {
                val listener=SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if(key=="registration_status") runOnUiThread {
                        pushStatus=P14Push.registrationStatus(this@P14InboxActivity)
                        if(pushStatus?.contains("推送注册成功")==true ||
                            pushStatus?.contains("失败")==true) pushBusy=false
                    }
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val startPush:()->Unit={
                pushBusy=true
                pushStatus="正在启动 UnifiedPush 注册…"
                P14Push.enable(this) { result ->
                    runOnUiThread {
                        pushBusy=false
                        pushStatus=P14Push.registrationStatus(this@P14InboxActivity)
                            ?: result.fold(
                                onSuccess={"分发器已受理请求，等待 P14 服务器确认"},
                                onFailure={"推送注册失败："+(it.message ?: "未知错误")},
                            )
                    }
                }
            }
            val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if(granted) startPush()
                else pushStatus="Android 通知权限未开放，请在系统设置中允许 RikkaHub 通知"
            }
            RikkahubTheme {
                P14InboxScreen(
                    repo=repo,
                    onBack={finish()},
                    onEnablePush={
                        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)
                                !=PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else startPush()
                    },
                    onOpenEntries={startActivity(Intent(this,P14EntryActivity::class.java))},
                    pushStatus=pushStatus,
                    pushBusy=pushBusy,
                    initialNotificationId=initialNotificationId,
                    discussingId=discussingId,
                    discussionError=discussionError,
                    onDiscuss={ id ->
                        if(discussingId==null) {
                            discussingId=id
                            discussionError=null
                            lifecycleScope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val chatId=nativeChatIdForNotification(id)
                                        if(nativeConversations.getConversationById(chatId)==null) {
                                            val snapshot=repo.authoritativeNotification(id)
                                            prepareNativeNotificationDiscussion(
                                                notification=snapshot,
                                                product=product,
                                                conversations=nativeConversations,
                                                settingsStore=settingsStore,
                                            )
                                        }
                                        chatId
                                    }
                                }.onSuccess { chatId ->
                                    P14RemoteWork.enqueue(this@P14InboxActivity)
                                    startActivity(
                                        Intent(this@P14InboxActivity,RouteActivity::class.java)
                                            .putExtra("conversationId",chatId.toString())
                                            .putExtra(P14_EXTRA_NATIVE_ENTRY_CHAT,true)
                                    )
                                }.onFailure {
                                    discussionError="无法打开通知讨论："+(it.message ?: "请检查 P14 连接和远程模型设置")
                                }
                                discussingId=null
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun P14InboxScreen(
    repo:P14RemoteRepository,
    onBack:()->Unit,
    onEnablePush:()->Unit,
    onOpenEntries:()->Unit,
    pushStatus:String?,
    pushBusy:Boolean,
    initialNotificationId:String?,
    discussingId:String?,
    discussionError:String?,
    onDiscuss:(String)->Unit,
) {
    val notifications by repo.notifications.collectAsState(initial=emptyList())
    val scope=rememberCoroutineScope()
    var expandedId by rememberSaveable { mutableStateOf(initialNotificationId) }
    Scaffold(
        topBar={ TopAppBar(title={Text("P14 Remote Inbox")},navigationIcon={TextButton(onClick=onBack){Text("Back")}},actions={TextButton(onClick=onOpenEntries){Text("Entries")};TextButton(enabled=!pushBusy,onClick=onEnablePush){Text(if(pushBusy) "Connecting…" else "Enable Push")}}) }
    ){ pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if(!pushStatus.isNullOrBlank()) {
                Surface(
                    modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
                    shape=MaterialTheme.shapes.medium,
                    color=MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        pushStatus,
                        modifier=Modifier.padding(14.dp),
                        style=MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if(!discussionError.isNullOrBlank()) {
                Text(discussionError,Modifier.padding(horizontal=16.dp,vertical=8.dp),
                    color=MaterialTheme.colorScheme.error)
            }
            if(notifications.isEmpty()) Box(Modifier.fillMaxSize()) {
                Text("No durable notifications",Modifier.padding(24.dp))
            }
            else LazyColumn(
                modifier=Modifier.fillMaxSize(),
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp),
            ) {
                items(notifications,key={it.notificationId}) { n ->
                    P14NotificationCard(
                        n=n,repo=repo,scope=scope,
                        expanded=expandedId==n.notificationId,
                        discussing=discussingId==n.notificationId,
                        onToggle={
                            expandedId=if(expandedId==n.notificationId) null else n.notificationId
                            if(expandedId==n.notificationId && n.state in setOf("UNREAD","READ")) {
                                scope.launch { runCatching { repo.queueState(n.notificationId,"OPENED") } }
                            }
                        },
                        onDiscuss={onDiscuss(n.notificationId)},
                    )
                }
            }
        }
    }
}

@Composable private fun P14NotificationCard(
    n:P14NotificationEntity,
    repo:P14RemoteRepository,
    scope:kotlinx.coroutines.CoroutineScope,
    expanded:Boolean,
    discussing:Boolean,
    onToggle:()->Unit,
    onDiscuss:()->Unit,
) {
    var interaction by remember(n.notificationId,n.revision){ mutableStateOf<P14InteractionEntity?>(null) }
    var detail by remember(n.notificationId) { mutableStateOf<P14NotificationDto?>(null) }
    var detailError by remember(n.notificationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(n.notificationId,n.revision) { interaction=repo.interaction(n.notificationId) }
    LaunchedEffect(n.notificationId,expanded) {
        if(expanded) {
            runCatching { repo.authoritativeNotification(n.notificationId) }
                .onSuccess { detail=it;detailError=null }
                .onFailure { detailError="离线：暂时只能查看本机保存的通知摘要" }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(n.title,style=MaterialTheme.typography.titleMedium); Text(n.state,style=MaterialTheme.typography.labelMedium) }
            Text(n.body,style=MaterialTheme.typography.bodyMedium)
            Text("${n.severity} · rev ${n.revision}",style=MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onToggle) { Text(if(expanded) "收起详情" else "查看详情") }
                Button(onClick=onDiscuss,enabled=!discussing) {
                    Text(if(discussing) "正在打开…" else "与 AI 讨论")
                }
            }
            if(expanded) {
                HorizontalDivider()
                Text("通知 ID：${n.notificationId}",style=MaterialTheme.typography.labelSmall)
                Text("关联会话：${n.conversationId ?: "暂无"}",style=MaterialTheme.typography.labelSmall)
                detail?.let { snapshot ->
                    Text("来源：${snapshot.producerId} · ${snapshot.producerEventId}",
                        style=MaterialTheme.typography.labelSmall)
                    Text("创建于：${snapshot.createdAt}",style=MaterialTheme.typography.labelSmall)
                    if(snapshot.contextRefs.isNotEmpty()) {
                        Text("关联资料",style=MaterialTheme.typography.titleSmall)
                        snapshot.contextRefs.forEach { (key,value) ->
                            Text("$key：$value",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("通知内容只作为背景资料；涉及修复、部署、批准等操作仍需服务端确认权限。",
                        style=MaterialTheme.typography.bodySmall)
                }
                detailError?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
            }
            if(n.state=="UNREAD") TextButton(onClick={scope.launch{repo.queueState(n.notificationId,"READ")}}){Text("Mark read")}
            interaction?.let { i ->
                Text(i.prompt,style=MaterialTheme.typography.bodyMedium)
                if(i.actionable && i.status=="PENDING") {
                    val options=runCatching { Json.decodeFromString<List<P14InteractionOption>>(i.optionsJson) }.getOrDefault(emptyList())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        options.take(3).forEach { o -> Button(onClick={scope.launch { repo.queueInteraction(n.notificationId,o.id); interaction=repo.interaction(n.notificationId) }}){Text(o.label)} }
                    }
                } else Text(i.status,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
