// Binds captured PCM frames to the recognition session that owns them.
package com.brycewg.asrkb.asr

fun interface AudioFrameSink {
    fun onAudioFrame(pcm: ByteArray, sampleRate: Int, channels: Int)
}
