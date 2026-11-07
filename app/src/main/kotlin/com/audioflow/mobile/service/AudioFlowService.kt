package com.audioflow.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.audioflow.mobile.MainActivity
import com.audioflow.mobile.R
import com.audioflow.mobile.audio.GlobalDspEngine
import com.audioflow.mobile.audio.ImportedCurve

class AudioFlowService : Service() {
  companion object {
    private const val CHANNEL_ID = "audio_flow_effect"
    private const val NOTIFICATION_ID = 41

    const val ACTION_ENABLE = "com.audioflow.mobile.action.ENABLE"
    const val ACTION_DISABLE = "com.audioflow.mobile.action.DISABLE"
    const val ACTION_UPDATE = "com.audioflow.mobile.action.UPDATE"
    const val ACTION_PRESET = "com.audioflow.mobile.action.PRESET"
    const val ACTION_PROFILE = "com.audioflow.mobile.action.PROFILE"
    const val ACTION_IMPORTED = "com.audioflow.mobile.action.IMPORTED"
    const val ACTION_APP_FILTER = "com.audioflow.mobile.action.APP_FILTER"
    const val ACTION_REFRESH_NOTIFICATION = "com.audioflow.mobile.action.REFRESH_NOTIFICATION"
    const val ACTION_STOP_FOREGROUND_KEEP_ENGINE =
      "com.audioflow.mobile.action.STOP_FOREGROUND_KEEP_ENGINE"
    const val ACTION_BYPASS = "com.audioflow.mobile.action.BYPASS"
    const val ACTION_SAVE_SNAPSHOT = "com.audioflow.mobile.action.SAVE_SNAPSHOT"
    const val ACTION_LOAD_SNAPSHOT = "com.audioflow.mobile.action.LOAD_SNAPSHOT"
    const val ACTION_DELETE_SNAPSHOT = "com.audioflow.mobile.action.DELETE_SNAPSHOT"
    const val EXTRA_GAINS = "gains"
    const val EXTRA_PREAMP = "preamp"
    const val EXTRA_DYNAMIC_BASS = "dynamic_bass"
    const val EXTRA_PROTECTION = "protection_enabled"
    const val EXTRA_PRESET = "preset"
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_IMPORTED_NAME = "imported_name"
    const val EXTRA_IMPORTED_SOURCE = "imported_source"
    const val EXTRA_APP_FILTER_ENABLED = "app_filter_enabled"
    const val EXTRA_SELECTED_PACKAGES = "selected_packages"
    const val EXTRA_BYPASS = "bypass"
    const val EXTRA_SNAPSHOT_NAME = "snapshot_name"
    const val EXTRA_SNAPSHOT_ID = "snapshot_id"

    fun enable(context: Context) {
      start(context, Intent(context, AudioFlowService::class.java).setAction(ACTION_ENABLE))
    }

    fun disable(context: Context) {
      context.startService(Intent(context, AudioFlowService::class.java).setAction(ACTION_DISABLE))
    }

    fun update(context: Context, gains: FloatArray, preampDb: Float, dynamicBass: Float) {
      val intent =
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_UPDATE)
          .putExtra(EXTRA_GAINS, gains)
          .putExtra(EXTRA_PREAMP, preampDb)
          .putExtra(EXTRA_DYNAMIC_BASS, dynamicBass)
          .putExtra(EXTRA_PROTECTION, GlobalDspEngine.state.value.protectionEnabled)
      start(context, intent)
    }

