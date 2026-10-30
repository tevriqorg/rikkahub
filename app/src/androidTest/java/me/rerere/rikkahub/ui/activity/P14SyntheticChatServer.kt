package me.rerere.rikkahub.ui.activity

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.concurrent.thread

/** Disposable emulator loopback only. Never forwards any request to an AI or real Server. */
internal class P14SyntheticChatServer(private val target:String):Closeable {
    private val server=ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"))
    val baseUrl="http://127.0.0.1:${server.localPort}/v1"
    private val events=mutableListOf<JsonObject>()
    private val accepted=mutableSetOf<String>()
    private var pending:Pair<Long,JsonObject>?=null
    var retries=0;private set
    var targetTurns=0;private set
    val targetMessageIds=mutableSetOf<String>()
    @Volatile private var open=true
    init {thread(isDaemon=true,name="p14-synthetic-http") {while(open)try {val s=server.accept();thread(isDaemon=true){s.use {handle(it)}}}catch(_:Exception){}}}
    @Synchronized private fun event(type:String,payload:JsonObject,operation:String?=null) {
        events.add(buildJsonObject {put("event_id",UUID.randomUUID().toString());put("sync_seq",events.size+1);put("event_type",type);put("entity_type","message");put("entity_id",UUID.randomUUID().toString());put("occurred_at",Instant.now().toString());operation?.let {put("caused_by_operation_id",it)};put("payload",payload)})
    }
    private fun message(cid:String,id:String,role:String,text:String)=buildJsonObject {put("message",buildJsonObject {put("message_id",id);put("conversation_id",cid);put("role",role);put("body_text",text);put("created_at",Instant.now().toString())})}
    @Synchronized private fun respond(method:String,url:String,payload:JsonObject):Pair<Int,String> {
        val path=url.substringBefore('?')
        if(path.endsWith("/sync/ws"))return 404 to "{}"
        if(path.endsWith("/remote-manifest"))return 200 to """{"schema_version":"p14.remote-manifest/v1","manifest_version":1,"minimum_client_protocol":1,"maximum_client_protocol":1,"compatibility":"COMPATIBLE","enabled_views":["chat","inbox","todo","idea"],"message_capabilities":["text","reply","edit"]}"""
        if(path.endsWith("/sync/events")) {
            pending?.takeIf {System.currentTimeMillis()>=it.first}?.let {event("conversation.message.created",it.second);pending=null}
            val after=url.substringAfter("after=","0").substringBefore('&').toLong()
            return 200 to buildJsonObject {put("events",JsonArray(events.filter {it["sync_seq"]!!.jsonPrimitive.long>after}));put("has_more",false);put("next_after",events.size);put("watermark",events.size)}.toString()
        }
        if(method=="POST") {
            if(path.endsWith("/messages")) {
                val cid=path.substringAfter("/conversations/").substringBefore('/')
                if(cid!=target)return 200 to "{}"
                val op=payload["client_operation_id"]!!.jsonPrimitive.content
                if(cid==target&&retries++==0)return 503 to "{}"
                if(accepted.add(op)) {
                    val mid=payload["message_id"]!!.jsonPrimitive.content
                    val text=payload["text"]!!.jsonPrimitive.content
                    if(cid==target){targetTurns++;targetMessageIds.add(mid)}
                    event("conversation.message.created",message(cid,mid,"user",text),op)
                    val draft=UUID.randomUUID().toString()
                    event("assistant.draft.updated",buildJsonObject {put("conversation_id",cid);put("draft_id",draft);put("body_text","正在组织合成回复…")})
                    val final=buildJsonObject {message(cid,UUID.randomUUID().toString(),"assistant","合成回复：$text").forEach {(k,v)->put(k,v)};put("finalizes_draft_id",draft)}
                    pending=(System.currentTimeMillis()+3_000) to final
                }
            }
            return 200 to "{}"
        }
        if(path.endsWith("/bootstrap/entries"))return 200 to """{"items":[],"watermark":0,"has_more":false}"""
        return 404 to "{}"
    }
    private fun handle(socket:Socket) {
        socket.soTimeout=10_000
        val input=socket.getInputStream().buffered()
        val header=java.io.ByteArrayOutputStream()
        while(true){val b=input.read();if(b<0)return;header.write(b);val bytes=header.toByteArray();if(bytes.size>=4&&bytes.takeLast(4)==listOf<Byte>(13,10,13,10))break;check(bytes.size<65_536)}
        val lines=header.toString(Charsets.US_ASCII.name()).split("\r\n")
        val first=lines.first().split(' ')
        val length=lines.firstOrNull {it.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt() ?: 0
        val bytesIn=input.readNBytes(length)
        val payload=if(length>0)Json.parseToJsonElement(bytesIn.toString(Charsets.UTF_8)).jsonObject else buildJsonObject{}
        val (code,body)=respond(first[0],first[1],payload)
        val bytes=body.toByteArray()
        socket.getOutputStream().apply {write("HTTP/1.1 $code synthetic\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
    }
    override fun close(){open=false;server.close()}
}
