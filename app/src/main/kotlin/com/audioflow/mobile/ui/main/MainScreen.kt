package com.audioflow.mobile.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.audioflow.mobile.audio.DspUiState
import com.audioflow.mobile.audio.ConfigSnapshot
import com.audioflow.mobile.audio.GlobalDspEngine
import com.audioflow.mobile.audio.SavedProfile
import com.audioflow.mobile.appearance.AppearanceManager
import com.audioflow.mobile.appearance.AppearanceState
import com.audioflow.mobile.ui.theme.MeldrykBrandFont
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val DefaultAccent = Color(0xFF75F77B)
private var Accent by mutableStateOf(DefaultAccent)
private val Muted = Color(0xFF9DADA1)
private val DefaultCardColor = Color(0xFF151D17)
private var CardColor by mutableStateOf(DefaultCardColor)
private var CardBlur by mutableFloatStateOf(0f)
private val CardBorder = Color(0xFF2A392D)

private data class AppChoice(
  val label: String,
  val packageName: String,
)

private enum class ScreenSection {
  Appearance,
  Profile,
  Equalizer,
  AppFilter,
  Background,
  Snapshot,
}

private data class CardBackdrop(
  val image: ImageBitmap?,
  val viewportSize: IntSize,
  val viewportOrigin: Offset,
  val dim: Float,
)

private val LocalCardBackdrop = compositionLocalOf<CardBackdrop?> { null }

