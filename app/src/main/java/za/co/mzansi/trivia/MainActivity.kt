package za.co.mzansi.trivia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.delay
import java.util.Locale

private val Navy = Color(0xFF180B43)
private val Yellow = Color(0xFFFFE268)
private val Blue = Color(0xFF4FE9FF)
private val Pink = Color(0xFFFF62C2)
private val choices = listOf(Color(0xFFFFB92E),Color(0xFF36E5D3),Color(0xFFFF76B2),Color(0xFFAC83FF))
private val choiceEnds = listOf(Color(0xFFFF793F),Color(0xFF12A9CD),Color(0xFFDB4E91),Color(0xFF6B53DB))
private val arcadeBackground = Brush.linearGradient(listOf(Color(0xFF2B0B60),Color(0xFF171044),Color(0xFF101A49)))
private fun candyGradient(start: Color, end: Color): Brush = Brush.verticalGradient(listOf(start.copy(alpha=0.98f),start,end))

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val engine = GameEngine()
    private lateinit var host: LocalHost
    private var speech: TextToSpeech? = null
    private var speechReady = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = TextToSpeech(this,this)
        host = LocalHost(engine)
        host.start()
        setContent { GameScreen(engine,host) { speak(it) } }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = speech?.setLanguage(Locale.forLanguageTag("en-ZA"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) speech?.language = Locale.ENGLISH
            speechReady = true
        }
    }
    private fun speak(text: String) {
        if (speechReady) speech?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"question")
    }
    override fun onDestroy() { host.stop(); speech?.stop(); speech?.shutdown(); super.onDestroy() }
}

