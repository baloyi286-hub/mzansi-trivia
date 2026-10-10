package za.co.mzansi.trivia

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Question(val prompt: String, val options: List<String>, val correct: Int, val category: String)
data class GameState(
    val index: Int = 0, val health: List<Int> = listOf(5, 5),
    val correctCounts: List<Int> = listOf(0, 0), val answers: Map<Int, Int> = emptyMap(),
    val players: List<Boolean> = listOf(false, false), val revealed: Boolean = false,
    val started: Boolean = false, val finished: Boolean = false
)
class GameEngine {
    val questions = listOf(
        Question("Which province is home to the Blyde River Canyon?", listOf("Limpopo","Mpumalanga","Free State","North West"),1,"Geography"),
        Question("Which city is known as the Mother City?",listOf("Durban","Cape Town","Pretoria","Gqeberha"),1,"Cities"),
        Question("Where is the Cradle of Humankind located?",listOf("Gauteng","Northern Cape","KwaZulu-Natal","Limpopo"),0,"Heritage"),
        Question("Which South African artist released the song Jerusalema?",listOf("Black Coffee","Master KG","Kabza De Small","DJ Maphorisa"),1,"Music"),
        Question("Which island held Nelson Mandela as a prisoner for many years?",listOf("Marion Island","Robben Island","Dassen Island","Seal Island"),1,"History"),
        Question("Which province is home to Kimberley?",listOf("Northern Cape","Eastern Cape","Free State","North West"),0,"Provinces"),
        Question("Who directed the film Titanic?",listOf("Christopher Nolan","James Cameron","Steven Spielberg","Peter Jackson"),1,"Movies"),
        Question("Which city hosts the Union Buildings?",listOf("Johannesburg","Pretoria","Bloemfontein","Polokwane"),1,"Landmarks"),
        Question("Which South African group performed the hit Homeless with Paul Simon?",listOf("Mafikizolo","Ladysmith Black Mambazo","Freshlyground","The Soil"),1,"Arts"),
        Question("Which South African city is famous for its Golden Mile beachfront?",listOf("Durban","East London","Cape Town","Mossel Bay"),0,"Cities"),
        Question("Which province contains the town of Stellenbosch?",listOf("Western Cape","Eastern Cape","Gauteng","Mpumalanga"),0,"Provinces"),
        Question("Which actor played T'Challa in Black Panther?",listOf("Michael B. Jordan","Chadwick Boseman","John Boyega","Idris Elba"),1,"Movies"),
        Question("Which South African city is home to the Apartheid Museum?",listOf("Pretoria","Johannesburg","Durban","Kimberley"),1,"History"),
        Question("What is the highest mountain range in South Africa?",listOf("Magaliesberg","Drakensberg","Waterberg","Cederberg"),1,"Geography"),
        Question("Who sang the song Rolling in the Deep?",listOf("Beyoncé","Adele","Rihanna","Sia"),1,"Music")
    )
    private val selected = questions.shuffled().take(10)
    private val mutable = MutableStateFlow(GameState())
    val state = mutable.asStateFlow()
    val current: Question get() = selected[mutable.value.index.coerceIn(0, selected.lastIndex)]
    @Synchronized fun join(player: Int) {
        if (player !in 0..1) return
        mutable.value = mutable.value.copy(players = mutable.value.players.toMutableList().apply { set(player,true) })
    }
    @Synchronized fun start() {
        if (mutable.value.players.all { it }) mutable.value = mutable.value.copy(started = true)
    }
    @Synchronized fun answer(player: Int, option: Int) {
        val s = mutable.value
        if (!s.started || s.finished || s.revealed || player !in 0..1 || option !in 0..3 || s.answers.containsKey(player)) return
        mutable.value = s.copy(answers = s.answers + (player to option))
        if (mutable.value.answers.size == 2) reveal()
    }
    @Synchronized fun reveal() {
        val s = mutable.value
        if (!s.started || s.revealed || s.finished) return
        val hp = s.health.toMutableList()
        val counts = s.correctCounts.toMutableList()
        for (player in 0..1) {
            if (s.answers[player] == current.correct) counts[player]++ else hp[player] = (hp[player] - 1).coerceAtLeast(0)
        }
        mutable.value = s.copy(health = hp, correctCounts = counts, revealed = true)
    }
    @Synchronized fun next() {
        val s = mutable.value
        if (!s.revealed) return
        val done = s.index >= 9 || s.health.any { it == 0 }
        mutable.value = s.copy(index = if (done) s.index else s.index+1, answers = emptyMap(), revealed = false, finished = done)
    }
}