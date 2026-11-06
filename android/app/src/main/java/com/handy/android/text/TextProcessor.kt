package com.handy.android.text

import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max

/**
 * Transcript post-processing, ported from Handy's `audio_toolkit/text.rs`:
 * custom-word correction (Levenshtein + Soundex, n-grams up to 3 words),
 * filler-word removal, and stutter / whitespace normalisation.
 */
object TextProcessor {

    // ---------------------------------------------------------------- custom words

    private class MatchKey(val wordIndex: Int, val key: String)

    private fun matchKey(word: String): String = buildString {
        for (c in word) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    private fun isSupportedFuzzyKey(key: String) =
        key.isNotEmpty() && key.all { it.code < 128 && it.isLetterOrDigit() }

    private fun supportsSoundex(key: String) =
        key.isNotEmpty() && key.all { it.code < 128 && it.isLetter() }

    private fun customWordKeys(word: String, index: Int): List<MatchKey> {
        val keys = ArrayList<MatchKey>(2)
        val primary = matchKey(word)
        if (isSupportedFuzzyKey(primary)) keys += MatchKey(index, primary)
        if ('&' in word) {
            val expanded = matchKey(word.replace("&", " and "))
            if (isSupportedFuzzyKey(expanded) && expanded != primary) keys += MatchKey(index, expanded)
        }
        return keys
    }

    private fun findBestMatch(
        candidate: String,
        customWords: List<String>,
        keys: List<MatchKey>,
        threshold: Double,
    ): Pair<String, Double>? {
        if (!isSupportedFuzzyKey(candidate) || candidate.length > 50) return null
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (k in keys) {
            val lenDiff = abs(candidate.length - k.key.length).toDouble()
            val maxLen = max(candidate.length, k.key.length).toDouble()
            if (lenDiff > max(maxLen * 0.25, 2.0)) continue
            val lev = if (maxLen > 0) levenshtein(candidate, k.key) / maxLen else 1.0
            val phonetic = supportsSoundex(candidate) && supportsSoundex(k.key) &&
                soundex(candidate) == soundex(k.key)
            val score = if (phonetic) lev * 0.3 else lev
            if (score < threshold && score < bestScore) {
                best = customWords[k.wordIndex]
                bestScore = score
            }
        }
        return best?.let { it to bestScore }
    }

    fun applyCustomWords(text: String, customWords: List<String>, threshold: Double): String {
        if (customWords.isEmpty()) return text
        val keys = customWords.flatMapIndexed { i, w -> customWordKeys(w, i) }
        if (keys.isEmpty()) return text
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var best: Triple<Int, String, Double>? = null
            for (n in 3 downTo 1) {
                if (i + n > words.size) continue
                val ngram = words.subList(i, i + n)
                // Never consume across punctuation: "Charge B, che" stops at "B,".
                if (ngram.dropLast(1).any { extractPunctuation(it).second.isNotEmpty() }) continue
                val key = ngram.joinToString("") { matchKey(it) }
                val m = findBestMatch(key, customWords, keys, threshold) ?: continue
                if (best == null || m.second < best.third) best = Triple(n, m.first, m.second)
            }
            if (best != null) {
                val (n, replacement, _) = best
                val prefix = extractPunctuation(words[i]).first
                val suffix = extractPunctuation(words[i + n - 1]).second
                out += prefix + preserveCase(words[i], replacement) + suffix
                i += n
            } else {
                out += words[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    private fun preserveCase(original: String, replacement: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } && letters.length > 1 -> replacement.uppercase()
            original.firstOrNull { it.isLetterOrDigit() }?.isUpperCase() == true ->
                replacement.replaceFirstChar { it.uppercaseChar() }
            else -> replacement
        }
    }

    internal fun extractPunctuation(word: String): Pair<String, String> {
        val start = word.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) return word to ""
        val end = word.indexOfLast { it.isLetterOrDigit() } + 1
        return word.substring(0, start) to word.substring(end)
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** American Soundex (letters only, 4 chars). */
    internal fun soundex(s: String): String {
        val word = s.uppercase().filter { it in 'A'..'Z' }
        if (word.isEmpty()) return ""
        fun code(c: Char) = when (c) {
            'B', 'F', 'P', 'V' -> '1'
            'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
            'D', 'T' -> '3'
            'L' -> '4'
            'M', 'N' -> '5'
            'R' -> '6'
            else -> '0' // vowels + H, W, Y
        }
        val sb = StringBuilder().append(word[0])
        var last = code(word[0])
        for (idx in 1 until word.length) {
            val c = word[idx]
            val d = code(c)
            if (d != '0' && d != last) sb.append(d)
            // H and W do not separate letters with the same code; vowels do.
            if (c != 'H' && c != 'W') last = d
            if (sb.length == 4) break
        }
        while (sb.length < 4) sb.append('0')
        return sb.toString()
    }

    // ---------------------------------------------------------------- fillers

    private val UNIVERSAL_FILLERS = listOf(
        "uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм",
    )

    private fun languageFillers(lang: String?): List<String> =
        when (lang?.substringBefore('-')?.substringBefore('_')) {
            "en" -> listOf("um", "ah", "eh")
            "de" -> listOf("äh", "ähm")
            "fr" -> listOf("euh")
            else -> emptyList()
        }

    private val patternCache = HashMap<String, Pattern>()

    private fun fillerPattern(word: String): Pattern = synchronized(patternCache) {
        patternCache.getOrPut(word) {
            Pattern.compile(
                "\\b" + Pattern.quote(word) + "\\b[,.]?",
                // No UNICODE_CHARACTER_CLASS: Android (ICU) rejects it, and ICU word boundaries are Unicode-aware already.
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
        }
    }

    private fun opensSentence(kept: CharSequence): Boolean {
        val t = kept.trimEnd()
        return t.isEmpty() || t.last() in ".!?…"
    }

    private fun appendRestoringCapital(kept: StringBuilder, segment: String, owed: BooleanArray) {
        if (owed[0]) {
            val idx = segment.indexOfFirst { it.isLetterOrDigit() }
            if (idx >= 0) {
                owed[0] = false
                kept.append(segment, 0, idx).append(segment[idx].uppercaseChar()).append(segment, idx + 1, segment.length)
                return
            }
        }
        kept.append(segment)
    }

    private fun removeMatches(text: String, pattern: Pattern): String {
        val m: Matcher = pattern.matcher(text)
        val kept = StringBuilder(text.length)
        var resume = 0
        val owed = booleanArrayOf(false)
        while (m.find()) {
            appendRestoringCapital(kept, text.substring(resume, m.start()), owed)
            val capitalized = m.group().firstOrNull()?.isUpperCase() == true
            if (capitalized && opensSentence(kept)) owed[0] = true
            resume = m.end()
        }
        appendRestoringCapital(kept, text.substring(resume), owed)
        return kept.toString()
    }

    fun removeFillerWords(text: String, language: String?, customFillers: List<String>? = null): String {
        val words = customFillers ?: (UNIVERSAL_FILLERS + languageFillers(language))
        var out = text
        for (w in words) if (w.isNotBlank()) out = removeMatches(out, fillerPattern(w))
        return out
    }

    // ---------------------------------------------------------------- normalise

    private val WHITESPACE = Regex("\\s+")
    private val MULTI_SPACE = Regex("\\s{2,}")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([,.!?;:])")

    /** Collapses 3+ repeats of the same word ("the the the" -> "the"). */
    internal fun collapseStutters(text: String): String {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val lower = w.lowercase()
            if (lower.all { it.isLetter() }) {
                var count = 1
                while (i + count < words.size && words[i + count].lowercase() == lower) count++
                out += w
                i += if (count >= 3) count else 1
            } else {
                out += w
                i++
            }
        }
        return out.joinToString(" ")
    }

    fun normalize(text: String): String {
        var s = collapseStutters(text)
        s = MULTI_SPACE.replace(s, " ")
        s = SPACE_BEFORE_PUNCT.replace(s, "$1")
        return s.trim()
    }

    /** Full pipeline applied to every transcript. */
    fun process(
        raw: String,
        language: String?,
        removeFillers: Boolean,
        customWords: List<String>,
        threshold: Double,
    ): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (customWords.isNotEmpty()) s = applyCustomWords(s, customWords, threshold)
        if (removeFillers) s = removeFillerWords(s, language)
        s = normalize(s)
        // A filler at the very start can leave a dangling comma: ", hello".
        s = s.trimStart(',', ' ')
        return s
    }

    /** Joins chunk transcripts, avoiding double spaces and spaces before punctuation. */
    fun joinSegments(parts: List<String>): String =
        normalize(parts.filter { it.isNotBlank() }.joinToString(" ") { it.trim() })
}
