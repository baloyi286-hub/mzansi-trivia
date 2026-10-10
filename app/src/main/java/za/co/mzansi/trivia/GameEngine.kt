package za.co.mzansi.trivia

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Question(val prompt: String, val options: List<String>, val correct: Int, val category: String)
data class GameState(
    val index: Int = 0, val health: List<Int> = listOf(5, 5),
    val correctCounts: List<Int> = listOf(0, 0), val answers: Map<Int, Int> = emptyMap(),
    val players: List<Boolean> = listOf(false, false),
    val names: List<String> = listOf("Player 1", "Player 2"), val revealed: Boolean = false,
    val started: Boolean = false, val finished: Boolean = false,
    val round: Int = 0
)
class GameEngine {
    val questions = QuestionBank.all
    private val recentlyUsed = mutableSetOf<String>()
    private fun selectQuestions(): List<Question> {
        if (questions.size - recentlyUsed.size < 10) recentlyUsed.clear()
        val available = questions.filterNot { it.prompt in recentlyUsed }
        val groups = available.groupBy { it.category }.values.map { it.shuffled().toMutableList() }.shuffled()
        val picked = mutableListOf<Question>()
        while (picked.size < 10 && groups.any { it.isNotEmpty() }) {
            for (group in groups) {
                if (picked.size >= 10) break
                if (group.isNotEmpty()) picked += group.removeAt(0)
            }
        }
        recentlyUsed.addAll(picked.map { it.prompt })
        return picked.shuffled().map { q ->
            val correctAnswer = q.options[q.correct]
            val shuffled = q.options.shuffled()
            q.copy(options = shuffled, correct = shuffled.indexOf(correctAnswer))
        }
    }
    private var selected = selectQuestions()
    private val mutable = MutableStateFlow(GameState())
    val state = mutable.asStateFlow()
    val questionCount: Int get() = selected.size
    val current: Question get() = selected[mutable.value.index.coerceIn(0, selected.lastIndex)]
    @Synchronized fun join(player: Int, name: String) {
        if (player !in 0..1) return
        val cleanName = name.trim().take(18).ifBlank { "Player ${player + 1}" }
        mutable.value = mutable.value.copy(
            players = mutable.value.players.toMutableList().apply { set(player, true) },
            names = mutable.value.names.toMutableList().apply { set(player, cleanName) }
        )
    }
    @Synchronized fun newGame() {
        val previous = mutable.value
        selected = selectQuestions()
        mutable.value = GameState(
            players = previous.players,
            names = previous.names,
            round = previous.round + 1
        )
    }
    @Synchronized fun newPlayers() {
        selected = selectQuestions()
        mutable.value = GameState(round = mutable.value.round + 1)
    }
    @Synchronized fun start() {
        if (!mutable.value.started && !mutable.value.finished && mutable.value.players.all { it }) mutable.value = mutable.value.copy(started = true)
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
        val done = s.index >= selected.lastIndex
        mutable.value = s.copy(index = if (done) s.index else s.index+1, answers = emptyMap(), revealed = false, finished = done)
    }
}