@Composable
fun AudioFlowScreen(
  state: DspUiState,
  appearance: AppearanceState,
  onToggle: (Boolean) -> Unit,
  onPreset: (String) -> Unit,
  onProfile: (String) -> Unit,
  onImportProfile: () -> Unit,
  onEqualizerChanged: (FloatArray, Float, Float, Boolean) -> Unit,
  onProtectionChanged: (Boolean) -> Unit,
  onNotificationDetailsChanged: (Boolean) -> Unit,
  onAccessibilityKeepAliveChanged: (Boolean) -> Unit,
  onPickIcon: () -> Unit,
  onPickBackground: () -> Unit,
  onBackgroundDimChanged: (Float) -> Unit,
  onBackgroundBlurChanged: (Float) -> Unit,
  onCardOpacityChanged: (Float) -> Unit,
  onCardBlurChanged: (Float) -> Unit,
  onUiFontScaleChanged: (Float) -> Unit,
  onLauncherIconSelected: (String) -> Unit,
  onResetBackground: () -> Unit,
  onResetAppearance: () -> Unit,
  onAutoAccentChanged: (Boolean) -> Unit,
  onPinShortcut: () -> Unit,
  onBypassChanged: (Boolean) -> Unit,
  onSaveSnapshot: (String) -> Unit,
  onLoadSnapshot: (Long) -> Unit,
  onDeleteSnapshot: (Long) -> Unit,
  onAppFilterChanged: (Boolean, Set<String>) -> Unit,
  onTestTone: () -> Unit,
) {
  val context = LocalContext.current
  val accentColor =
    remember(appearance.accentColorArgb, appearance.autoAccentEnabled) {
      Color(AppearanceManager.displayAccent(appearance))
    }
  SideEffect { Accent = accentColor }
  SideEffect { CardColor = DefaultCardColor.copy(alpha = appearance.cardOpacity) }
  SideEffect { CardBlur = appearance.cardBlur }
  val backgroundBitmap = remember(appearance.revision, appearance.customBackgroundPath) {
    AppearanceManager.loadBackground(appearance)
  }
  val backgroundImage = remember(backgroundBitmap) { backgroundBitmap?.asImageBitmap() }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var viewportOrigin by remember { mutableStateOf(Offset.Zero) }
  val cardBackdrop =
    remember(backgroundImage, viewportSize, viewportOrigin, appearance.backgroundDim) {
      CardBackdrop(backgroundImage, viewportSize, viewportOrigin, appearance.backgroundDim)
    }
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val navigateTo: (ScreenSection) -> Unit = { section ->
    val itemIndex =
      when (section) {
        ScreenSection.Appearance -> 3
        ScreenSection.Profile -> 4
        ScreenSection.Equalizer -> 6
        ScreenSection.AppFilter -> 7
        ScreenSection.Background -> 8
        ScreenSection.Snapshot -> 9
      }
    scope.launch { listState.animateScrollToItem(itemIndex) }
  }
  val draftGains = remember(state.gains) { mutableStateListOf<Float>().apply { addAll(state.gains) } }
  var draftPreamp by remember(state.manualPreampDb) { mutableStateOf(state.manualPreampDb) }
  var draftBass by remember(state.dynamicBass) { mutableStateOf(state.dynamicBass) }
  var nudgeStep by remember { mutableFloatStateOf(1f) }
  var showProfiles by remember { mutableStateOf(false) }
  var showApps by remember { mutableStateOf(false) }
  val apps = remember { loadLaunchableApps(context) }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .onSizeChanged { viewportSize = it }
        .onGloballyPositioned { viewportOrigin = it.positionInRoot() },
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize().background(
          Brush.verticalGradient(
            listOf(Color(0xFF080D09), Color(0xFF0D140F), Color(0xFF080B09)),
          ),
        ),
    )
    if (backgroundImage != null) {
      Image(
        bitmap = backgroundImage,
        contentDescription = "自定义背景",
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxSize().blur(
            appearance.backgroundBlur.dp,
            edgeTreatment = BlurredEdgeTreatment.Unbounded,
          ),
      )
      Box(
        modifier =
          Modifier.fillMaxSize().background(
            Brush.verticalGradient(
              listOf(
                Color.Black.copy(alpha = appearance.backgroundDim),
                Color(0xFF071008).copy(alpha = (appearance.backgroundDim + 0.06f).coerceAtMost(0.97f)),
                Color.Black.copy(alpha = (appearance.backgroundDim + 0.10f).coerceAtMost(0.98f)),
              ),
            ),
          ),
      )
    }
    CompositionLocalProvider(LocalCardBackdrop provides cardBackdrop) {
      LazyColumn(
        state = listState,
        modifier =
          Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
      item(key = "top-space") { Spacer(Modifier.height(10.dp)) }
      item(key = "header") { Header(state, appearance, navigateTo) }
      item(key = "master") { MasterCard(state, onToggle) }
      item(key = "appearance") {
        AppearanceCard(
          appearance = appearance,
          onPickIcon = onPickIcon,
          onPickBackground = onPickBackground,
          onBackgroundDimChanged = onBackgroundDimChanged,
          onBackgroundBlurChanged = onBackgroundBlurChanged,
          onCardOpacityChanged = onCardOpacityChanged,
          onCardBlurChanged = onCardBlurChanged,
          onUiFontScaleChanged = onUiFontScaleChanged,
          onLauncherIconSelected = onLauncherIconSelected,
          onResetBackground = onResetBackground,
          onReset = onResetAppearance,
          onAutoAccentChanged = onAutoAccentChanged,
          onPinShortcut = onPinShortcut,
        )
      }
      item(key = "profile") {
        ProfileCard(state, onSelect = { showProfiles = true }, onImport = onImportProfile)
      }
      item(key = "presets") { PresetRow(state.presetId, onPreset) }
      item(key = "equalizer") {
        EqualizerCard(
          state = state,
          gains = draftGains,
          preampDb = draftPreamp,
          dynamicBass = draftBass,
          protectionEnabled = state.protectionEnabled,
          nudgeStep = nudgeStep,
          onGainChanged = { index, value -> draftGains[index] = value },
          onPreampChanged = { draftPreamp = it },
          onBassChanged = { draftBass = it },
          onNudgeStepChanged = { nudgeStep = it },
          onNudge = { index, delta ->
            draftGains[index] = quantize((draftGains[index] + delta).coerceIn(-12f, 12f), nudgeStep)
            onEqualizerChanged(
              draftGains.toFloatArray(),
              draftPreamp,
              draftBass,
              state.protectionEnabled,
            )
          },
          onProtectionChanged = onProtectionChanged,
          onCommit = {
            onEqualizerChanged(
              draftGains.toFloatArray(),
              draftPreamp,
              draftBass,
              state.protectionEnabled,
            )
          },
        )
      }
      item(key = "app-filter") {
        AppFilterCard(
          state = state,
          apps = apps,
          onEnabled = { enabled ->
            onAppFilterChanged(enabled, state.selectedPackages)
            if (enabled && state.selectedPackages.isEmpty()) showApps = true
          },
          onSelect = { showApps = true },
        )
      }
      item(key = "background-notification") {
        BackgroundAndNotificationCard(
          state = state,
          onNotificationDetailsChanged = onNotificationDetailsChanged,
          onAccessibilityKeepAliveChanged = onAccessibilityKeepAliveChanged,
        )
      }
      item(key = "snapshots") {
        SnapshotCard(
          snapshots = state.snapshots,
          onSave = onSaveSnapshot,
          onLoad = onLoadSnapshot,
          onDelete = onDeleteSnapshot,
        )
      }
      item(key = "ab-compare") {
        AbCompareButton(state.bypassActive, onBypassChanged)
      }
      item(key = "test-tone") {
        Button(
          onClick = onTestTone,
          modifier = Modifier.fillMaxWidth().height(54.dp),
          shape = RoundedCornerShape(16.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = Color(0xFF223228),
              contentColor = Accent,
            ),
        ) {
          Text("试听 100 Hz → 10 kHz", fontWeight = FontWeight.SemiBold)
        }
      }
      state.lastImport?.let { importMessage ->
        item(key = "last-import") {
          Text(
            importMessage,
            color = Accent,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
          )
        }
      }
      item(key = "footer") {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
          Text(
            text = "免ROOT · 不录音 · 全局音效由 Android 音频服务处理",
            color = Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
          )
          Row {
            Text(
              "Powered By ",
              color = Muted,
              fontFamily = MeldrykBrandFont,
              fontWeight = FontWeight.SemiBold,
              fontSize = 12.sp,
              letterSpacing = 0.8.sp,
            )
            Text(
              "Meldryk",
              color = Accent,
              fontFamily = MeldrykBrandFont,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 13.sp,
              letterSpacing = 1.1.sp,
            )
          }
        }
      }
        item(key = "bottom-space") { Spacer(Modifier.height(18.dp)) }
      }
    }
  }

  if (showProfiles) {
    ProfileDialog(
      profiles = state.profiles,
      current = state.profileName,
      onDismiss = { showProfiles = false },
      onSelect = {
        showProfiles = false
        onProfile(it)
      },
    )
  }
  if (showApps) {
    AppPickerDialog(
      apps = apps,
      initialSelection = state.selectedPackages,
      onDismiss = { showApps = false },
      onConfirm = {
        showApps = false
        onAppFilterChanged(true, it)
      },
    )
  }
}

