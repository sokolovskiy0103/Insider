package com.sokolovskiy.insider

import org.json.JSONArray
import kotlin.random.Random

/** Secret words for the Master. Hands out each word once per game, then reshuffles. */
class WordBank(words: List<String>) {
    private val all = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val used = HashSet<String>()

    init {
        require(all.isNotEmpty()) { "word list is empty" }
    }

    val size get() = all.size

    fun next(random: Random): String {
        if (used.size >= all.size) used.clear()
        val w = all.filter { it !in used }.random(random)
        used += w
        return w
    }

    fun reset() = used.clear()

    companion object {
        fun fromJson(text: String): WordBank {
            val arr = JSONArray(text)
            return WordBank((0 until arr.length()).map { arr.getString(it) })
        }
    }
}
