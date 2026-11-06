package com.handy.android.model

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

enum class ModelFamily { PARAKEET, WHISPER, MOONSHINE, SENSE_VOICE }

data class RemoteFile(val name: String, val url: String, val sizeBytes: Long)

/** Where a model's files come from. */
sealed interface ModelSource {
    val downloadBytes: Long

    /** Individual files fetched directly (resumable, no extraction). */
    data class Files(val files: List<RemoteFile>) : ModelSource {
        override val downloadBytes get() = files.sumOf { it.sizeBytes }
    }

    /** A .tar.bz2 archive; [rootDir] is the top-level folder inside it. */
    data class Archive(val url: String, override val downloadBytes: Long, val rootDir: String) : ModelSource
}

data class ModelSpec(
    val id: String,
    val name: String,
    val description: String,
    val family: ModelFamily,
    val languages: String,
    /** Languages the user can pin (ISO 639-1). Empty = model is single-language or auto-only. */
    val selectableLanguages: List<String>,
    val source: ModelSource,
    /** Disk usage once installed. */
    val installedBytes: Long,
    /** Rough resident memory while loaded. Used for device recommendations. */
    val ramMb: Int,
    /** 1..5, relative to the other models on a mid-range phone. */
    val speed: Int,
    val accuracy: Int,
    /**
     * Longest single chunk we hand the model. Whisper is hard-limited to 30 s;
     * the others degrade gracefully but long chunks cost memory.
     */
    val maxChunkSeconds: Float,
    /** Whether the model emits its own punctuation & casing. */
    val punctuates: Boolean = true,
    /** Files that must exist for the install to count as complete. */
    val requiredFiles: List<String>,
    val buildConfig: (dir: File, language: String) -> OfflineModelConfig,
) {
    val supportsLanguageSelection get() = selectableLanguages.isNotEmpty()
}

object ModelCatalog {
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private fun hf(repo: String, file: String) = "https://huggingface.co/csukuangfj/$repo/resolve/main/$file"

