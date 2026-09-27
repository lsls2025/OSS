package com.agit.app.vcs

import com.agit.app.data.model.DiffLine
import com.agit.app.data.model.DiffLineKind

/**
 * Myers O(ND) 行级差异算法（从纯文本行序列生成编辑脚本）。
 */
object DiffEngine {

    private enum class Op { EQUAL, INSERT, DELETE }

    private data class Edit(val kind: Op, val ia: Int, val ib: Int)

    fun diffLines(oldText: List<String>, newText: List<String>): List<DiffLine> {
        val a = oldText
        val b = newText

        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++

        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val midA = if (prefix <= a.size - suffix) a.subList(prefix, a.size - suffix) else emptyList()
        val midB = if (prefix <= b.size - suffix) b.subList(prefix, b.size - suffix) else emptyList()

        val edits = compute(midA, midB)
        val result = mutableListOf<DiffLine>()
        var oldNo = 1
        var newNo = 1

        for (i in 0 until prefix) result.add(DiffLine(DiffLineKind.CONTEXT, a[i], oldNo++, newNo++))
        for (e in edits) {
            when (e.kind) {
                Op.EQUAL -> result.add(DiffLine(DiffLineKind.CONTEXT, midA[e.ia], oldNo++, newNo++))
                Op.DELETE -> result.add(DiffLine(DiffLineKind.REMOVE, midA[e.ia], oldNo++, -1))
                Op.INSERT -> result.add(DiffLine(DiffLineKind.ADD, midB[e.ib], -1, newNo++))
            }
        }

        val so = a.size - suffix
        val sn = b.size - suffix
        oldNo = so + 1
        newNo = sn + 1
        for (i in so until a.size) result.add(DiffLine(DiffLineKind.CONTEXT, a[i], oldNo++, newNo++))
        return result
    }

    private fun compute(a: List<String>, b: List<String>): List<Edit> {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        if (n == 0) return (0 until m).map { Edit(Op.INSERT, -1, it) }
        if (m == 0) return (0 until n).map { Edit(Op.DELETE, it, -1) }
        if (n.toLong() * m > 40_000_000L) {
            val list = (0 until n).map { Edit(Op.DELETE, it, -1) }.toMutableList()
            list += (0 until m).map { Edit(Op.INSERT, -1, it) }
            return list
        }

        val offset = max
        val size = 2 * max + 1
        val v = IntArray(size)
        val trace = mutableListOf<IntArray>()

        for (d in 0..max) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]))
                    v[offset + k + 1]
                else
                    v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) return backtrack(trace, a, b, offset, n, m)
                k += 2
            }
        }

        val list = (0 until n).map { Edit(Op.DELETE, it, -1) }.toMutableList()
        list += (0 until m).map { Edit(Op.INSERT, -1, it) }
        return list
    }

    private fun backtrack(
        trace: List<IntArray>,
        a: List<String>,
        b: List<String>,
        offset: Int,
        n: Int,
        m: Int
    ): List<Edit> {
        val edits = mutableListOf<Edit>()
        var x = n
        var y = m
        for (d in trace.lastIndex downTo 0) {
            val v = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) k + 1 else k - 1
            val prevX = v[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
                edits.add(Edit(Op.EQUAL, x, y))
            }
            if (d > 0) {
                if (x == prevX) {
                    y--
                    edits.add(Edit(Op.INSERT, -1, y))
                } else {
                    x--
                    edits.add(Edit(Op.DELETE, x, -1))
                }
            }
            x = prevX
            y = prevY
        }
        edits.reverse()
        return edits
    }

    fun isBinary(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val check = minOf(data.size, 8000)
        var ctrl = 0
        for (i in 0 until check) {
            val byte = data[i]
            if (byte == 0.toByte()) return true
            if (byte < 9 || (byte > 13 && byte < 32)) ctrl++
        }
        return ctrl > check / 10
    }
}
