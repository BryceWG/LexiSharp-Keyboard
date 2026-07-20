// Implemented by direct-capture engines that accept a session-scoped audio sink.
package com.brycewg.asrkb.asr

interface AudioFrameSinkOwner {
    var audioFrameSink: AudioFrameSink?
}
