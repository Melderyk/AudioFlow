package com.audioflow.mobile.appearance

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.audioflow.mobile.MainActivity
import com.audioflow.mobile.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppearanceState(
  val customIconPath: String? = null,
  val customBackgroundPath: String? = null,
  val accentColorArgb: Int = DEFAULT_ACCENT_ARGB,
  val autoAccentEnabled: Boolean = true,
  val backgroundDim: Float = 0.82f,
  val backgroundBlur: Float = 0f,
  val cardOpacity: Float = 0.70f,
  val cardBlur: Float = 16f,
  val uiFontScale: Float = 1f,
  val launcherIconId: String = "neon",
  val revision: Long = 0L,
) {
  companion object {
    const val DEFAULT_ACCENT_ARGB: Int = 0xFF75F77B.toInt()
  }
}

data class LauncherIconOption(
  val id: String,
  val label: String,
  val foregroundRes: Int,
  val backgroundArgb: Int,
  val componentName: String,
  val accentColorArgb: Int,
)

object AppearanceManager {
  private const val PREFS = "audio_flow_appearance"
  private const val KEY_ICON_PATH = "custom_icon_path"
  private const val KEY_BACKGROUND_PATH = "custom_background_path"
  private const val KEY_ACCENT = "accent_color"
  private const val KEY_AUTO_ACCENT = "auto_accent"
  private const val KEY_BACKGROUND_DIM = "background_dim"
  private const val KEY_BACKGROUND_BLUR = "background_blur"
  private const val KEY_CARD_OPACITY = "card_opacity"
  private const val KEY_CARD_BLUR = "card_blur"
  private const val KEY_UI_FONT_SCALE = "ui_font_scale"
  private const val KEY_GLASS_PRESET_V1 = "glass_preset_v1"
  private const val KEY_LAUNCHER_ICON = "launcher_icon"
  private const val ICON_FILE = "audioflow_custom_icon.png"
  private const val BACKGROUND_FILE = "audioflow_custom_background.png"
  private const val SHORTCUT_ID = "audioflow_custom_shortcut"

  private val _state = MutableStateFlow(AppearanceState())
  val state: StateFlow<AppearanceState> = _state.asStateFlow()
  private var initialized = false

  val launcherIcons =
    listOf(
      LauncherIconOption("neon", "霓虹 EQ", R.drawable.icon_fg_neon, 0xFF07130A.toInt(), "com.audioflow.mobile.launcher.IconNeon", 0xFF7DFF7F.toInt()),
      LauncherIconOption("pulse", "脉冲", R.drawable.icon_fg_pulse, 0xFF071B2C.toInt(), "com.audioflow.mobile.launcher.IconPulse", 0xFF63DDFF.toInt()),
      LauncherIconOption("sunset", "日落耳机", R.drawable.icon_fg_sunset, 0xFF2A111A.toInt(), "com.audioflow.mobile.launcher.IconSunset", 0xFFFF9A76.toInt()),
      LauncherIconOption("mono", "M 标志", R.drawable.icon_fg_mono, 0xFF111411.toInt(), "com.audioflow.mobile.launcher.IconMono", 0xFFF4F5F2.toInt()),
      LauncherIconOption("violet", "紫环", R.drawable.icon_fg_violet, 0xFF1A1229.toInt(), "com.audioflow.mobile.launcher.IconViolet", 0xFFC9A5FF.toInt()),
      LauncherIconOption("emerald", "磁带", R.drawable.icon_fg_emerald, 0xFF061D17.toInt(), "com.audioflow.mobile.launcher.IconEmerald", 0xFF7CFFCB.toInt()),
      LauncherIconOption("amber", "低频波", R.drawable.icon_fg_amber, 0xFF291B08.toInt(), "com.audioflow.mobile.launcher.IconAmber", 0xFFFFD166.toInt()),
      LauncherIconOption("cyan", "频谱", R.drawable.icon_fg_cyan, 0xFF071D22.toInt(), "com.audioflow.mobile.launcher.IconCyan", 0xFF69E5F0.toInt()),
    )

