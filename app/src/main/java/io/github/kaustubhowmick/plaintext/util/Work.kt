package io.github.kaustubhowmick.plaintext.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Background threads (design.md §3.6): one IO thread for file reads, writes and
 * recovery writes (single thread = ordered), one compute thread for search,
 * Replace All and print layout, plus the main-thread handler.
 */
class Work {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "plaintext-io") }
    val compute: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "plaintext-compute") }
    val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the IO thread and delivers its result (or failure) on the main thread. */
    fun <T> onIo(block: () -> T, done: (Result<T>) -> Unit) = submit(io, block, done)

    fun <T> onCompute(block: () -> T, done: (Result<T>) -> Unit) = submit(compute, block, done)

    private fun <T> submit(exec: ExecutorService, block: () -> T, done: (Result<T>) -> Unit) {
        exec.execute {
            val result = try {
                Result.success(block())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            main.post { done(result) }
        }
    }

    fun shutdown() {
        io.shutdown()
        compute.shutdown()
    }
}
