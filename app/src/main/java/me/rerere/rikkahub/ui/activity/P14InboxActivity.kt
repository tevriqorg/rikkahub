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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.p14.*
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import okhttp3.OkHttpClient
import org.koin.android.ext.android.get

class P14InboxActivity:ComponentActivity() {
    private lateinit var repo:P14RemoteRepository
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        repo=P14RemoteRepository(applicationContext,get<SettingsStore>(),get<OkHttpClient>())
        lifecycleScope.launch { runCatching { repo.refreshActive() } }
        intent.getStringExtra("notification_id")?.let { id -> lifecycleScope.launch { repo.queueState(id,"OPENED") } }
        setContent {
            val preferences = remember { getSharedPreferences("p14-push",Context.MODE_PRIVATE) }
            var pushStatus by remember { mutableStateOf(P14Push.registrationStatus(this)) }
            var pushBusy by remember { mutableStateOf(false) }
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
) {
    val notifications by repo.notifications.collectAsState(initial=emptyList())
    val scope=rememberCoroutineScope()
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
            if(notifications.isEmpty()) Box(Modifier.fillMaxSize()) {
                Text("No durable notifications",Modifier.padding(24.dp))
            }
            else LazyColumn(
                modifier=Modifier.fillMaxSize(),
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp),
            ) {
                items(notifications,key={it.notificationId}) { n -> P14NotificationCard(n,repo,scope) }
            }
        }
    }
}

@Composable private fun P14NotificationCard(n:P14NotificationEntity,repo:P14RemoteRepository,scope:kotlinx.coroutines.CoroutineScope) {
    var interaction by remember(n.notificationId,n.revision){ mutableStateOf<P14InteractionEntity?>(null) }
    LaunchedEffect(n.notificationId,n.revision) { interaction=repo.interaction(n.notificationId) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(n.title,style=MaterialTheme.typography.titleMedium); Text(n.state,style=MaterialTheme.typography.labelMedium) }
            Text(n.body,style=MaterialTheme.typography.bodyMedium)
            Text("${n.severity} · rev ${n.revision}",style=MaterialTheme.typography.labelSmall)
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
