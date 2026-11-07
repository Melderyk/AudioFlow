package com.audioflow.mobile.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.audioflow.mobile.audio.GlobalDspEngine

class AudioFlowTileService : TileService() {
  override fun onStartListening() {
    super.onStartListening()
    GlobalDspEngine.initialize(this)
    updateTile(GlobalDspEngine.state.value.effectActive)
  }

  override fun onClick() {
    super.onClick()
    if (GlobalDspEngine.state.value.effectActive) {
      if (
        GlobalDspEngine.state.value.accessibilityKeepAlive &&
          GlobalDspEngine.state.value.accessibilityConnected
      ) {
        GlobalDspEngine.disable(this)
      } else {
        AudioFlowService.disable(this)
      }
      updateTile(false)
    } else {
      if (
        GlobalDspEngine.state.value.accessibilityKeepAlive &&
          GlobalDspEngine.state.value.accessibilityConnected
      ) {
        GlobalDspEngine.enable(this)
      } else {
        AudioFlowService.enable(this)
      }
      updateTile(true)
    }
  }

  private fun updateTile(active: Boolean) {
    qsTile?.apply {
      state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
      label = "AudioFlow"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        subtitle = if (active) "全局 EQ 已开启" else "全局 EQ 已关闭"
      }
      updateTile()
    }
  }
}
