package com.audioflow.mobile.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.audioflow.mobile.audio.GlobalDspEngine

class AudioFlowAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    super.onServiceConnected()
    GlobalDspEngine.initialize(this)
    GlobalDspEngine.setAccessibilityConnected(this, true)
    if (
      GlobalDspEngine.state.value.accessibilityKeepAlive &&
        GlobalDspEngine.requestedEnabled(this)
    ) {
      GlobalDspEngine.enable(this)
      AudioFlowService.stopForegroundKeepingEffect(this)
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit

  override fun onDestroy() {
    GlobalDspEngine.setAccessibilityConnected(this, false)
    if (GlobalDspEngine.requestedEnabled(this)) {
      AudioFlowService.enable(this)
    }
    super.onDestroy()
  }
}
