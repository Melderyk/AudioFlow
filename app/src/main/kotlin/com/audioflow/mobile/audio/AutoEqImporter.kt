package com.audioflow.mobile.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object AutoEqImporter {
  private const val SAMPLE_RATE = 48_000.0
  private val preampPattern = Regex("""(?im)^\s*Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB""")
  private val graphicPattern = Regex("""(?im)^\s*GraphicEQ:\s*(.+)$""")
  private val pairPattern = Regex("""(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)""")
  private val filterPattern =
    Regex(
      """(?im)^\s*Filter\s+\d+:\s+(ON|OFF)\s+(PK|PEAK|LSC|HSC|LS|HS)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
    )

  fun parse(text: String, fallbackName: String): ImportedCurve {
    val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n')
    val preamp = preampPattern.find(normalizedText)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

    val graphicLine = graphicPattern.find(normalizedText)?.groupValues?.get(1)
    if (graphicLine != null) {
      val pairs =
        pairPattern.findAll(graphicLine).mapNotNull { match ->
          val frequency = match.groupValues[1].toFloatOrNull()
          val gain = match.groupValues[2].toFloatOrNull()
          if (frequency != null && gain != null) frequency to gain else null
        }.toList()
      require(pairs.isNotEmpty()) { "GraphicEQ 行中没有可用的频点" }
      val gains =
        resampleCurve(
          pairs.map { it.first },
          pairs.map { it.second },
          GlobalDspEngine.FREQUENCIES,
        ).map { it.coerceIn(-12f, 12f) }
      return ImportedCurve(
        name = fallbackName,
        gains = gains,
        preampDb = preamp.coerceIn(-24f, 6f),
        dynamicBass = 0f,
        sourceFormat = "AutoEQ GraphicEQ",
      )
    }

    val filters =
      filterPattern.findAll(normalizedText).mapNotNull { match ->
        if (match.groupValues[1].uppercase() != "ON") return@mapNotNull null
        ParametricFilter(
          frequency = match.groupValues[3].toDouble(),
          gainDb = match.groupValues[4].toDouble(),
          q = match.groupValues[5].toDoubleOrNull()?.coerceAtLeast(0.1) ?: 0.707,
          type = match.groupValues[2].uppercase(),
        )
      }.toList()
    require(filters.isNotEmpty()) { "没有找到 AutoEQ Filter 或 GraphicEQ 数据" }

    val gains =
      GlobalDspEngine.FREQUENCIES.map { frequency ->
        filters.sumOf { it.magnitudeAt(frequency.toDouble()) }
          .toFloat()
          .coerceIn(-12f, 12f)
      }
    return ImportedCurve(
      name = fallbackName,
      gains = gains,
      preampDb = preamp.coerceIn(-24f, 6f),
      dynamicBass = 0f,
      sourceFormat = "AutoEQ PEQ · ${filters.size} filters",
    )
  }

  private data class ParametricFilter(
    val frequency: Double,
    val gainDb: Double,
    val q: Double,
    val type: String,
  ) {
    fun magnitudeAt(frequency: Double): Double {
      val coefficients = coefficients()
      val omega = 2.0 * PI * frequency / SAMPLE_RATE
      val cos1 = cos(omega)
      val cos2 = cos(2.0 * omega)
      val numerator =
        coefficients.b0 * coefficients.b0 +
          coefficients.b1 * coefficients.b1 +
          coefficients.b2 * coefficients.b2 +
          2.0 * (coefficients.b0 * coefficients.b1 + coefficients.b1 * coefficients.b2) * cos1 +
          2.0 * coefficients.b0 * coefficients.b2 * cos2
      val denominator =
        coefficients.a0 * coefficients.a0 +
          coefficients.a1 * coefficients.a1 +
          coefficients.a2 * coefficients.a2 +
          2.0 * (coefficients.a0 * coefficients.a1 + coefficients.a1 * coefficients.a2) * cos1 +
          2.0 * coefficients.a0 * coefficients.a2 * cos2
      return ratioToDecibels(numerator, denominator)
    }

    private fun coefficients(): Biquad {
      val gain = 10.0.pow(gainDb / 40.0)
      val omega = 2.0 * PI * frequency / SAMPLE_RATE
      val sinOmega = sin(omega)
      val cosOmega = cos(omega)
      val alpha = sinOmega / (2.0 * q)
      return when (type) {
        "LSC", "LS" -> {
          val sqrtGain = sqrt(gain)
          Biquad(
            b0 = gain * ((gain + 1.0) - (gain - 1.0) * cosOmega + 2.0 * sqrtGain * alpha),
            b1 = 2.0 * gain * ((gain - 1.0) - (gain + 1.0) * cosOmega),
            b2 = gain * ((gain + 1.0) - (gain - 1.0) * cosOmega - 2.0 * sqrtGain * alpha),
            a0 = (gain + 1.0) + (gain - 1.0) * cosOmega + 2.0 * sqrtGain * alpha,
            a1 = -2.0 * ((gain - 1.0) + (gain + 1.0) * cosOmega),
            a2 = (gain + 1.0) + (gain - 1.0) * cosOmega - 2.0 * sqrtGain * alpha,
          )
        }
        "HSC", "HS" -> {
          val sqrtGain = sqrt(gain)
          Biquad(
            b0 = gain * ((gain + 1.0) + (gain - 1.0) * cosOmega + 2.0 * sqrtGain * alpha),
            b1 = -2.0 * gain * ((gain - 1.0) + (gain + 1.0) * cosOmega),
            b2 = gain * ((gain + 1.0) + (gain - 1.0) * cosOmega - 2.0 * sqrtGain * alpha),
            a0 = (gain + 1.0) - (gain - 1.0) * cosOmega + 2.0 * sqrtGain * alpha,
            a1 = 2.0 * ((gain - 1.0) - (gain + 1.0) * cosOmega),
            a2 = (gain + 1.0) - (gain - 1.0) * cosOmega - 2.0 * sqrtGain * alpha,
          )
        }
        else ->
          Biquad(
            b0 = 1.0 + alpha * gain,
            b1 = -2.0 * cosOmega,
            b2 = 1.0 - alpha * gain,
            a0 = 1.0 + alpha / gain,
            a1 = -2.0 * cosOmega,
            a2 = 1.0 - alpha / gain,
          )
      }
    }
  }

  private data class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a0: Double,
    val a1: Double,
    val a2: Double,
  )
}