@Composable
private fun Header(
  state: DspUiState,
  appearance: AppearanceState,
  onNavigate: (ScreenSection) -> Unit,
) {
  val iconBitmap = remember(appearance.revision, appearance.customIconPath) {
    AppearanceManager.loadIcon(appearance)
  }
  var menuExpanded by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text("AUDIOFLOW", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
      Text("31 段全局音质增强", color = Muted, fontSize = 13.sp)
    }
    Box {
      Box(
        modifier =
          Modifier.size(48.dp)
            .background(if (state.effectActive) Accent.copy(alpha = 0.18f) else Color(0xFF202621), CircleShape)
            .border(1.dp, if (state.effectActive) Accent else Color(0xFF3A463C), CircleShape)
            .clickable { menuExpanded = true },
        contentAlignment = Alignment.Center,
      ) {
        if (iconBitmap != null) {
          Image(
            bitmap = iconBitmap.asImageBitmap(),
            contentDescription = "打开快速导航",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
          )
        } else {
          Text("EQ", color = if (state.effectActive) Accent else Muted, fontWeight = FontWeight.Black)
        }
        Box(
          modifier =
            Modifier.align(Alignment.BottomEnd)
              .padding(3.dp)
              .size(18.dp)
              .background(Accent, CircleShape)
              .padding(3.dp),
          contentAlignment = Alignment.Center,
        ) {
          RoundedChevron(
            pointsDown = true,
            color = Color(0xFF071008),
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.8f,
          )
        }
      }
      DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        listOf(
          ScreenSection.Appearance to "图标与自动配色",
          ScreenSection.Profile to "耳机 / 配置文件",
          ScreenSection.Equalizer to "31 段均衡器",
          ScreenSection.AppFilter to "独立应用音效",
          ScreenSection.Background to "后台与通知",
          ScreenSection.Snapshot to "配置快照",
        ).forEach { (section, label) ->
          DropdownMenuItem(
            text = { Text(label) },
            onClick = {
              menuExpanded = false
              onNavigate(section)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun GlassCard(
  modifier: Modifier = Modifier,
  shape: RoundedCornerShape = RoundedCornerShape(20.dp),
  content: @Composable () -> Unit,
) {
  var cardOrigin by remember { mutableStateOf(Offset.Zero) }
  val backdrop = LocalCardBackdrop.current
  Surface(
    color = Color.Transparent,
    shape = shape,
    modifier = modifier.onGloballyPositioned { cardOrigin = it.positionInRoot() },
  ) {
    Box {
      val image = backdrop?.image
      val viewportSize = backdrop?.viewportSize
      if (
        image != null &&
          viewportSize != null &&
          viewportSize.width > 0 &&
          viewportSize.height > 0
      ) {
        Canvas(
          Modifier
            .matchParentSize()
            .blur(CardBlur.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
        ) {
          val scale =
            max(
              viewportSize.width.toFloat() / image.width,
              viewportSize.height.toFloat() / image.height,
            )
          val destinationWidth = image.width * scale
          val destinationHeight = image.height * scale
          val viewportLeft = (viewportSize.width - destinationWidth) / 2f
          val viewportTop = (viewportSize.height - destinationHeight) / 2f
          val cardLeft = cardOrigin.x - backdrop.viewportOrigin.x
          val cardTop = cardOrigin.y - backdrop.viewportOrigin.y
          drawImage(
            image = image,
            dstOffset =
              IntOffset(
                (viewportLeft - cardLeft).roundToInt(),
                (viewportTop - cardTop).roundToInt(),
              ),
            dstSize =
              IntSize(
                destinationWidth.roundToInt(),
                destinationHeight.roundToInt(),
              ),
            filterQuality = FilterQuality.High,
          )
        }
        Box(
          Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = (backdrop.dim * 0.25f).coerceIn(0f, 0.28f))),
        )
      }
      Box(Modifier.matchParentSize().background(CardColor))
      val frost = (CardBlur / 32f).coerceIn(0f, 1f)
      if (frost > 0.01f) {
        Box(
          Modifier
            .matchParentSize()
            .background(
              Brush.verticalGradient(
                listOf(
                  Color.White.copy(alpha = 0.025f + frost * 0.045f),
                  Accent.copy(alpha = 0.012f + frost * 0.018f),
                  Color.Transparent,
                ),
              ),
            )
            .blur((CardBlur * 0.25f).dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
        )
      }
      content()
    }
  }
}

@Composable
private fun MasterCard(state: DspUiState, onToggle: (Boolean) -> Unit) {
  GlassCard(
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(24.dp)),
  ) {
    Row(
      modifier = Modifier.padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          if (state.effectActive) "全局效果运行中" else "启用全局效果",
          color = Color.White,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(state.status, color = if (state.supported == false) Color(0xFFFF8A80) else Muted, fontSize = 13.sp)
        Spacer(Modifier.height(9.dp))
        Text("输出 · ${state.outputName}", color = Muted, fontSize = 12.sp)
        if (state.effectId >= 0) {
          Text("Effect ID ${state.effectId}", color = Color(0xFF5F6E63), fontSize = 11.sp)
        }
      }
      Switch(
        checked = state.effectActive,
        onCheckedChange = onToggle,
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF071008),
            checkedTrackColor = Accent,
            uncheckedThumbColor = Muted,
            uncheckedTrackColor = Color(0xFF2A312B),
          ),
      )
    }
  }
}

@Composable
private fun AppearanceCard(
  appearance: AppearanceState,
  onPickIcon: () -> Unit,
  onPickBackground: () -> Unit,
  onBackgroundDimChanged: (Float) -> Unit,
  onBackgroundBlurChanged: (Float) -> Unit,
  onCardOpacityChanged: (Float) -> Unit,
  onCardBlurChanged: (Float) -> Unit,
  onUiFontScaleChanged: (Float) -> Unit,
  onLauncherIconSelected: (String) -> Unit,
  onResetBackground: () -> Unit,
  onReset: () -> Unit,
  onAutoAccentChanged: (Boolean) -> Unit,
  onPinShortcut: () -> Unit,
) {
  var collapsed by remember { mutableStateOf(false) }
  var backgroundDim by remember(appearance.backgroundDim) {
    mutableFloatStateOf(appearance.backgroundDim * 100f)
  }
  var backgroundBlur by remember(appearance.backgroundBlur) {
    mutableFloatStateOf(appearance.backgroundBlur)
  }
  var cardOpacity by remember(appearance.cardOpacity) {
    mutableFloatStateOf(appearance.cardOpacity * 100f)
  }
  var cardBlur by remember(appearance.cardBlur) {
    mutableFloatStateOf(appearance.cardBlur)
  }
  var uiFontScale by remember(appearance.uiFontScale) {
    mutableFloatStateOf(appearance.uiFontScale * 100f)
  }
  val iconBitmap = remember(appearance.revision, appearance.customIconPath) {
    AppearanceManager.loadIcon(appearance)
  }
  GlassCard(
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          modifier =
            Modifier.size(62.dp)
              .clip(RoundedCornerShape(18.dp))
              .background(Color(0xFF202A22))
              .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
          contentAlignment = Alignment.Center,
        ) {
          if (iconBitmap != null) {
            Image(
              bitmap = iconBitmap.asImageBitmap(),
              contentDescription = "自定义图标",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Text("EQ", color = Accent, fontWeight = FontWeight.Black, fontSize = 20.sp)
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text("图标与配色", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
          Text(
            if (appearance.customIconPath == null) "使用默认图标"
            else "已从图片提取主色",
            color = Muted,
            fontSize = 12.sp,
          )
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(horizontalAlignment = Alignment.End) {
            Text("跟随图标", color = Muted, fontSize = 10.sp)
            Switch(
              checked = appearance.autoAccentEnabled,
              onCheckedChange = onAutoAccentChanged,
              colors =
                SwitchDefaults.colors(
                  checkedThumbColor = Color(0xFF071008),
                  checkedTrackColor = Accent,
                  uncheckedThumbColor = Muted,
                  uncheckedTrackColor = Color(0xFF2A312B),
                ),
            )
          }
          CollapseButton(collapsed) { collapsed = !collapsed }
        }
      }
      if (!collapsed) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onPickIcon,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263229), contentColor = Accent),
        ) {
          Text("选择图片")
        }
        Button(
          onClick = onPickBackground,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263229), contentColor = Accent),
        ) {
          Text("选择背景")
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onPinShortcut,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263229), contentColor = Accent),
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        ) {
          Text("添加图标")
        }
        Button(
          onClick = onResetBackground,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263229), contentColor = Muted),
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        ) {
          Text("默认背景", maxLines = 1)
        }
        Button(
          onClick = onReset,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263229), contentColor = Muted),
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        ) {
          Text("全部重置", maxLines = 1)
        }
      }
      HorizontalDivider(color = CardBorder)
      Text("内置桌面图标 · 直接替换启动图标", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AppearanceManager.launcherIcons.forEach { option ->
          val selected = appearance.launcherIconId == option.id
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
              Modifier.width(64.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                  if (selected) Accent.copy(alpha = 0.18f)
                  else Color.Transparent,
                )
                .border(
                  1.dp,
                  if (selected) Accent else CardBorder,
                  RoundedCornerShape(15.dp),
                )
                .padding(5.dp),
          ) {
            Surface(
              onClick = { onLauncherIconSelected(option.id) },
              modifier = Modifier.size(52.dp),
              shape = RoundedCornerShape(14.dp),
              color = Color(option.backgroundArgb),
            ) {
              Image(
                painter = painterResource(option.foregroundRes),
                contentDescription = option.label,
                modifier = Modifier.padding(5.dp),
              )
            }
            Text(option.label, color = Muted, fontSize = 8.sp, maxLines = 1)
          }
        }
      }
      HorizontalDivider(color = CardBorder)
      ControlSlider(
        "卡片模糊",
        cardBlur,
        0f,
        32f,
        { cardBlur = it },
        { onCardBlurChanged(cardBlur) },
        "${cardBlur.roundToInt()}dp",
      )
      ControlSlider(
        "卡片不透明度",
        cardOpacity,
        35f,
        100f,
        { cardOpacity = it },
        { onCardOpacityChanged(cardOpacity / 100f) },
        "${cardOpacity.roundToInt()}%",
      )
      ControlSlider(
        "界面字体",
        uiFontScale,
        80f,
        130f,
        { uiFontScale = it },
        { onUiFontScaleChanged(uiFontScale / 100f) },
        "${uiFontScale.roundToInt()}%",
      )
      if (appearance.customBackgroundPath != null) {
        HorizontalDivider(color = CardBorder)
        ControlSlider(
          "背景暗度",
          backgroundDim,
          35f,
          95f,
          { backgroundDim = it },
          { onBackgroundDimChanged(backgroundDim / 100f) },
          "${backgroundDim.roundToInt()}%",
        )
        ControlSlider(
          "背景模糊",
          backgroundBlur,
          0f,
          30f,
          { backgroundBlur = it },
          { onBackgroundBlurChanged(backgroundBlur) },
          "${backgroundBlur.roundToInt()}dp",
        )
      }
      Text(
        "卡片会取对应位置的背景做局部模糊，再叠加透明染色层；文字和图标保持锐利，字体比例不受系统字体大小影响。",
        color = Muted,
        fontSize = 9.sp,
        lineHeight = 13.sp,
      )
      Text(
        "图标和背景分别选择；图片会自动提取主题色。Android 不允许普通 App 任意替换已安装图标，因此自定义图片会用于 App 内图标，并可创建带该图片的桌面快捷方式。",
        color = Muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
      )
      }
    }
  }
}