    fun applyPreset(context: Context, presetId: String) {
      val intent =
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_PRESET)
          .putExtra(EXTRA_PRESET, presetId)
      start(context, intent)
    }

    fun applyProfile(context: Context, profile: String) {
      val intent =
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_PROFILE)
          .putExtra(EXTRA_PROFILE, profile)
      start(context, intent)
    }

    fun applyImportedCurve(context: Context, curve: ImportedCurve) {
      val intent =
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_IMPORTED)
          .putExtra(EXTRA_GAINS, curve.gains.toFloatArray())
          .putExtra(EXTRA_PREAMP, curve.preampDb)
          .putExtra(EXTRA_DYNAMIC_BASS, curve.dynamicBass)
          .putExtra(EXTRA_IMPORTED_NAME, curve.name)
          .putExtra(EXTRA_IMPORTED_SOURCE, curve.sourceFormat)
      start(context, intent)
    }

    fun updateAppFilter(context: Context, enabled: Boolean, packages: Set<String>) {
      val intent =
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_APP_FILTER)
          .putExtra(EXTRA_APP_FILTER_ENABLED, enabled)
          .putStringArrayListExtra(EXTRA_SELECTED_PACKAGES, ArrayList(packages))
      start(context, intent)
    }

    fun refreshNotification(context: Context) {
      context.startService(
        Intent(context, AudioFlowService::class.java).setAction(ACTION_REFRESH_NOTIFICATION),
      )
    }

    fun stopForegroundKeepingEffect(context: Context) {
      context.startService(
        Intent(context, AudioFlowService::class.java).setAction(ACTION_STOP_FOREGROUND_KEEP_ENGINE),
      )
    }

    fun setBypass(context: Context, bypass: Boolean) {
      context.startService(
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_BYPASS)
          .putExtra(EXTRA_BYPASS, bypass),
      )
    }

    fun saveSnapshot(context: Context, name: String) {
      start(
        context,
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_SAVE_SNAPSHOT)
          .putExtra(EXTRA_SNAPSHOT_NAME, name),
      )
    }

    fun loadSnapshot(context: Context, id: Long) {
      start(
        context,
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_LOAD_SNAPSHOT)
          .putExtra(EXTRA_SNAPSHOT_ID, id),
      )
    }

    fun deleteSnapshot(context: Context, id: Long) {
      start(
        context,
        Intent(context, AudioFlowService::class.java)
          .setAction(ACTION_DELETE_SNAPSHOT)
          .putExtra(EXTRA_SNAPSHOT_ID, id),
      )
    }

    private fun start(context: Context, intent: Intent) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }

  private var keepEngineOnDestroy = false

  override fun onCreate() {
    super.onCreate()
    GlobalDspEngine.initialize(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_FOREGROUND_KEEP_ENGINE) {
      keepEngineOnDestroy = true
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    startInForeground(GlobalDspEngine.state.value.effectActive)
    when (intent?.action) {
      ACTION_DISABLE -> {
        keepEngineOnDestroy = false
        GlobalDspEngine.disable(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_UPDATE -> {
        val gains = intent.getFloatArrayExtra(EXTRA_GAINS)
        val preamp = intent.getFloatExtra(EXTRA_PREAMP, 0f)
        val dynamicBass = intent.getFloatExtra(EXTRA_DYNAMIC_BASS, 0f)
        val protection =
          intent.getBooleanExtra(
            EXTRA_PROTECTION,
            GlobalDspEngine.state.value.protectionEnabled,
          )
        if (gains == null) {
          GlobalDspEngine.enable(this)
        } else {
          GlobalDspEngine.updateEqualizer(this, gains, preamp, dynamicBass, protection)
        }
      }
      ACTION_PRESET -> {
        val presetId = intent.getStringExtra(EXTRA_PRESET) ?: "flat"
        GlobalDspEngine.selectPreset(this, presetId)
        if (!GlobalDspEngine.state.value.effectActive) {
          GlobalDspEngine.enable(this)
        }
      }
      ACTION_PROFILE -> {
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: "中性耳机"
        GlobalDspEngine.selectProfile(this, profile)
        if (!GlobalDspEngine.state.value.effectActive) GlobalDspEngine.enable(this)
      }
      ACTION_IMPORTED -> {
        val gains = intent.getFloatArrayExtra(EXTRA_GAINS) ?: FloatArray(0)
        val curve =
          ImportedCurve(
            name = intent.getStringExtra(EXTRA_IMPORTED_NAME) ?: "导入配置",
            gains = gains.toList(),
            preampDb = intent.getFloatExtra(EXTRA_PREAMP, 0f),
            dynamicBass = intent.getFloatExtra(EXTRA_DYNAMIC_BASS, 0f),
            sourceFormat = intent.getStringExtra(EXTRA_IMPORTED_SOURCE) ?: "导入配置",
          )
        GlobalDspEngine.applyImportedCurve(this, curve)
        if (!GlobalDspEngine.state.value.effectActive) GlobalDspEngine.enable(this)
      }
      ACTION_APP_FILTER -> {
        val enabled = intent.getBooleanExtra(EXTRA_APP_FILTER_ENABLED, false)
        val packages =
          intent.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES)?.toSet().orEmpty()
        GlobalDspEngine.updateAppFilter(this, enabled, packages)
      }
      ACTION_REFRESH_NOTIFICATION -> Unit
      ACTION_BYPASS ->
        GlobalDspEngine.setBypass(this, intent.getBooleanExtra(EXTRA_BYPASS, false))
      ACTION_SAVE_SNAPSHOT ->
        GlobalDspEngine.saveSnapshot(this, intent.getStringExtra(EXTRA_SNAPSHOT_NAME).orEmpty())
      ACTION_LOAD_SNAPSHOT ->
        GlobalDspEngine.loadSnapshot(this, intent.getLongExtra(EXTRA_SNAPSHOT_ID, -1L))
      ACTION_DELETE_SNAPSHOT ->
        GlobalDspEngine.deleteSnapshot(this, intent.getLongExtra(EXTRA_SNAPSHOT_ID, -1L))
      else -> GlobalDspEngine.enable(this)
    }
    updateNotification(GlobalDspEngine.state.value.effectActive)
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    if (!keepEngineOnDestroy) GlobalDspEngine.release()
    super.onDestroy()
  }

  private fun startInForeground(effectActive: Boolean) {
    val notification = buildNotification(effectActive)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateNotification(effectActive: Boolean) {
    getSystemService(NotificationManager::class.java)
      .notify(NOTIFICATION_ID, buildNotification(effectActive))
  }

  private fun buildNotification(effectActive: Boolean): Notification {
    val openIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, AudioFlowService::class.java).setAction(ACTION_DISABLE),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val detailed = GlobalDspEngine.state.value.detailedNotification
    val text =
      when {
        !detailed -> "正在运行"
        effectActive -> "全局 EQ 正在运行 · ${GlobalDspEngine.state.value.outputName}"
        else -> "正在准备全局 EQ"
      }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle("AudioFlow")
      .setContentText(text)
      .setContentIntent(openIntent)
      .setOngoing(effectActive)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, "关闭效果", stopIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.notification_channel_description)
        setShowBadge(false)
      }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }
}
