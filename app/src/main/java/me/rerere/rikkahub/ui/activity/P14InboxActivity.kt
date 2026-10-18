package me.rerere.rikkahub.ui.activity

import android.os.Bundle
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
        setContent { RikkahubTheme { P14InboxScreen(repo,{finish()},{ P14Push.enable(this){} },{startActivity(Intent(this,P14EntryActivity::class.java))}) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun P14InboxScreen(repo:P14RemoteRepository,onBack:()->Unit,onEnablePush:()->Unit,onOpenEntries:()->Unit) {
    val notifications by repo.notifications.collectAsState(initial=emptyList())
    val scope=rememberCoroutineScope()
    Scaffold(
        topBar={ TopAppBar(title={Text("P14 Remote Inbox")},navigationIcon={TextButton(onClick=onBack){Text("Back")}},actions={TextButton(onClick=onOpenEntries){Text("Entries")};TextButton(onClick=onEnablePush){Text("Enable Push")}}) }
    ){ pad ->
        if(notifications.isEmpty()) Box(Modifier.padding(pad).fillMaxSize()) { Text("No durable notifications",Modifier.padding(24.dp)) }
        else LazyColumn(Modifier.padding(pad).fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(notifications,key={it.notificationId}) { n -> P14NotificationCard(n,repo,scope) }
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