    private val WHISPER_LANGS = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv",
        "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
        "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr",
        "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
        "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu",
        "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
        "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue",
    )

    private fun parakeetConfig(dir: File) = OfflineModelConfig(
        transducer = OfflineTransducerModelConfig(
            encoder = File(dir, "encoder.int8.onnx").path,
            decoder = File(dir, "decoder.int8.onnx").path,
            joiner = File(dir, "joiner.int8.onnx").path,
        ),
        tokens = File(dir, "tokens.txt").path,
        modelType = "nemo_transducer",
    )

    private fun moonshineV2Config(dir: File) = OfflineModelConfig(
        moonshine = OfflineMoonshineModelConfig(
            encoder = File(dir, "encoder_model.ort").path,
            mergedDecoder = File(dir, "decoder_model_merged.ort").path,
        ),
        tokens = File(dir, "tokens.txt").path,
    )

    private fun whisperConfig(dir: File, prefix: String, language: String) = OfflineModelConfig(
        whisper = OfflineWhisperModelConfig(
            encoder = File(dir, "$prefix-encoder.int8.onnx").path,
            decoder = File(dir, "$prefix-decoder.int8.onnx").path,
            // Empty = let Whisper detect the language itself.
            language = if (language == AUTO) "" else language,
            task = "transcribe",
            tailPaddings = 1000,
        ),
        tokens = File(dir, "$prefix-tokens.txt").path,
        modelType = "whisper",
    )

    const val AUTO = "auto"

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "moonshine-tiny-en",
            name = "Moonshine Tiny",
            description = "Tiny and very fast. Good for quick notes and messages on any phone.",
            family = ModelFamily.MOONSHINE,
            languages = "English",
            selectableLanguages = emptyList(),
            source = ModelSource.Archive(
                "$GH/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2",
                29_858_559, "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
            ),
            installedBytes = 44_243_206, ramMb = 120, speed = 5, accuracy = 2,
            maxChunkSeconds = 20f,
            requiredFiles = listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"),
            buildConfig = { dir, _ -> moonshineV2Config(dir) },
        ),
        ModelSpec(
            id = "moonshine-base-en",
            name = "Moonshine Base",
            description = "Fast, accurate English. A great default for most phones.",
            family = ModelFamily.MOONSHINE,
            languages = "English",
            selectableLanguages = emptyList(),
            source = ModelSource.Archive(
                "$GH/sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2",
                111_266_225, "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
            ),
            installedBytes = 141_300_566, ramMb = 300, speed = 4, accuracy = 3,
            maxChunkSeconds = 20f,
            requiredFiles = listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"),
            buildConfig = { dir, _ -> moonshineV2Config(dir) },
        ),
        ModelSpec(
            id = "parakeet-110m-en",
            name = "Parakeet 110M",
            description = "NVIDIA Parakeet TDT, small edition. Excellent English accuracy for its size.",
            family = ModelFamily.PARAKEET,
            languages = "English",
            selectableLanguages = emptyList(),
            source = ModelSource.Archive(
                "$GH/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
                108_035_095, "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
            ),
            installedBytes = 136_490_421, ramMb = 300, speed = 4, accuracy = 4,
            maxChunkSeconds = 30f,
            requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            buildConfig = { dir, _ -> parakeetConfig(dir) },
        ),
        ModelSpec(
            id = "parakeet-v3",
            name = "Parakeet V3",
            description = "Handy's flagship model. Best accuracy with automatic language detection " +
                "across 25 European languages. Best on phones with 8 GB+ RAM.",
            family = ModelFamily.PARAKEET,
            languages = "25 European languages (auto-detect)",
            selectableLanguages = emptyList(),
            source = ModelSource.Files(
                listOf(
                    RemoteFile("encoder.int8.onnx", hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "encoder.int8.onnx"), 652_184_281),
                    RemoteFile("decoder.int8.onnx", hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "decoder.int8.onnx"), 11_845_275),
                    RemoteFile("joiner.int8.onnx", hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "joiner.int8.onnx"), 6_355_277),
                    RemoteFile("tokens.txt", hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "tokens.txt"), 93_939),
                ),
            ),
            installedBytes = 670_478_772, ramMb = 1100, speed = 3, accuracy = 5,
            maxChunkSeconds = 30f,
            requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            buildConfig = { dir, _ -> parakeetConfig(dir) },
        ),
        ModelSpec(
            id = "whisper-base",
            name = "Whisper Base",
            description = "OpenAI Whisper, base size. 99 languages, lightweight.",
            family = ModelFamily.WHISPER,
            languages = "99 languages",
            selectableLanguages = WHISPER_LANGS,
            source = ModelSource.Files(
                listOf(
                    RemoteFile("base-encoder.int8.onnx", hf("sherpa-onnx-whisper-base", "base-encoder.int8.onnx"), 29_120_534),
                    RemoteFile("base-decoder.int8.onnx", hf("sherpa-onnx-whisper-base", "base-decoder.int8.onnx"), 130_672_026),
                    RemoteFile("base-tokens.txt", hf("sherpa-onnx-whisper-base", "base-tokens.txt"), 816_730),
                ),
            ),
            installedBytes = 160_609_290, ramMb = 350, speed = 3, accuracy = 3,
            maxChunkSeconds = 28f,
            requiredFiles = listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"),
            buildConfig = { dir, lang -> whisperConfig(dir, "base", lang) },
        ),
        ModelSpec(
            id = "whisper-small",
            name = "Whisper Small",
            description = "OpenAI Whisper, small size. Better multilingual accuracy, slower.",
            family = ModelFamily.WHISPER,
            languages = "99 languages",
            selectableLanguages = WHISPER_LANGS,
            source = ModelSource.Files(
                listOf(
                    RemoteFile("small-encoder.int8.onnx", hf("sherpa-onnx-whisper-small", "small-encoder.int8.onnx"), 112_442_483),
                    RemoteFile("small-decoder.int8.onnx", hf("sherpa-onnx-whisper-small", "small-decoder.int8.onnx"), 262_226_114),
                    RemoteFile("small-tokens.txt", hf("sherpa-onnx-whisper-small", "small-tokens.txt"), 816_730),
                ),
            ),
            installedBytes = 375_485_327, ramMb = 800, speed = 2, accuracy = 4,
            maxChunkSeconds = 28f,
            requiredFiles = listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"),
            buildConfig = { dir, lang -> whisperConfig(dir, "small", lang) },
        ),
        ModelSpec(
            id = "sense-voice",
            name = "SenseVoice",
            description = "Very fast multilingual model for Chinese, Cantonese, English, Japanese and Korean.",
            family = ModelFamily.SENSE_VOICE,
            languages = "中文, 粵語, English, 日本語, 한국어",
            selectableLanguages = listOf("zh", "yue", "en", "ja", "ko"),
            source = ModelSource.Files(
                listOf(
                    RemoteFile("model.int8.onnx", hf("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09", "model.int8.onnx"), 237_115_547),
                    RemoteFile("tokens.txt", hf("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09", "tokens.txt"), 315_894),
                ),
            ),
            installedBytes = 237_431_441, ramMb = 450, speed = 5, accuracy = 3,
            maxChunkSeconds = 30f,
            requiredFiles = listOf("model.int8.onnx", "tokens.txt"),
            buildConfig = { dir, lang ->
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").path,
                        language = if (lang == AUTO) "auto" else lang,
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, "tokens.txt").path,
                )
            },
        ),
    )

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    /** Picks a sensible default for this phone's memory. */
    fun recommendedFor(totalRamMb: Long): ModelSpec = when {
        totalRamMb >= 7_500 -> byId("parakeet-v3")!!
        totalRamMb >= 3_500 -> byId("parakeet-110m-en")!!
        else -> byId("moonshine-base-en")!!
    }
}