@Composable
private fun RoundedChevron(
  pointsDown: Boolean,
  color: Color,
  modifier: Modifier = Modifier,
  strokeWidth: Float = 2.2f,
) {
  Canvas(modifier = modifier) {
    val stroke = strokeWidth.dp.toPx()
    val startY = size.height * if (pointsDown) 0.32f else 0.68f
    val joinY = size.height * if (pointsDown) 0.68f else 0.32f
    val start = Offset(size.width * 0.18f, startY)
    val join = Offset(size.width * 0.5f, joinY)
    val end = Offset(size.width * 0.82f, startY)
    drawLine(color, start, join, stroke, cap = StrokeCap.Round)
    drawLine(color, join, end, stroke, cap = StrokeCap.Round)
  }
}

@Composable
private fun CollapseButton(collapsed: Boolean, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = Accent.copy(alpha = 0.11f),
    contentColor = Accent,
    modifier =
      Modifier.size(36.dp)
        .border(1.dp, Accent.copy(alpha = 0.16f), CircleShape),
  ) {
    Box(contentAlignment = Alignment.Center) {
      RoundedChevron(
        pointsDown = collapsed,
        color = Accent,
        modifier = Modifier.size(16.dp),
      )
    }
  }
}

@Composable
private fun ProfileCard(state: DspUiState, onSelect: () -> Unit, onImport: () -> Unit) {
  var collapsed by remember { mutableStateOf(false) }
  GlassCard(
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
          Text("耳机 / 配置文件", color = Color.White, fontWeight = FontWeight.Bold)
          Text(state.profileName, color = Accent, fontSize = 13.sp)
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = onSelect) { Text("选择配置") }
          CollapseButton(collapsed) { collapsed = !collapsed }
        }
      }
      if (!collapsed) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onImport,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(13.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF243229), contentColor = Accent),
        ) {
          Text("导入 FxSound .fac / AutoEQ")
        }
      }
      }
    }
  }
}

