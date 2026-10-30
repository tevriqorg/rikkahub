package me.rerere.rikkahub.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.remote.p14.*

/** Full-screen in-place title/body editor. Only Back/Done accepts a revision into the durable Outbox. */
@Composable internal fun P14EntryEditor(
    initial:P14EntryEditDraft,store:P14EntryEditDraftStore,status:String?,attachments:List<P14ProductAttachmentEntity>,onAttachment:(P14ProductAttachmentEntity)->Unit,
    availableViews:List<String>,selectedViews:Set<String>,onToggleView:(String)->Unit,onSave:(P14EntryEditDraft,(Boolean)->Unit)->Unit,onDiscard:()->Unit,currentRevision:Int=initial.baseRevision,currentBody:String?=null,
) {
    val c=p14Palette();val keyboard=LocalSoftwareKeyboardController.current
    var draft by remember(initial.entryId) {mutableStateOf(store.read(initial.entryId) ?: initial)}
    var title by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(draft.snapshot.title,TextRange(draft.snapshot.titleStart.coerceIn(0,draft.snapshot.title.length),draft.snapshot.titleEnd.coerceIn(0,draft.snapshot.title.length))))}
    var body by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(draft.snapshot.body,TextRange(draft.snapshot.bodyStart.coerceIn(0,draft.snapshot.body.length),draft.snapshot.bodyEnd.coerceIn(0,draft.snapshot.body.length))))}
    val titleFocus=remember {FocusRequester()};val bodyFocus=remember {FocusRequester()};val scroll=rememberScrollState(draft.scrollOffset)
    var submitting by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)};var menu by remember {mutableStateOf(false)};var viewMenu by remember {mutableStateOf(false)};var attachmentMenu by remember {mutableStateOf(false)};var formatMenu by remember {mutableStateOf(false)};var lastUndoAt by remember {mutableLongStateOf(0L)};var compare by remember {mutableStateOf(false)}
    fun persist(next:P14EntryEditDraft) {runCatching {store.save(next)}.onSuccess {draft=next;error=null}.onFailure {error="草稿写入失败，请暂勿关闭"}}
    fun snapshot(focus:String=draft.snapshot.focused)=P14EditorSnapshot(title.text,body.text,title.selection.start,title.selection.end,body.selection.start,body.selection.end,focus)
    fun changed(nextTitle:TextFieldValue=title,nextBody:TextFieldValue=body,focus:String=draft.snapshot.focused) {
        val changedText=nextTitle.text!=title.text||nextBody.text!=body.text
        val now=android.os.SystemClock.uptimeMillis()
        val undo=if(changedText&&(now-lastUndoAt>500||draft.undo.isEmpty()))(draft.undo+draft.snapshot).takeLast(30) else draft.undo
        if(changedText&&undo!=draft.undo)lastUndoAt=now
        title=nextTitle;body=nextBody
        persist(draft.copy(snapshot=snapshot(focus),undo=undo,redo=if(changedText)emptyList() else draft.redo))
    }
    fun restore(value:P14EditorSnapshot,undo:List<P14EditorSnapshot>,redo:List<P14EditorSnapshot>) {
        title=TextFieldValue(value.title,TextRange(value.titleStart.coerceIn(0,value.title.length),value.titleEnd.coerceIn(0,value.title.length)))
        body=TextFieldValue(value.body,TextRange(value.bodyStart.coerceIn(0,value.body.length),value.bodyEnd.coerceIn(0,value.body.length)))
        persist(draft.copy(snapshot=value,undo=undo,redo=redo))
    }
    fun insert(prefix:String,suffix:String="") {
        val value=if(draft.snapshot.focused=="title")title else body
        val start=minOf(value.selection.start,value.selection.end);val end=maxOf(value.selection.start,value.selection.end)
        val selected=value.text.substring(start,end)
        val content=value.text.replaceRange(start,end,prefix+selected+suffix)
        val next=TextFieldValue(content,TextRange(start+prefix.length,start+prefix.length+selected.length))
        lastUndoAt=0
        if(draft.snapshot.focused=="title")changed(nextTitle=next) else changed(nextBody=next)
        if(draft.snapshot.focused=="title")titleFocus.requestFocus() else bodyFocus.requestFocus()
        keyboard?.show()
    }
    fun finish() {
        if(submitting)return
        if(title.text==initial.snapshot.title&&body.text==initial.snapshot.body&&draft.baseRevision==initial.baseRevision&&draft.undo.isEmpty()){store.clear(initial.entryId);keyboard?.hide();onDiscard();return}
        if(draft.text.isBlank()){error="记录内容不能为空，草稿仍会保留";return}
        if(draft.baseRevision!=currentRevision&&draft.text!=currentBody){compare=true;return}
        submitting=true;store.save(draft.copy(snapshot=snapshot(),scrollOffset=scroll.value))
        onSave(draft.copy(snapshot=snapshot(),scrollOffset=scroll.value)){accepted->submitting=false;if(accepted)keyboard?.hide() else error="修改未提交，编辑草稿已保留"}
    }
    if(compare)AlertDialog(onDismissRequest={compare=false},title={Text("记录已有更新")},text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
        Text("本机草稿",fontWeight=FontWeight.SemiBold);Text(draft.text,fontSize=13.sp);Spacer(Modifier.height(16.dp))
        Text("当前修订 v$currentRevision",fontWeight=FontWeight.SemiBold);Text(currentBody.orEmpty(),fontSize=13.sp)
    }},confirmButton={TextButton(onClick={compare=false;persist(draft.copy(baseRevision=currentRevision));finish()}){Text("保留本机并产生新修订")}},dismissButton={TextButton(onClick={compare=false;store.clear(initial.entryId);keyboard?.hide();onDiscard()}){Text("采用当前修订")}})
    BackHandler {finish()}
    val orientation=LocalConfiguration.current.orientation
    val windowFocused=LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(orientation,windowFocused){if(windowFocused){withFrameNanos {};delay(350);if(draft.snapshot.focused=="title")titleFocus.requestFocus() else bodyFocus.requestFocus();delay(150);keyboard?.show()}}
    LaunchedEffect(scroll){snapshotFlow {scroll.value}.collect {offset->if(offset!=draft.scrollOffset)persist(draft.copy(scrollOffset=offset))}}
    Column(Modifier.fillMaxSize().background(c.background).imePadding().testTag("p14-entry-editor")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(61.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            P14IconButton(P14Symbol.BACK,"保存并返回详情",{finish()},Modifier.border(.7.dp,c.line,CircleShape).testTag("p14-editor-back"),enabled=!submitting)
            Text("收件箱",fontSize=15.sp,color=c.ink,modifier=Modifier.weight(1f));P14Glyph(P14Symbol.DOWN,tint=c.muted,modifier=Modifier.size(12.dp))
            P14IconButton(P14Symbol.FLAG,"优先级，待实现",{},Modifier.border(.7.dp,c.line,CircleShape),enabled=false,pending=true)
            Box {P14IconButton(P14Symbol.MORE_HORIZONTAL,"编辑更多操作",{menu=true},Modifier.border(.7.dp,c.line,CircleShape));DropdownMenu(menu,{menu=false},containerColor=c.surface) {
                DropdownMenuItem(text={Text("完成并保存")},onClick={menu=false;finish()},enabled=!submitting)
                DropdownMenuItem(text={Text("保留草稿并返回")},onClick={menu=false;store.save(draft);keyboard?.hide();onDiscard()})
                DropdownMenuItem(text={Text("原始 Capture 始终保留")},enabled=false,onClick={})
            }}
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal=20.dp,vertical=15.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                P14Glyph(P14Symbol.CALENDAR,tint=c.muted.copy(alpha=.5f),modifier=Modifier.size(17.dp));Text("日期&提醒 · 待实现",fontSize=12.sp,color=c.muted.copy(alpha=.7f));Spacer(Modifier.weight(1f));Text("修订 v${draft.baseRevision}",fontSize=10.sp,color=c.muted)
            }
            BasicTextField(value=title,onValueChange={changed(nextTitle=it,focus="title")},textStyle=TextStyle(fontSize=23.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,color=c.ink),cursorBrush=SolidColor(c.blue),modifier=Modifier.fillMaxWidth().heightIn(min=38.dp).focusRequester(titleFocus).onFocusChanged {if(it.isFocused&&draft.snapshot.focused!="title")persist(draft.copy(snapshot=snapshot("title")))}.testTag("p14-editor-title"),decorationBox={inner->Box {if(title.text.isBlank())Text("标题",fontSize=23.sp,color=c.muted);inner()}})
            Spacer(Modifier.height(15.dp))
            BasicTextField(value=body,onValueChange={changed(nextBody=it,focus="body")},textStyle=TextStyle(fontSize=16.sp,lineHeight=26.sp,color=c.ink),cursorBrush=SolidColor(c.blue),modifier=Modifier.fillMaxWidth().heightIn(min=140.dp).focusRequester(bodyFocus).onFocusChanged {if(it.isFocused&&draft.snapshot.focused!="body")persist(draft.copy(snapshot=snapshot("body")))}.testTag("p14-editor-body"),decorationBox={inner->Box {if(body.text.isBlank())Text("内容",fontSize=16.sp,color=c.muted.copy(alpha=.6f));inner()}})
            Spacer(Modifier.height(90.dp))
        }
        (error ?: status)?.let {Text(it,color=if(error!=null)androidx.compose.ui.graphics.Color(0xFFC4574B) else c.muted,fontSize=10.sp,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))}
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(Modifier.weight(1f).height(43.dp).border(.7.dp,c.line,CircleShape).padding(horizontal=4.dp).testTag("p14-editor-toolbar"),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                Box {P14IconButton(P14Symbol.TAG,"整理分类",{viewMenu=true});DropdownMenu(viewMenu,{viewMenu=false},containerColor=c.surface,properties=PopupProperties(focusable=false)) {
                    availableViews.forEach {view->DropdownMenuItem(text={Text((if(view in selectedViews)"✓ " else "")+p14ViewTitle(view))},onClick={onToggleView(view)})}
                    DropdownMenuItem(text={Text("自定义标签：待实现")},enabled=false,onClick={})
                }}
                P14IconButton(P14Symbol.LIST,"插入项目列表",{insert("\n- ")})
                Box {P14IconButton(P14Symbol.ATTACH,"查看已有附件",{attachmentMenu=true});DropdownMenu(attachmentMenu,{attachmentMenu=false},containerColor=c.surface,properties=PopupProperties(focusable=false)) {
                    attachments.forEach {a->DropdownMenuItem(text={Text(a.fileName)},onClick={attachmentMenu=false;onAttachment(a)})}
                    if(attachments.isEmpty())DropdownMenuItem(text={Text("这条记录暂无附件")},enabled=false,onClick={})
                    DropdownMenuItem(text={Text("新增附件请使用快速添加；现有记录追加待实现")},enabled=false,onClick={})
                }}
                Box {P14IconButton(P14Symbol.FORMAT,"文字格式",{formatMenu=true});DropdownMenu(formatMenu,{formatMenu=false},containerColor=c.surface,properties=PopupProperties(focusable=false)) {
                    DropdownMenuItem(text={Text("加粗（Markdown）")},onClick={formatMenu=false;insert("**","**")})
                    DropdownMenuItem(text={Text("引用")},onClick={formatMenu=false;insert("\n> ")})
                    DropdownMenuItem(text={Text("编号列表")},onClick={formatMenu=false;insert("\n1. ")})
                }}
                P14IconButton(P14Symbol.UNDO,"撤销",{val prev=draft.undo.last();restore(prev,draft.undo.dropLast(1),(draft.redo+draft.snapshot).takeLast(30))},enabled=draft.undo.isNotEmpty(),tint=c.muted)
                P14IconButton(P14Symbol.REDO,"重做",{val next=draft.redo.last();restore(next,(draft.undo+draft.snapshot).takeLast(30),draft.redo.dropLast(1))},enabled=draft.redo.isNotEmpty(),tint=c.muted)
            }
            P14IconButton(P14Symbol.KEYBOARD,"收起键盘",{keyboard?.hide()},Modifier.border(.7.dp,c.line,CircleShape))
        }
    }
}
