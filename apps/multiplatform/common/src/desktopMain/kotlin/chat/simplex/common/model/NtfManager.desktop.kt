package chat.simplex.common.model

import androidx.compose.ui.graphics.*
import chat.simplex.common.platform.*
import chat.simplex.common.views.call.CallMediaType
import chat.simplex.common.views.call.RcvCallInvitation
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import com.sshtools.twoslices.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.*
import java.awt.TrayIcon.MessageType
import java.io.File
import javax.imageio.ImageIO

object NtfManager {
  private val prevNtfs = arrayListOf<Pair<Pair<Long, ChatId>, Slice>>()
  private val prevNtfsMutex: Mutex = Mutex()
  private const val desktopAppName = "SimpleX Chat"
  private const val desktopIconName = "simplex"
  private const val desktopIconPath = "/opt/simplex/lib/simplex.png"
  private const val desktopEntryId = "chat.simplex.simplex"
  private enum class LinuxNotificationUrgency(val value: String) {
    Low("low"),
    Normal("normal"),
    Critical("critical")
  }

  fun notifyCallInvitation(invitation: RcvCallInvitation): Boolean {
    val contactId = invitation.contact.id
    Log.d(TAG, "notifyCallInvitation $contactId")
    val image = invitation.contact.image
    val text = generalGetString(
      if (invitation.callType.media == CallMediaType.Video) {
        if (invitation.sharedKey == null) MR.strings.video_call_no_encryption else MR.strings.encrypted_video_call
      } else {
        if (invitation.sharedKey == null) MR.strings.audio_call_no_encryption else MR.strings.encrypted_audio_call
      }
    )
    val previewMode = appPreferences.notificationPreviewMode.get()
    val title = if (previewMode == NotificationPreviewMode.HIDDEN.name)
      generalGetString(MR.strings.notification_preview_somebody)
    else
      invitation.contact.displayName
    val largeIcon = if (image == null || previewMode == NotificationPreviewMode.HIDDEN.name)
      MR.images.icon_foreground_common.image.toComposeImageBitmap()
    else
      base64ToBitmap(image)

    val actions = listOf(
      generalGetString(MR.strings.accept) to { ntfManager.acceptCallAction(invitation.contact.id) },
      generalGetString(MR.strings.reject) to { ChatModel.callManager.endCall(invitation = invitation) }
    )
    displayNotificationViaLib(invitation.user.userId, contactId, title, text, prepareIconPath(largeIcon), actions, "call.incoming", LinuxNotificationUrgency.Critical) {
      ntfManager.openChatAction(invitation.user.userId, contactId)
    }
    return true
  }

  fun showMessage(title: String, text: String) {
    displayNotificationViaLib(-1, "MESSAGE", title, text, null, emptyList(), "im.received", LinuxNotificationUrgency.Normal) {}
  }

  fun hasNotificationsForChat(chatId: ChatId) = false//prevNtfs.any { it.first == chatId }

  fun cancelNotificationsForChat(chatId: ChatId) {
    withBGApi {
      prevNtfsMutex.withLock {
        val ntf = prevNtfs.firstOrNull { (userChat) -> userChat.second == chatId }
        if (ntf != null) {
          prevNtfs.remove(ntf)
          /*try {
            ntf.second.close()
          } catch (e: Exception) {
            // Can be java.lang.UnsupportedOperationException, for example. May do nothing
            Log.e(TAG, "Failed to close notification: ${e.stackTraceToString()}")
          }*/
        }
      }
    }
  }

  fun cancelNotificationsForUser(userId: Long) {
    withBGApi {
      prevNtfsMutex.withLock {
        prevNtfs.filter { (userChat) -> userChat.first == userId }.forEach {
          prevNtfs.remove(it)
        }
      }
    }
  }

  fun cancelAllNotifications() {
//    prevNtfs.forEach { try { it.second.close() } catch (e: Exception) { Log.e(TAG, "Failed to close notification: ${e
    //    .stackTraceToString()}") } }
    withBGApi {
      prevNtfsMutex.withLock {
        prevNtfs.clear()
      }
    }
  }

  fun displayNotification(user: UserLike, chatId: String, displayName: String, msgText: String, image: String?, actions: List<Pair<NotificationAction, () -> Unit>>) {
    if (!user.showNotifications) return
    Log.d(TAG, "notifyMessageReceived $chatId")
    val previewMode = appPreferences.notificationPreviewMode.get()
    val title = if (previewMode == NotificationPreviewMode.HIDDEN.name) generalGetString(MR.strings.notification_preview_somebody) else displayName
    val content = if (previewMode != NotificationPreviewMode.MESSAGE.name) generalGetString(MR.strings.notification_preview_new_message) else msgText
    val largeIcon = when {
      actions.isEmpty() -> null
      image == null || previewMode == NotificationPreviewMode.HIDDEN.name -> MR.images.icon_foreground_common.image.toComposeImageBitmap()
      else -> base64ToBitmap(image)
    }

    displayNotificationViaLib(user.userId, chatId, title, content, prepareIconPath(largeIcon), actions.map { it.first.name to it.second }, "im.received", LinuxNotificationUrgency.Normal) {
      ntfManager.openChatAction(user.userId, chatId)
    }
  }

