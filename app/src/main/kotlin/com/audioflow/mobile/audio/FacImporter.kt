package com.audioflow.mobile.audio

object FacImporter {
  private val mainPattern = Regex("""^\s*(-?\d+(?:\.\d+)?):\s*Main\s+([0-5])\s*$""")
  private val integerPattern = Regex("""^\s*(-?\d+):\s*Integer\[([0-6])\]\s*$""")
  private val bandHeaderPattern = Regex("""^\s*Band\s+(\d+)\s*$""")
  private val frequencyPattern = Regex("""^\s*(-?\d+(?:\.\d+)?):\s*CF\s*$""")
  private val gainPattern = Regex("""^\s*(-?\d+(?:\.\d+)?):\s*Boost/Cut\s*$""")

  fun parse(text: String, fallbackName: String): ImportedCurve {
    val rawLines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val lines = rawLines.map { it.trim().removePrefix("\uFEFF") }
    require(lines.any { it == "CLASS1 : Effect Type" }) {
      "这不是 FxSound .fac 配置文件"
    }

    val name = lines.getOrNull(2)?.takeIf { it.isNotBlank() } ?: fallbackName
    val main =
      buildMap {
        lines.forEach { line ->
          mainPattern.matchEntire(line)?.let { match ->
            put(match.groupValues[2].toInt(), match.groupValues[1].toFloat())
          }
        }
      }
    val flags =
      buildMap {
        lines.forEach { line ->
          integerPattern.matchEntire(line)?.let { match ->
            put(match.groupValues[2].toInt(), match.groupValues[1].toInt())
          }
        }
      }

    val frequencies = mutableListOf<Float>()
    val gains = mutableListOf<Float>()
    var index = 0
    while (index < lines.size) {
      if (bandHeaderPattern.matchEntire(lines[index]) != null) {
        val frequencyMatch = lines.getOrNull(index + 1)?.let(frequencyPattern::matchEntire)
        val gainMatch = lines.getOrNull(index + 2)?.let(gainPattern::matchEntire)
        val frequency = frequencyMatch?.groupValues?.get(1)?.toFloatOrNull()
        val gain = gainMatch?.groupValues?.get(1)?.toFloatOrNull()
        if (frequency != null && gain != null) {
          frequencies += frequency
          gains += gain
        }
      }
      index++
    }
    require(frequencies.isNotEmpty()) { ".fac 文件中没有找到 EQ Band 数据" }

    val normalized =
      resampleCurve(frequencies, gains, GlobalDspEngine.FREQUENCIES)
        .map { it.coerceIn(-12f, 12f) }

    // FxSound stores dynamics controls in Main[0..5]. Main 4 maps to the
    // dynamic-enhancement amount in the installed presets.
    val dynamicBass = (((main[4] ?: 0f) / 120f) * 100f).coerceIn(0f, 100f)
    val clarityEnabled = flags[0] == 1
    val clarity = if (clarityEnabled) ((main[0] ?: 0f) / 100f).coerceIn(0f, 1f) else 0f
    val adjusted =
      if (clarity > 0f) {
        GlobalDspEngine.FREQUENCIES.mapIndexed { band, frequency ->
          val amount =
            when {
              frequency >= 8_000f -> clarity * 2.5f
              frequency >= 4_000f -> clarity * 1.5f
              frequency >= 2_000f -> clarity * 0.5f
              else -> 0f
            }
          (normalized[band] + amount).coerceIn(-12f, 12f)
        }
      } else {
        normalized
      }

    return ImportedCurve(
      name = name,
      gains = adjusted,
      preampDb = 0f,
      dynamicBass = dynamicBass,
      sourceFormat = "FxSound .fac · ${frequencies.size} 段",
    )
  }
}
