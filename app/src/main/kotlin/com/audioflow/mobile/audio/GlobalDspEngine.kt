package com.audioflow.mobile.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class EqPreset(
  val id: String,
  val label: String,
  val description: String,
  val gains: List<Float>,
  val preampDb: Float,
)

data class SavedProfile(
  val name: String,
  val gains: List<Float>,
  val preampDb: Float,
  val sourceFormat: String,
)

data class ConfigSnapshot(
  val id: Long,
  val name: String,
  val gains: List<Float>,
  val preampDb: Float,
  val dynamicBass: Float,
  val protectionEnabled: Boolean,
  val presetId: String,
  val profileName: String,
  val createdAt: Long,
)

data class DspUiState(
  val effectActive: Boolean = false,
  val supported: Boolean? = null,
  val status: String = "效果器尚未启动",
  val presetId: String = "flat",
  val gains: List<Float> = GlobalDspEngine.FLAT_GAINS,
  val manualPreampDb: Float = 0f,
  val effectivePreampDb: Float = 0f,
  val dynamicBass: Float = 0f,
  val protectionEnabled: Boolean = true,
  val detailedNotification: Boolean = true,
  val accessibilityKeepAlive: Boolean = false,
  val accessibilityConnected: Boolean = false,
  val bypassActive: Boolean = false,
  val snapshots: List<ConfigSnapshot> = emptyList(),
  val outputName: String = "正在检测输出设备",
  val profileName: String = "中性耳机",
  val profiles: List<SavedProfile> = emptyList(),
  val lastImport: String? = null,
  val appFilterEnabled: Boolean = false,
  val selectedPackages: Set<String> = emptySet(),
  val activeSelectedPackages: Set<String> = emptySet(),
  val effectId: Int = -1,
  val lastError: String? = null,
)

/**
 * Owns one DynamicsProcessing effect attached to Android's global output mix.
 * No microphone capture or audio proxy is used.
 */
object GlobalDspEngine {
  private const val TAG = "AudioFlow/Dsp"
  private const val PREFS = "audio_flow_prefs"
  private const val KEY_REQUESTED = "requested_enabled"
  private const val KEY_PRESET = "preset"
  private const val KEY_GAINS = "gains"
  private const val KEY_PREAMP = "preamp"
  private const val KEY_DYNAMIC_BASS = "dynamic_bass"
  private const val KEY_PROTECTION = "protection_enabled"
  private const val KEY_DETAILED_NOTIFICATION = "detailed_notification"
  private const val KEY_ACCESSIBILITY_KEEP_ALIVE = "accessibility_keep_alive"
  private const val KEY_ACCESSIBILITY_CONNECTED = "accessibility_connected"
  private const val KEY_SNAPSHOTS = "config_snapshots"
  private const val KEY_PROFILE_NAME = "profile_name"
  private const val KEY_SAVED_PROFILES = "saved_profiles"
  private const val KEY_APP_FILTER = "app_filter_enabled"
  private const val KEY_SELECTED_APPS = "selected_apps"

  val FREQUENCIES =
    listOf(
      20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
      200f, 250f, 315f, 400f, 500f, 630f, 800f, 1_000f, 1_250f, 1_600f,
      2_000f, 2_500f, 3_150f, 4_000f, 5_000f, 6_300f, 8_000f, 10_000f,
      12_500f, 16_000f, 20_000f,
    )
  val FLAT_GAINS = List(FREQUENCIES.size) { 0f }

  private val controlFrequencies =
    listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

  val PRESETS =
    listOf(
      EqPreset("flat", "原声", "不改变频响", FLAT_GAINS, 0f),
      EqPreset("bass", "低频", "更厚实的鼓点与低音", curve(listOf(6f, 5f, 4f, 2f, 0.5f, -0.5f, -1f, 0f, 0.5f, 1f)), 0f),
      EqPreset("movie", "电影", "增强氛围、对白与空间感", curve(listOf(4f, 3.5f, 2.5f, 1f, 0f, -0.5f, 1.5f, 2.5f, 2f, 1f)), 0f),
      EqPreset("vocal", "人声", "突出播客与歌声", curve(listOf(-2f, -1f, 0f, 2f, 3.5f, 3.5f, 2f, 0f, -1f, -1f)), 0f),
      EqPreset("bright", "通透", "增加高频与空气感", curve(listOf(-1f, 0f, 0.5f, 1f, 2f, 2.5f, 3f, 4f, 4.5f, 3f)), 0f),
      EqPreset("game", "游戏", "强化脚步和环境定位", curve(listOf(3f, 3f, 2f, 1f, 0f, 1f, 2.5f, 4f, 4f, 2f)), 0f),
    )