  fun initialize(context: Context) {
    if (initialized) return
    initialized = true
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val storedPath = prefs.getString(KEY_ICON_PATH, null)
    val iconPath = storedPath?.takeIf { File(it).isFile }
    val storedBackground = prefs.getString(KEY_BACKGROUND_PATH, null)
    val backgroundPath = storedBackground?.takeIf { File(it).isFile }
    val needsGlassMigration = !prefs.getBoolean(KEY_GLASS_PRESET_V1, false)
    val storedCardOpacity = prefs.getFloat(KEY_CARD_OPACITY, 0.70f)
    val storedCardBlur = prefs.getFloat(KEY_CARD_BLUR, 16f)
    _state.value =
      AppearanceState(
        customIconPath = iconPath,
        customBackgroundPath = backgroundPath,
        accentColorArgb =
          ensureReadableAccent(
            prefs.getInt(KEY_ACCENT, AppearanceState.DEFAULT_ACCENT_ARGB),
          ),
        autoAccentEnabled = prefs.getBoolean(KEY_AUTO_ACCENT, true),
        backgroundDim = prefs.getFloat(KEY_BACKGROUND_DIM, 0.82f).coerceIn(0.35f, 0.95f),
        backgroundBlur = prefs.getFloat(KEY_BACKGROUND_BLUR, 0f).coerceIn(0f, 30f),
        cardOpacity =
          if (needsGlassMigration && storedCardOpacity >= 0.88f) 0.70f
          else storedCardOpacity.coerceIn(0.35f, 1f),
        cardBlur =
          if (needsGlassMigration && storedCardBlur <= 10f) 16f
          else storedCardBlur.coerceIn(0f, 32f),
        uiFontScale = prefs.getFloat(KEY_UI_FONT_SCALE, 1f).coerceIn(0.8f, 1.3f),
        launcherIconId = prefs.getString(KEY_LAUNCHER_ICON, "neon") ?: "neon",
        revision = prefs.getLong("revision", 0L),
      )
    if (needsGlassMigration) save(context, _state.value)
  }

  fun loadIcon(state: AppearanceState): Bitmap? =
    state.customIconPath?.let { path ->
      BitmapFactory.decodeFile(path)
    }

  fun loadBackground(state: AppearanceState): Bitmap? =
    state.customBackgroundPath?.let { path ->
      BitmapFactory.decodeFile(path)
    }