@Composable
private fun GameScreen(engine: GameEngine, host: LocalHost, speak: (String)->Unit) {
    val url = host.url
    val state by engine.state.collectAsState()
    var seconds by remember(state.round,state.index,state.started) { mutableIntStateOf(15) }
    LaunchedEffect(state.round,state.started,state.index,state.finished) {
        if (state.started && !state.finished) speak(engine.current.prompt + ". " + engine.current.options.mapIndexed { i,v -> "${'A'+i}: $v." }.joinToString(" "))
    }
    LaunchedEffect(state.round,state.started,state.index,state.revealed,state.finished) {
        if (state.started && !state.revealed && !state.finished) {
            seconds=15
            while (seconds > 0 && !engine.state.value.revealed) { delay(1000); seconds-- }
            if (!engine.state.value.revealed) engine.reveal()
        }
    }
    LaunchedEffect(state.round,state.revealed,state.index) {
        if (state.revealed && !state.finished) { delay(3300); engine.next() }
    }
    val infinite = rememberInfiniteTransition(label="background")
    val pulse by infinite.animateFloat(0.94f,1.06f,infiniteRepeatable(tween(900),RepeatMode.Reverse),label="pulse")
    Row(Modifier.fillMaxSize().background(arcadeBackground).padding(28.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        HealthColumn(state.names[0],state.health[0],Blue,Modifier.weight(0.12f))
        Column(Modifier.weight(0.76f).fillMaxHeight(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            if (!state.started) {
                Text("READY TO PLAY?",fontSize=36.sp,fontWeight=FontWeight.Black,color=Yellow)
                Spacer(Modifier.height(12.dp))
                Text("Scan the QR code on both phones",color=Color.White,fontSize=22.sp)
                Spacer(Modifier.height(14.dp))
                if (url.isNotBlank()) {
                    val bitmap = remember(url) { qrBitmap(url) }
                    Image(bitmap.asImageBitmap(),null,Modifier.size(170.dp))
                    Text(url,color=Color.White,fontSize=15.sp)
                    Text("ROOM CODE: "+url.substringAfter("room="),color=Yellow,fontSize=22.sp,fontWeight=FontWeight.Black)
                } else Text("Connect your TV to Wi-Fi",color=Pink)
                Spacer(Modifier.height(10.dp))
                Text("${state.names[0]}: ${if(state.players[0]) "CONNECTED" else "WAITING"}    •    ${state.names[1]}: ${if(state.players[1]) "CONNECTED" else "WAITING"}",color=Yellow,fontSize=16.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick={engine.start()},enabled=state.players.all{it},modifier=Modifier.shadow(12.dp,RoundedCornerShape(24.dp)),shape=RoundedCornerShape(24.dp),colors=ButtonDefaults.buttonColors(containerColor=Pink)) { Text("✦ START GAME ✦",fontSize=20.sp,fontWeight=FontWeight.Black) }
            } else if(state.finished) {
                val winnerIndex = when {
                    state.health[0] > state.health[1] -> 0
                    state.health[1] > state.health[0] -> 1
                    state.correctCounts[0] > state.correctCounts[1] -> 0
                    state.correctCounts[1] > state.correctCounts[0] -> 1
                    else -> -1
                }
                val winner = if (winnerIndex < 0) "IT'S A DRAW!" else "${state.names[winnerIndex]} WINS!"
                val celebration by infinite.animateFloat(0.96f,1.05f,infiniteRepeatable(tween(700),RepeatMode.Reverse),label="celebration")
                Text("🎉 $winner 🎉",modifier=Modifier.graphicsLayer(scaleX=celebration,scaleY=celebration),color=Yellow,fontSize=36.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text("Correct answers: ${state.names[0]} ${state.correctCounts[0]} — ${state.names[1]} ${state.correctCounts[1]}",color=Color.White,fontSize=20.sp,textAlign=TextAlign.Center)
                if (winnerIndex >= 0) {
                    val loserIndex = 1 - winnerIndex
                    val shots = 5 - state.health[loserIndex]
                    Spacer(Modifier.height(20.dp))
                    Text("${state.names[loserIndex]} takes $shots ${if(shots == 1) "shot" else "shots"}! 🥃",color=Pink,fontSize=27.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Button(onClick={engine.newGame()},modifier=Modifier.shadow(12.dp,RoundedCornerShape(24.dp)),shape=RoundedCornerShape(24.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue)) { Text("↻ NEW GAME",color=Navy,fontWeight=FontWeight.Black) }
                    Button(onClick={host.newPlayers()},modifier=Modifier.shadow(12.dp,RoundedCornerShape(24.dp)),shape=RoundedCornerShape(24.dp),colors=ButtonDefaults.buttonColors(containerColor=Pink)) { Text("✦ NEW PLAYERS",color=Navy,fontWeight=FontWeight.Black) }
                }
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("QUESTION ${state.index+1} / ${engine.questionCount}",color=Yellow,fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.width(30.dp))
                    val timerColor by animateColorAsState(
                        if (state.revealed) Color(0xFF4ADE80) else if (seconds <= 5) Color.Red else Pink,
                        animationSpec = tween(250), label = "timerColor"
                    )
                    val timerScale by animateFloatAsState(
                        if (!state.revealed && seconds <= 5 && seconds % 2 == 0) 1.15f else 1f,
                        animationSpec = tween(180), label = "timerScale"
                    )
                    Text(if(state.revealed) "✓ ANSWER REVEAL!" else "⏱ $seconds",
                        modifier=Modifier.graphicsLayer(scaleX=timerScale,scaleY=timerScale),
                        color=timerColor,fontSize=25.sp,fontWeight=FontWeight.Black)
                }
                Spacer(Modifier.height(12.dp))
                AnimatedContent(
                    targetState=state.round to state.index,
                    transitionSpec = {
                        (fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 3 })
                            .togetherWith(fadeOut(tween(200)))
                    },
                    label="question"
                ) { _ ->
                    Box(Modifier.fillMaxWidth().shadow(15.dp,RoundedCornerShape(24.dp)).background(candyGradient(Color(0xFF8540D8),Color(0xFF472C9D)),RoundedCornerShape(24.dp)).border(2.dp,Color(0xFFB99BFF).copy(alpha=0.6f),RoundedCornerShape(24.dp)).padding(20.dp),contentAlignment=Alignment.Center) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(engine.current.category.uppercase(),color=Yellow,fontSize=14.sp,fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(engine.current.prompt,color=Color.White,fontWeight=FontWeight.ExtraBold,fontSize=27.sp,lineHeight=34.sp,textAlign=TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                engine.current.options.chunked(2).forEachIndexed { row, items ->
                    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.fillMaxWidth()) {
                        items.forEachIndexed { col, answer ->
                            val i=row*2+col
                            val correct=state.revealed && engine.current.correct==i
                            val bg by animateColorAsState(
                                if(correct) Color(0xFF4ADE80) else if(state.revealed) choices[i].copy(alpha=0.42f) else choices[i],
                                animationSpec=tween(420),label="optionColor"
                            )
                            val optionScale by animateFloatAsState(if(correct) 1.045f else 1f,animationSpec=spring(dampingRatio=Spring.DampingRatioMediumBouncy),label="correctScale")
                            Box(Modifier.weight(1f).height(76.dp).graphicsLayer(scaleX=optionScale,scaleY=optionScale).shadow(if(correct) 18.dp else 9.dp,RoundedCornerShape(20.dp)).background(candyGradient(if(correct) Color(0xFF93FFD0) else bg,if(correct) Color(0xFF15B980) else choiceEnds[i].copy(alpha=if(state.revealed) 0.42f else 1f)),RoundedCornerShape(20.dp)).border(if(correct) 4.dp else 2.dp,Color.White.copy(alpha=if(correct) 1f else 0.52f),RoundedCornerShape(20.dp)).padding(12.dp),contentAlignment=Alignment.Center) {
                                Text("${'A'+i}. $answer",color=Color(0xFF19143D),fontSize=20.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if(state.revealed) {
                    val results = (0..1).joinToString("    •    ") { player ->
                        val answer = state.answers[player]
                        val result = if (answer == null) "TIME UP" else if (answer == engine.current.correct) "CORRECT ✓" else "WRONG ✕"
                        "${state.names[player]}: $result"
                    }
                    Text("✨ ${engine.current.options[engine.current.correct]} ✨",color=Yellow,fontSize=(23*pulse).sp,fontWeight=FontWeight.Black)
                    Text(results,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
                }
                else Text("Answer on your phone!",color=Color.White,fontSize=18.sp)
            }
        }
        HealthColumn(state.names[1],state.health[1],Pink,Modifier.weight(0.12f))
    }
}
@Composable
private fun HealthColumn(name:String,hp:Int,color:Color,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(name,color=color,fontSize=19.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center,maxLines=2)
        Spacer(Modifier.height(16.dp))
        repeat(5) { i ->
            val active = i >= 5-hp
            val fill by animateColorAsState(if(active) color else Color(0xFF443B61),animationSpec=tween(650),label="health")
            val healthScale by animateFloatAsState(if(active) 1f else 0.87f,animationSpec=spring(dampingRatio=Spring.DampingRatioMediumBouncy),label="healthScale")
            Box(Modifier.fillMaxWidth().height(58.dp).padding(vertical=4.dp).graphicsLayer(scaleX=healthScale,scaleY=healthScale,alpha=if(active) 1f else 0.5f).shadow(if(active) 8.dp else 0.dp,RoundedCornerShape(14.dp)).background(candyGradient(if(active) Color.White.copy(alpha=0.65f) else fill,fill),RoundedCornerShape(14.dp)).border(1.dp,Color.White.copy(alpha=if(active) 0.55f else 0.12f),RoundedCornerShape(14.dp)))
        }
    }
}
private fun qrBitmap(value:String):Bitmap {
    val matrix:BitMatrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,300,300)
    return Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888).apply {
        for(y in 0 until 300) for(x in 0 until 300) setPixel(x,y,if(matrix[x,y]) AndroidColor.BLACK else AndroidColor.WHITE)
    }
}
