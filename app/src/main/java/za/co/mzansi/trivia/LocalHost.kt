package za.co.mzansi.trivia

import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LocalHost(private val engine: GameEngine) {
    private var room = (100000..999999).random().toString()
    private val sessions = ConcurrentHashMap<WebSocket,Int>()
    private val identities = ConcurrentHashMap<String,Int>()
    private val lastSeen = ConcurrentHashMap<Int,WebSocket>()
    private val address: String = try {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        val preferred = listOf("wlan0", "eth0")
        val ordered = interfaces.sortedBy { iface ->
            val index = preferred.indexOf(iface.name)
            if (index >= 0) index else preferred.size
        }
        ordered.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress ?: ""
    } catch (ex: Exception) {
        android.util.Log.e("MzansiTrivia", "Could not resolve TV network address", ex)
        ""
    }
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private val httpPort = freePort()
    private val wsPort = generateSequence { freePort() }.first { it != httpPort }
    val url: String get() = if(address.isBlank()) "" else "http://$address:$httpPort/?room=$room"
    private val wsStarted = CountDownLatch(1)
    fun newPlayers() {
        engine.newPlayers()
        identities.clear()
        sessions.clear()
        lastSeen.clear()
        room = (100000..999999).random().toString()
        ws.connections.toList().forEach { connection ->
            try { connection.send(JSONObject().put("type", "reset").toString()) } catch (_: Exception) {}
        }
    }
    private val http = object:NanoHTTPD(httpPort) {
        override fun serve(session:IHTTPSession):Response {
            if (session.uri == "/health") {
                val status = JSONObject()
                    .put("http", "ok")
                    .put("websocket", if (wsReady) "ready" else "starting_or_failed")
                    .put("ip", address)
                    .put("httpPort", httpPort)
                    .put("websocketPort", wsPort)
                    .toString()
                return newFixedLengthResponse(Response.Status.OK, "application/json", status)
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", controllerHtml)
        }
    }
    @Volatile private var wsReady = false
    private val ws = object:WebSocketServer(InetSocketAddress("0.0.0.0", wsPort)) {
        override fun onOpen(conn:WebSocket,handshake:ClientHandshake) {}
        override fun onClose(conn:WebSocket,code:Int,reason:String,remote:Boolean) {
            sessions.remove(conn)
        }
        override fun onMessage(conn:WebSocket,message:String) {
            try {
                val obj=JSONObject(message)
                when(obj.optString("type")) {
                    "join" -> {
                        if(obj.optString("room")!=room) { conn.send("""{"type":"error","message":"Invalid room code"}"""); return }
                        val identity=obj.optString("id").take(80)
                        if(identity.isBlank()) return
                        val player=identities[identity] ?: synchronized(identities) {
                            identities[identity] ?: (0..1).firstOrNull { it !in identities.values }?.also { identities[identity]=it }
                        }
                        if(player==null) { conn.send("""{"type":"error","message":"Room is full"}"""); return }
                        lastSeen[player]?.let { if(it!=conn) { sessions.remove(it); it.close() } }
                        sessions[conn]=player
                        lastSeen[player]=conn
                        engine.join(player, obj.optString("name"))
                        conn.send(JSONObject().put("type","joined").put("player",player+1).toString())
                        broadcast()
                    }
                    "start" -> {
                        if (sessions.containsKey(conn)) {
                            engine.start()
                            broadcast()
                        }
                    }
                    "new_game" -> {
                        if (sessions.containsKey(conn) && engine.state.value.finished) {
                            engine.newGame()
                            broadcast()
                        }
                    }
                    "new_players" -> {
                        if (sessions.containsKey(conn) && engine.state.value.finished) newPlayers()
                    }
                    "answer" -> {
                        val player=sessions[conn] ?: return
                        engine.answer(player,obj.optInt("option",-1))
                        broadcast()
                    }
                }
            } catch (_:Exception) { conn.send("""{"type":"error","message":"Invalid message"}""") }
        }
        override fun onError(conn:WebSocket?,ex:Exception) { android.util.Log.e("MzansiTrivia","WebSocket error on port $wsPort",ex); wsStarted.countDown() }
        override fun onStart() { wsReady = true; wsStarted.countDown(); android.util.Log.i("MzansiTrivia", "WebSocket listening on port $wsPort") }
    }
    @Volatile private var running=false
    fun start() {
        http.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)
        ws.start()
        wsStarted.await(5, TimeUnit.SECONDS)
        if (!wsReady) {
            http.stop()
            throw IllegalStateException("WebSocket could not start on port $wsPort; see MzansiTrivia logs")
        }
        running=true
        thread(name="trivia-state-broadcast",isDaemon=true) {
            var previous=""
            while(running) {
                val snapshot=stateJson()
                if(snapshot!=previous) { previous=snapshot; broadcast(snapshot) }
                Thread.sleep(150)
            }
        }
    }
    fun stop() { running=false; ws.stop(1000); http.stop() }
    private fun stateJson():String {
        val s=engine.state.value
        return JSONObject().put("type","state").put("index",s.index).put("started",s.started)
            .put("finished",s.finished).put("revealed",s.revealed)
            .put("names",org.json.JSONArray(s.names))
            .put("players",org.json.JSONArray(s.players))
            .put("round",s.round
            .put("health",org.json.JSONArray(s.health)).put("answered",org.json.JSONArray((0..1).map { s.answers.containsKey(it) }))
            .put("correct",if(s.revealed) engine.current.correct else JSONObject.NULL).toString()
    }
    private fun broadcast(snapshot:String=stateJson()) {
        sessions.keys.forEach { if(it.isOpen) try { it.send(snapshot) } catch (_:Exception) {} }
    }
    private val controllerHtml = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no"/>
<style>
*{box-sizing:border-box}body{margin:0;background:linear-gradient(145deg,#16134c,#5e2489);color:white;font-family:system-ui,sans-serif;text-align:center;min-height:100vh;padding:24px}
h1{color:#ffdb4a;font-size:2.2rem}button{border:0;border-radius:20px;padding:20px;font-weight:900;font-size:1.3rem;color:#201344;box-shadow:0 7px 0 #1c174c}
#actions{display:flex;gap:12px;justify-content:center;flex-wrap:wrap;margin:16px 0}#actions button{font-size:1rem;padding:15px;background:#ffdb4a}#options{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:30px}
#options button{height:130px;font-size:3rem}#options button:nth-child(1){background:#ffb703}#options button:nth-child(2){background:#19c6c0}#options button:nth-child(3){background:#fd6c86}#options button:nth-child(4){background:#9c79ff}
#options button:disabled{opacity:.35}input{padding:18px;border-radius:15px;font-size:1.3rem;width:220px;max-width:90%;text-align:center}
[hidden]{display:none!important}small{opacity:.7}#status{margin:24px 0;font-size:1.3rem}#join{background:#ffdb4a;margin:12px}
</style></head><body>
<h1>🎉 READY TO PLAY?</h1><p id="status">Connect to the TV game</p>
<div id="lobby"><input id="name" maxlength="18" placeholder="Your name" autocomplete="nickname"/><br/><br/><input id="code" inputmode="numeric" maxlength="6" placeholder="Room code"/><br/><button id="join" onclick="join()">JOIN GAME</button></div>
<div id="actions" hidden><button id="start" onclick="sendAction('start')">START GAME</button><button id="replay" onclick="sendAction('new_game')">NEW GAME</button><button id="newplayers" onclick="sendAction('new_players')">NEW PLAYERS</button></div>
<div id="options" hidden><button onclick="answer(0)">A</button><button onclick="answer(1)">B</button><button onclick="answer(2)">C</button><button onclick="answer(3)">D</button></div>
<p><small>Watch the TV for the question and answer choices.</small></p>
<script>
const params=new URLSearchParams(location.search);document.getElementById('code').value=params.get('room')||'';
let socket=null,player=0,answered=false,lastIndex=-1,lastRound=-1;
let identity=localStorage.getItem('mzansi-id');if(!identity){identity='phone-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);localStorage.setItem('mzansi-id',identity)}
function join(){
 const room=document.getElementById('code').value.trim();
 const name=document.getElementById('name').value.trim().slice(0,18);
 if(!name){document.getElementById('status').textContent='Enter your name first';return}
 if(!/^\d{6}$/.test(room)){document.getElementById('status').textContent='Enter a six-digit room code';return}
 document.getElementById('status').textContent='Connecting to TV…';
 if(socket)socket.close();
 socket=new WebSocket('ws://'+location.hostname+':$wsPort');
 socket.onopen=()=>{document.getElementById('status').textContent='Connected to TV. Joining room…';socket.send(JSON.stringify({type:'join',room,id:identity,name}));};
 socket.onerror=()=>document.getElementById('status').textContent='Cannot connect to TV WebSocket (port $wsPort). Check the TV game is open.';
 socket.onclose=()=>{if(!player)document.getElementById('status').textContent='Live connection closed (port $wsPort). Tap JOIN to retry.';else document.getElementById('status').textContent='Disconnected. Tap JOIN to reconnect.';};
 socket.onmessage=e=>{
  const data=JSON.parse(e.data);
  if(data.type==='reset'){
   player=0;answered=false;lastIndex=-1;lastRound=-1;
   document.getElementById('lobby').hidden=false;
   document.getElementById('actions').hidden=true;
   document.getElementById('options').hidden=true;
   document.getElementById('code').value='';
   document.getElementById('status').textContent='New players: scan the new TV QR code to join.';
   socket.close();return;
  }
  if(data.type==='error'){document.getElementById('status').textContent=data.message;return}
  if(data.type==='joined'){player=data.player;document.getElementById('lobby').hidden=true;document.getElementById('status').textContent='Welcome, '+document.getElementById('name').value.trim()+'!';return}
  if(data.type==='state'){
   if(data.index!==lastIndex||data.round!==lastRound){answered=false;lastIndex=data.index;lastRound=data.round}
   const waiting=!data.started||data.finished||data.revealed;
   const already=data.answered[player-1]||answered;
   document.getElementById('options').hidden=waiting;
   document.getElementById('actions').hidden=false;
   document.getElementById('start').hidden=data.started||data.finished||!data.players.every(Boolean);
   document.getElementById('replay').hidden=!data.finished;
   document.getElementById('newplayers').hidden=!data.finished;
   document.querySelectorAll('#options button').forEach(b=>b.disabled=already);
   document.getElementById('status').textContent=data.finished?'Game over!':!data.started?'Waiting for both players and TV host…':data.revealed?'Answer revealed!':already?'Answer locked in!':'Question '+(data.index+1)+'/10 — Choose an answer!';
  }
 }
}
function sendAction(type){if(socket&&socket.readyState===1)socket.send(JSON.stringify({type}));}
function answer(option){if(!socket||socket.readyState!==1||answered)return;answered=true;socket.send(JSON.stringify({type:'answer',option}));document.querySelectorAll('#options button').forEach(b=>b.disabled=true)}
// Let each player enter their name before joining.
</script></body></html>
""".trimIndent()
}