  @RequiresApi(Build.VERSION_CODES.P)
  fun setCustomIcon(context: Context, uri: Uri): AppearanceState {
    initialize(context)
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val decoded =
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val maxDimension = max(info.size.width, info.size.height)
        if (maxDimension > 1024) {
          val scale = 1024f / maxDimension
          decoder.setTargetSize(
            (info.size.width * scale).toInt().coerceAtLeast(1),
            (info.size.height * scale).toInt().coerceAtLeast(1),
          )
        }
      }
    val file = File(context.filesDir, ICON_FILE)
    FileOutputStream(file).use { output ->
      check(decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) {
        "无法保存自定义图标"
      }
    }
    val accent = extractAccent(decoded)
    val revision = System.currentTimeMillis()
    val next =
      _state.value.copy(
        customIconPath = file.absolutePath,
        accentColorArgb = accent,
        revision = revision,
      )
    _state.value = next
    save(context, next)
    updatePinnedShortcut(context)
    return next
  }

  @RequiresApi(Build.VERSION_CODES.P)
  fun setCustomBackground(context: Context, uri: Uri): AppearanceState {
    initialize(context)
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val decoded =
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val maxDimension = max(info.size.width, info.size.height)
        if (maxDimension > 1600) {
          val scale = 1600f / maxDimension
          decoder.setTargetSize(
            (info.size.width * scale).toInt().coerceAtLeast(1),
            (info.size.height * scale).toInt().coerceAtLeast(1),
          )
        }
      }
    val file = File(context.filesDir, BACKGROUND_FILE)
    FileOutputStream(file).use { output ->
      check(decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) {
        "无法保存自定义背景"
      }
    }
    val accent = extractAccent(decoded)
    val next =
      _state.value.copy(
        customBackgroundPath = file.absolutePath,
        accentColorArgb = accent,
        revision = System.currentTimeMillis(),
      )
    _state.value = next
    save(context, next)
    return next
  }

  fun setAutoAccent(context: Context, enabled: Boolean): AppearanceState {
    initialize(context)
    val next = _state.value.copy(autoAccentEnabled = enabled)
    _state.value = next
    save(context, next)
    return next
  }

  fun setBackgroundDim(context: Context, value: Float): AppearanceState {
    initialize(context)
    val next = _state.value.copy(backgroundDim = value.coerceIn(0.35f, 0.95f))
    _state.value = next
    save(context, next)
    return next
  }

  fun setBackgroundBlur(context: Context, value: Float): AppearanceState {
    initialize(context)
    val next = _state.value.copy(backgroundBlur = value.coerceIn(0f, 30f))
    _state.value = next
    save(context, next)
    return next
  }

  fun setCardOpacity(context: Context, value: Float): AppearanceState {
    initialize(context)
    val next = _state.value.copy(cardOpacity = value.coerceIn(0.35f, 1f))
    _state.value = next
    save(context, next)
    return next
  }

  fun setCardBlur(context: Context, value: Float): AppearanceState {
    initialize(context)
    val next = _state.value.copy(cardBlur = value.coerceIn(0f, 32f))
    _state.value = next
    save(context, next)
    return next
  }

  fun setUiFontScale(context: Context, value: Float): AppearanceState {
    initialize(context)
    val next = _state.value.copy(uiFontScale = value.coerceIn(0.8f, 1.3f))
    _state.value = next
    save(context, next)
    return next
  }

  fun setLauncherIcon(context: Context, iconId: String): AppearanceState {
    initialize(context)
    val target = launcherIcons.firstOrNull { it.id == iconId } ?: return _state.value
    val packageManager = context.packageManager
    val targetComponent = ComponentName(context.packageName, target.componentName)
    packageManager.setComponentEnabledSetting(
      targetComponent,
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
      PackageManager.DONT_KILL_APP,
    )
    launcherIcons
      .filterNot { it.id == target.id }
      .forEach { option ->
        packageManager.setComponentEnabledSetting(
          ComponentName(context.packageName, option.componentName),
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP,
        )
      }
    val next =
      _state.value.copy(
        launcherIconId = target.id,
        accentColorArgb = target.accentColorArgb,
        revision = System.currentTimeMillis(),
      )
    _state.value = next
    save(context, next)
    return next
  }

  fun reset(context: Context): AppearanceState {
    initialize(context)
    File(context.filesDir, ICON_FILE).delete()
    File(context.filesDir, BACKGROUND_FILE).delete()
    val next =
      AppearanceState(
        autoAccentEnabled = _state.value.autoAccentEnabled,
        revision = System.currentTimeMillis(),
      )
    _state.value = next
    save(context, next)
    setLauncherIcon(context, "neon")
    updatePinnedShortcut(context)
    return _state.value
  }

  fun resetBackground(context: Context): AppearanceState {
    initialize(context)
    File(context.filesDir, BACKGROUND_FILE).delete()
    val iconBitmap = loadIcon(_state.value)
    val next =
      _state.value.copy(
        customBackgroundPath = null,
        accentColorArgb =
          iconBitmap?.let(::extractAccent) ?: AppearanceState.DEFAULT_ACCENT_ARGB,
        revision = System.currentTimeMillis(),
      )
    _state.value = next
    save(context, next)
    return next
  }

  fun pinShortcut(activity: Activity): Boolean {
    initialize(activity)
    val manager = activity.getSystemService(android.content.pm.ShortcutManager::class.java)
      ?: return false
    if (!manager.isRequestPinShortcutSupported) return false
    val bitmap = loadIcon(_state.value) ?: createDefaultShortcutBitmap()
    val shortcut =
      android.content.pm.ShortcutInfo.Builder(activity, SHORTCUT_ID)
        .setShortLabel("AudioFlow")
        .setLongLabel("AudioFlow 全局音效")
        .setIcon(Icon.createWithAdaptiveBitmap(centerCropSquare(bitmap)))
        .setIntent(
          Intent(activity, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER),
        )
        .build()
    return manager.requestPinShortcut(shortcut, null)
  }

  fun displayAccent(state: AppearanceState): Int =
    if (state.autoAccentEnabled) state.accentColorArgb else AppearanceState.DEFAULT_ACCENT_ARGB

  private fun save(context: Context, state: AppearanceState) {
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_ICON_PATH, state.customIconPath)
      .putString(KEY_BACKGROUND_PATH, state.customBackgroundPath)
      .putInt(KEY_ACCENT, state.accentColorArgb)
      .putBoolean(KEY_AUTO_ACCENT, state.autoAccentEnabled)
      .putFloat(KEY_BACKGROUND_DIM, state.backgroundDim)
      .putFloat(KEY_BACKGROUND_BLUR, state.backgroundBlur)
      .putFloat(KEY_CARD_OPACITY, state.cardOpacity)
      .putFloat(KEY_CARD_BLUR, state.cardBlur)
      .putFloat(KEY_UI_FONT_SCALE, state.uiFontScale)
      .putBoolean(KEY_GLASS_PRESET_V1, true)
      .putString(KEY_LAUNCHER_ICON, state.launcherIconId)
      .putLong("revision", state.revision)
      .apply()
  }

  private fun updatePinnedShortcut(context: Context) {
    val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
      ?: return
    val exists =
      manager.dynamicShortcuts.any { it.id == SHORTCUT_ID } ||
        manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }
    if (!exists) return
    val bitmap = loadIcon(_state.value) ?: createDefaultShortcutBitmap()
    val shortcut =
      android.content.pm.ShortcutInfo.Builder(context, SHORTCUT_ID)
        .setShortLabel("AudioFlow")
        .setLongLabel("AudioFlow 全局音效")
        .setIcon(Icon.createWithAdaptiveBitmap(centerCropSquare(bitmap)))
        .setIntent(
          Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER),
        )
        .build()
    runCatching { manager.updateShortcuts(listOf(shortcut)) }
  }

  private fun extractAccent(bitmap: Bitmap): Int {
    val sampleStep = max(1, max(bitmap.width, bitmap.height) / 64)
    var weightSum = 0.0
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    val hsv = FloatArray(3)
    var fallbackCount = 0
    var fallbackRed = 0.0
    var fallbackGreen = 0.0
    var fallbackBlue = 0.0
    for (y in 0 until bitmap.height step sampleStep) {
      for (x in 0 until bitmap.width step sampleStep) {
        val color = bitmap.getPixel(x, y)
        val alpha = AndroidColor.alpha(color)
        if (alpha < 160) continue
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        AndroidColor.RGBToHSV(r, g, b, hsv)
        if (hsv[1] < 0.16f || hsv[2] < 0.1f || hsv[2] > 0.97f) continue
        val weight = (0.25 + hsv[1] * 0.75).toDouble()
        weightSum += weight
        red += r * weight
        green += g * weight
        blue += b * weight
        fallbackCount++
        fallbackRed += r
        fallbackGreen += g
        fallbackBlue += b
      }
    }
    return if (weightSum > 0.0) {
      ensureReadableAccent(
        AndroidColor.rgb(
        (red / weightSum).toInt().coerceIn(0, 255),
        (green / weightSum).toInt().coerceIn(0, 255),
        (blue / weightSum).toInt().coerceIn(0, 255),
        ),
      )
    } else if (fallbackCount > 0) {
      ensureReadableAccent(
        AndroidColor.rgb(
          (fallbackRed / fallbackCount).toInt().coerceIn(0, 255),
          (fallbackGreen / fallbackCount).toInt().coerceIn(0, 255),
          (fallbackBlue / fallbackCount).toInt().coerceIn(0, 255),
        ),
      )
    } else {
      AppearanceState.DEFAULT_ACCENT_ARGB
    }
  }

  private fun ensureReadableAccent(color: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.58f)
    hsv[2] = hsv[2].coerceAtLeast(0.74f).coerceAtMost(0.94f)
    return AndroidColor.HSVToColor(hsv)
  }

  private fun centerCropSquare(bitmap: Bitmap): Bitmap {
    val side = minOf(bitmap.width, bitmap.height)
    val x = (bitmap.width - side) / 2
    val y = (bitmap.height - side) / 2
    return Bitmap.createBitmap(bitmap, x, y, side, side)
  }

  private fun createDefaultShortcutBitmap(): Bitmap {
    val size = 432
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(AndroidColor.rgb(8, 13, 9))
    val paint =
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AppearanceState.DEFAULT_ACCENT_ARGB
        style = android.graphics.Paint.Style.FILL
      }
    repeat(5) { index ->
      val left = 60f + index * 62f
      val top = 150f + (index % 2) * 38f
      canvas.drawRoundRect(left, top, left + 34f, 300f, 18f, 18f, paint)
    }
    return bitmap
  }
}
