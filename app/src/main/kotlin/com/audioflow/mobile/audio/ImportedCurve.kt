package com.audioflow.mobile.audio

import kotlin.math.log10
import kotlin.math.log2

data class ImportedCurve(
  val name: String,
  val gains: List<Float>,
  val preampDb: Float,
  val dynamicBass: Float,
  val sourceFormat: String,
)

internal fun resampleCurve(
  sourceFrequencies: List<Float>,
  sourceGains: List<Float>,
  targetFrequencies: List<Float>,
): List<Float> {
  require(sourceFrequencies.size == sourceGains.size)
  require(sourceFrequencies.isNotEmpty())
  val points =
    sourceFrequencies
      .zip(sourceGains)
      .sortedBy { it.first }
      .distinctBy { it.first }
  if (points.size == 1) return List(targetFrequencies.size) { points.first().second }

  return targetFrequencies.map { target ->
    if (target <= points.first().first) return@map points.first().second
    if (target >= points.last().first) return@map points.last().second
    val upperIndex = points.indexOfFirst { it.first >= target }
    val lower = points[upperIndex - 1]
    val upper = points[upperIndex]
    val fraction =
      (log2(target / lower.first) / log2(upper.first / lower.first)).toFloat()
    lower.second + (upper.second - lower.second) * fraction
  }
}

internal fun ratioToDecibels(numerator: Double, denominator: Double): Double =
  10.0 * log10((numerator.coerceAtLeast(1e-12) / denominator.coerceAtLeast(1e-12)))
