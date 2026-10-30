package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.remote.p14.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Visual authority: the human's 2026-10-30 real TickTick quick-add/editor captures,
// then the attached six-screen P14 reference. Product identities/protocol are unchanged.
internal data class P14Palette(val blue:Color,val background:Color,val surface:Color,val ink:Color,val muted:Color,val line:Color,val chip:Color,val user:Color)
private val light=P14Palette(Color(0xFF2868F6),Color(0xFFFCFDFE),Color.White,Color(0xFF1C222C),Color(0xFF7E8997),Color(0xFFECEFF3),Color(0xFFECF2FF),Color(0xFFE6EFFF))
private val dark=P14Palette(Color(0xFF719FFF),Color(0xFF0F151F),Color(0xFF151E2B),Color(0xFFEDF1F8),Color(0xFF9BA8B9),Color(0xFF27313E),Color(0xFF203351),Color(0xFF243E66))
@Composable internal fun p14Palette()=if(isSystemInDarkTheme()) dark else light

internal enum class P14Symbol { MENU,SEARCH,MORE,MORE_HORIZONTAL,INBOX,CALENDAR,TAG,USER,PLUS,BACK,FLAG,ATTACH,LIST,FORMAT,UNDO,REDO,MIC,SEND,ARCHIVE,PIN,SHARE,FOLDER,CLOSE,CHECK,FILE,MOVE,IMAGE,AUDIO,DOWN,EDIT,KEYBOARD }
@Composable internal fun P14Glyph(symbol:P14Symbol,description:String?=null,tint:Color=p14Palette().ink,modifier:Modifier=Modifier.size(22.dp)) {
    Canvas(modifier.then(if(description!=null) Modifier.semantics { contentDescription=description } else Modifier)) {
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero) {
            val stroke=Stroke(width=1.65f,cap=StrokeCap.Round)
            fun line(x:Float,y:Float,u:Float,v:Float)=drawLine(tint,Offset(x,y),Offset(u,v),1.65f,StrokeCap.Round)
            fun path(vararg pts:Float) { val p=Path();p.moveTo(pts[0],pts[1]);for(i in 2 until pts.size step 2)p.lineTo(pts[i],pts[i+1]);drawPath(p,tint,style=stroke) }
            when(symbol) {
                P14Symbol.MENU -> {line(4f,6f,20f,6f);line(4f,12f,20f,12f);line(4f,18f,20f,18f)}
                P14Symbol.SEARCH -> {drawCircle(tint,7f,Offset(10f,10f),style=stroke);line(15f,15f,21f,21f)}
                P14Symbol.MORE -> for(y in listOf(5f,12f,19f))drawCircle(tint,1.5f,Offset(12f,y))
                P14Symbol.MORE_HORIZONTAL -> for(x in listOf(5f,12f,19f))drawCircle(tint,1.5f,Offset(x,12f))
                P14Symbol.PLUS -> {line(5f,12f,19f,12f);line(12f,5f,12f,19f)}
                P14Symbol.BACK -> path(15f,4f,7f,12f,15f,20f)
                P14Symbol.DOWN -> path(6f,9f,12f,15f,18f,9f)
                P14Symbol.CLOSE -> {line(5f,5f,19f,19f);line(19f,5f,5f,19f)}
                P14Symbol.CHECK -> path(4f,12f,9f,17f,20f,6f)
                P14Symbol.FLAG -> {line(5f,3f,5f,22f);path(5f,4f,20f,4f,16f,9f,20f,14f,5f,14f)}
                P14Symbol.CALENDAR -> {drawRoundRect(tint,Offset(3f,5f),Size(18f,16f),androidx.compose.ui.geometry.CornerRadius(3f),style=stroke);line(3f,10f,21f,10f);line(8f,2f,8f,7f);line(16f,2f,16f,7f);drawCircle(tint,1f,Offset(8f,14f));drawCircle(tint,1f,Offset(13f,14f));drawCircle(tint,1f,Offset(8f,18f))}
                P14Symbol.INBOX,P14Symbol.ARCHIVE -> {path(4f,4f,20f,4f,22f,15f,22f,21f,2f,21f,2f,15f,4f,4f);path(2f,15f,8f,15f,10f,18f,14f,18f,16f,15f,22f,15f);if(symbol==P14Symbol.ARCHIVE){line(12f,6f,12f,12f);path(9f,10f,12f,13f,15f,10f)}}
                P14Symbol.TAG -> {path(3f,3f,12f,3f,22f,13f,13f,22f,3f,12f,3f,3f);drawCircle(tint,1.5f,Offset(7.5f,7.5f),style=stroke)}
                P14Symbol.USER -> {drawCircle(tint,4f,Offset(12f,7f),style=stroke);val p=Path();p.moveTo(3f,22f);p.cubicTo(3f,11f,21f,11f,21f,22f);drawPath(p,tint,style=stroke)}
                P14Symbol.FOLDER,P14Symbol.MOVE -> {path(2f,7f,2f,21f,22f,21f,22f,7f,12f,7f,9f,3f,2f,3f,2f,7f);if(symbol==P14Symbol.MOVE){line(8f,14f,17f,14f);path(14f,11f,17f,14f,14f,17f)}}
                P14Symbol.ATTACH -> {val p=Path();p.moveTo(17f,9f);p.lineTo(9f,17f);p.cubicTo(5f,21f,1f,16f,5f,12f);p.lineTo(14f,3f);p.cubicTo(20f,-2f,26f,5f,20f,11f);p.lineTo(10f,21f);drawPath(p,tint,style=stroke)}
                P14Symbol.LIST -> for(y in listOf(6f,12f,18f)){drawCircle(tint,1.3f,Offset(4f,y));line(9f,y,21f,y)}
                P14Symbol.FORMAT -> {path(5f,18f,12f,3f,19f,18f);line(8f,13f,16f,13f);line(3f,22f,21f,22f)}
                P14Symbol.UNDO,P14Symbol.REDO -> {
                    fun x(value:Float):Float {return if(symbol==P14Symbol.UNDO)value else 24f-value}
                    path(x(8f),5f,x(3f),10f,x(8f),15f)
                    val p=Path();p.moveTo(x(3f),10f);p.lineTo(x(14f),10f);p.cubicTo(x(23f),10f,x(23f),21f,x(12f),21f)
                    drawPath(p,tint,style=stroke)
                }
                P14Symbol.MIC,P14Symbol.AUDIO -> {drawRoundRect(tint,Offset(8f,2f),Size(8f,13f),androidx.compose.ui.geometry.CornerRadius(4f),style=stroke);val p=Path();p.moveTo(4f,11f);p.cubicTo(4f,22f,20f,22f,20f,11f);drawPath(p,tint,style=stroke);line(12f,20f,12f,24f)}
                P14Symbol.SEND -> path(3f,3f,22f,12f,3f,21f,7f,12f,3f,3f,22f,12f,7f,12f)
                P14Symbol.PIN -> {path(8f,2f,17f,2f,16f,10f,21f,15f,3f,15f,8f,10f,8f,2f);line(12f,15f,12f,23f)}
                P14Symbol.SHARE -> {drawCircle(tint,2.5f,Offset(18f,4f),style=stroke);drawCircle(tint,2.5f,Offset(5f,12f),style=stroke);drawCircle(tint,2.5f,Offset(18f,20f),style=stroke);line(7f,11f,16f,5f);line(7f,13f,16f,19f)}
                P14Symbol.FILE,P14Symbol.EDIT -> {path(5f,2f,15f,2f,21f,8f,21f,22f,5f,22f,5f,2f);path(15f,2f,15f,8f,21f,8f);line(9f,13f,17f,13f);line(9f,17f,16f,17f)}
                P14Symbol.IMAGE -> {drawRoundRect(tint,Offset(2f,3f),Size(20f,18f),androidx.compose.ui.geometry.CornerRadius(3f),style=stroke);drawCircle(tint,2f,Offset(8f,8f),style=stroke);path(3f,19f,10f,12f,14f,16f,18f,12f,22f,17f)}
                P14Symbol.KEYBOARD -> {drawRoundRect(tint,Offset(2f,3f),Size(20f,13f),androidx.compose.ui.geometry.CornerRadius(3f),style=stroke);for(y in listOf(7f,11f))for(x in listOf(6f,10f,14f,18f))drawCircle(tint,.7f,Offset(x,y));path(9f,20f,12f,23f,15f,20f)}
            }
        }
    }
}
@Composable internal fun P14IconButton(symbol:P14Symbol,label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,pending:Boolean=false,tint:Color=p14Palette().ink) {
    IconButton(onClick=onClick,enabled=enabled,modifier=modifier.size(42.dp).semantics {contentDescription=label;if(pending)stateDescription="待实现"}) {
        Box {P14Glyph(symbol,tint=tint.copy(alpha=if(enabled)1f else .4f));if(pending)Text("待",fontSize=7.sp,color=p14Palette().muted,modifier=Modifier.align(Alignment.BottomEnd).offset(x=7.dp,y=6.dp))}
    }
}
internal fun p14InboxTitle(body:String,entryId:String):String=body.lineSequence().map {it.trim()}.firstOrNull {it.isNotBlank()}?.take(120) ?: "未命名条目"
internal fun p14InboxPreview(body:String):String=body.lineSequence().map {it.trim()}.filter {it.isNotBlank()}.drop(1).take(2).joinToString(" ").take(150)
internal fun p14InboxFiltered(rows:List<P14InboxRow>,query:String):List<P14InboxRow> = if(query.isBlank())rows else rows.filter {it.bodyText.contains(query.trim(),true)}
internal fun p14InboxConversationMessages(messages:List<P14ProductMessageEntity>,entryBody:String,entryCreatedAt:Long,sourceMessageId:String?=null):List<P14ProductMessageEntity> {
    val visible=p14VisibleEntryMessages(messages)
    val source=sourceMessageId ?: visible.firstOrNull {it.role.equals("user",true)&&it.bodyText.trim()==entryBody.trim()&&kotlin.math.abs(it.createdAtEpochMs-entryCreatedAt)<=15_000L}?.messageId
    return visible.filterNot {it.messageId==source}
}
private fun recordDate(epoch:Long):String {val now=java.util.Calendar.getInstance();val at=java.util.Calendar.getInstance().apply {timeInMillis=epoch};return if(now.get(java.util.Calendar.YEAR)==at.get(java.util.Calendar.YEAR)&&now.get(java.util.Calendar.DAY_OF_YEAR)==at.get(java.util.Calendar.DAY_OF_YEAR)) "今天 "+SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(epoch)) else SimpleDateFormat("M月d日",Locale.CHINA).format(Date(epoch))}
internal fun p14ViewTitle(view:String)=when(view){"todo"->"待办";"idea"->"想法";"research"->"研究";"execute"->"执行";else->view}
@Composable private fun ClassificationPill(text:String,modifier:Modifier=Modifier) {val c=p14Palette();Text(text,color=c.blue,fontSize=10.sp,lineHeight=14.sp,modifier=modifier.clip(RoundedCornerShape(7.dp)).background(c.chip).padding(horizontal=6.dp,vertical=2.dp))}
@Composable private fun P14SmallChip(label:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {val c=p14Palette();Text(label,fontSize=12.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,color=if(selected)Color.White else c.muted,modifier=modifier.clip(CircleShape).background(if(selected)c.blue else c.line.copy(alpha=.7f)).clickable(onClick=onClick).padding(horizontal=13.dp,vertical=7.dp))}
@Composable private fun AttachmentThumbnail(locator:String?,sizeDp:Int=58,onClick:(()->Unit)?=null) {val c=p14Palette();Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(c.line).then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier),contentAlignment=Alignment.Center) {if(!locator.isNullOrBlank())AsyncImage(model=Uri.parse(locator),contentDescription="真实图片附件",contentScale=androidx.compose.ui.layout.ContentScale.Crop,modifier=Modifier.fillMaxSize()) else P14Glyph(P14Symbol.IMAGE,tint=c.muted)}}
@Composable private fun InboxRecordRow(row:P14InboxRow,onOpen:()->Unit,onArchive:()->Unit,onPin:()->Unit,onLoadThumbnail:(String)->Unit) {
    val c=p14Palette();var menu by remember {mutableStateOf(false)};val context=LocalContext.current
    LaunchedEffect(row.thumbnailAttachmentId,row.thumbnailLocator) {if(row.thumbnailLocator.isNullOrBlank())row.thumbnailAttachmentId?.let(onLoadThumbnail)}
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick=onOpen,onLongClick={menu=true}).padding(horizontal=18.dp,vertical=12.dp).testTag("p14-inbox-item-${row.entryId}"),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(p14InboxTitle(row.bodyText,row.entryId),fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=c.ink,maxLines=2,overflow=TextOverflow.Ellipsis)
                val summary=p14InboxPreview(row.bodyText);if(summary.isNotBlank())Text(summary,fontSize=12.sp,lineHeight=17.sp,color=c.muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    row.views?.split(',')?.filter {it.isNotBlank()}?.take(2)?.forEach {ClassificationPill(p14ViewTitle(it))}
                    Text(recordDate(row.createdAtEpochMs),color=c.muted,fontSize=10.sp,maxLines=1)
                    if(row.attachmentCount>0&&row.thumbnailAttachmentId==null){P14Glyph(P14Symbol.ATTACH,tint=c.muted,modifier=Modifier.size(11.dp));Text(row.attachmentCount.toString(),fontSize=10.sp,color=c.muted)}
                }
            }
            if(row.thumbnailAttachmentId!=null)AttachmentThumbnail(row.thumbnailLocator)
        }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false},containerColor=c.surface) {
            DropdownMenuItem(text={Text(if(row.pinned)"取消置顶" else "置顶")},onClick={menu=false;onPin()},leadingIcon={P14Glyph(P14Symbol.PIN)})
            DropdownMenuItem(text={Text(if(row.archived)"移回收件箱" else "归档")},onClick={menu=false;onArchive()},leadingIcon={P14Glyph(P14Symbol.ARCHIVE)},modifier=Modifier.testTag("p14-inbox-toggle-${row.entryId}"))
            DropdownMenuItem(text={Text("分享记录")},onClick={menu=false;context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,row.bodyText),"分享记录"))},leadingIcon={P14Glyph(P14Symbol.SHARE)})
        }
    }
    HorizontalDivider(Modifier.padding(horizontal=18.dp),color=c.line,thickness=.6.dp)
}
@Composable private fun InboxSection(label:String,count:Int,pinned:Boolean=false) {val c=p14Palette();Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){if(pinned)P14Glyph(P14Symbol.PIN,tint=c.blue,modifier=Modifier.size(13.dp));Text(label,color=c.muted,fontSize=11.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.weight(1f));Text(count.toString(),fontSize=11.sp,color=c.muted)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun P14TickTickInboxScreen(
    rows:List<P14InboxRow>,archived:Boolean,onShowArchived:(Boolean)->Unit,draftText:String,onDraftChange:(String)->Unit,saving:Boolean,status:String?,onQuickSave:()->Unit,onRichCapture:()->Unit,onOpen:(String)->Unit,onToggleArchive:(P14InboxRow)->Unit,onMore:()->Unit,onLoadMore:()->Unit,hasMore:Boolean,onBack:()->Unit,
    resumeQuickDraft:Boolean=false,onSettings:()->Unit=onBack,onTogglePin:(P14InboxRow)->Unit={},onLoadThumbnail:(String)->Unit={},onSearchChange:(String)->Unit={},managedSearch:Boolean=false,onViewFilter:(String)->Unit={},availableViews:List<String> = listOf("todo","idea","research","execute"),activeView:String="",draftMaterials:List<P14DraftMaterial> = emptyList(),onRemoveDraftMaterial:(P14DraftMaterial)->Unit={},onRecordVoice:()->Unit={},recording:Boolean=false,draftViews:Set<String> = emptySet(),onDraftView:(String)->Unit={},
) {
    val c=p14Palette();val scope=rememberCoroutineScope();val drawer=rememberDrawerState(DrawerValue.Closed)
    var searchOpen by rememberSaveable {mutableStateOf(false)};var query by rememberSaveable {mutableStateOf("")};var addOpen by rememberSaveable {mutableStateOf(resumeQuickDraft)};var nav by rememberSaveable {mutableStateOf("收件箱")};var more by remember {mutableStateOf(false)}
    val display=if(managedSearch)rows else p14InboxFiltered(rows,query);val pinned=display.filter {it.pinned};val normal=display.filterNot {it.pinned}
    BackHandler(enabled=nav!="收件箱"||searchOpen) {if(searchOpen){searchOpen=false;query="";onSearchChange("")} else nav="收件箱"}
    ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet(drawerContainerColor=c.surface) {
        Text("P14 Entry",fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=c.ink,modifier=Modifier.padding(22.dp))
        NavigationDrawerItem(label={Text("收件箱")},selected=!archived,onClick={onShowArchived(false);onViewFilter("");nav="收件箱";scope.launch {drawer.close()}})
        NavigationDrawerItem(label={Text("已归档")},selected=archived,onClick={onShowArchived(true);nav="收件箱";scope.launch {drawer.close()}})
        HorizontalDivider(Modifier.padding(vertical=12.dp),color=c.line)
        NavigationDrawerItem(label={Text("连接与设置")},selected=false,onClick=onSettings)
        NavigationDrawerItem(label={Text("更多管理功能")},selected=false,onClick=onMore)
    }}) {
        Scaffold(containerColor=c.background,contentWindowInsets=WindowInsets(0,0,0,0),topBar={
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal=7.dp),verticalAlignment=Alignment.CenterVertically) {
                P14IconButton(P14Symbol.MENU,"打开侧边栏",{scope.launch {drawer.open()}})
                Text(if(nav!="收件箱")nav else if(archived)"已归档" else if(activeView.isNotBlank())p14ViewTitle(activeView) else "收件箱",fontSize=21.sp,fontWeight=FontWeight.Bold,color=c.ink,modifier=Modifier.weight(1f).padding(start=5.dp))
                P14IconButton(P14Symbol.SEARCH,"搜索记录",{nav="收件箱";searchOpen=!searchOpen;if(!searchOpen){query="";onSearchChange("")}})
                Box {P14IconButton(P14Symbol.MORE,"收件箱更多操作",{more=true});DropdownMenu(more,{more=false},containerColor=c.surface) {
                    DropdownMenuItem(text={Text(if(archived)"查看收件箱" else "查看已归档")},onClick={more=false;onShowArchived(!archived)})
                    DropdownMenuItem(text={Text("更多管理功能")},onClick={more=false;onMore()})
                }}
            }
        },bottomBar={Surface(color=c.background) {Row(Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp),horizontalArrangement=Arrangement.SpaceAround,verticalAlignment=Alignment.CenterVertically) {
            listOf("收件箱" to P14Symbol.INBOX,"日历" to P14Symbol.CALENDAR,"标签" to P14Symbol.TAG,"我的" to P14Symbol.USER).forEach { (label,icon) ->
                Column(Modifier.weight(1f).fillMaxHeight().clickable {nav=label}.padding(top=8.dp).testTag("p14-nav-$label"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(38.dp,25.dp).clip(CircleShape).background(if(nav==label)c.chip else Color.Transparent),contentAlignment=Alignment.Center){P14Glyph(icon,tint=if(nav==label)c.blue else c.muted,modifier=Modifier.size(21.dp))}
                    Text(label,fontSize=10.sp,color=if(nav==label)c.blue else c.muted)
                }
            }
        }}},floatingActionButton={if(nav=="收件箱"&&!archived)FloatingActionButton(onClick={addOpen=true},containerColor=c.blue,contentColor=Color.White,shape=CircleShape,modifier=Modifier.size(54.dp).testTag("p14-inbox-add")){Text("+",fontSize=31.sp,fontWeight=FontWeight.Light)}}) { pad ->
            Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
                if(nav=="收件箱") {
                    if(searchOpen)BasicTextField(value=query,onValueChange={query=it;onSearchChange(it)},singleLine=true,textStyle=TextStyle(color=c.ink,fontSize=15.sp),cursorBrush=SolidColor(c.blue),modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp).clip(RoundedCornerShape(9.dp)).background(c.line).padding(12.dp).testTag("p14-inbox-search"),decorationBox={inner->Box {if(query.isBlank())Text("搜索记录与对话",color=c.muted,fontSize=14.sp);inner()}})
                    Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=9.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        P14SmallChip("全部 ${display.size}${if(hasMore)"+" else ""}",!archived&&activeView.isEmpty(),{onShowArchived(false);onViewFilter("")},Modifier.testTag("p14-inbox-all-tab"))
                        if("todo" in availableViews)P14SmallChip("待办",activeView=="todo"&&!archived,{onShowArchived(false);onViewFilter("todo")})
                        P14SmallChip("已归档",archived,{onShowArchived(true)},Modifier.testTag("p14-inbox-archived-tab"))
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("p14-inbox-list"),contentPadding=PaddingValues(bottom=78.dp)) {
                        if(status!=null)item("status"){Text(status,color=c.blue,fontSize=11.sp,modifier=Modifier.padding(horizontal=18.dp,vertical=5.dp))}
                        if(display.isEmpty())item("empty"){Column(Modifier.fillMaxWidth().padding(vertical=80.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){P14Glyph(P14Symbol.INBOX,tint=c.muted,modifier=Modifier.size(36.dp));Text(if(query.isNotBlank())"没有匹配的记录" else if(archived)"还没有已归档的记录" else "随时记下想法，点 + 开始",color=c.muted,fontSize=14.sp)}}
                        if(pinned.isNotEmpty())item("pin-title"){InboxSection("已置顶",pinned.size,true)}
                        items(pinned,key={it.entryId}){row->InboxRecordRow(row,{onOpen(row.conversationId)},{onToggleArchive(row)},{onTogglePin(row)},onLoadThumbnail)}
                        if(normal.isNotEmpty())item("records-title"){InboxSection(if(archived)"已归档" else "记录",normal.size)}
                        items(normal,key={it.entryId}){row->InboxRecordRow(row,{onOpen(row.conversationId)},{onToggleArchive(row)},{onTogglePin(row)},onLoadThumbnail)}
                        if(hasMore)item("more"){TextButton(onClick=onLoadMore,modifier=Modifier.fillMaxWidth()){Text(if(query.isBlank())"加载更多" else "继续搜索历史记录",color=c.blue)}}
                    }
                } else if(nav=="标签") {
                    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("分类视图",fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=c.ink)
                        Text("复用当前 P14 分类。同一条记录可以出现在多个视图中。",color=c.muted,fontSize=13.sp,lineHeight=20.sp)
                        availableViews.forEach {view->Row(Modifier.fillMaxWidth().clickable {onShowArchived(false);onViewFilter(view);nav="收件箱"}.padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){P14Glyph(P14Symbol.TAG,tint=c.blue);Text(p14ViewTitle(view),fontSize=16.sp,color=c.ink)}}
                        HorizontalDivider(color=c.line);Text("自定义标签：待实现",color=c.muted,fontSize=12.sp)
                    }
                } else if(nav=="日历") {
                    Column(Modifier.fillMaxWidth().padding(28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){P14Glyph(P14Symbol.CALENDAR,tint=c.muted,modifier=Modifier.size(34.dp));Text("日历 · 待实现",color=c.ink,fontSize=21.sp,fontWeight=FontWeight.SemiBold);Text("当前协议没有截止日期、提醒与重复规则。这里不会把创建时间冒充任务日期。",color=c.muted,fontSize=14.sp,lineHeight=23.sp)}
                } else {
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){Text("P14 Entry",color=c.ink,fontSize=25.sp,fontWeight=FontWeight.Bold);Text("记录优先，AI 可选",color=c.muted,fontSize=15.sp);TextButton(onClick=onSettings){Text("连接与设置",color=c.blue)};TextButton(onClick=onMore){Text("Profile、通知与高级管理",color=c.blue)};Text("浅色与深色跟随 Android 系统。",color=c.muted,fontSize=12.sp)}
                }
            }
        }
    }
    if(addOpen&&!archived)P14QuickCaptureSheet(draftText,onDraftChange,saving,status,draftMaterials,onRemoveDraftMaterial,onRichCapture,onRecordVoice,recording,onQuickSave,{addOpen=false},availableViews,draftViews,onDraftView)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun P14QuickCaptureSheet(text:String,onText:(String)->Unit,saving:Boolean,status:String?,materials:List<P14DraftMaterial>,onRemove:(P14DraftMaterial)->Unit,onAttach:()->Unit,onVoice:()->Unit,recording:Boolean,onSave:()->Unit,onDismiss:()->Unit,availableViews:List<String> = emptyList(),selectedViews:Set<String> = emptySet(),onView:(String)->Unit={}) {
    val c=p14Palette();val focus=remember {FocusRequester()};val keyboard=LocalSoftwareKeyboardController.current
    var value by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(text,TextRange(text.length)))}
    var more by remember {mutableStateOf(false)};var viewMenu by remember {mutableStateOf(false)};var requested by remember {mutableStateOf(false)}
    LaunchedEffect(text){if(value.text!=text)value=TextFieldValue(text,TextRange(text.length))}
    LaunchedEffect(saving,text,materials.size){if(requested&&!saving&&text.isBlank()&&materials.isEmpty())onDismiss()}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp),containerColor=c.surface,scrimColor=Color.Black.copy(alpha=.32f),dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            val orientation=LocalConfiguration.current.orientation
            val windowFocused=LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(orientation,windowFocused){if(windowFocused){withFrameNanos {};delay(350);focus.requestFocus();delay(150);keyboard?.show()}}
            BasicTextField(value=value,onValueChange={value=it;onText(it.text)},textStyle=TextStyle(color=c.ink,fontSize=17.sp,lineHeight=24.sp),cursorBrush=SolidColor(c.blue),modifier=Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,top=17.dp,bottom=3.dp).heightIn(min=76.dp,max=200.dp).focusRequester(focus).testTag("p14-inbox-quick-input"),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Default),decorationBox={inner->Box {if(value.text.isBlank())Text("例如：下午 3 点组织大家开会",color=c.muted.copy(alpha=.55f),fontSize=16.sp);inner()}})
            if(materials.isNotEmpty())Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){materials.take(3).forEach {m->Text(m.originalName.take(18)+" ×",fontSize=11.sp,color=c.blue,modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(c.chip).clickable {onRemove(m)}.padding(6.dp))}}
            if(selectedViews.isNotEmpty())Row(Modifier.padding(horizontal=18.dp,vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){selectedViews.forEach {ClassificationPill(p14ViewTitle(it))}}
            if(recording)Text("正在录音 · 点麦克风停止",color=Color(0xFFD34A50),fontSize=12.sp,modifier=Modifier.padding(horizontal=18.dp,vertical=5.dp))
            if(status!=null&&requested&&!saving)Text(status,color=c.blue,fontSize=11.sp,modifier=Modifier.padding(horizontal=18.dp))
            Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal=8.dp).testTag("p14-quick-sheet"),verticalAlignment=Alignment.CenterVertically) {
                P14IconButton(P14Symbol.CALENDAR,"日期与提醒，待实现",{},enabled=false,pending=true,tint=c.muted)
                P14IconButton(P14Symbol.FLAG,"优先级，待实现",{},enabled=false,pending=true,tint=c.muted)
                Box {P14IconButton(P14Symbol.TAG,"选择分类视图",{viewMenu=true},enabled=availableViews.isNotEmpty(),tint=c.muted)
                    DropdownMenu(viewMenu,{viewMenu=false},containerColor=c.surface,properties=PopupProperties(focusable=false)) {
                        DropdownMenuItem(text={Text("分类视图 · 不改变记录身份")},onClick={},enabled=false)
                        availableViews.forEach {view -> DropdownMenuItem(text={Text((if(view in selectedViews)"✓ " else "")+p14ViewTitle(view))},onClick={onView(view)})}
                    }
                }
                P14IconButton(P14Symbol.MOVE,"加入分类视图",{viewMenu=true},enabled=availableViews.isNotEmpty(),tint=c.muted)
                Box {P14IconButton(P14Symbol.MORE_HORIZONTAL,"快速添加更多",{more=true},tint=c.muted);DropdownMenu(more,{more=false},containerColor=c.surface) {
                    DropdownMenuItem(text={Text("添加图片、语音或文件")},onClick={more=false;onAttach()})
                    DropdownMenuItem(text={Text(if(recording)"停止录音" else "录制语音")},onClick={more=false;onVoice()})
                    DropdownMenuItem(text={Text("保留草稿并关闭")},onClick={more=false;onDismiss()})
                }}
                Spacer(Modifier.weight(1f))
                if(text.isNotBlank()||materials.isNotEmpty())IconButton(onClick={requested=true;onSave()},enabled=!saving&&!recording,modifier=Modifier.size(36.dp).clip(CircleShape).background(c.blue).testTag("p14-inbox-quick-save").semantics {contentDescription="保存记录"}) {P14Glyph(P14Symbol.SEND,tint=Color.White,modifier=Modifier.size(19.dp))}
                else P14IconButton(P14Symbol.MIC,if(recording)"停止录音" else "录制语音",onVoice,tint=if(recording)Color(0xFFD34A50) else c.muted)
            }
        }
    }
}

