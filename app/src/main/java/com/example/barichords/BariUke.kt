package com.example.barichords

/**
 * Баритон-укулеле: стрій D3 G3 B3 E4 (4-а -> 1-а струна), як верхні 4 струни гітари.
 * Аплікатури генеруються автоматично для будь-якого акорда.
 */
object BariUke {
    /** Класи висот відкритих струн: D, G, B, E */
    val OPEN = intArrayOf(2, 7, 11, 4)
    val STRING_NAMES = arrayOf("D", "G", "B", "E")

    private val cache = HashMap<Chord, IntArray?>()

    /** Повертає лади для струн [D, G, B, E]; 0 = відкрита. */
    fun fingering(chord: Chord): IntArray? =
        if (cache.containsKey(chord)) cache[chord] else compute(chord).also { cache[chord] = it }

    private fun compute(chord: Chord): IntArray? {
        val tones = chord.type.intervals.map { (chord.root + it) % 12 }.toSet()
        for (maxFret in intArrayOf(4, 7, 12)) {
            var best: IntArray? = null
            var bestScore = Int.MAX_VALUE
            for (a in 0..maxFret) for (b in 0..maxFret) for (c in 0..maxFret) for (d in 0..maxFret) {
                val f = intArrayOf(a, b, c, d)
                val pcs = HashSet<Int>()
                var ok = true
                for (i in 0..3) {
                    val pc = (OPEN[i] + f[i]) % 12
                    if (pc !in tones) { ok = false; break }
                    pcs.add(pc)
                }
                if (!ok || pcs.size != tones.size) continue
                val fretted = f.filter { it > 0 }
                val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
                if (span > 3) continue
                val bassPenalty = if ((OPEN[0] + f[0]) % 12 == chord.root) 0 else 2
                val score = span * 3 + f.sum() + f.max() * 2 + bassPenalty
                if (score < bestScore) { bestScore = score; best = f }
            }
            if (best != null) return best
        }
        return null
    }
}