@Composable
private fun PresetRow(selected: String, onPreset: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Text("声音预设", color = Color.White, fontWeight = FontWeight.Bold)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      GlobalDspEngine.PRESETS.forEach { preset ->
        val active = selected == preset.id
        Surface(
          color = if (active) Accent else CardColor,
          contentColor = if (active) Color(0xFF071008) else Color.White,
          shape = RoundedCornerShape(13.dp),
          modifier = Modifier.border(1.dp, if (active) Accent else CardBorder, RoundedCornerShape(13.dp)),
          onClick = { onPreset(preset.id) },
        ) {
          Text(
            preset.label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun EqualizerCard(
  state: DspUiState,
  gains: List<Float>,
  preampDb: Float,
  dynamicBass: Float,
  protectionEnabled: Boolean,
  nudgeStep: Float,
  onGainChanged: (Int, Float) -> Unit,
  onPreampChanged: (Float) -> Unit,
  onBassChanged: (Float) -> Unit,
  onNudgeStepChanged: (Float) -> Unit,
  onNudge: (Int, Float) -> Unit,
  onProtectionChanged: (Boolean) -> Unit,
  onCommit: () -> Unit,
) {
  var collapsed by remember { mutableStateOf(false) }
  GlassCard(
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(24.dp)),
  ) {
    Column(modifier = Modifier.padding(vertical = 18.dp, horizontal = 14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("31 段均衡器", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          Text("拖动频段 · 松手后应用", color = Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(horizontalAlignment = Alignment.End) {
            Text("防削波保护", color = Color.White, fontSize = 12.sp)
            Text(
              if (protectionEnabled) "自动 ${formatDb(state.effectivePreampDb)} dB" else "已关闭",
              color = if (protectionEnabled) Accent else Muted,
              fontSize = 10.sp,
            )
          }
          Switch(
            checked = protectionEnabled,
            onCheckedChange = onProtectionChanged,
            modifier = Modifier.padding(start = 6.dp),
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF071008),
                checkedTrackColor = Accent,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Color(0xFF2A312B),
              ),
          )
          CollapseButton(collapsed) { collapsed = !collapsed }
        }
      }
      if (!collapsed) {
      Text(
        if (protectionEnabled) {
          "自动预留峰值余量并使用 Limiter，避免大幅 EQ 提升造成破音；手动前级仍会叠加。"
        } else {
          "保护关闭后不再自动降低前级，也不会启用 Limiter；大幅提升时可能破音。"
        },
        color = Muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
      )
      Row(
        modifier = Modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        Text("加减步进", color = Muted, fontSize = 11.sp)
        listOf(0.1f, 0.5f, 1f, 2f).forEach { step ->
          StepChip(
            label = if (step < 1f) step.toString() else step.toInt().toString(),
            selected = nudgeStep == step,
            onClick = { onNudgeStepChanged(step) },
          )
        }
      }
      Spacer(Modifier.height(16.dp))
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        GlobalDspEngine.FREQUENCIES.forEachIndexed { index, frequency ->
          VerticalEqBand(
            frequency = frequency,
            gain = gains.getOrElse(index) { 0f },
            step = nudgeStep,
            onValueChange = { onGainChanged(index, it) },
            onValueChangeFinished = onCommit,
            onNudge = { delta -> onNudge(index, delta) },
          )
        }
      }
      Spacer(Modifier.height(14.dp))
      HorizontalDivider(color = CardBorder)
      ControlSlider("前级增益", preampDb, -12f, 6f, onPreampChanged, onCommit, formatDb(preampDb))
      ControlSlider(
        "动态低音",
        dynamicBass,
        0f,
        100f,
        onBassChanged,
        onCommit,
        "${dynamicBass.roundToInt()}%",
      )
      Text(
        "前级增益是进入 EQ 前的整体音量：0 dB 为原始输入，正数更响但更容易破音，负数更安静并留出余量。动态低音会在 20–315 Hz 增加随低频能量变化的低频增强。",
        color = Muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(horizontal = 6.dp),
      )
      state.lastError?.let {
        Text("错误：$it", color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
      }
      }
    }
  }
}

@Composable
private fun StepChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    color = if (selected) Accent else Color(0xFF202A22),
    contentColor = if (selected) Color(0xFF071008) else Color.White,
    shape = RoundedCornerShape(9.dp),
  ) {
    Text(
      label,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun ControlSlider(
  label: String,
  value: Float,
  min: Float,
  max: Float,
  onValueChange: (Float) -> Unit,
  onFinished: () -> Unit,
  valueLabel: String,
) {
  Row(
    modifier = Modifier.padding(horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      color = Muted,
      fontSize = 12.sp,
      maxLines = 1,
      modifier = Modifier.width(98.dp),
    )
    Slider(
      value = value,
      onValueChange = onValueChange,
      onValueChangeFinished = onFinished,
      valueRange = min..max,
      modifier = Modifier.weight(1f),
    )
    Text(
      valueLabel,
      color = Color.White,
      fontSize = 12.sp,
      maxLines = 1,
      modifier = Modifier.width(64.dp),
      textAlign = TextAlign.End,
    )
  }
}

@Composable
private fun VerticalEqBand(
  frequency: Float,
  gain: Float,
  step: Float,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  onNudge: (Float) -> Unit,
) {
  val currentGain by rememberUpdatedState(gain)
  val changeGain by rememberUpdatedState(onValueChange)
  val finishGain by rememberUpdatedState(onValueChangeFinished)
  val currentStep by rememberUpdatedState(step)

  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(27.dp)) {
    Text(formatDb(gain), color = if (gain == 0f) Muted else Accent, fontSize = 9.sp)
    Surface(
      onClick = { onNudge(step) },
      shape = CircleShape,
      color = Color(0xFF263229),
      contentColor = Accent,
      modifier = Modifier.size(21.dp),
    ) {
      Box(contentAlignment = Alignment.Center) { Text("+", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
    Box(
      modifier =
        Modifier.width(27.dp)
          .height(190.dp)
          .pointerInput(Unit) {
            detectDragGestures(
              onDragStart = { offset ->
                val ratio = (1f - offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                changeGain(quantize(ratio * 24f - 12f, currentStep))
              },
              onDrag = { change, _ ->
                change.consume()
                val ratio = (1f - change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                changeGain(quantize(ratio * 24f - 12f, currentStep))
              },
              onDragEnd = finishGain,
              onDragCancel = finishGain,
            )
          },
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val x = size.width / 2f
        val knobY = (12f - currentGain) / 24f * size.height
        drawLine(Color(0xFF344139), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        drawLine(Color(0xFF75F77B), Offset(x, size.height / 2f), Offset(x, knobY), 2.dp.toPx())
        drawCircle(Color(0xFF080D09), 6.dp.toPx(), Offset(x, knobY))
        drawCircle(Accent, 4.dp.toPx(), Offset(x, knobY))
      }
    }
    Surface(
      onClick = { onNudge(-step) },
      shape = CircleShape,
      color = Color(0xFF263229),
      contentColor = Accent,
      modifier = Modifier.size(21.dp),
    ) {
      Box(contentAlignment = Alignment.Center) { Text("−", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
    Text(formatFrequency(frequency), color = Muted, fontSize = 8.sp, maxLines = 1)
  }
}

@Composable
private fun AppFilterCard(
  state: DspUiState,
  apps: List<AppChoice>,
  onEnabled: (Boolean) -> Unit,
  onSelect: () -> Unit,
) {
  var collapsed by remember { mutableStateOf(false) }
  val selectedNames =
    apps.filter { it.packageName in state.selectedPackages }.map { it.label }
  GlassCard(
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("独立应用音效", color = Color.White, fontWeight = FontWeight.Bold)
          Text(
            if (!state.appFilterEnabled) "当前：所有应用"
            else if (state.selectedPackages.isEmpty()) "尚未选择应用"
            else "仅在所选应用播放时启用",
            color = Muted,
            fontSize = 12.sp,
          )
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Switch(
            checked = state.appFilterEnabled,
            onCheckedChange = onEnabled,
            colors =
              SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF071008),
                checkedTrackColor = Accent,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Color(0xFF2A312B),
              ),
          )
          CollapseButton(collapsed) { collapsed = !collapsed }
        }
      }
      if (!collapsed && state.appFilterEnabled) {
        Text(
          if (selectedNames.isEmpty()) "未选择应用"
          else selectedNames.joinToString(" · ").take(120),
          color = Accent,
          fontSize = 12.sp,
        )
        TextButton(onClick = onSelect) { Text("选择允许使用音效的应用") }
        Text(
          "Android 全局混音限制：所选应用单独播放时是独立处理；若其他应用同时出声，也会经过同一个全局效果。",
          color = Muted,
          fontSize = 10.sp,
          lineHeight = 14.sp,
        )
      }
    }
  }
}

@Composable
private fun BackgroundAndNotificationCard(
  state: DspUiState,
  onNotificationDetailsChanged: (Boolean) -> Unit,
  onAccessibilityKeepAliveChanged: (Boolean) -> Unit,
) {
  var collapsed by remember { mutableStateOf(false) }
  GlassCard(
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("后台与通知", color = Color.White, fontWeight = FontWeight.Bold)
        CollapseButton(collapsed) { collapsed = !collapsed }
      }
      if (!collapsed) {
      SettingSwitchRow(
        title = "通知栏详细状态",
        subtitle =
          if (state.detailedNotification) "显示输出设备和运行状态"
          else "仅显示系统要求的最简通知",
        checked = state.detailedNotification,
        onCheckedChange = onNotificationDetailsChanged,
      )
      HorizontalDivider(color = CardBorder)
      SettingSwitchRow(
        title = "无通知保活（无障碍）",
        subtitle =
          when {
            !state.accessibilityKeepAlive -> "关闭；当前使用带通知的前台服务"
            state.accessibilityConnected -> "无障碍服务已连接，效果器无需前台通知"
            else -> "等待系统无障碍授权"
          },
        checked = state.accessibilityKeepAlive,
        onCheckedChange = onAccessibilityKeepAliveChanged,
      )
      Text(
        "普通 Android App 不允许完全隐藏运行通知。无通知模式需要一次无障碍授权，不读取屏幕内容；仅建议个人测试使用。",
        color = Muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
      )
      }
    }
  }
}

@Composable
private fun SettingSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
      Text(subtitle, color = Muted, fontSize = 11.sp)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = Color(0xFF071008),
          checkedTrackColor = Accent,
          uncheckedThumbColor = Muted,
          uncheckedTrackColor = Color(0xFF2A312B),
        ),
    )
  }
}

