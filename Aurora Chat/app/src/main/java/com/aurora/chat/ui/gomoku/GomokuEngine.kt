package com.aurora.chat.ui.gomoku

/**
 * 纯算法逻辑（照搬 C:\五子棋\js\ai.js 的启发式评估 AI 与胜负判定）。
 * 棋盘用一维 IntArray 表示：index = r * SIZE + c，0=空，1=黑，2=白。
 */
object GomokuEngine {
    const val SIZE = 15
    private val DIRS = listOf(-1 to 0, 0 to 1, -1 to 1, 1 to 1)

    fun idx(r: Int, c: Int) = r * SIZE + c

    fun emptyBoard(): IntArray = IntArray(SIZE * SIZE)

    fun inBounds(r: Int, c: Int) = r in 0 until SIZE && c in 0 until SIZE

    fun checkWin(b: IntArray, r: Int, c: Int, player: Int): Boolean {
        for ((dr, dc) in DIRS) {
            var count = 1
            var i = 1
            while (inBounds(r + dr * i, c + dc * i) && b[idx(r + dr * i, c + dc * i)] == player) { count++; i++ }
            i = 1
            while (inBounds(r - dr * i, c - dc * i) && b[idx(r - dr * i, c - dc * i)] == player) { count++; i++ }
            if (count >= 5) return true
        }
        return false
    }

    fun isFull(b: IntArray): Boolean = b.none { it == 0 }

    private fun patternScore(count: Int, openEnds: Int): Int {
        if (count >= 5) return 1000000
        if (openEnds == 0) return 0
        return when (count) {
            4 -> if (openEnds == 2) 100000 else 10000
            3 -> if (openEnds == 2) 5000 else 500
            2 -> if (openEnds == 2) 500 else 100
            1 -> if (openEnds == 2) 50 else 10
            else -> 0
        }
    }

    private fun scorePoint(b: IntArray, r: Int, c: Int, player: Int): Int {
        b[idx(r, c)] = player
        var total = 0
        for ((dr, dc) in DIRS) {
            var count = 1
            var openEnds = 0
            var i = 1
            while (true) {
                val nr = r + dr * i; val nc = c + dc * i
                if (inBounds(nr, nc) && b[idx(nr, nc)] == player) { count++; i++ }
                else { if (inBounds(nr, nc) && b[idx(nr, nc)] == 0) openEnds++; break }
            }
            i = 1
            while (true) {
                val nr = r - dr * i; val nc = c - dc * i
                if (inBounds(nr, nc) && b[idx(nr, nc)] == player) { count++; i++ }
                else { if (inBounds(nr, nc) && b[idx(nr, nc)] == 0) openEnds++; break }
            }
            total += patternScore(count, openEnds)
        }
        b[idx(r, c)] = 0
        return total
    }

    private fun opponentCanWin(b: IntArray, human: Int): Boolean {
        for ((r, c) in getCandidates(b)) {
            if (scorePoint(b, r, c, human) >= 1000000) return true
        }
        return false
    }

    private fun getCandidates(b: IntArray): List<Pair<Int, Int>> {
        val set = LinkedHashSet<Int>()
        var has = false
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                if (b[idx(r, c)] != 0) {
                    has = true
                    for (dr in -2..2) for (dc in -2..2) {
                        val nr = r + dr; val nc = c + dc
                        if (inBounds(nr, nc) && b[idx(nr, nc)] == 0) set.add(idx(nr, nc))
                    }
                }
            }
        }
        if (!has) return listOf(7 to 7)
        return set.map { (it / SIZE) to (it % SIZE) }
    }

    fun getBestMove(b: IntArray, aiPlayer: Int): Pair<Int, Int>? {
        val human = if (aiPlayer == 1) 2 else 1
        val candidates = getCandidates(b)
        if (candidates.size == 1) return candidates[0]

        var bestMove: Pair<Int, Int>? = null
        var bestScore = Int.MIN_VALUE
        for ((r, c) in candidates) {
            val atk = scorePoint(b, r, c, aiPlayer)
            val def = scorePoint(b, r, c, human)
            var score = atk + def
            score += (7 - kotlin.math.abs(r - 7)) + (7 - kotlin.math.abs(c - 7))
            if (atk >= 1000000) return r to c
            if (def >= 1000000) score += 2000000
            b[idx(r, c)] = aiPlayer
            if (opponentCanWin(b, human)) score -= 500000
            b[idx(r, c)] = 0
            if (score > bestScore) { bestScore = score; bestMove = r to c }
        }
        return bestMove
    }
}