  private val builtInProfiles =
    listOf(
      SavedProfile("中性耳机", FLAT_GAINS, 0f, "内置"),
      SavedProfile("暖声耳机", curve(listOf(3f, 2.5f, 2f, 1f, 0f, -0.5f, -1f, -1f, -1.5f, -1.5f)), 0f, "内置通用调校"),
      SavedProfile("明亮耳机", curve(listOf(-1f, -0.5f, 0f, 1f, 1.5f, 2f, 2.5f, 1.5f, 0f, -1f)), 0f, "内置通用调校"),
    )

  private val lock = Any()
  private val initialized = AtomicBoolean(false)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val _state = MutableStateFlow(DspUiState())
  val state: StateFlow<DspUiState> = _state.asStateFlow()

  private var appContext: Context? = null
  private var audioManager: AudioManager? = null
  private var effect: DynamicsProcessing? = null
  private var deviceCallbackRegistered = false
  private var playbackCallbackRegistered = false
  private var requestedEnabled = false
  private var presetId = "flat"
  private var gains = FLAT_GAINS
  private var manualPreampDb = 0f
  private var dynamicBass = 0f
  private var protectionEnabled = true
  private var detailedNotification = true
  private var accessibilityKeepAlive = false
  private var accessibilityConnected = false
  private var bypassActive = false
  private var snapshots = emptyList<ConfigSnapshot>()
  private var profileName = "中性耳机"
  private var savedProfiles = emptyList<SavedProfile>()
  private var appFilterEnabled = false
  private var selectedPackages = emptySet<String>()
  private var activeSelectedPackages = emptySet<String>()

