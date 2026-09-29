package com.plexus.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal const val ACTION_OPEN_CONVERSATION = "com.plexus.app.action.OPEN_CONVERSATION"
internal const val EXTRA_CONVERSATION_CONTACT_ID = "com.plexus.app.extra.CONVERSATION_CONTACT_ID"

internal fun shouldNotifyIncomingMessage(
    visibleContactNodeId: String?,
    senderNodeId: String
): Boolean = visibleContactNodeId != senderNodeId

internal fun canPostIncomingMessage(
    notificationPermissionGranted: Boolean,
    visibleContactNodeId: String?,
    senderNodeId: String
): Boolean = notificationPermissionGranted && shouldNotifyIncomingMessage(
    visibleContactNodeId, senderNodeId
)

internal fun conversationNotificationTag(contactNodeId: String): String = contactNodeId

internal fun conversationContactId(action: String?, contactNodeId: String?): String? =
    contactNodeId?.takeIf { action == ACTION_OPEN_CONVERSATION && it.isNotBlank() }

internal fun conversationContactIdFromIntent(intent: Intent?): String? =
    conversationContactId(intent?.action, intent?.getStringExtra(EXTRA_CONVERSATION_CONTACT_ID))

internal class IncomingMessageNotifier(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "plexus_messages"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_HISTORY = 5
    }

    fun notifyConversation(
        contact: ContactEntity,
        messages: List<MessageEntity>,
        visibleContactNodeId: String?
    ) {
        val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!canPostIncomingMessage(
                notificationPermissionGranted, visibleContactNodeId, contact.nodeId
            )) return

        ensureChannel()
        val person = androidx.core.app.Person.Builder().setName(contact.displayName).build()
        val me = androidx.core.app.Person.Builder().setName(context.getString(R.string.app_name)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(contact.displayName)
            .setGroupConversation(false)
        messages.takeLast(MAX_HISTORY).forEach { message ->
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message.text,
                    message.timestamp,
                    if (message.isMine) me else person
                )
            )
        }
        val openIntent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION)
            .putExtra(EXTRA_CONVERSATION_CONTACT_ID, contact.nodeId)
            .setData(android.net.Uri.parse("plexus://conversation/${android.net.Uri.encode(contact.nodeId)}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plexus_service)
            .setStyle(style)
            .setContentTitle(contact.displayName)
            .setContentText(messages.lastOrNull()?.text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                conversationNotificationTag(contact.nodeId), NOTIFICATION_ID, notification
            )
        } catch (exception: SecurityException) {
            Log.w("PlexusNotify", "Incoming notification unavailable", exception)
        }
    }

    fun cancelConversation(contactNodeId: String) {
        NotificationManagerCompat.from(context).cancel(
            conversationNotificationTag(contactNodeId), NOTIFICATION_ID
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.message_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
