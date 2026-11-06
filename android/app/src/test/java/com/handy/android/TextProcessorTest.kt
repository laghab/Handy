package com.handy.android

import com.handy.android.audio.StreamingResampler
import com.handy.android.engine.DictationSession
import com.handy.android.model.ModelCatalog
import com.handy.android.model.ModelSource
import com.handy.android.text.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Cases ported from Handy's `audio_toolkit/text.rs` test-suite. */
class TextProcessorTest {
    private fun filter(text: String, lang: String?, custom: List<String>? = null) =
        TextProcessor.normalize(TextProcessor.removeFillerWords(text, lang, custom))

    @Test fun customWordsExact() = assertEquals("Hello World", TextProcessor.applyCustomWords("hello world", listOf("Hello", "World"), 0.5))
    @Test fun customWordsFuzzy() = assertEquals("hello world", TextProcessor.applyCustomWords("helo wrold", listOf("hello", "world"), 0.5))
    @Test fun customWordsEmpty() = assertEquals("hello world", TextProcessor.applyCustomWords("hello world", emptyList(), 0.5))
    @Test fun customWordsNgram() =
        assertEquals("I use ChargeBee daily", TextProcessor.applyCustomWords("I use Charge B daily", listOf("ChargeBee"), 0.18))
    @Test fun customWordsKeepPunctuation() =
        assertEquals("Deploy to Kubernetes.", TextProcessor.applyCustomWords("Deploy to kubernetis.", listOf("Kubernetes"), 0.18))

    @Test fun extractPunctuation() {
        assertEquals("" to "", TextProcessor.extractPunctuation("hello"))
        assertEquals("!" to "?", TextProcessor.extractPunctuation("!hello?"))
        assertEquals("..." to "...", TextProcessor.extractPunctuation("...hello..."))
        assertEquals("" to "。", TextProcessor.extractPunctuation("你好。"))
        assertEquals("「" to "」", TextProcessor.extractPunctuation("「你好」"))
    }

    @Test fun soundex() {
        assertEquals("R163", TextProcessor.soundex("Robert"))
        assertEquals("R163", TextProcessor.soundex("Rupert"))
        assertEquals("A261", TextProcessor.soundex("Ashcraft"))
        assertEquals("T522", TextProcessor.soundex("Tymczak"))
        assertEquals("P236", TextProcessor.soundex("Pfister"))
    }

    @Test fun levenshtein() {
        assertEquals(3, TextProcessor.levenshtein("kitten", "sitting"))
        assertEquals(0, TextProcessor.levenshtein("same", "same"))
    }

    @Test fun fillers() = assertEquals("So I was thinking about this", filter("So uhm I was thinking uh about this", "en"))
    @Test fun fillersCaseInsensitive() = assertEquals("This is a test", filter("UHM this is UH a test", "en"))
    @Test fun fillersPunctuation() = assertEquals("Well, I think, that's right", filter("Well, uhm, I think, uh. that's right", "en"))
    @Test fun whitespace() = assertEquals("Hello world test", filter("Hello    world   test", "en"))
    @Test fun trims() = assertEquals("Hello world", filter("  Hello world  ", "en"))
    @Test fun combined() = assertEquals("So I was, thinking about this", filter("  Uhm, so I was, uh, thinking about this  ", "en"))
    @Test fun leadingFillerKeepsCapital() {
        assertEquals("So I think we should ship it.", filter("Um, so I think we should ship it.", "en"))
        assertEquals("That works. Let me check.", filter("That works. Um, let me check.", "en"))
        assertEquals("He said, not today.", filter("He said, Um, not today.", "en"))
    }
    @Test fun preservesValid() = assertEquals("This is a completely normal sentence.", filter("This is a completely normal sentence.", "en"))
    @Test fun stutters() {
        assertEquals("w wh why", filter("w wh wh wh wh wh wh wh wh wh why", "en"))
        assertEquals("I think so", filter("I I I I think so so so so", "en"))
        assertEquals("Check data doc documentation.", filter("Check data doc doc doc doc documentation.", "en"))
        assertEquals("No", filter("No NO no NO no", "en"))
        assertEquals("no no is fine", filter("no no is fine", "en"))
    }
    @Test fun languageGating() {
        assertEquals("I think this is good", filter("um I think um this is good", "en"))
        assertEquals("um gato bonito", filter("um gato bonito", "pt"))
        assertEquals("um gato bonito", filter("um gato bonito", "pt-BR"))
        assertEquals("ha sido un buen día", filter("ha sido un buen día", "es"))
        assertEquals("I think this works", filter("uh I think uhm this works", "xx"))
        assertEquals("um I think this works", filter("um I think this works", "xx"))
    }
    @Test fun customFillers() {
        assertEquals("so I think this works", filter("okay so I think right this works", "en", listOf("okay", "right")))
        assertEquals("So uhm I was thinking uh about this", filter("So uhm I was thinking uh about this", "en", emptyList()))
    }

    @Test fun pipelineStripsDanglingComma() =
        assertEquals("Hello there.", TextProcessor.process("Uh, hello there.", "en", true, emptyList(), 0.18).replaceFirstChar { it.uppercase() })

    @Test fun joinSegments() =
        assertEquals("Hello there. How are you?", TextProcessor.joinSegments(listOf(" Hello there. ", "", "How are you?")))

    @Test fun stripsWhisperAnnotations() {
        assertEquals("", DictationSession.stripNonSpeech("[BLANK_AUDIO]"))
        assertEquals("Hello", DictationSession.stripNonSpeech("(music) Hello"))
    }

    @Test fun catalogIsConsistent() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (m in ModelCatalog.all) {
            assertTrue(m.requiredFiles.isNotEmpty())
            val src = m.source
            if (src is ModelSource.Files) {
                assertEquals("${m.id} must download every required file", m.requiredFiles.toSet(), src.files.map { it.name }.toSet())
            }
            assertTrue(m.maxChunkSeconds <= 30f)
        }
        assertEquals("parakeet-v3", ModelCatalog.recommendedFor(12_000).id)
        assertEquals("moonshine-base-en", ModelCatalog.recommendedFor(3_000).id)
    }

    @Test fun resamplerPreservesToneAndLength() {
        val src = 48_000
        val input = FloatArray(src) { sin(2 * PI * 440 * it / src).toFloat() * 0.5f }
        val r = StreamingResampler(src, 16_000)
        // Feed in odd-sized blocks to exercise the streaming state.
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val n = minOf(777, input.size - i)
            r.process(input.copyOfRange(i, i + n)).forEach { out += it }
            i += n
        }
        assertTrue("got ${out.size}", abs(out.size - 16_000) <= 2)
        // Zero crossings of a 440 Hz tone over 1 s ≈ 880.
        val crossings = (1 until out.size).count { (out[it - 1] < 0) != (out[it] < 0) }
        assertTrue("crossings=$crossings", crossings in 870..890)
    }
}
