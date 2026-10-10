package za.co.mzansi.trivia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val Navy = Color(0xFF171443)
private val Yellow = Color(0xFFFFD747)
private val Blue = Color(0xFF32C6FF)
private val Pink = Color(0xFFFF5D9E)
private val choices = listOf(Color(0xFFFFB703),Color(0xFF19C6C0),Color(0xFFFD6C86),Color(0xFF9C79FF))

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
        setContent { GameScreen(engine,host.url) { speak(it) } }
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
private fun GameScreen(engine: GameEngine, url: String, speak: (String)->Unit) {
    val state by engine.state.collectAsState()
    var seconds by remember(state.index,state.started) { mutableIntStateOf(15) }
    LaunchedEffect(state.started,state.index,state.finished) {
        if (state.started && !state.finished) speak(engine.current.prompt + ". " + engine.current.options.mapIndexed { i,v -> "${'A'+i}: $v." }.joinToString(" "))
    }
    LaunchedEffect(state.started,state.index,state.revealed,state.finished) {
        if (state.started && !state.revealed && !state.finished) {
            seconds=15
            while (seconds > 0 && !engine.state.value.revealed) { delay(1000); seconds-- }
            if (!engine.state.value.revealed) engine.reveal()
        }
    }
    LaunchedEffect(state.revealed,state.index) {
        if (state.revealed && !state.finished) { delay(3300); engine.next() }
    }
    val infinite = rememberInfiniteTransition(label="background")
    val pulse by infinite.animateFloat(0.94f,1.06f,infiniteRepeatable(tween(900),RepeatMode.Reverse),label="pulse")
    Row(Modifier.fillMaxSize().background(Navy).padding(28.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        HealthColumn(0,state.health[0],Blue,Modifier.weight(0.12f))
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
                } else Text("Connect your TV to Wi-Fi",color=Pink)
                Spacer(Modifier.height(10.dp))
                Text("Player 1: ${if(state.players[0]) "CONNECTED" else "WAITING"}    •    Player 2: ${if(state.players[1]) "CONNECTED" else "WAITING"}",color=Yellow,fontSize=16.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick={engine.start()},enabled=state.players.all{it},colors=ButtonDefaults.buttonColors(containerColor=Pink)) { Text("START GAME",fontSize=20.sp) }
            } else if(state.finished) {
                val winner = when {
                    state.health[0]>state.health[1] -> "PLAYER 1 WINS!"
                    state.health[1]>state.health[0] -> "PLAYER 2 WINS!"
                    state.correctCounts[0]>state.correctCounts[1] -> "PLAYER 1 WINS!"
                    state.correctCounts[1]>state.correctCounts[0] -> "PLAYER 2 WINS!"
                    else -> "IT'S A DRAW!"
                }
                Text("🎉 $winner 🎉",color=Yellow,fontSize=36.sp,fontWeight=FontWeight.Black)
                Text("Correct answers: ${state.correctCounts[0]} — ${state.correctCounts[1]}",color=Color.White,fontSize=22.sp)
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("QUESTION ${state.index+1} / 10",color=Yellow,fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.width(30.dp))
                    Text(if(state.revealed) "ANSWER REVEAL!" else "⏱ $seconds",color=Pink,fontSize=25.sp,fontWeight=FontWeight.Black)
                }
                Spacer(Modifier.height(12.dp))
                AnimatedContent(targetState=state.index,label="question") { _ ->
                    Box(Modifier.fillMaxWidth().background(Color(0xFF31286F),RoundedCornerShape(24.dp)).padding(20.dp),contentAlignment=Alignment.Center) {
                        Text(engine.current.prompt,color=Color.White,fontWeight=FontWeight.ExtraBold,fontSize=27.sp,lineHeight=34.sp,textAlign=TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(18.dp))
                engine.current.options.chunked(2).forEachIndexed { row, items ->
                    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.fillMaxWidth()) {
                        items.forEachIndexed { col, answer ->
                            val i=row*2+col
                            val correct=state.revealed && engine.current.correct==i
                            val bg=if(correct) Color(0xFF4ADE80) else choices[i]
                            Box(Modifier.weight(1f).height(76.dp).background(bg,RoundedCornerShape(20.dp)).border(if(correct) 4.dp else 0.dp,Color.White,RoundedCornerShape(20.dp)).padding(12.dp),contentAlignment=Alignment.Center) {
                                Text("${'A'+i}. $answer",color=Color(0xFF19143D),fontSize=20.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if(state.revealed) Text("✨ ${engine.current.options[engine.current.correct]} ✨",color=Yellow,fontSize=(23*pulse).sp,fontWeight=FontWeight.Black)
                else Text("Answer on your phone!",color=Color.White,fontSize=18.sp)
            }
        }
        HealthColumn(1,state.health[1],Pink,Modifier.weight(0.12f))
    }
}
@Composable
private fun HealthColumn(player:Int,hp:Int,color:Color,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("P${player+1}",color=color,fontSize=25.sp,fontWeight=FontWeight.Black)
        Spacer(Modifier.height(16.dp))
        repeat(5) { i ->
            val fill by animateColorAsState(if(i<5-hp) Color(0xFF443B61) else color,animationSpec=tween(650),label="health")
            Box(Modifier.fillMaxWidth().height(58.dp).padding(vertical=4.dp).background(fill,RoundedCornerShape(12.dp)))
        }
        Spacer(Modifier.height(8.dp))
        Text("$hp HP",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
    }
}
private fun qrBitmap(value:String):Bitmap {
    val matrix:BitMatrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,300,300)
    return Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888).apply {
        for(y in 0 until 300) for(x in 0 until 300) setPixel(x,y,if(matrix[x,y]) AndroidColor.BLACK else AndroidColor.WHITE)
    }
}
