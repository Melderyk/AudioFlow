package com.audioflow.mobile

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.audioflow.mobile.audio.AutoEqImporter
import com.audioflow.mobile.audio.FacImporter
import com.audioflow.mobile.audio.GlobalDspEngine
import com.audioflow.mobile.audio.ImportedCurve
import com.audioflow.mobile.appearance.AppearanceManager
import com.audioflow.mobile.service.AudioFlowAccessibilityService
import com.audioflow.mobile.service.AudioFlowService
import com.audioflow.mobile.service.TestTonePlayer
import com.audioflow.mobile.ui.main.AudioFlowScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.audioflow.mobile.ui.theme.AudioFlowTheme

class MainActivity : ComponentActivity() {
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
  private val importProfile =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) importCurve(uri)
    }
  private val pickIcon =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      if (uri != null) setCustomIcon(uri)
    }
  private val pickBackground =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      if (uri != null) setCustomBackground(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    GlobalDspEngine.initialize(this)
    AppearanceManager.initialize(this)
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      val state by GlobalDspEngine.state.collectAsStateWithLifecycle()
      val appearance by AppearanceManager.state.collectAsStateWithLifecycle()
      val systemDensity = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(systemDensity.density, appearance.uiFontScale),
      ) {
        AudioFlowTheme(accentColor = Color(AppearanceManager.displayAccent(appearance))) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AudioFlowScreen(
            state = state,
            appearance = appearance,
            onToggle = { enabled ->
              setEffectEnabled(enabled)
            },
            onPreset = { applyPreset(it) },
            onProfile = { applyProfile(it) },
            onImportProfile = { importProfile.launch(arrayOf("*/*")) },
            onEqualizerChanged = { gains, preamp, dynamicBass, protection ->
              applyEqualizer(gains, preamp, dynamicBass, protection)
            },
            onProtectionChanged = { enabled ->
              GlobalDspEngine.setProtectionEnabled(this, enabled)
            },
            onNotificationDetailsChanged = { detailed ->
              GlobalDspEngine.setNotificationDetails(this, detailed)
              if (!isAccessibilityModeActive()) AudioFlowService.refreshNotification(this)
            },
            onAccessibilityKeepAliveChanged = { enabled ->
              setAccessibilityKeepAlive(enabled)
            },
            onPickIcon = { pickIcon.launch("image/*") },
            onPickBackground = { pickBackground.launch("image/*") },
            onBackgroundDimChanged = { AppearanceManager.setBackgroundDim(this, it) },
            onBackgroundBlurChanged = { AppearanceManager.setBackgroundBlur(this, it) },
            onCardOpacityChanged = { AppearanceManager.setCardOpacity(this, it) },
            onCardBlurChanged = { AppearanceManager.setCardBlur(this, it) },
            onUiFontScaleChanged = { AppearanceManager.setUiFontScale(this, it) },
            onLauncherIconSelected = { AppearanceManager.setLauncherIcon(this, it) },
            onResetBackground = {
              AppearanceManager.resetBackground(this)
              Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show()
            },
            onResetAppearance = {
              AppearanceManager.reset(this)
              Toast.makeText(this, "已恢复默认图标和配色", Toast.LENGTH_SHORT).show()
            },
            onAutoAccentChanged = { AppearanceManager.setAutoAccent(this, it) },
            onPinShortcut = {
              val requested = AppearanceManager.pinShortcut(this)
              Toast.makeText(
                this,
                if (requested) "请在桌面确认添加快捷方式" else "当前桌面不支持固定快捷方式",
                Toast.LENGTH_LONG,
              ).show()
            },
            onBypassChanged = { bypass ->
              if (isAccessibilityModeActive()) {
                GlobalDspEngine.setBypass(this, bypass)
              } else {
                AudioFlowService.setBypass(this, bypass)
              }
            },
            onSaveSnapshot = { name ->
              GlobalDspEngine.saveSnapshot(this, name)
            },
            onLoadSnapshot = { id ->
              GlobalDspEngine.loadSnapshot(this, id)
            },
            onDeleteSnapshot = { id ->
              GlobalDspEngine.deleteSnapshot(this, id)
            },
            onAppFilterChanged = { enabled, packages ->
              if (isAccessibilityModeActive()) {
                GlobalDspEngine.updateAppFilter(this, enabled, packages)
              } else {
                AudioFlowService.updateAppFilter(this, enabled, packages)
              }
            },
            onTestTone = { TestTonePlayer.play() },
            )
          }
        }
      }
    }
    intent?.data?.let(::importCurve)
  }

  override fun onStart() {
    super.onStart()
    val accessibilityActive = readAccessibilityServiceActive()
    GlobalDspEngine.setAccessibilityConnected(this, accessibilityActive)
    if (GlobalDspEngine.requestedEnabled(this)) {
      if (
        GlobalDspEngine.state.value.accessibilityKeepAlive &&
          accessibilityActive
      ) {
        GlobalDspEngine.enable(this)
        AudioFlowService.stopForegroundKeepingEffect(this)
      } else {
        AudioFlowService.enable(this)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let(::importCurve)
  }

  private fun importCurve(uri: Uri) {
    lifecycleScope.launch {
      try {
        val displayName = queryDisplayName(uri)
        val text =
          withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
              ?: error("无法读取所选文件")
          }
        val curve =
          withContext(Dispatchers.Default) {
            val fallbackName = displayName.substringBeforeLast('.').ifBlank { "导入配置" }
            if (
              displayName.endsWith(".fac", ignoreCase = true) ||
                text.contains("CLASS1 : Effect Type", ignoreCase = true)
            ) {
              FacImporter.parse(text, fallbackName)
            } else {
              AutoEqImporter.parse(text, fallbackName)
            }
          }
        if (isAccessibilityModeActive()) {
          GlobalDspEngine.applyImportedCurve(this@MainActivity, curve)
          GlobalDspEngine.enable(this@MainActivity)
        } else {
          AudioFlowService.applyImportedCurve(this@MainActivity, curve)
        }
        Toast.makeText(
          this@MainActivity,
          "${curve.sourceFormat} 已导入为「${curve.name}」",
          Toast.LENGTH_LONG,
        ).show()
      } catch (error: Throwable) {
        Toast.makeText(
          this@MainActivity,
          "导入失败：${error.message ?: error.javaClass.simpleName}",
          Toast.LENGTH_LONG,
        ).show()
      }
    }
  }

  private fun setCustomIcon(uri: Uri) {
    lifecycleScope.launch {
      try {
        withContext(Dispatchers.IO) {
          AppearanceManager.setCustomIcon(this@MainActivity, uri)
        }
        Toast.makeText(this@MainActivity, "自定义图标和自动配色已更新", Toast.LENGTH_SHORT).show()
      } catch (error: Throwable) {
        Toast.makeText(
          this@MainActivity,
          "图标读取失败：${error.message ?: error.javaClass.simpleName}",
          Toast.LENGTH_LONG,
        ).show()
      }
    }
  }

  private fun setCustomBackground(uri: Uri) {
    lifecycleScope.launch {
      try {
        withContext(Dispatchers.IO) {
          AppearanceManager.setCustomBackground(this@MainActivity, uri)
        }
        Toast.makeText(this@MainActivity, "背景和自动配色已更新", Toast.LENGTH_SHORT).show()
      } catch (error: Throwable) {
        Toast.makeText(
          this@MainActivity,
          "背景读取失败：${error.message ?: error.javaClass.simpleName}",
          Toast.LENGTH_LONG,
        ).show()
      }
    }
  }

  private fun setEffectEnabled(enabled: Boolean) {
    if (!enabled) {
      AudioFlowService.disable(this)
      return
    }
    if (isAccessibilityModeActive()) {
      GlobalDspEngine.enable(this)
      AudioFlowService.stopForegroundKeepingEffect(this)
    } else {
      AudioFlowService.enable(this)
    }
  }

  private fun applyPreset(preset: String) {
    if (isAccessibilityModeActive()) {
      GlobalDspEngine.selectPreset(this, preset)
      GlobalDspEngine.enable(this)
    } else {
      AudioFlowService.applyPreset(this, preset)
    }
  }

  private fun applyProfile(profile: String) {
    if (isAccessibilityModeActive()) {
      GlobalDspEngine.selectProfile(this, profile)
      GlobalDspEngine.enable(this)
    } else {
      AudioFlowService.applyProfile(this, profile)
    }
  }

  private fun applyEqualizer(
    gains: FloatArray,
    preampDb: Float,
    dynamicBass: Float,
    protectionEnabled: Boolean,
  ) {
    if (isAccessibilityModeActive()) {
      GlobalDspEngine.updateEqualizer(
        this,
        gains,
        preampDb,
        dynamicBass,
        protectionEnabled,
      )
    } else {
      GlobalDspEngine.setProtectionEnabled(this, protectionEnabled)
      AudioFlowService.update(this, gains, preampDb, dynamicBass)
    }
  }

  private fun setAccessibilityKeepAlive(enabled: Boolean) {
    GlobalDspEngine.setAccessibilityKeepAlive(this, enabled)
    val active = readAccessibilityServiceActive()
    GlobalDspEngine.setAccessibilityConnected(this, active)
    if (enabled && active) {
      if (GlobalDspEngine.requestedEnabled(this)) GlobalDspEngine.enable(this)
      AudioFlowService.stopForegroundKeepingEffect(this)
    } else if (enabled) {
      startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } else if (GlobalDspEngine.requestedEnabled(this)) {
      AudioFlowService.enable(this)
    }
  }

  private fun isAccessibilityModeActive(): Boolean =
    GlobalDspEngine.state.value.accessibilityKeepAlive && readAccessibilityServiceActive()

  private fun readAccessibilityServiceActive(): Boolean {
    val manager = getSystemService(AccessibilityManager::class.java)
    return manager
      .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any {
        val serviceInfo = it.resolveInfo?.serviceInfo ?: return@any false
        serviceInfo.packageName == packageName &&
          serviceInfo.name == AudioFlowAccessibilityService::class.java.name
      }
  }

  private fun queryDisplayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) {
        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) return it.getString(index) ?: "导入配置"
      }
    }
    return uri.lastPathSegment ?: "导入配置"
  }
}