  private val deviceCallback =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        refreshOutputDevice()
        reapply()
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        refreshOutputDevice()
        reapply()
      }
    }

  private val playbackCallback =
    object : AudioManager.AudioPlaybackCallback() {
      override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
        synchronized(lock) {
          refreshPlaybackFilterLocked(configs.orEmpty(), forceCreate = true)
        }
      }
    }

  private val recreateRunnable =
    Runnable {
      synchronized(lock) {
        if (requestedEnabled && shouldRunForCurrentAppsLocked() && effect?.hasControl() != true) {
          Log.i(TAG, "Effect control was lost; recreating global effect")
          createEffectLocked(recreating = true)
        }
      }
    }

  fun initialize(context: Context) {
    if (!initialized.compareAndSet(false, true)) return
    appContext = context.applicationContext
    audioManager = context.getSystemService(AudioManager::class.java)
    restoreSettings(context)
    audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
    audioManager?.registerAudioPlaybackCallback(playbackCallback, mainHandler)
    deviceCallbackRegistered = true
    playbackCallbackRegistered = true
    refreshOutputDevice()
    synchronized(lock) {
      refreshPlaybackFilterLocked(audioManager?.activePlaybackConfigurations.orEmpty(), forceCreate = false)
    }
  }

  fun requestedEnabled(context: Context): Boolean {
    ensureInitialized(context)
    return requestedEnabled
  }

  fun enable(context: Context): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      requestedEnabled = true
      persistLocked()
      refreshPlaybackFilterLocked(audioManager?.activePlaybackConfigurations.orEmpty(), forceCreate = true)
      return _state.value
    }
  }

  fun disable(context: Context): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      requestedEnabled = false
      bypassActive = false
      persistLocked()
      releaseEffectLocked()
      _state.value =
        _state.value.copy(
          effectActive = false,
          status = "全局效果已关闭",
          effectId = -1,
          lastError = null,
        )
      return _state.value
    }
  }

  fun setBypass(context: Context, bypass: Boolean): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      bypassActive = bypass
      try {
        if (effect != null) {
          effect?.setEnabled(!bypass)
        }
      } catch (error: Throwable) {
        _state.value =
          _state.value.copy(
            bypassActive = false,
            lastError = error.message ?: error.javaClass.simpleName,
          )
        return _state.value
      }
      _state.value =
        _state.value.copy(
          bypassActive = bypass,
          status = if (bypass) "A/B 对比：正在监听原声" else "全局效果已启用",
        )
      return _state.value
    }
  }

  fun saveSnapshot(context: Context, requestedName: String): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      val now = System.currentTimeMillis()
      val name =
        requestedName.trim().ifBlank {
          val base = profileName.ifBlank { "配置" }
          "$base ${snapshots.size + 1}"
        }.take(60)
      val snapshot =
        ConfigSnapshot(
          id = now,
          name = name,
          gains = gains,
          preampDb = manualPreampDb,
          dynamicBass = dynamicBass,
          protectionEnabled = protectionEnabled,
          presetId = presetId,
          profileName = profileName,
          createdAt = now,
        )
      snapshots = (listOf(snapshot) + snapshots).take(30)
      persistLocked()
      _state.value = publishSettingsLocked()
      return _state.value
    }
  }

  fun loadSnapshot(context: Context, id: Long): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      val snapshot = snapshots.firstOrNull { it.id == id } ?: return _state.value
      gains = snapshot.gains
      manualPreampDb = snapshot.preampDb
      dynamicBass = snapshot.dynamicBass
      protectionEnabled = snapshot.protectionEnabled
      presetId = snapshot.presetId
      profileName = snapshot.profileName
      applyCurrentSettingsLocked()
      persistLocked()
      return publishSettingsLocked()
    }
  }

  fun deleteSnapshot(context: Context, id: Long): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      snapshots = snapshots.filterNot { it.id == id }
      persistLocked()
      _state.value = publishSettingsLocked()
      return _state.value
    }
  }

  fun selectPreset(context: Context, id: String): DspUiState {
    ensureInitialized(context)
    val preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()
    synchronized(lock) {
      presetId = preset.id
      gains = preset.gains
      manualPreampDb = preset.preampDb
      profileName = preset.label
      applyCurrentSettingsLocked()
      persistLocked()
      return publishSettingsLocked()
    }
  }

  fun selectProfile(context: Context, name: String): DspUiState {
    ensureInitialized(context)
    val profile = allProfilesLocked().firstOrNull { it.name == name } ?: return _state.value
    synchronized(lock) {
      profileName = profile.name
      gains = profile.gains
      manualPreampDb = profile.preampDb
      presetId = "custom"
      applyCurrentSettingsLocked()
      persistLocked()
      return publishSettingsLocked()
    }
  }

  fun applyImportedCurve(context: Context, curve: ImportedCurve): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      val profile =
        SavedProfile(
          name = curve.name.ifBlank { "导入配置" }.take(80),
          gains = normalizeGains(curve.gains),
          preampDb = curve.preampDb.coerceIn(-24f, 6f),
          sourceFormat = curve.sourceFormat,
        )
      savedProfiles = (savedProfiles.filterNot { it.name == profile.name } + profile).takeLast(40)
      profileName = profile.name
      gains = profile.gains
      manualPreampDb = profile.preampDb
      dynamicBass = curve.dynamicBass.coerceIn(0f, 100f)
      presetId = "custom"
      persistLocked()
      applyCurrentSettingsLocked()
      _state.value =
        publishSettingsLocked().copy(
          lastImport = "${curve.sourceFormat} · 已保存为「${profile.name}」",
          lastError = null,
        )
      return _state.value
    }
  }

  fun updateEqualizer(
    context: Context,
    newGains: FloatArray,
    preampDb: Float,
    newDynamicBass: Float,
    newProtectionEnabled: Boolean,
  ): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      gains = normalizeGains(newGains.toList())
      manualPreampDb = preampDb.coerceIn(-12f, 6f)
      dynamicBass = newDynamicBass.coerceIn(0f, 100f)
      protectionEnabled = newProtectionEnabled
      presetId = "custom"
      profileName = "自定义"
      applyCurrentSettingsLocked()
      persistLocked()
      return publishSettingsLocked()
    }
  }

  fun setNotificationDetails(context: Context, detailed: Boolean): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      detailedNotification = detailed
      persistLocked()
      _state.value = _state.value.copy(detailedNotification = detailedNotification)
      return _state.value
    }
  }

  fun setProtectionEnabled(context: Context, enabled: Boolean): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      protectionEnabled = enabled
      applyCurrentSettingsLocked()
      persistLocked()
      return publishSettingsLocked()
    }
  }

  fun setAccessibilityKeepAlive(context: Context, enabled: Boolean): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      accessibilityKeepAlive = enabled
      persistLocked()
      _state.value = _state.value.copy(accessibilityKeepAlive = enabled)
      return _state.value
    }
  }

  fun setAccessibilityConnected(context: Context, connected: Boolean): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      accessibilityConnected = connected
      persistLocked()
      _state.value = _state.value.copy(accessibilityConnected = connected)
      return _state.value
    }
  }

  fun updateAppFilter(
    context: Context,
    enabled: Boolean,
    packages: Set<String>,
  ): DspUiState {
    ensureInitialized(context)
    synchronized(lock) {
      appFilterEnabled = enabled
      selectedPackages = packages
      persistLocked()
      refreshPlaybackFilterLocked(audioManager?.activePlaybackConfigurations.orEmpty(), forceCreate = true)
      return _state.value
    }
  }

  fun reapply() {
    synchronized(lock) {
      if (effect != null) {
        applyCurrentSettingsLocked()
        refreshOutputDevice()
      }
    }
  }

  fun release() {
    synchronized(lock) {
      mainHandler.removeCallbacks(recreateRunnable)
      releaseEffectLocked()
      if (deviceCallbackRegistered) {
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        deviceCallbackRegistered = false
      }
      if (playbackCallbackRegistered) {
        audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        playbackCallbackRegistered = false
      }
      initialized.set(false)
    }
  }

  private fun ensureInitialized(context: Context) {
    if (!initialized.get()) initialize(context)
  }

  private fun shouldRunForCurrentAppsLocked(): Boolean {
    return !appFilterEnabled || selectedPackages.any { it in activeSelectedPackages }
  }

  private fun refreshPlaybackFilterLocked(
    configs: List<AudioPlaybackConfiguration>,
    forceCreate: Boolean,
  ) {
    val context = appContext ?: return
    val activeUids =
      configs
        .filter { it.isActiveCompat() }
        .map { it.clientUidCompat() }
        .toSet()
    val activePackages =
      activeUids
        .flatMap { uid ->
          context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        }
        .toSet()
    activeSelectedPackages =
      if (appFilterEnabled) selectedPackages.intersect(activePackages) else emptySet()
    _state.value =
      _state.value.copy(
        selectedPackages = selectedPackages,
        appFilterEnabled = appFilterEnabled,
        activeSelectedPackages = activeSelectedPackages,
      )

    if (!requestedEnabled) return
    if (appFilterEnabled) {
      if (shouldRunForCurrentAppsLocked()) {
        if (effect == null && forceCreate) createEffectLocked(recreating = false)
      } else if (effect != null) {
        releaseEffectLocked()
        _state.value =
          _state.value.copy(
            effectActive = false,
            effectId = -1,
            status =
              if (selectedPackages.isEmpty()) "请先选择要启用音效的应用"
              else "等待所选应用开始播放",
          )
      }
    } else if (effect == null && forceCreate) {
      createEffectLocked(recreating = false)
    }
  }

  private fun createEffectLocked(recreating: Boolean) {
    releaseEffectLocked()
    _state.value =
      _state.value.copy(
        effectActive = false,
        supported = null,
        status = if (recreating) "正在恢复全局效果" else "正在创建全局效果",
        lastError = null,
      )

    try {
      val preEq = DynamicsProcessing.Eq(true, true, FREQUENCIES.size)
      FREQUENCIES.forEachIndexed { index, frequency ->
        preEq.setBand(
          index,
          DynamicsProcessing.EqBand(true, frequency, effectiveGainAt(index)),
        )
      }
      val mbc = buildMbc()
      val limiter = buildLimiter()
      val builder =
        DynamicsProcessing.Config.Builder(
          DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
          2,
          true,
          FREQUENCIES.size,
          true,
          3,
          false,
          0,
          true,
        )
      for (channel in 0 until 2) {
        builder.setPreEqByChannelIndex(channel, preEq)
        builder.setMbcByChannelIndex(channel, mbc)
        builder.setLimiterByChannelIndex(channel, limiter)
        builder.setInputGainByChannelIndex(channel, effectivePreamp())
      }

      val created = DynamicsProcessing(Int.MAX_VALUE, 0, builder.build())
      val enableResult = created.setEnabled(true)
      if (enableResult < 0) {
        created.release()
        throw IllegalStateException("系统拒绝启用效果器，错误码 $enableResult")
      }

      effect = created
      created.setControlStatusListener { _, hasControl ->
        if (!hasControl && requestedEnabled) {
          mainHandler.removeCallbacks(recreateRunnable)
          mainHandler.postDelayed(recreateRunnable, 600)
        }
      }
      _state.value =
        publishSettingsLocked().copy(
          effectActive = created.hasControl(),
          supported = true,
          status = if (created.hasControl()) "全局效果已启用" else "效果已创建，正在获取控制权",
          effectId = created.id,
          lastError = null,
        )
      Log.i(TAG, "Global effect active: id=${created.id}, hasControl=${created.hasControl()}")
    } catch (error: Throwable) {
      Log.e(TAG, "Unable to create global DynamicsProcessing effect", error)
      releaseEffectLocked()
      requestedEnabled = false
      persistLocked()
      _state.value =
        _state.value.copy(
          effectActive = false,
          supported = false,
          status = "此设备未接受当前全局 DYNAMICS 配置",
          effectId = -1,
          lastError = error.message ?: error.javaClass.simpleName,
        )
    }
  }

  private fun buildMbc(): DynamicsProcessing.Mbc {
    val amount = dynamicBass / 100f
    val mbc = DynamicsProcessing.Mbc(true, true, 3)
    mbc.setBand(
      0,
      DynamicsProcessing.MbcBand(
        true,
        120f,
        10f,
        140f,
        1f + 2.5f * amount,
        -28f,
        6f,
        -70f,
        1f,
        2.5f * amount,
        0.8f * amount,
      ),
    )
    mbc.setBand(
      1,
      DynamicsProcessing.MbcBand(true, 2_500f, 10f, 120f, 1f, -18f, 6f, -70f, 1f, 0f, 0f),
    )
    mbc.setBand(
      2,
      DynamicsProcessing.MbcBand(true, 16_000f, 10f, 120f, 1f, -18f, 6f, -70f, 1f, 0f, 0f),
    )
    return mbc
  }

  private fun applyCurrentSettingsLocked() {
    val active = effect ?: return
    try {
      FREQUENCIES.forEachIndexed { index, frequency ->
        active.setPreEqBandAllChannelsTo(
          index,
          DynamicsProcessing.EqBand(true, frequency, effectiveGainAt(index)),
        )
      }
      active.setMbcAllChannelsTo(buildMbc())
      active.setLimiterAllChannelsTo(buildLimiter())
      active.setInputGainAllChannelsTo(effectivePreamp())
      _state.value = publishSettingsLocked()
    } catch (error: Throwable) {
      Log.e(TAG, "Failed to update effect", error)
      _state.value =
        _state.value.copy(
          lastError = error.message ?: error.javaClass.simpleName,
          status = "效果参数更新失败",
        )
    }
  }

  private fun publishSettingsLocked(): DspUiState {
    return _state.value.copy(
      presetId = presetId,
      gains = gains,
      manualPreampDb = manualPreampDb,
      effectivePreampDb = effectivePreamp(),
      dynamicBass = dynamicBass,
      protectionEnabled = protectionEnabled,
      detailedNotification = detailedNotification,
      accessibilityKeepAlive = accessibilityKeepAlive,
      accessibilityConnected = accessibilityConnected,
      bypassActive = bypassActive,
      snapshots = snapshots,
      outputName = currentOutputName(),
      profileName = profileName,
      profiles = allProfilesLocked(),
      appFilterEnabled = appFilterEnabled,
      selectedPackages = selectedPackages,
      activeSelectedPackages = activeSelectedPackages,
      effectActive = effect?.hasControl() == true,
    )
  }

  private fun effectivePreamp(): Float {
    if (!protectionEnabled) return manualPreampDb
    val positiveBoost =
      FREQUENCIES.indices
        .map(::effectiveGainAt)
        .maxOrNull()
        ?.coerceAtLeast(0f)
        ?: 0f
    return (manualPreampDb - positiveBoost * 0.24f).coerceIn(-24f, 6f)
  }

  private fun effectiveGainAt(index: Int): Float {
    val frequency = FREQUENCIES[index]
    val amount = dynamicBass / 100f
    val bassBoost =
      when {
        frequency <= 40f -> 8f
        frequency <= 80f -> 7f
        frequency <= 125f -> 5.5f
        frequency <= 200f -> 3.5f
        frequency <= 315f -> 1.5f
        else -> 0f
      } * amount
    return (gains[index] + bassBoost).coerceIn(-15f, 18f)
  }

  private fun releaseEffectLocked() {
    mainHandler.removeCallbacks(recreateRunnable)
    try {
      effect?.setEnabled(false)
    } catch (_: Throwable) {
    }
    try {
      effect?.release()
    } catch (_: Throwable) {
    }
    effect = null
  }

  private fun restoreSettings(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    requestedEnabled = prefs.getBoolean(KEY_REQUESTED, false)
    presetId = prefs.getString(KEY_PRESET, "flat") ?: "flat"
    manualPreampDb = prefs.getFloat(KEY_PREAMP, 0f)
    dynamicBass = prefs.getFloat(KEY_DYNAMIC_BASS, 0f)
    protectionEnabled = prefs.getBoolean(KEY_PROTECTION, true)
    detailedNotification = prefs.getBoolean(KEY_DETAILED_NOTIFICATION, true)
    accessibilityKeepAlive = prefs.getBoolean(KEY_ACCESSIBILITY_KEEP_ALIVE, false)
    accessibilityConnected = prefs.getBoolean(KEY_ACCESSIBILITY_CONNECTED, false)
    profileName = prefs.getString(KEY_PROFILE_NAME, "中性耳机") ?: "中性耳机"
    appFilterEnabled = prefs.getBoolean(KEY_APP_FILTER, false)
    selectedPackages =
      prefs.getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet().orEmpty()
    savedProfiles = readSavedProfiles(prefs.getString(KEY_SAVED_PROFILES, null))
    snapshots = readSnapshots(prefs.getString(KEY_SNAPSHOTS, null))
    val storedGains =
      prefs
        .getString(KEY_GAINS, null)
        ?.split(',')
        ?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == FREQUENCIES.size }
    gains =
      storedGains
        ?: allProfilesLocked().firstOrNull { it.name == profileName }?.gains
        ?: FLAT_GAINS
    _state.value =
      publishSettingsLocked().copy(
        effectActive = false,
        status = if (requestedEnabled) "效果已保存，等待启动" else "效果器尚未启动",
      )
  }

  private fun persistLocked() {
    val context = appContext ?: return
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_REQUESTED, requestedEnabled)
      .putString(KEY_PRESET, presetId)
      .putString(KEY_GAINS, gains.joinToString(","))
      .putFloat(KEY_PREAMP, manualPreampDb)
      .putFloat(KEY_DYNAMIC_BASS, dynamicBass)
      .putBoolean(KEY_PROTECTION, protectionEnabled)
      .putBoolean(KEY_DETAILED_NOTIFICATION, detailedNotification)
      .putBoolean(KEY_ACCESSIBILITY_KEEP_ALIVE, accessibilityKeepAlive)
      .putBoolean(KEY_ACCESSIBILITY_CONNECTED, accessibilityConnected)
      .putString(KEY_SNAPSHOTS, writeSnapshots())
      .putString(KEY_PROFILE_NAME, profileName)
      .putString(KEY_SAVED_PROFILES, writeSavedProfiles())
      .putBoolean(KEY_APP_FILTER, appFilterEnabled)
      .putStringSet(KEY_SELECTED_APPS, selectedPackages)
      .apply()
  }

  private fun allProfilesLocked(): List<SavedProfile> = builtInProfiles + savedProfiles

  private fun normalizeGains(values: List<Float>): List<Float> =
    if (values.size == FREQUENCIES.size) values.map { it.coerceIn(-12f, 12f) }
    else resampleCurve(controlFrequencies, values, FREQUENCIES).map { it.coerceIn(-12f, 12f) }

  private fun readSavedProfiles(raw: String?): List<SavedProfile> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.getJSONObject(index)
          val gainArray = item.getJSONArray("gains")
          val profileGains =
            buildList {
              for (gainIndex in 0 until gainArray.length()) {
                add(gainArray.getDouble(gainIndex).toFloat())
              }
            }
          add(
            SavedProfile(
              name = item.getString("name"),
              gains = normalizeGains(profileGains),
              preampDb = item.optDouble("preamp", 0.0).toFloat(),
              sourceFormat = item.optString("source", "已导入"),
            ),
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun writeSavedProfiles(): String {
    val array = JSONArray()
    savedProfiles.forEach { profile ->
      val gainsArray = JSONArray()
      profile.gains.forEach(gainsArray::put)
      array.put(
        JSONObject()
          .put("name", profile.name)
          .put("gains", gainsArray)
          .put("preamp", profile.preampDb)
          .put("source", profile.sourceFormat),
      )
    }
    return array.toString()
  }

  private fun readSnapshots(raw: String?): List<ConfigSnapshot> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.getJSONObject(index)
          val gainArray = item.getJSONArray("gains")
          val snapshotGains =
            buildList {
              for (gainIndex in 0 until gainArray.length()) {
                add(gainArray.getDouble(gainIndex).toFloat())
              }
            }
          add(
            ConfigSnapshot(
              id = item.getLong("id"),
              name = item.getString("name"),
              gains = normalizeGains(snapshotGains),
              preampDb = item.optDouble("preamp", 0.0).toFloat(),
              dynamicBass = item.optDouble("bass", 0.0).toFloat(),
              protectionEnabled = item.optBoolean("protection", true),
              presetId = item.optString("preset", "custom"),
              profileName = item.optString("profile", "自定义"),
              createdAt = item.optLong("createdAt", item.getLong("id")),
            ),
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun writeSnapshots(): String {
    val array = JSONArray()
    snapshots.forEach { snapshot ->
      val gainArray = JSONArray()
      snapshot.gains.forEach(gainArray::put)
      array.put(
        JSONObject()
          .put("id", snapshot.id)
          .put("name", snapshot.name)
          .put("gains", gainArray)
          .put("preamp", snapshot.preampDb)
          .put("bass", snapshot.dynamicBass)
          .put("protection", snapshot.protectionEnabled)
          .put("preset", snapshot.presetId)
          .put("profile", snapshot.profileName)
          .put("createdAt", snapshot.createdAt),
      )
    }
    return array.toString()
  }

  private fun refreshOutputDevice() {
    _state.value = _state.value.copy(outputName = currentOutputName())
  }

  private fun currentOutputName(): String {
    val devices =
      audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.filter { it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        .orEmpty()
    val device =
      devices.firstOrNull {
        it.type in
          setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_DOCK,
          )
      }
        ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        ?: devices.firstOrNull()
    if (device == null) return "未知输出设备"
    return when (device.type) {
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
      AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
      AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙耳机"
      AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
      else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "音频输出"
    }
  }

  private fun curve(values: List<Float>): List<Float> =
    resampleCurve(controlFrequencies, values, FREQUENCIES)

  private fun buildLimiter(): DynamicsProcessing.Limiter =
    DynamicsProcessing.Limiter(
      true,
      protectionEnabled,
      1,
      1f,
      60f,
      10f,
      -2f,
      0f,
    )

  private fun AudioPlaybackConfiguration.isActiveCompat(): Boolean =
    runCatching { javaClass.getMethod("isActive").invoke(this) as Boolean }.getOrDefault(false)

  private fun AudioPlaybackConfiguration.clientUidCompat(): Int =
    runCatching {
      (javaClass.getMethod("getClientUid").invoke(this) as Number).toInt()
    }.getOrDefault(-1)
}