@Composable
private fun AbCompareButton(active: Boolean, onBypassChanged: (Boolean) -> Unit) {
  val latestCallback by rememberUpdatedState(onBypassChanged)
  Surface(
    color = if (active) Color(0xFF4A241F) else Color(0xFF19231B),
    contentColor = if (active) Color(0xFFFFB4A9) else Accent,
    shape = RoundedCornerShape(16.dp),
    modifier =
      Modifier.fillMaxWidth()
        .height(58.dp)
        .border(1.dp, if (active) Color(0xFFB85D50) else CardBorder, RoundedCornerShape(16.dp))
        .pointerInput(Unit) {
          detectTapGestures(
            onPress = {
              latestCallback(true)
              try {
                tryAwaitRelease()
              } finally {
                latestCallback(false)
              }
            },
          )
        },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        if (active) "正在听原声 · 松开恢复音效" else "按住 A/B · 听原声",
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun SnapshotCard(
  snapshots: List<ConfigSnapshot>,
  onSave: (String) -> Unit,
  onLoad: (Long) -> Unit,
  onDelete: (Long) -> Unit,
) {
  var collapsed by remember { mutableStateOf(false) }
  var showDialog by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  GlassCard(
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("配置快照", color = Color.White, fontWeight = FontWeight.Bold)
          Text(
            if (collapsed) "${snapshots.size} 个快照" else "保存、恢复或删除当前声音",
            color = Muted,
            fontSize = 11.sp,
          )
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = { showDialog = true }) { Text("保存当前") }
          CollapseButton(collapsed) { collapsed = !collapsed }
        }
      }
      if (!collapsed) {
      if (snapshots.isEmpty()) {
        Text("还没有快照。调好声音后保存一个，随时可以恢复。", color = Muted, fontSize = 11.sp)
      } else {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          snapshots.forEach { snapshot ->
            Surface(
              color = Color(0xFF202A22),
              shape = RoundedCornerShape(12.dp),
            ) {
              Row(
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column {
                  Text(snapshot.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                  Text(
                    "低音 ${snapshot.dynamicBass.roundToInt()}% · ${if (snapshot.protectionEnabled) "保护开" else "保护关"}",
                    color = Muted,
                    fontSize = 9.sp,
                  )
                }
                TextButton(onClick = { onLoad(snapshot.id) }) {
                  Text("载入", color = Accent, fontSize = 10.sp)
                }
                TextButton(onClick = { onDelete(snapshot.id) }) {
                  Text("×", color = Muted, fontSize = 18.sp)
                }
              }
            }
          }
        }
      }
      }
    }
  }
  if (showDialog) {
    AlertDialog(
      onDismissRequest = { showDialog = false },
      title = { Text("保存配置快照") },
      text = {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it.take(60) },
          label = { Text("名称（留空自动命名）") },
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDialog = false
            onSave(name)
            name = ""
          },
        ) { Text("保存") }
      },
      dismissButton = {
        TextButton(onClick = { showDialog = false }) { Text("取消") }
      },
    )
  }
}