@Composable private fun DetailAttachment(a:P14ProductAttachmentEntity,onOpen:()->Unit) {
    val c=p14Palette();Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(vertical=11.dp).testTag("p14-entry-attachment-${a.localAttachmentId}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        P14Glyph(if(a.mimeType.startsWith("audio/"))P14Symbol.AUDIO else P14Symbol.FILE,tint=if(a.mimeType=="application/pdf")Color(0xFFDD666B) else c.blue,modifier=Modifier.size(27.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(a.fileName,color=c.ink,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(listOfNotNull(a.byteSize?.let {if(it>1_048_576)"%.1f MB".format(it/1_048_576.0) else "${it/1024} KB"},a.durationMs?.let {"${it/1000} 秒"},recordDate(a.createdAtEpochMs)).joinToString(" · "),fontSize=10.sp,color=c.muted)}
        P14Glyph(P14Symbol.MORE,tint=c.muted,modifier=Modifier.size(17.dp))
    };HorizontalDivider(color=c.line,thickness=.6.dp)
}
@Composable private fun DetailAction(symbol:P14Symbol,label:String,onClick:()->Unit,modifier:Modifier=Modifier,blue:Boolean=false) {val c=p14Palette();Column(modifier.clickable(onClick=onClick).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){P14Glyph(symbol,tint=if(blue)c.blue else c.ink,modifier=Modifier.size(21.dp));Text(label,color=if(blue)c.blue else c.ink,fontSize=10.sp,maxLines=1)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun P14TickTickEntryDetailScreen(
    entryBody:String?,createdAt:Long?,conversationId:String,messages:List<P14ProductMessageEntity>,input:String,onInputChange:(String)->Unit,onSend:()->Unit,sending:Boolean,canChat:Boolean,status:String?,onBack:()->Unit,onAdvanced:()->Unit,attachments:List<P14ProductAttachmentEntity> = emptyList(),onOpenAttachment:(P14ProductAttachmentEntity)->Unit={},availableViews:List<String> = emptyList(),selectedViews:Set<String> = emptySet(),onToggleView:(String)->Unit={},
    entryId:String=conversationId,displayBody:String?=null,sourceMessageId:String?=null,sourceRevision:Int=1,onCommitEdit:((P14EntryEditDraft,(Boolean)->Unit)->Unit)?=null,onToggleArchive:()->Unit={},onTogglePin:()->Unit={},pinned:Boolean=false,archived:Boolean=false,onLoadThumbnail:(String)->Unit={},editConflict:Boolean=false,onResolveConflict:(Boolean)->Unit={},onChatActive:(Boolean)->Unit={},onRetryChat:()->Unit={},onOpenNativeChat:(()->Unit)?=null,
) {
    val c=p14Palette();val context=LocalContext.current;val raw=entryBody.orEmpty();val body=displayBody ?: raw
    var ai by rememberSaveable(entryId){mutableStateOf(false)};var edit by rememberSaveable(entryId){mutableStateOf(false)};var categories by rememberSaveable(entryId){mutableStateOf(false)};var more by remember {mutableStateOf(false)};var original by remember {mutableStateOf(false)};var attachmentMenu by remember {mutableStateOf(false)};var conflictDialog by remember {mutableStateOf(false)}
    DisposableEffect(ai) {onChatActive(ai);onDispose {onChatActive(false)}}
    val store=remember(context){P14EntryEditDraftStore(context)}
    if(edit) {
        val initial=store.read(entryId) ?: P14EntryEditDraft(entryId,sourceMessageId.orEmpty(),sourceRevision,P14EditorSnapshot(body.substringBefore('\n'),body.substringAfter('\n',"")))
        P14EntryEditor(initial,store,status,attachments,onOpenAttachment,availableViews,selectedViews,onToggleView,onSave={draft,done->onCommitEdit?.invoke(draft){accepted->if(accepted){store.clear(entryId);edit=false};done(accepted)}},onDiscard={edit=false},currentRevision=sourceRevision,currentBody=body)
        return
    }
    BackHandler {when {categories->categories=false;ai->ai=false;else->onBack()}}
    if(categories) {
        Scaffold(containerColor=c.background,contentWindowInsets=WindowInsets(0,0,0,0),topBar={Row(Modifier.statusBarsPadding().height(56.dp),verticalAlignment=Alignment.CenterVertically){P14IconButton(P14Symbol.BACK,"返回详情",{categories=false});Text("标签与分类",fontSize=20.sp,color=c.ink)}}) {pad->Column(Modifier.padding(pad).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("分类视图",fontSize=19.sp,fontWeight=FontWeight.SemiBold,color=c.ink)
            Text("整理同一条记录，不会创建新的 Entry。",color=c.muted,fontSize=13.sp)
            availableViews.forEach {view->FilterChip(selected=view in selectedViews,onClick={onToggleView(view)},label={Text(p14ViewTitle(view))},modifier=Modifier.fillMaxWidth().testTag("p14-entry-view-$view"))}
            if(availableViews.isEmpty())Text("当前配置没有可用分类视图",color=c.muted)
            Text("自定义标签、日历和提醒：待实现",color=c.muted,fontSize=12.sp)
        }};return
    }
    val visible=p14InboxConversationMessages(messages,raw,createdAt ?: 0L,sourceMessageId)
    val images=attachments.filter {it.mimeType.startsWith("image/")}
    LaunchedEffect(attachments.map {it.localAttachmentId}) {images.filter {it.localLocator.isBlank()}.forEach {onLoadThumbnail(it.localAttachmentId)}}
    Scaffold(modifier=Modifier.imePadding(),containerColor=c.background,contentWindowInsets=WindowInsets(0,0,0,0),topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
            P14IconButton(P14Symbol.BACK,if(ai)"返回条目详情" else "返回收件箱",{if(ai)ai=false else onBack()},Modifier.testTag("p14-entry-back"))
            if(ai)Text("与 AI 讨论",color=c.ink,fontSize=19.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            else Row(Modifier.weight(1f).clickable {categories=true},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Text("收件箱",fontSize=15.sp,color=c.ink);P14Glyph(P14Symbol.DOWN,tint=c.muted,modifier=Modifier.size(12.dp))}
            if(!ai)P14IconButton(P14Symbol.FLAG,"优先级，待实现",{},enabled=false,pending=true)
            Box {P14IconButton(P14Symbol.MORE,"记录更多操作",{more=true});DropdownMenu(more,{more=false},containerColor=c.surface) {
                DropdownMenuItem(text={Text("编辑记录")},enabled=onCommitEdit!=null&&sourceMessageId!=null,onClick={more=false;edit=true})
                DropdownMenuItem(text={Text(if(pinned)"取消置顶" else "置顶")},onClick={more=false;onTogglePin()})
                DropdownMenuItem(text={Text("查看原始 Capture")},onClick={more=false;original=true})
                DropdownMenuItem(text={Text("高级管理")},onClick={more=false;onAdvanced()})
            }}
        }
    },bottomBar={if(!ai)Surface(color=c.background,shadowElevation=1.dp) {Row(Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp),horizontalArrangement=Arrangement.SpaceAround) {
        DetailAction(P14Symbol.USER,"与 AI 讨论",{if (onOpenNativeChat != null) onOpenNativeChat() else ai=true},Modifier.weight(1f).testTag("p14-inbox-open-ai"),true)
        DetailAction(P14Symbol.SHARE,"分享",{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,body),"分享记录"))},Modifier.weight(1f))
        DetailAction(P14Symbol.MOVE,"移动",{categories=true},Modifier.weight(1f).testTag("p14-entry-categories"))
        DetailAction(P14Symbol.ARCHIVE,if(archived)"移回" else "归档",onToggleArchive,Modifier.weight(1f))
        DetailAction(P14Symbol.MORE,"更多",{more=true},Modifier.weight(1f))
    }} else Surface(color=c.background) {Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        P14IconButton(P14Symbol.PLUS,"查看上下文附件",{attachmentMenu=true})
        BasicTextField(value=input,onValueChange=onInputChange,enabled=canChat,textStyle=TextStyle(color=c.ink,fontSize=15.sp,lineHeight=21.sp),cursorBrush=SolidColor(c.blue),modifier=Modifier.weight(1f).clip(RoundedCornerShape(19.dp)).background(c.surface).padding(horizontal=13.dp,vertical=11.dp).heightIn(min=22.dp,max=110.dp).testTag("p14-entry-message-input"),decorationBox={inner->Box {if(input.isBlank())Text("输入消息…",fontSize=14.sp,color=c.muted);inner()}})
        IconButton(onClick=onSend,enabled=input.isNotBlank()&&!sending&&canChat,modifier=Modifier.size(39.dp).clip(CircleShape).background(if(input.isNotBlank()&&canChat)c.blue else c.line).testTag("p14-inbox-ai-send")){P14Glyph(P14Symbol.SEND,tint=if(input.isNotBlank()&&canChat)Color.White else c.muted,modifier=Modifier.size(21.dp))}
    }}}) {pad->
        if(!ai)LazyColumn(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().testTag("p14-inbox-detail"),contentPadding=PaddingValues(horizontal=19.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            item("content") {Column(Modifier.fillMaxWidth().testTag("p14-entry-body").then(if(onCommitEdit!=null&&sourceMessageId!=null)Modifier.clickable {edit=true} else Modifier),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){P14Glyph(P14Symbol.CALENDAR,tint=c.muted.copy(alpha=.6f),modifier=Modifier.size(15.dp));Text("日期&提醒 · 待实现",color=c.muted.copy(alpha=.7f),fontSize=12.sp)}
                Spacer(Modifier.height(1.dp));Text(p14InboxTitle(body,entryId),fontSize=23.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,color=c.ink)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){selectedViews.forEach {ClassificationPill(p14ViewTitle(it))};createdAt?.let {Text(recordDate(it),color=c.muted,fontSize=11.sp)};if(sourceRevision>1)Text("修订 v$sourceRevision",fontSize=10.sp,color=c.blue)}
                val remaining=body.substringAfter('\n',"");if(remaining.isNotBlank())me.rerere.ui.markdown.Markdown(remaining,style=TextStyle(color=c.ink,fontSize=15.sp,lineHeight=25.sp))
            }}
            if(images.isNotEmpty())item("images"){Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){images.take(4).forEach {a->AttachmentThumbnail(a.localLocator,67){onOpenAttachment(a)}}}}
            if(attachments.isNotEmpty())item("attachments"){Column {Text("附件 (${attachments.size})",fontSize=13.sp,fontWeight=FontWeight.Medium,color=c.ink,modifier=Modifier.padding(vertical=6.dp));attachments.forEach {a->DetailAttachment(a){onOpenAttachment(a)}}}}
            if(sourceMessageId==null&&onCommitEdit!=null)item("loading-source"){Text("正在恢复可编辑原始记录…",fontSize=12.sp,color=c.muted)}
            if(editConflict)item("conflict"){Text("检测到其他设备的修订。本机修改已保留 · 点此处理",color=Color(0xFFB45B3A),fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.clickable {conflictDialog=true}.padding(vertical=8.dp))}
            if(status!=null)item("status"){Text(status,color=c.blue,fontSize=11.sp,lineHeight=18.sp)}
        } else Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().testTag("p14-inbox-detail")) {
            Row(Modifier.padding(horizontal=15.dp,vertical=9.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(10.dp).testTag("p14-ai-source"),horizontalArrangement=Arrangement.spacedBy(9.dp),verticalAlignment=Alignment.CenterVertically) {
                images.firstOrNull()?.let {AttachmentThumbnail(it.localLocator,39)}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(p14InboxTitle(body,entryId),color=c.ink,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text("正在讨论这条记录",fontSize=10.sp,color=c.muted)}
            }
            val list=rememberLazyListState();var initialScroll by remember(entryId){mutableStateOf(true)}
            LaunchedEffect(visible.lastOrNull()?.messageId,visible.lastOrNull()?.bodyText?.length) {if(visible.isNotEmpty()&&(initialScroll||(list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= visible.size-3)){list.animateScrollToItem(visible.lastIndex);initialScroll=false}}
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(horizontal=15.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                if(visible.isEmpty())item {Text("围绕这条记录继续讨论。保存记录不会自动调用 AI。",fontSize=13.sp,color=c.muted,lineHeight=22.sp,modifier=Modifier.padding(vertical=25.dp))}
                items(visible,key={it.messageId}) {message->val user=message.role.equals("user",true);Row(Modifier.fillMaxWidth(),horizontalArrangement=if(user)Arrangement.End else Arrangement.Start,verticalAlignment=Alignment.Top) {
                    if(!user){Box(Modifier.size(25.dp).clip(CircleShape).background(c.blue),contentAlignment=Alignment.Center){Text("AI",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.SemiBold)};Spacer(Modifier.width(8.dp))}
                    Column(Modifier.fillMaxWidth(if(user).84f else .91f),verticalArrangement=Arrangement.spacedBy(4.dp),horizontalAlignment=if(user)Alignment.End else Alignment.Start) {
                        me.rerere.ui.markdown.Markdown(if(message.tombstone)"消息已删除" else message.bodyText,style=TextStyle(color=c.ink,fontSize=14.sp,lineHeight=23.sp),modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(user)c.user else c.surface.copy(alpha=.55f)).padding(12.dp))
                        Text(when(message.deliveryState){"STREAMING"->"正在回复…";"LOCAL_PENDING","EDIT_PENDING"->"正在发送…";else->SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(message.createdAtEpochMs))},color=c.muted,fontSize=9.sp)
                    }
                }}
                if(status==null&&visible.lastOrNull()?.let {it.role.equals("user",true)&&it.deliveryState=="SYNCED"}==true)item("awaiting-reply"){Text("已发送，等待 AI 回复…",color=c.muted,fontSize=12.sp)}
                if(status!=null)item("chat-status"){Row(verticalAlignment=Alignment.CenterVertically){Text(status,color=c.muted,fontSize=12.sp,modifier=Modifier.weight(1f));TextButton(onClick=onRetryChat){Text("重试")}}}
            }
        }
    }
    if(conflictDialog)AlertDialog(onDismissRequest={conflictDialog=false},title={Text("处理同步冲突")},text={Text("选择本机内容会基于云端版本产生新修订；采用云端会替换当前显示内容。原始 Capture 始终保留。")},confirmButton={TextButton(onClick={conflictDialog=false;onResolveConflict(true)}){Text("保留本机并重试")}},dismissButton={TextButton(onClick={conflictDialog=false;onResolveConflict(false)}){Text("采用云端")}})
    if(original)AlertDialog(onDismissRequest={original=false},title={Text("原始 Capture")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())){Text(raw,fontSize=14.sp,lineHeight=22.sp)}},confirmButton={TextButton(onClick={original=false}){Text("关闭")}})
    if(attachmentMenu)ModalBottomSheet(onDismissRequest={attachmentMenu=false},containerColor=c.surface){Column(Modifier.padding(20.dp).navigationBarsPadding()){Text("当前记录附件",fontSize=19.sp,color=c.ink);if(attachments.isEmpty())Text("这条记录没有附件",color=c.muted,modifier=Modifier.padding(vertical=20.dp));attachments.forEach {a->DetailAttachment(a){onOpenAttachment(a)}}}}
}
