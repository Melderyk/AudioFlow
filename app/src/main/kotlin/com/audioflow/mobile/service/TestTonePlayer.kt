package com.audioflow.mobile.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TestTonePlayer {
  private const val SAMPLE_RATE = 48_000
  private const val DURATION_SECONDS = 4
  private val scope = CoroutineScope(Dispatchers.Default)
  private var playback: Job? = null

  fun play() {
    playback?.cancel()
    playback =
      scope.launch {
        val samples = withContext(Dispatchers.Default) { createSweep() }
        val track =
          AudioTrack.Builder()
            .setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            )
            .setAudioFormat(
              AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
          track.write(samples, 0, samples.size)
          track.setVolume(0.22f)
          track.play()
          delay((DURATION_SECONDS + 1) * 1_000L)
        } finally {
          runCatching { track.stop() }
          track.release()
        }
      }
  }

  fun stop() {
    playback?.cancel()
    playback = null
  }

  private fun createSweep(): ShortArray {
    val frameCount = SAMPLE_RATE * DURATION_SECONDS
    val frames = ShortArray(frameCount * 2)
    var phase = 0.0
    for (frame in 0 until frameCount) {
      val progress = frame.toDouble() / frameCount
      val frequency = 100.0 * Math.pow(100.0, progress)
      phase += 2.0 * PI * frequency / SAMPLE_RATE
      val fade =
        minOf(1.0, frame / (SAMPLE_RATE * 0.08))
          .coerceAtMost((frameCount - frame) / (SAMPLE_RATE * 0.15))
          .coerceIn(0.0, 1.0)
      val sample = (sin(phase) * Short.MAX_VALUE * 0.5 * fade).toInt().toShort()
      frames[frame * 2] = sample
      frames[frame * 2 + 1] = sample
    }
    return frames
  }
}