@Composable
private fun ProfileDialog(
  profiles: List<SavedProfile>,
  current: String,
  onDismiss: () -> Unit,
  onSelect: (String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("耳机型号 / 配置") },
    text = {
      LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
        items(profiles, key = { it.name }) { profile ->
          Surface(
            onClick = { onSelect(profile.name) },
            color = if (profile.name == current) Color(0xFF173A20) else Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
              Text(profile.name, fontWeight = FontWeight.SemiBold)
              Text(profile.sourceFormat, fontSize = 11.sp, color = Muted)
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
  )
}

@Composable
private fun AppPickerDialog(
  apps: List<AppChoice>,
  initialSelection: Set<String>,
  onDismiss: () -> Unit,
  onConfirm: (Set<String>) -> Unit,
) {
  val selected = remember(initialSelection) { mutableStateListOf<String>().apply { addAll(initialSelection) } }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("选择应用") },
    text = {
      LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
        items(apps, key = { it.packageName }) { app ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = app.packageName in selected,
              onCheckedChange = { checked ->
                if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
              },
            )
            Column {
              Text(app.label, fontWeight = FontWeight.Medium)
              Text(app.packageName, fontSize = 10.sp, color = Muted)
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(selected.toSet()) }) { Text("应用") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

private fun loadLaunchableApps(context: Context): List<AppChoice> {
  val launcherIntent =
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val packageManager = context.packageManager
  val resolved =
    packageManager.queryIntentActivities(
      launcherIntent,
      PackageManager.MATCH_ALL,
    )
  return resolved
    .map {
      AppChoice(
        label = it.loadLabel(packageManager).toString(),
        packageName = it.activityInfo.packageName,
      )
    }
    .filter { it.packageName != context.packageName }
    .distinctBy { it.packageName }
    .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun formatDb(value: Float): String {
  val rounded = (value * 10f).roundToInt() / 10f
  val magnitude =
    if (abs(rounded - rounded.roundToInt()) < 0.01f) abs(rounded).roundToInt().toString()
    else String.format(Locale.US, "%.1f", abs(rounded))
  return when {
    rounded > 0f -> "+$magnitude"
    rounded < 0f -> "-$magnitude"
    else -> "0"
  }
}

private fun formatFrequency(frequency: Float): String {
  if (frequency < 1_000f) return frequency.roundToInt().toString()
  val thousands = frequency / 1_000f
  val text =
    if (floor(thousands) == thousands) thousands.toInt().toString()
    else String.format(Locale.US, "%.2f", thousands).trimEnd('0').trimEnd('.')
  return "${text}k"
}

private fun quantize(value: Float, step: Float): Float {
  if (step <= 0f) return value
  return ((value / step).roundToInt() * step).coerceIn(-12f, 12f)
}
