// X-ASR single-writer session FIFO and finish-barrier tests.
package com.brycewg.asrkb.asr

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XAsrStreamSessionTest {
    @Test
    fun finishAcceptsAllQueuedPcmInOrderBeforePadding() = runTest {
        val sink = FakeXAsrStreamSink(resultText = "ok")
        val finals = mutableListOf<String>()
        val session = session(onFinal = { finals.add(it) })
        session.start()
        session.enqueuePcm(pcmOf(1000))
        session.enqueuePcm(pcmOf(2000, 3000))
        session.finish()
        session.attachSink(sink)
        session.awaitCompletion()

        assertEquals(listOf("ok"), finals)
        assertEquals(3, sink.realWaveforms.sumOf { it.size })
        assertFloatSamples(sink.realWaveforms.flatten(), 1000, 2000, 3000)
        assertTrue(sink.padding.isNotEmpty())
        assertTrue(sink.padding.all { it == 0f })
        assertEquals(15_360, sink.padding.size)
        assertEquals(2, sink.ops.count { it == "pad" })
        val lastRealIndex = sink.ops.indexOfLast { it == "accept" }
        val firstPadIndex = sink.ops.indexOf("pad")
        val finishedIndex = sink.ops.indexOf("inputFinished")
        assertTrue(lastRealIndex >= 0)
        assertTrue(firstPadIndex > lastRealIndex)
        assertTrue(finishedIndex > sink.ops.indexOfLast { it == "pad" })
        assertEquals("release", sink.ops.last())
    }

    @Test
    fun pcmQueuedBeforeSinkStillEntersStreamFirst() = runTest {
        val sink = FakeXAsrStreamSink()
        val session = session()
        session.start()
        session.enqueuePcm(pcmOf(111))
        session.attachSink(sink)
        session.enqueuePcm(pcmOf(222))
        session.finish()
        session.awaitCompletion()

        assertFloatSamples(sink.realWaveforms.flatten(), 111, 222)
    }

    @Test
    fun pcmAfterFinishIsDropped() = runTest {
        val sink = FakeXAsrStreamSink()
        val session = session()
        session.start()
        session.attachSink(sink)
        session.enqueuePcm(pcmOf(111))
        session.finish()
        val acceptedLate = session.enqueuePcm(pcmOf(999))
        session.awaitCompletion()

        assertFalse(acceptedLate)
        assertFloatSamples(sink.realWaveforms.flatten(), 111)
        assertFalse(sink.realWaveforms.flatten().any { kotlin.math.abs(it - 999 / 32768.0f) < 1e-6f })
    }

    @Test
    fun concurrentEnqueueDoesNotDropOrDuplicatePreFinishPcm() = runTest {
        val sink = FakeXAsrStreamSink()
        val session = session()
        session.start()
        session.attachSink(sink)
        val markers = (1..20).map { it.toShort() }
        coroutineScope {
            markers.map { marker ->
                async { session.enqueuePcm(pcmOf(marker)) }
            }.awaitAll()
        }
        session.finish()
        session.awaitCompletion()

        val accepted = sink.realWaveforms.flatten().map { sample ->
            (sample * 32768.0f).toInt().toShort()
        }.toSet()
        assertEquals(markers.toSet(), accepted)
        assertEquals(markers.size, sink.realWaveforms.sumOf { it.size })
    }

    @Test
    fun inputFinishedHappensAfterRealPcmAndPadding() = runTest {
        val sink = FakeXAsrStreamSink()
        val session = session()
        session.start()
        session.attachSink(sink)
        session.enqueuePcm(pcmOf(50))
        session.finish()
        session.awaitCompletion()

        val padIndex = sink.ops.indexOf("pad")
        val finishedIndex = sink.ops.indexOf("inputFinished")
        val lastRealIndex = sink.ops.indexOfLast { it == "accept" }
        assertTrue(lastRealIndex >= 0)
        assertTrue(padIndex > lastRealIndex)
        assertTrue(finishedIndex > sink.ops.indexOfLast { it == "pad" })
    }

    @Test
    fun finalizePadsEnoughSilenceToDecodeTwoTrailing480msChunks() = runTest {
        val chunkSamples = 16000 * 480 / 1000
        val sink = ChunkedXAsrStreamSink(samplesPerChunk = chunkSamples)
        val session = session()
        session.start()
        session.attachSink(sink)
        session.enqueuePcm(pcmOf(*ShortArray(chunkSamples * 2) { 1000 }))
        session.finish()
        session.awaitCompletion()

        // 两段语音 chunk 会在 accept 时 decode；再补 2 个 480ms right-context chunk。
        assertTrue(
            "decodedChunks=${sink.decodedChunks}",
            sink.decodedChunks >= 4
        )
        assertEquals(chunkSamples * 2, sink.paddingSamples)
    }

    @Test
    fun finalizeKeepsDecodingUntilSinkIsNotReady() = runTest {
        val sink = CountedReadyXAsrStreamSink(readyCount = 20)
        val session = session()
        session.start()
        session.attachSink(sink)
        session.enqueuePcm(pcmOf(1))
        session.finish()
        session.awaitCompletion()

        assertEquals(20, sink.decodeCount)
    }

    @Test
    fun cancelThenFinishDoesNotEmitFinal() = runTest {
        val sink = FakeXAsrStreamSink(resultText = "should-not-emit")
        val finals = mutableListOf<String>()
        val session = session(onFinal = { finals.add(it) })
        session.start()
        session.attachSink(sink)
        session.enqueuePcm(pcmOf(111))
        session.cancel()
        session.finish()
        session.awaitCompletion()

        assertTrue(finals.isEmpty())
    }

    @Test
    fun preSinkEnqueueDropsWhenPrebufferExceedsCap() = runTest {
        val session = session()
        session.start()
        val chunk = pcmOf(*ShortArray(1000) { 1 })
        var acceptedBytes = 0
        var dropped = 0
        repeat(300) {
            if (session.enqueuePcm(chunk)) {
                acceptedBytes += chunk.size
            } else {
                dropped++
            }
        }
        assertTrue(dropped > 0)
        assertTrue(acceptedBytes <= 384 * 1024)
        session.cancel()
        session.awaitCompletion()
    }

    private fun CoroutineScope.session(
        onFinal: (String) -> Unit = {},
        dispatcher: CoroutineDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    ): XAsrStreamSession = XAsrStreamSession(
        scope = this,
        sampleRate = 16000,
        frameMs = 200,
        useItn = false,
        nowMs = { 0L },
        onPartial = {},
        onFinal = onFinal,
        processorDispatcher = dispatcher
    )

    private fun pcmOf(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            val v = sample.toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun assertFloatSamples(actual: List<Float>, vararg samples: Short) {
        assertEquals(samples.size, actual.size)
        samples.forEachIndexed { i, sample ->
            assertEquals(sample / 32768.0f, actual[i], 1e-5f)
        }
    }

    private fun List<FloatArray>.flatten(): List<Float> = flatMap { it.toList() }

    private class FakeXAsrStreamSink(
        private val resultText: String = "ok"
    ) : XAsrStreamSink {
        val realWaveforms = mutableListOf<FloatArray>()
        private val paddingSamples = mutableListOf<Float>()
        val padding: FloatArray
            get() = paddingSamples.toFloatArray()
        val ops = CopyOnWriteArrayList<String>()

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            if (samples.isNotEmpty() && samples.all { it == 0f }) {
                paddingSamples.addAll(samples.toList())
                ops.add("pad")
            } else {
                realWaveforms.add(samples.copyOf())
                ops.add("accept")
            }
        }

        override fun isReady(): Boolean = false

        override fun decode() {
            ops.add("decode")
        }

        override fun getResultText(): String = resultText

        override fun inputFinished() {
            ops.add("inputFinished")
        }

        override fun release() {
            ops.add("release")
        }
    }

    private class ChunkedXAsrStreamSink(
        private val samplesPerChunk: Int
    ) : XAsrStreamSink {
        var decodedChunks: Int = 0
            private set
        var paddingSamples: Int = 0
            private set
        private var pending = 0
        private var finished = false

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            pending += samples.size
            if (samples.isNotEmpty() && samples.all { it == 0f }) {
                paddingSamples += samples.size
            }
        }

        override fun isReady(): Boolean = pending >= samplesPerChunk || (finished && pending > 0)

        override fun decode() {
            val consume = if (finished && pending < samplesPerChunk) pending else samplesPerChunk
            if (consume <= 0) return
            pending -= consume
            decodedChunks++
        }

        override fun getResultText(): String = decodedChunks.toString()

        override fun inputFinished() {
            finished = true
        }

        override fun release() {
        }
    }

    private class CountedReadyXAsrStreamSink(
        private val readyCount: Int
    ) : XAsrStreamSink {
        var decodeCount: Int = 0
            private set
        private var remainingReady = readyCount

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        }

        override fun isReady(): Boolean = remainingReady > 0

        override fun decode() {
            if (remainingReady <= 0) return
            remainingReady--
            decodeCount++
        }

        override fun getResultText(): String = decodeCount.toString()

        override fun inputFinished() {
        }

        override fun release() {
        }
    }
}