  private fun displayNotificationViaLib(
    userId: Long,
    chatId: String,
    title: String,
    text: String,
    iconPath: String?,
    actions: List<Pair<String, () -> Unit>>,
    linuxCategory: String,
    linuxUrgency: LinuxNotificationUrgency,
    defaultAction: (() -> Unit)?
  ) {
    if (desktopPlatform.isLinux()) {
      displayLinuxNotification(title, text, iconPath, actions, linuxCategory, linuxUrgency, defaultAction)
      return
    }

    val builder = Toast.builder()
      .title(title)
      .content(text)
    if (iconPath != null) {
      builder.icon(iconPath)
    }
    if (defaultAction != null) {
      builder.defaultAction(defaultAction)
    }
    actions.forEach {
      builder.action(it.first, it.second)
    }
    try {
      withBGApi {
        prevNtfsMutex.withLock {
          prevNtfs.add(Pair(userId, chatId) to builder.toast())
        }
      }
    } catch (e: Throwable) {
      Log.e(TAG, e.stackTraceToString())
      if (e !is Exception) {
        val text = e.stackTraceToString().lines().getOrNull(0) ?: ""
        showToast(generalGetString(MR.strings.error_showing_desktop_notification) + " " + text, 4_000)
      }
    }
  }

  private fun displayLinuxNotification(
    title: String,
    text: String,
    iconPath: String?,
    actions: List<Pair<String, () -> Unit>>,
    category: String,
    urgency: LinuxNotificationUrgency,
    defaultAction: (() -> Unit)?
  ) {
    Thread {
      try {
        val command = mutableListOf(
          "notify-send",
          "--app-name", desktopAppName,
          "--app-icon", desktopIconName,
          "--icon", iconPath ?: desktopIconPath,
          "--category", category,
          "--urgency", urgency.value,
          "--hint", "string:desktop-entry:$desktopEntryId"
        )
        actions.forEachIndexed { index, action ->
          command.add("--action")
          command.add("action$index=${action.first}")
        }
        if (actions.isNotEmpty()) {
          command.add("--wait")
        }
        command.add(title)
        command.add(text)

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (actions.isEmpty()) {
          return@Thread
        }
        val selectedAction = if (actions.isNotEmpty()) {
          process.inputStream.bufferedReader().readText().trim()
        } else {
          ""
        }
        process.waitFor()
        when {
          selectedAction == "default" -> defaultAction?.invoke()
          selectedAction.startsWith("action") -> selectedAction.removePrefix("action").toIntOrNull()?.let { actions.getOrNull(it)?.second?.invoke() }
        }
      } catch (e: Throwable) {
        Log.e(TAG, e.stackTraceToString())
        if (e !is Exception) {
          val errText = e.stackTraceToString().lines().getOrNull(0) ?: ""
          showToast(generalGetString(MR.strings.error_showing_desktop_notification) + " " + errText, 4_000)
        }
      }
    }.apply {
      name = "simplex-linux-notification"
      isDaemon = true
      start()
    }
  }

  private fun prepareIconPath(icon: ImageBitmap?): String? = if (icon != null) {
    tmpDir.mkdir()
    val newFile = File(tmpDir.absolutePath + File.separator + generateNewFileName("IMG", "png", tmpDir))
    try {
      ImageIO.write(icon.toAwtImage(), "PNG", newFile.outputStream())
      newFile.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write an icon to tmpDir: ${e.stackTraceToString()}")
      null
    }
  } else null

  private fun displayNotification(title: String, text: String, icon: ImageBitmap?) = when (desktopPlatform) {
    DesktopPlatform.LINUX_X86_64, DesktopPlatform.LINUX_AARCH64 -> linuxDisplayNotification(title, text, prepareIconPath(icon))
    DesktopPlatform.WINDOWS_X86_64 -> windowsDisplayNotification(title, text, icon)
    DesktopPlatform.MAC_X86_64, DesktopPlatform.MAC_AARCH64 -> macDisplayNotification(title, text, prepareIconPath(icon))
  }

  private fun linuxDisplayNotification(title: String, text: String, iconPath: String?) {
    if (iconPath != null) {
      Runtime.getRuntime().exec(arrayOf("notify-send", "-i", iconPath, title, text))
    } else {
      Toast.toast(ToastType.INFO, title, text)
      Runtime.getRuntime().exec(arrayOf("notify-send", title, text))
    }
  }

  private fun windowsDisplayNotification(title: String, text: String, icon: ImageBitmap?) {
    if (SystemTray.isSupported()) {
      val tray = SystemTray.getSystemTray()
      tray.remove(tray.trayIcons.firstOrNull { it.toolTip == "SimpleX" })
      val trayIcon = TrayIcon(icon?.toAwtImage(), "SimpleX")
      trayIcon.isImageAutoSize = true
      tray.add(trayIcon)
      trayIcon.displayMessage(title, text, MessageType.INFO)
    } else {
      Log.e(TAG, "System tray not supported!")
    }
  }

  private fun macDisplayNotification(title: String, text: String, iconPath: String?) {
    Runtime.getRuntime().exec(arrayOf("osascript", "-e", """display notification "${text.replace("\"", "\\\"")}" with title "${title.replace("\"", "\\\"")}""""))
  }
}
