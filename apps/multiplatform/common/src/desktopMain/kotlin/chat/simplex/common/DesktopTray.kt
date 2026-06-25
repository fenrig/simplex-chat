package chat.simplex.common

import SectionItemView
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.*
import chat.simplex.common.model.ChatModel
import chat.simplex.common.model.CloseBehavior
import chat.simplex.common.model.ChatController.appPrefs
import chat.simplex.common.views.helpers.AlertManager
import chat.simplex.common.views.helpers.generalGetString
import chat.simplex.res.MR
import chat.simplex.common.ui.theme.isInDarkTheme
import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.Separator
import dorkbox.systemTray.SystemTray as DorkboxSystemTray
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import java.awt.GraphicsEnvironment

private const val trayAppName = "SimpleX Chat"

val trayIsAvailable: Boolean by lazy {
  !GraphicsEnvironment.isHeadless()
}

@Composable
fun ApplicationScope.SimplexTray() {
  if (!trayIsAvailable) return
  if (!singleInstanceLock) return
  // Sum of per-profile unread (UserInfo.unreadCount, the same field UserPicker renders
  // per row). Skip muted profiles unless they're the active one.
  val unread by remember {
    derivedStateOf {
      ChatModel.users.sumOf {
        if (!it.user.showNtfs && !it.user.activeUser) 0 else it.unreadCount
      }
    }
  }
  val tooltip =
    if (unread > 0) stringResource(MR.strings.tray_tooltip_unread, unread)
    else stringResource(MR.strings.tray_tooltip)
  val windowVisible by remember { simplexWindowState.windowVisible }
  val showLabel = "Hide/Show SimpleX"
  val quitLabel = stringResource(MR.strings.tray_quit)
  val exit = { exitApplication() }
  val iconRes = if (unread > 0) {
    if (isInDarkTheme()) MR.images.ic_simplex_tray_dot_light else MR.images.ic_simplex_tray_dot
  } else {
    if (isInDarkTheme()) MR.images.ic_simplex_tray_light else MR.images.ic_simplex
  }
  val iconPainter = painterResource(iconRes)
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val tray = remember { DorkboxSystemTray.get(trayAppName) } ?: return
  val trayMenu = remember { tray.getMenu() }
  val separator = remember { Separator() }
  val quitItem = remember { MenuItem(quitLabel) { exit() } }
  val toggleItem = remember { mutableStateOf<MenuItem?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      tray.remove()
    }
  }

  LaunchedEffect(Unit) {
    trayMenu.add(separator)
    trayMenu.add(quitItem)
  }

  SideEffect {
    tray.setImage(iconPainter.trayImage(density, layoutDirection))
    tray.setTooltip(tooltip)
    quitItem.text = quitLabel
    toggleItem.value?.let { trayMenu.remove(it) }
    val newToggle = MenuItem(showLabel) {
      if (windowVisible) hideWindow() else showWindow()
    }
    trayMenu.add(newToggle, 0)
    toggleItem.value = newToggle
  }
}

private fun Painter.trayImage(density: Density, layoutDirection: LayoutDirection) =
  toAwtImage(density, layoutDirection, Size(22f, 22f))

// Renders in the main app window via AlertManager (same surface as e.g. the link
// previews confirmation). Lambdas close over the calling ApplicationScope; if the
// app crashes while the dialog is open, the crash handler's alert replaces it, so
// stale closures never get clicked.
fun ApplicationScope.requestCloseBehavior() {
  val pref = appPrefs.closeBehavior
  AlertManager.shared.showAlertDialogButtonsColumn(
    title = generalGetString(MR.strings.close_behavior_dialog_title),
    text = AnnotatedString(generalGetString(MR.strings.close_behavior_dialog_text)),
    buttons = {
      Column {
        SectionItemView({
          AlertManager.shared.hideAlert()
          pref.set(CloseBehavior.Quit)
          exitApplication()
        }) {
          Text(
            stringResource(MR.strings.close_behavior_dialog_close),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = Color.Red
          )
        }
        SectionItemView({
          AlertManager.shared.hideAlert()
          pref.set(CloseBehavior.MinimizeToTray)
          hideWindow()
        }) {
          Text(
            stringResource(MR.strings.close_behavior_dialog_minimize),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary
          )
        }
      }
    }
  )
}
