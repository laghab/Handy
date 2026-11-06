package com.handy.android.engine

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Hardware facts used to tune inference.
 *
 * Phones are big.LITTLE: running ONNX Runtime on every core makes the slow
 * efficiency cores the bottleneck and burns battery. We count the "performance"
 * cores (max frequency within ~20% of the fastest core) and use those.
 */
object DeviceProfile {

    val performanceCores: Int by lazy { detectPerformanceCores() }

    fun recommendedThreads(): Int = performanceCores.coerceIn(1, 4)

    fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun availableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun detectPerformanceCores(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.size < 2) return (cores / 2).coerceAtLeast(1)
        val fastest = freqs.max()
        val perf = freqs.count { it >= fastest * 0.8 }
        // A single prime core alone is too few; pull in the next tier too.
        return if (perf <= 1) {
            val sorted = freqs.sortedDescending()
            sorted.count { it >= sorted.getOrElse(1) { fastest } * 0.95 }.coerceAtLeast(2)
        } else perf
    }
}
