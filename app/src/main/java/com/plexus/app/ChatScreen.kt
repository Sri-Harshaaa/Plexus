package com.plexus.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


data class ChatMessage(

    val id: String,

    val text: String,

    val isMine: Boolean,

    val timestamp: Long,

    val deliveryStatus: String,

    val transportType: String,

    val replyToMessageId: String?
)


private enum class PlexusChatConfirmAction {
    CLEAR_CHAT,
    DELETE_CHAT
}


private val PlexusBackground =
    Color(0xFF050505)

private val PlexusSurface =
    Color(0xFF0B0E0C)

private val PlexusSurfaceLight =
    Color(0xFF151816)

private val PlexusGreen =
    Color(0xFF4DFF8B)

private val PlexusSecondary =
    Color(0xFF949A96)

private val PlexusDivider =
    Color(0xFF172219)


@Composable
fun PlexusChatScreen(

    contact: ContactEntity,

    messages: List<ChatMessage>,

    onBack: () -> Unit,

    onSendMessage: (
        text: String,
        replyToMessageId: String?,
        onPersisted: (Boolean) -> Unit
    ) -> Unit,

    onDeleteMessage: (String) -> Unit,

    onClearChat: () -> Unit,

    onDeleteChat: () -> Unit,

    onEditContactName: (String) -> Unit

) {

    BackHandler {
        onBack()
    }


    var messageValue by
    rememberSaveable(
        contact.nodeId, stateSaver = TextFieldValue.Saver
    ) {

        mutableStateOf(
            TextFieldValue("")
        )
    }


    var sendInProgress by remember(contact.nodeId) { mutableStateOf(false) }

    var replyingToMessageId by
    remember(
        contact.nodeId
    ) {

        mutableStateOf<String?>(
            null
        )
    }


    var messagePendingDelete by
    remember(
        contact.nodeId
    ) {

        mutableStateOf<ChatMessage?>(
            null
        )
    }


    var confirmAction by
    remember(
        contact.nodeId
    ) {

        mutableStateOf<PlexusChatConfirmAction?>(
            null
        )
    }


    var editContactNameOpen by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    var editContactNameText by
    remember(
        contact.nodeId,
        contact.displayName
    ) {

        mutableStateOf(
            contact.displayName
        )
    }


    /*
     * Keep date labels correct even if the user leaves this chat open
     * across midnight. Reopening is not required for Today -> Yesterday.
     */
    var dateLabelNow by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            System.currentTimeMillis()
        )
    }


    LaunchedEffect(
        contact.nodeId
    ) {

        while (
            true
        ) {

            delay(
                millisUntilNextLocalMidnight()
            )

            dateLabelNow =
                System.currentTimeMillis()
        }
    }


    val messagesById =
        remember(
            messages
        ) {

            messages.associateBy {
                it.id
            }
        }


    val replyingToMessage =
        replyingToMessageId
            ?.let {
                messagesById[it]
            }


    val listState =
        rememberLazyListState()


    /*
     * Keep the chat viewport aware of the software keyboard.
     *
     * Root IME padding means the keyboard reduces the usable chat area
     * instead of covering the list/composer.
     *
     * If the user was already at the bottom, keep the newest message pinned
     * while the IME animates. If they deliberately scrolled upward, do not
     * yank them back down.
     */
    val density =
        LocalDensity.current


    val imeBottom =
        WindowInsets.ime
            .getBottom(
                density
            )


    val keyboardVisible =
        imeBottom > 0


    var keyboardWasVisible by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    var pinBottomDuringImeAnimation by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    /*
     * Opening a long conversation must never visibly animate through old
     * messages. First position at the newest message with an instant jump
     * while the list is temporarily transparent.
     *
     * Later, new messages auto-scroll only if the user was already near
     * the bottom.
     */
    var initialPositionApplied by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    var previousMessageCount by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            0
        )
    }


    LaunchedEffect(
        contact.nodeId,
        messages.size
    ) {

        if (
            messages.isEmpty()
        ) {

            previousMessageCount =
                0

            return@LaunchedEffect
        }


        if (
            !initialPositionApplied
        ) {

            listState.scrollToItem(
                messages.lastIndex
            )

            previousMessageCount =
                messages.size

            initialPositionApplied =
                true

            return@LaunchedEffect
        }


        if (
            messages.size >
            previousMessageCount
        ) {

            val lastVisibleIndex =
                listState
                    .layoutInfo
                    .visibleItemsInfo
                    .lastOrNull()
                    ?.index
                    ?: -1


            val wasNearBottom =
                previousMessageCount == 0 ||
                        lastVisibleIndex >=
                        previousMessageCount - 2


            if (
                wasNearBottom
            ) {

                listState.animateScrollToItem(
                    messages.lastIndex
                )
            }
        }


        previousMessageCount =
            messages.size
    }


    LaunchedEffect(
        keyboardVisible,
        imeBottom,
        messages.size,
        contact.nodeId
    ) {

        if (
            messages.isEmpty()
        ) {

            keyboardWasVisible =
                keyboardVisible

            return@LaunchedEffect
        }


        if (
            keyboardVisible &&
            !keyboardWasVisible
        ) {

            val lastVisibleIndex =
                listState
                    .layoutInfo
                    .visibleItemsInfo
                    .lastOrNull()
                    ?.index
                    ?: -1


            pinBottomDuringImeAnimation =
                lastVisibleIndex >=
                        messages.lastIndex - 1
        }


        if (
            keyboardVisible &&
            pinBottomDuringImeAnimation
        ) {

            listState.scrollToItem(
                messages.lastIndex
            )
        }


        if (
            !keyboardVisible
        ) {

            pinBottomDuringImeAnimation =
                false
        }


        keyboardWasVisible =
            keyboardVisible
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                PlexusBackground
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {

        PlexusChatHeader(
            contact =
                contact,

            onBack =
                onBack,

            onClearChatRequest = {

                confirmAction =
                    PlexusChatConfirmAction.CLEAR_CHAT
            },

            onDeleteChatRequest = {

                confirmAction =
                    PlexusChatConfirmAction.DELETE_CHAT
            },

            onEditContactNameRequest = {

                editContactNameText =
                    contact.displayName

                editContactNameOpen =
                    true
            }
        )


        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    1.dp
                )
                .background(
                    PlexusDivider
                )
        )


        LazyColumn(
            modifier = Modifier
                .weight(
                    1f
                )
                .fillMaxWidth()
                .alpha(
                    if (
                        messages.isNotEmpty() &&
                        !initialPositionApplied
                    ) {

                        0f

                    } else {

                        1f
                    }
                ),

            state =
                listState,

            contentPadding =
                PaddingValues(
                    horizontal =
                        10.dp,

                    vertical =
                        10.dp
                ),

            verticalArrangement =
                Arrangement.spacedBy(
                    6.dp
                )
        ) {

            itemsIndexed(
                items =
                    messages,

                key = { _, message ->
                    message.id
                }
            ) { index, message ->


                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    val showDateSeparator =
                        index == 0 ||
                                !isSameLocalDay(
                                    messages[
                                        index - 1
                                    ].timestamp,

                                    message.timestamp
                                )


                    if (
                        showDateSeparator
                    ) {

                        PlexusDateSeparator(
                            label =
                                formatDateSeparator(
                                    timestamp =
                                        message.timestamp,

                                    nowMillis =
                                        dateLabelNow
                                )
                        )


                        Spacer(
                            modifier =
                                Modifier.height(
                                    8.dp
                                )
                        )
                    }


                    val repliedMessage =
                        message.replyToMessageId
                            ?.let {
                                messagesById[it]
                            }


                    PlexusMessageBubble(

                        message =
                            message,

                        repliedMessage =
                            repliedMessage,

                        contactName =
                            contact.displayName,

                        onReply = {

                            replyingToMessageId =
                                message.id
                        },

                        onDeleteRequest = {

                            messagePendingDelete =
                                message
                        }
                    )
                }
            }
        }


        if (
            replyingToMessageId != null
        ) {

            PlexusReplyComposerPreview(

                repliedMessage =
                    replyingToMessage,

                contactName =
                    contact.displayName,

                onCancel = {

                    replyingToMessageId =
                        null
                }
            )
        }


        if (
            messagePendingDelete != null
        ) {

            AlertDialog(
                onDismissRequest = {

                    messagePendingDelete =
                        null
                },

                title = {

                    Text(
                        text =
                            "Delete message?"
                    )
                },

                text = {

                    Text(
                        text =
                            "This removes the message only from this device."
                    )
                },

                confirmButton = {

                    TextButton(
                        onClick = {

                            val messageId =
                                messagePendingDelete
                                    ?.id

                            messagePendingDelete =
                                null

                            if (
                                messageId != null
                            ) {

                                if (
                                    replyingToMessageId ==
                                    messageId
                                ) {

                                    replyingToMessageId =
                                        null
                                }

                                onDeleteMessage(
                                    messageId
                                )
                            }
                        }
                    ) {

                        Text(
                            text =
                                "Delete for me",

                            color =
                                PlexusGreen
                        )
                    }
                },

                dismissButton = {

                    TextButton(
                        onClick = {

                            messagePendingDelete =
                                null
                        }
                    ) {

                        Text(
                            text =
                                "Cancel"
                        )
                    }
                },

                containerColor =
                    PlexusSurfaceLight,

                titleContentColor =
                    Color.White,

                textContentColor =
                    PlexusSecondary
            )
        }


        if (
            editContactNameOpen
        ) {

            val cleanContactName =
                editContactNameText.trim()


            AlertDialog(
                onDismissRequest = {

                    editContactNameOpen =
                        false
                },

                title = {

                    Text(
                        text =
                            "Edit contact name"
                    )
                },

                text = {

                    TextField(
                        value =
                            editContactNameText,

                        onValueChange = { updatedName ->

                            editContactNameText =
                                updatedName
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine =
                            true,

                        placeholder = {

                            Text(
                                text =
                                    "Contact name"
                            )
                        },

                        colors =
                            TextFieldDefaults.colors(

                                focusedContainerColor =
                                    PlexusSurface,

                                unfocusedContainerColor =
                                    PlexusSurface,

                                focusedTextColor =
                                    Color.White,

                                unfocusedTextColor =
                                    Color.White,

                                cursorColor =
                                    PlexusGreen,

                                focusedIndicatorColor =
                                    PlexusGreen,

                                unfocusedIndicatorColor =
                                    PlexusDivider
                            )
                    )
                },

                confirmButton = {

                    TextButton(
                        enabled =
                            cleanContactName.isNotEmpty() &&
                                    cleanContactName !=
                                    contact.displayName,

                        onClick = {

                            if (
                                cleanContactName.isNotEmpty()
                            ) {

                                onEditContactName(
                                    cleanContactName
                                )

                                editContactNameOpen =
                                    false
                            }
                        }
                    ) {

                        Text(
                            text =
                                "Save",

                            color =
                                if (
                                    cleanContactName.isNotEmpty() &&
                                    cleanContactName !=
                                    contact.displayName
                                ) {

                                    PlexusGreen

                                } else {

                                    PlexusSecondary
                                }
                        )
                    }
                },

                dismissButton = {

                    TextButton(
                        onClick = {

                            editContactNameOpen =
                                false
                        }
                    ) {

                        Text(
                            text =
                                "Cancel"
                        )
                    }
                },

                containerColor =
                    PlexusSurfaceLight,

                titleContentColor =
                    Color.White,

                textContentColor =
                    PlexusSecondary
            )
        }


        if (
            confirmAction != null
        ) {

            val isDeleteChat =
                confirmAction ==
                        PlexusChatConfirmAction.DELETE_CHAT


            AlertDialog(
                onDismissRequest = {

                    confirmAction =
                        null
                },

                title = {

                    Text(
                        text =
                            if (
                                isDeleteChat
                            ) {

                                "Delete chat?"

                            } else {

                                "Clear chat?"
                            }
                    )
                },

                text = {

                    Text(
                        text =
                            if (
                                isDeleteChat
                            ) {

                                "Messages are removed only on this device and the chat disappears from Home. The trusted contact is kept, and a new incoming message will bring the chat back."

                            } else {

                                "All messages in this chat are removed only on this device. The contact stays visible on Home."
                            }
                    )
                },

                confirmButton = {

                    TextButton(
                        onClick = {

                            val action =
                                confirmAction

                            confirmAction =
                                null

                            replyingToMessageId =
                                null

                            messagePendingDelete =
                                null

                            when (
                                action
                            ) {

                                PlexusChatConfirmAction.CLEAR_CHAT ->
                                    onClearChat()

                                PlexusChatConfirmAction.DELETE_CHAT ->
                                    onDeleteChat()

                                null ->
                                    Unit
                            }
                        }
                    ) {

                        Text(
                            text =
                                if (
                                    isDeleteChat
                                ) {

                                    "Delete chat"

                                } else {

                                    "Clear"
                                },

                            color =
                                PlexusGreen
                        )
                    }
                },

                dismissButton = {

                    TextButton(
                        onClick = {

                            confirmAction =
                                null
                        }
                    ) {

                        Text(
                            text =
                                "Cancel"
                        )
                    }
                },

                containerColor =
                    PlexusSurfaceLight,

                titleContentColor =
                    Color.White,

                textContentColor =
                    PlexusSecondary
            )
        }




        PlexusMessageComposer(

            value =
                messageValue,

            onValueChanged = {
                // Keep the JSON + base64 mesh envelope within Nearby's small BYTES budget.
                if (it.text.length <= 2_000) messageValue = it
            },

            onSend = {

                val clean =
                    messageValue
                        .text
                        .trim()


                if (
                    clean.isNotEmpty() && !sendInProgress
                ) {
                    sendInProgress = true
                    val submittedDraft = messageValue.text
                    val submittedReply = replyingToMessageId
                    onSendMessage(
                        clean,
                        replyingToMessageId
                    ) { persisted ->
                        sendInProgress = false
                        if (persisted && messageValue.text == submittedDraft &&
                            replyingToMessageId == submittedReply) {
                            messageValue = TextFieldValue("")
                            replyingToMessageId = null
                        }
                    }
                }
            }
        )
    }
}


@Composable
private fun PlexusChatHeader(

    contact: ContactEntity,

    onBack: () -> Unit,

    onClearChatRequest: () -> Unit,

    onDeleteChatRequest: () -> Unit,

    onEditContactNameRequest: () -> Unit

) {

    var menuExpanded by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                68.dp
            )
            .padding(
                start =
                    5.dp,

                end =
                    14.dp
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        IconButton(
            onClick =
                onBack,

            modifier =
                Modifier.size(
                    42.dp
                )
        ) {

            PlexusChatBackArrow(
                color =
                    Color.White,

                modifier =
                    Modifier.size(
                        21.dp
                    )
            )
        }


        Spacer(
            modifier =
                Modifier.width(
                    4.dp
                )
        )


        PlexusChatAvatar(
            seed =
                contact.nodeId,

            size =
                43.dp
        )


        Spacer(
            modifier =
                Modifier.width(
                    12.dp
                )
        )


        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {

            Text(
                text =
                    contact.displayName,

                color =
                    Color.White,

                fontSize =
                    17.sp,

                fontWeight =
                    FontWeight.SemiBold,

                maxLines =
                    1,

                overflow =
                    TextOverflow.Ellipsis
            )


            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )


            Text(
                text =
                    "Plexus contact",

                color =
                    PlexusSecondary,

                fontSize =
                    11.5.sp
            )
        }


        Box {

            IconButton(
                onClick = {

                    menuExpanded =
                        true
                },

                modifier =
                    Modifier.size(
                        40.dp
                    )
            ) {

                Text(
                    text =
                        "⋮",

                    color =
                        Color.White,

                    fontSize =
                        25.sp,

                    fontWeight =
                        FontWeight.Medium
                )
            }


            DropdownMenu(
                expanded =
                    menuExpanded,

                onDismissRequest = {

                    menuExpanded =
                        false
                },

                containerColor =
                    PlexusSurfaceLight
            ) {

                DropdownMenuItem(
                    text = {

                        Text(
                            text =
                                "Edit contact name",

                            color =
                                Color.White
                        )
                    },

                    onClick = {

                        menuExpanded =
                            false

                        onEditContactNameRequest()
                    }
                )


                DropdownMenuItem(
                    text = {

                        Text(
                            text =
                                "Clear chat",

                            color =
                                Color.White
                        )
                    },

                    onClick = {

                        menuExpanded =
                            false

                        onClearChatRequest()
                    }
                )


                DropdownMenuItem(
                    text = {

                        Text(
                            text =
                                "Delete chat",

                            color =
                                Color(0xFFFF8A80)
                        )
                    },

                    onClick = {

                        menuExpanded =
                            false

                        onDeleteChatRequest()
                    }
                )
            }
        }
    }
}


@Composable
private fun PlexusDateSeparator(
    label: String
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            Arrangement.Center
    ) {

        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        9.dp
                    )
                )
                .background(
                    Color(
                        0xFF111713
                    )
                )
                .border(
                    width =
                        1.dp,

                    color =
                        PlexusGreen.copy(
                            alpha =
                                0.28f
                        ),

                    shape =
                        RoundedCornerShape(
                            9.dp
                        )
                )
                .padding(
                    horizontal =
                        11.dp,

                    vertical =
                        4.dp
                )
        ) {

            Text(
                text =
                    label,

                color =
                    Color.White.copy(
                        alpha =
                            0.78f
                    ),

                fontSize =
                    11.sp,

                fontWeight =
                    FontWeight.Medium
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlexusMessageBubble(

    message: ChatMessage,

    repliedMessage: ChatMessage?,

    contactName: String,

    onReply: () -> Unit,

    onDeleteRequest: () -> Unit

) {

    var swipeOffsetPx by
    remember(
        message.id
    ) {

        mutableStateOf(
            0f
        )
    }


    val density =
        LocalDensity.current


    val replyThresholdPx =
        with(
            density
        ) {

            46.dp.toPx()
        }


    val maximumSwipePx =
        with(
            density
        ) {

            62.dp.toPx()
        }


    val dragState =
        rememberDraggableState { delta ->

            /*
             * Horizontal draggable participates correctly beside LazyColumn's
             * vertical scroll. Tiny diagonal/vertical gestures therefore keep
             * scrolling the chat instead of getting trapped by swipe-to-reply.
             */
            swipeOffsetPx =
                (
                        swipeOffsetPx +
                                delta
                        )
                    .coerceIn(
                        0f,
                        maximumSwipePx
                    )
        }


    Box(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        if (
            swipeOffsetPx >
            4f
        ) {

            Box(
                modifier = Modifier
                    .align(
                        Alignment.CenterStart
                    )
                    .padding(
                        start =
                            8.dp
                    )
                    .size(
                        30.dp
                    )
                    .clip(
                        CircleShape
                    )
                    .background(
                        PlexusSurfaceLight
                    ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text =
                        "↩",

                    color =
                        PlexusGreen.copy(
                            alpha =
                                (swipeOffsetPx /
                                        replyThresholdPx)
                                    .coerceIn(
                                        0.35f,
                                        1f
                                    )
                        ),

                    fontSize =
                        17.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )
            }
        }


        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {

                    translationX =
                        swipeOffsetPx
                }
                .draggable(
                    state =
                        dragState,

                    orientation =
                        Orientation.Horizontal,

                    onDragStopped = {

                        val shouldReply =
                            swipeOffsetPx >=
                                    replyThresholdPx


                        swipeOffsetPx =
                            0f


                        if (
                            shouldReply
                        ) {

                            onReply()
                        }
                    }
                ),

            horizontalArrangement =
                if (
                    message.isMine
                ) {

                    Arrangement.End

                } else {

                    Arrangement.Start
                }
        ) {

            val bubbleShape =
                RoundedCornerShape(

                    topStart =
                        13.dp,

                    topEnd =
                        13.dp,

                    bottomStart =
                        if (
                            message.isMine
                        ) {

                            13.dp

                        } else {

                            4.dp
                        },

                    bottomEnd =
                        if (
                            message.isMine
                        ) {

                            4.dp

                        } else {

                            13.dp
                        }
                )


            val bubbleColor =
                if (
                    message.isMine
                ) {

                    Color(
                        0xFF0D5A42
                    )

                } else {

                    Color(
                        0xFF1A1D1B
                    )
                }


            Column(
                modifier = Modifier
                    .widthIn(
                        max =
                            300.dp
                    )
                    .clip(
                        bubbleShape
                    )
                    .background(
                        bubbleColor
                    )
                    .combinedClickable(
                        onClick = {
                            /* Tap is intentionally a no-op. */
                        },

                        onLongClick =
                            onDeleteRequest
                    )
                    .padding(
                        horizontal =
                            11.dp,

                        vertical =
                            6.dp
                    )
            ) {

                if (
                    message.replyToMessageId != null
                ) {

                    PlexusReplyQuote(

                        repliedMessage =
                            repliedMessage,

                        contactName =
                            contactName,

                        parentIsMine =
                            message.isMine
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                4.dp
                            )
                    )
                }


                val keepMetadataInline =
                    !message.text.contains(
                        '\n'
                    ) &&
                            message.text.length <=
                            27


                if (
                    keepMetadataInline
                ) {

                    Row(
                        verticalAlignment =
                            Alignment.Bottom
                    ) {

                        Text(
                            text =
                                message.text,

                            modifier =
                                Modifier.widthIn(
                                    max =
                                        if (
                                            message.isMine
                                        ) {

                                            205.dp

                                        } else {

                                            225.dp
                                        }
                                ),

                            color =
                                Color.White.copy(
                                    alpha =
                                        0.96f
                                ),

                            fontSize =
                                15.sp,

                            fontWeight =
                                FontWeight.Normal,

                            lineHeight =
                                18.sp
                        )


                        Spacer(
                            modifier =
                                Modifier.width(
                                    7.dp
                                )
                        )


                        PlexusMessageMetadata(
                            message =
                                message
                        )
                    }


                } else {

                    Text(
                        text =
                            message.text,

                        color =
                            Color.White.copy(
                                alpha =
                                    0.96f
                            ),

                        fontSize =
                            15.sp,

                        fontWeight =
                            FontWeight.Normal,

                        lineHeight =
                            19.sp
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                2.dp
                            )
                    )


                    Row(
                        modifier =
                            Modifier.align(
                                Alignment.End
                            )
                    ) {

                        PlexusMessageMetadata(
                            message =
                                message
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun PlexusReplyQuote(

    repliedMessage: ChatMessage?,

    contactName: String,

    parentIsMine: Boolean

) {

    val accentColor =
        if (
            parentIsMine
        ) {

            Color(
                0xFF4DFF8B
            )

        } else {

            PlexusGreen
        }


    Row(
        modifier = Modifier
            .widthIn(
                max =
                    230.dp
            )
            .clip(
                RoundedCornerShape(
                    7.dp
                )
            )
            .background(
                if (
                    parentIsMine
                ) {

                    Color.Black.copy(
                        alpha =
                            0.17f
                    )

                } else {

                    Color.White.copy(
                        alpha =
                            0.045f
                    )
                }
            )
    ) {

        Box(
            modifier = Modifier
                .width(
                    3.dp
                )
                .height(
                    36.dp
                )
                .background(
                    accentColor
                )
        )


        Column(
            modifier = Modifier
                .widthIn(
                    max =
                        205.dp
                )
                .padding(
                    horizontal =
                        7.dp,

                    vertical =
                        4.dp
                )
        ) {

            Text(
                text =
                    if (
                        repliedMessage == null
                    ) {

                        "Reply"

                    } else if (
                        repliedMessage.isMine
                    ) {

                        "You"

                    } else {

                        contactName
                    },

                color =
                    accentColor,

                fontSize =
                    10.5.sp,

                fontWeight =
                    FontWeight.SemiBold,

                maxLines =
                    1,

                overflow =
                    TextOverflow.Ellipsis
            )


            Spacer(
                modifier =
                    Modifier.height(
                        1.dp
                    )
            )


            Text(
                text =
                    repliedMessage
                        ?.text
                        ?: "Original message unavailable.",

                color =
                    Color.White.copy(
                        alpha =
                            0.64f
                    ),

                fontSize =
                    11.5.sp,

                maxLines =
                    1,

                overflow =
                    TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
private fun PlexusMessageMetadata(
    message: ChatMessage
) {

    val metadataColor =
        Color.White.copy(
            alpha =
                if (
                    message.isMine
                ) {

                    0.58f

                } else {

                    0.50f
                }
        )


    Row(
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        PlexusTransportIndicator(
            transportType =
                message.transportType,

            color =
                metadataColor
        )


        if (
            message.transportType !=
            MessageTransport.UNKNOWN
        ) {

            Spacer(
                modifier =
                    Modifier.width(
                        3.dp
                    )
            )
        }


        Text(
            text =
                formatMessageTime(
                    message.timestamp
                ),

            color =
                metadataColor,

            fontSize =
                10.5.sp,

            fontWeight =
                FontWeight.Normal,

            maxLines =
                1,

            lineHeight =
                12.sp
        )


        if (
            message.isMine
        ) {

            Spacer(
                modifier =
                    Modifier.width(
                        4.dp
                    )
            )


            PlexusDeliveryTicks(
                deliveryStatus =
                    message.deliveryStatus,

                mutedColor =
                    metadataColor
            )
        }
    }
}


@Composable
private fun PlexusTransportIndicator(

    transportType: String,

    color: Color

) {

    when (
        transportType
    ) {

        MessageTransport.CLOUD -> {

            Text(
                text =
                    "☁",

                color =
                    color,

                fontSize =
                    9.5.sp,

                lineHeight =
                    10.sp
            )
        }


        MessageTransport.MESH -> {

            Canvas(
                modifier =
                    Modifier.size(
                        10.dp
                    )
            ) {

                val stroke =
                    0.95.dp.toPx()


                val a =
                    Offset(
                        x =
                            size.width * 0.18f,

                        y =
                            size.height * 0.70f
                    )


                val b =
                    Offset(
                        x =
                            size.width * 0.50f,

                        y =
                            size.height * 0.25f
                    )


                val c =
                    Offset(
                        x =
                            size.width * 0.82f,

                        y =
                            size.height * 0.70f
                    )


                drawLine(
                    color =
                        color,

                    start =
                        a,

                    end =
                        b,

                    strokeWidth =
                        stroke,

                    cap =
                        StrokeCap.Round
                )


                drawLine(
                    color =
                        color,

                    start =
                        b,

                    end =
                        c,

                    strokeWidth =
                        stroke,

                    cap =
                        StrokeCap.Round
                )


                drawLine(
                    color =
                        color,

                    start =
                        a,

                    end =
                        c,

                    strokeWidth =
                        stroke,

                    cap =
                        StrokeCap.Round
                )


                val radius =
                    1.15.dp.toPx()


                drawCircle(
                    color =
                        color,

                    radius =
                        radius,

                    center =
                        a
                )


                drawCircle(
                    color =
                        color,

                    radius =
                        radius,

                    center =
                        b
                )


                drawCircle(
                    color =
                        color,

                    radius =
                        radius,

                    center =
                        c
                )
            }
        }
    }
}


@Composable
private fun PlexusDeliveryTicks(
    deliveryStatus: String,
    mutedColor: Color
) {

    val isRead =
        deliveryStatus ==
                MessageDeliveryStatus.READ

    val isDelivered =
        deliveryStatus ==
                MessageDeliveryStatus.DELIVERED ||
                isRead


    val tickColor =
        if (
            isRead
        ) {

            Color(
                0xFF53BDEB
            )

        } else {

            mutedColor
        }


    val canvasWidth =
        if (
            isDelivered
        ) {

            18.dp

        } else {

            11.dp
        }


    Canvas(
        modifier = Modifier
            .width(
                canvasWidth
            )
            .height(
                12.dp
            )
    ) {

        val strokeWidth =
            1.65.dp.toPx()


        fun drawTick(
            offsetX: Float
        ) {

            val start =
                Offset(
                    x =
                        offsetX +
                                0.8.dp.toPx(),

                    y =
                        6.2.dp.toPx()
                )


            val middle =
                Offset(
                    x =
                        offsetX +
                                4.0.dp.toPx(),

                    y =
                        9.2.dp.toPx()
                )


            val end =
                Offset(
                    x =
                        offsetX +
                                10.4.dp.toPx(),

                    y =
                        1.8.dp.toPx()
                )


            drawLine(
                color =
                    tickColor,

                start =
                    start,

                end =
                    middle,

                strokeWidth =
                    strokeWidth,

                cap =
                    StrokeCap.Square
            )


            drawLine(
                color =
                    tickColor,

                start =
                    middle,

                end =
                    end,

                strokeWidth =
                    strokeWidth,

                cap =
                    StrokeCap.Square
            )
        }


        /*
         * SENT
         *
         * One grey tick.
         */
        if (
            !isDelivered
        ) {

            drawTick(
                offsetX =
                    0f
            )


        } else {


            /*
             * DELIVERED / READ
             *
             * Two identical overlapping ticks.
             *
             * Delivered -> grey
             * Read      -> blue
             */

            drawTick(
                offsetX =
                    0f
            )


            drawTick(
                offsetX =
                    5.2.dp.toPx()
            )
        }
    }
}


@Composable
private fun PlexusReplyComposerPreview(

    repliedMessage: ChatMessage?,

    contactName: String,

    onCancel: () -> Unit

) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                PlexusSurface
            )
            .padding(
                start =
                    12.dp,

                end =
                    10.dp,

                top =
                    5.dp,

                bottom =
                    3.dp
            )
            .clip(
                RoundedCornerShape(
                    8.dp
                )
            )
            .background(
                Color(
                    0xFF1B211E
                )
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .width(
                    3.dp
                )
                .height(
                    42.dp
                )
                .background(
                    PlexusGreen
                )
        )


        Column(
            modifier = Modifier
                .weight(
                    1f
                )
                .padding(
                    horizontal =
                        8.dp,

                    vertical =
                        5.dp
                )
        ) {

            Text(
                text =
                    if (
                        repliedMessage?.isMine ==
                        true
                    ) {

                        "You"

                    } else {

                        contactName
                    },

                color =
                    PlexusGreen,

                fontSize =
                    11.sp,

                fontWeight =
                    FontWeight.SemiBold,

                maxLines =
                    1,

                overflow =
                    TextOverflow.Ellipsis
            )


            Text(
                text =
                    repliedMessage
                        ?.text
                        ?: "Original message unavailable.",

                color =
                    Color.White.copy(
                        alpha =
                            0.58f
                    ),

                fontSize =
                    11.5.sp,

                maxLines =
                    1,

                overflow =
                    TextOverflow.Ellipsis
            )
        }


        Text(
            text =
                "×",

            color =
                PlexusSecondary,

            fontSize =
                22.sp,

            modifier = Modifier
                .size(
                    36.dp
                )
                .clickable {
                    onCancel()
                }
                .padding(
                    start =
                        9.dp,

                    top =
                        3.dp
                )
        )
    }
}


@Composable
private fun PlexusMessageComposer(

    value: TextFieldValue,

    onValueChanged: (TextFieldValue) -> Unit,

    onSend: () -> Unit

) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                PlexusSurface
            )
            .padding(
                horizontal =
                    9.dp,

                vertical =
                    7.dp
            ),

        verticalAlignment =
            Alignment.Bottom
    ) {

        TextField(
            value =
                value,

            onValueChange =
                onValueChanged,

            modifier =
                Modifier.weight(
                    1f
                ),

            placeholder = {

                Text(
                    text =
                        "Message",

                    color =
                        Color(
                            0xFF777D79
                        ),

                    fontSize =
                        15.sp
                )
            },

            maxLines =
                5,

            shape =
                RoundedCornerShape(
                    24.dp
                ),

            colors =
                TextFieldDefaults.colors(

                    focusedContainerColor =
                        Color(
                            0xFF181C19
                        ),

                    unfocusedContainerColor =
                        Color(
                            0xFF181C19
                        ),

                    disabledContainerColor =
                        Color(
                            0xFF181C19
                        ),

                    focusedTextColor =
                        Color.White,

                    unfocusedTextColor =
                        Color.White,

                    cursorColor =
                        PlexusGreen,

                    focusedIndicatorColor =
                        Color.Transparent,

                    unfocusedIndicatorColor =
                        Color.Transparent,

                    disabledIndicatorColor =
                        Color.Transparent
                ),

            keyboardOptions =
                KeyboardOptions(
                    imeAction =
                        ImeAction.Send
                ),

            keyboardActions =
                KeyboardActions(
                    onSend = {

                        if (
                            value.text
                                .trim()
                                .isNotEmpty()
                        ) {

                            onSend()
                        }
                    }
                )
        )


        Spacer(
            modifier =
                Modifier.width(
                    7.dp
                )
        )


        val sendEnabled =
            value.text
                .trim()
                .isNotEmpty()


        IconButton(
            onClick =
                onSend,

            enabled =
                sendEnabled,

            modifier = Modifier
                .size(
                    46.dp
                )
                .clip(
                    CircleShape
                )
                .background(
                    if (
                        sendEnabled
                    ) {

                        PlexusGreen

                    } else {

                        Color(
                            0xFF202522
                        )
                    }
                )
        ) {

            Canvas(
                modifier =
                    Modifier.size(
                        21.dp
                    )
            ) {

                val iconColor =
                    if (
                        sendEnabled
                    ) {

                        Color(
                            0xFF001A09
                        )

                    } else {

                        Color(
                            0xFF626864
                        )
                    }


                val path =
                    Path().apply {

                        moveTo(
                            size.width * 0.12f,
                            size.height * 0.16f
                        )

                        lineTo(
                            size.width * 0.90f,
                            size.height * 0.50f
                        )

                        lineTo(
                            size.width * 0.12f,
                            size.height * 0.84f
                        )

                        lineTo(
                            size.width * 0.30f,
                            size.height * 0.55f
                        )

                        lineTo(
                            size.width * 0.90f,
                            size.height * 0.50f
                        )

                        lineTo(
                            size.width * 0.30f,
                            size.height * 0.45f
                        )

                        close()
                    }


                drawPath(
                    path =
                        path,

                    color =
                        iconColor
                )
            }
        }
    }
}


private fun isSameLocalDay(

    firstTimestamp: Long,

    secondTimestamp: Long

): Boolean {

    val first =
        Calendar.getInstance()
            .apply {
                timeInMillis =
                    firstTimestamp
            }


    val second =
        Calendar.getInstance()
            .apply {
                timeInMillis =
                    secondTimestamp
            }


    return first.get(
        Calendar.ERA
    ) ==
            second.get(
                Calendar.ERA
            ) &&
            first.get(
                Calendar.YEAR
            ) ==
            second.get(
                Calendar.YEAR
            ) &&
            first.get(
                Calendar.DAY_OF_YEAR
            ) ==
            second.get(
                Calendar.DAY_OF_YEAR
            )
}


private fun formatDateSeparator(

    timestamp: Long,

    nowMillis: Long

): String {

    val messageDay =
        Calendar.getInstance()
            .apply {

                timeInMillis =
                    timestamp

                set(
                    Calendar.HOUR_OF_DAY,
                    0
                )

                set(
                    Calendar.MINUTE,
                    0
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )
            }


    val today =
        Calendar.getInstance()
            .apply {

                timeInMillis =
                    nowMillis

                set(
                    Calendar.HOUR_OF_DAY,
                    0
                )

                set(
                    Calendar.MINUTE,
                    0
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )
            }


    if (
        isSameLocalDay(
            messageDay.timeInMillis,
            today.timeInMillis
        )
    ) {

        return "Today"
    }


    val yesterday =
        today.clone() as Calendar


    yesterday.add(
        Calendar.DAY_OF_YEAR,
        -1
    )


    if (
        isSameLocalDay(
            messageDay.timeInMillis,
            yesterday.timeInMillis
        )
    ) {

        return "Yesterday"
    }


    val sixDaysAgo =
        today.clone() as Calendar


    sixDaysAgo.add(
        Calendar.DAY_OF_YEAR,
        -6
    )


    if (
        !messageDay.before(
            sixDaysAgo
        ) &&
        messageDay.before(
            yesterday
        )
    ) {

        return SimpleDateFormat(
            "EEEE",
            Locale.getDefault()
        )
            .format(
                Date(
                    timestamp
                )
            )
    }


    return SimpleDateFormat(
        "d MMM yyyy",
        Locale.getDefault()
    )
        .format(
            Date(
                timestamp
            )
        )
}


private fun formatMessageTime(
    timestamp: Long
): String {

    return SimpleDateFormat(
        "h:mm a",
        Locale.getDefault()
    )
        .format(
            Date(
                timestamp
            )
        )
        .lowercase(
            Locale.getDefault()
        )
}


private fun millisUntilNextLocalMidnight(): Long {

    val now =
        Calendar.getInstance()


    val nextMidnight =
        Calendar.getInstance()
            .apply {

                timeInMillis =
                    now.timeInMillis

                add(
                    Calendar.DAY_OF_YEAR,
                    1
                )

                set(
                    Calendar.HOUR_OF_DAY,
                    0
                )

                set(
                    Calendar.MINUTE,
                    0
                )

                set(
                    Calendar.SECOND,
                    1
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )
            }


    return (
            nextMidnight.timeInMillis -
                    now.timeInMillis
            )
        .coerceAtLeast(
            1_000L
        )
}


@Composable
private fun PlexusChatAvatar(
    seed: String,
    size: Dp
) {

    /*
     * IMPORTANT:
     * This intentionally uses the exact same deterministic avatar algorithm
     * as the Home/Profile avatar in MainActivity.
     *
     * Same nodeId -> same avatar everywhere in Plexus.
     */
    val positiveHash =
        seed.hashCode() and 0x7FFFFFFF


    val palettes =
        listOf(

            listOf(
                Color(0xFF071B38),
                Color(0xFF0EA5E9),
                Color(0xFF67E8F9),
                Color(0xFFA78BFA)
            ),

            listOf(
                Color(0xFF32103F),
                Color(0xFFDB2777),
                Color(0xFFFF6B9E),
                Color(0xFFFBBF24)
            ),

            listOf(
                Color(0xFF052E2B),
                Color(0xFF0F766E),
                Color(0xFF2DD4BF),
                Color(0xFF4DFF8B)
            ),

            listOf(
                Color(0xFF451A03),
                Color(0xFFEA580C),
                Color(0xFFFBBF24),
                Color(0xFFFFE082)
            ),

            listOf(
                Color(0xFF201044),
                Color(0xFF7C3AED),
                Color(0xFFC084FC),
                Color(0xFFF472B6)
            ),

            listOf(
                Color(0xFF082F49),
                Color(0xFF0284C7),
                Color(0xFF38BDF8),
                Color(0xFF818CF8)
            ),

            listOf(
                Color(0xFF3F0713),
                Color(0xFFE11D48),
                Color(0xFFFB7185),
                Color(0xFFFDA4AF)
            ),

            listOf(
                Color(0xFF1F2937),
                Color(0xFF475569),
                Color(0xFF94A3B8),
                Color(0xFFE2E8F0)
            ),

            listOf(
                Color(0xFF1A2E05),
                Color(0xFF65A30D),
                Color(0xFFA3E635),
                Color(0xFFFACC15)
            ),

            listOf(
                Color(0xFF3B1606),
                Color(0xFFC2410C),
                Color(0xFFFB923C),
                Color(0xFFFDE047)
            ),

            listOf(
                Color(0xFF111827),
                Color(0xFF2563EB),
                Color(0xFF60A5FA),
                Color(0xFF22D3EE)
            ),

            listOf(
                Color(0xFF2E1065),
                Color(0xFF9333EA),
                Color(0xFFE879F9),
                Color(0xFFF0ABFC)
            ),

            listOf(
                Color(0xFF042F2E),
                Color(0xFF059669),
                Color(0xFF34D399),
                Color(0xFFA7F3D0)
            ),

            listOf(
                Color(0xFF450A0A),
                Color(0xFFDC2626),
                Color(0xFFF87171),
                Color(0xFFFCA5A5)
            ),

            listOf(
                Color(0xFF312E05),
                Color(0xFFCA8A04),
                Color(0xFFFACC15),
                Color(0xFFFEF08A)
            ),

            listOf(
                Color(0xFF172554),
                Color(0xFF4F46E5),
                Color(0xFF818CF8),
                Color(0xFFC4B5FD)
            ),

            listOf(
                Color(0xFF3A0B2E),
                Color(0xFFBE185D),
                Color(0xFFF472B6),
                Color(0xFFF9A8D4)
            ),

            listOf(
                Color(0xFF082F2C),
                Color(0xFF0891B2),
                Color(0xFF22D3EE),
                Color(0xFF99F6E4)
            )
        )


    val palette =
        palettes[
            positiveHash % palettes.size
        ]


    val pattern =
        (
                positiveHash shr 3
                ) % 5


    val shiftA =
        (
                (
                        positiveHash shr 7
                        ) % 20
                ).toFloat() / 100f


    val shiftB =
        (
                (
                        positiveHash shr 12
                        ) % 18
                ).toFloat() / 100f


    Box(
        modifier = Modifier
            .size(
                size
            )
            .clip(
                CircleShape
            )
            .background(
                brush =
                    Brush.linearGradient(
                        colors =
                            listOf(
                                palette[0],
                                palette[1],
                                palette[2]
                            )
                    )
            )
            .border(
                width =
                    1.5.dp,

                color =
                    Color.White.copy(
                        alpha = 0.92f
                    ),

                shape =
                    CircleShape
            )
    ) {


        Canvas(
            modifier =
                Modifier.fillMaxSize()
        ) {

            val dimension =
                this.size.minDimension


            when (
                pattern
            ) {

                0 -> {

                    drawCircle(
                        color =
                            palette[3].copy(
                                alpha = 0.88f
                            ),

                        radius =
                            dimension * 0.34f,

                        center =
                            Offset(
                                x =
                                    this.size.width *
                                            (0.74f - shiftA),

                                y =
                                    this.size.height * 0.27f
                            )
                    )


                    drawCircle(
                        color =
                            palette[0].copy(
                                alpha = 0.62f
                            ),

                        radius =
                            dimension * 0.29f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.29f,

                                y =
                                    this.size.height *
                                            (0.75f - shiftB)
                            )
                    )
                }


                1 -> {

                    drawCircle(
                        color =
                            palette[3].copy(
                                alpha = 0.82f
                            ),

                        radius =
                            dimension * 0.44f,

                        center =
                            Offset(
                                x =
                                    this.size.width *
                                            (0.20f + shiftA),

                                y =
                                    this.size.height * 0.24f
                            )
                    )


                    drawCircle(
                        color =
                            palette[1].copy(
                                alpha = 0.72f
                            ),

                        radius =
                            dimension * 0.25f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.78f,

                                y =
                                    this.size.height * 0.76f
                            )
                    )
                }


                2 -> {

                    drawCircle(
                        color =
                            palette[0].copy(
                                alpha = 0.70f
                            ),

                        radius =
                            dimension * 0.32f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.72f,

                                y =
                                    this.size.height *
                                            (0.72f - shiftA)
                            )
                    )


                    drawCircle(
                        color =
                            palette[3].copy(
                                alpha = 0.76f
                            ),

                        radius =
                            dimension * 0.22f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.28f,

                                y =
                                    this.size.height * 0.30f
                            )
                    )


                    drawCircle(
                        color =
                            palette[2].copy(
                                alpha = 0.54f
                            ),

                        radius =
                            dimension * 0.15f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.52f,

                                y =
                                    this.size.height * 0.48f
                            )
                    )
                }


                3 -> {

                    drawCircle(
                        color =
                            palette[3].copy(
                                alpha = 0.74f
                            ),

                        radius =
                            dimension * 0.28f,

                        center =
                            Offset(
                                x =
                                    this.size.width *
                                            (0.30f + shiftA),

                                y =
                                    this.size.height * 0.70f
                            )
                    )


                    drawCircle(
                        color =
                            palette[0].copy(
                                alpha = 0.58f
                            ),

                        radius =
                            dimension * 0.38f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.75f,

                                y =
                                    this.size.height * 0.26f
                            )
                    )
                }


                else -> {

                    drawCircle(
                        color =
                            palette[3].copy(
                                alpha = 0.80f
                            ),

                        radius =
                            dimension * 0.31f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.50f,

                                y =
                                    this.size.height *
                                            (0.18f + shiftB)
                            )
                    )


                    drawCircle(
                        color =
                            palette[0].copy(
                                alpha = 0.62f
                            ),

                        radius =
                            dimension * 0.30f,

                        center =
                            Offset(
                                x =
                                    this.size.width *
                                            (0.22f + shiftA),

                                y =
                                    this.size.height * 0.79f
                            )
                    )


                    drawCircle(
                        color =
                            palette[2].copy(
                                alpha = 0.52f
                            ),

                        radius =
                            dimension * 0.18f,

                        center =
                            Offset(
                                x =
                                    this.size.width * 0.82f,

                                y =
                                    this.size.height * 0.70f
                            )
                    )
                }
            }


            drawCircle(
                color =
                    Color.White.copy(
                        alpha = 0.58f
                    ),

                radius =
                    dimension * 0.045f,

                center =
                    Offset(
                        x =
                            this.size.width *
                                    (0.61f + shiftB / 2f),

                        y =
                            this.size.height * 0.29f
                    )
            )
        }
    }
}

@Composable
private fun PlexusChatBackArrow(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
    ) {
        val stroke = 2.2.dp.toPx()

        drawLine(
            color = color,
            start = Offset(size.width * 0.16f, size.height * 0.50f),
            end = Offset(size.width * 0.86f, size.height * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        drawLine(
            color = color,
            start = Offset(size.width * 0.16f, size.height * 0.50f),
            end = Offset(size.width * 0.43f, size.height * 0.24f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        drawLine(
            color = color,
            start = Offset(size.width * 0.16f, size.height * 0.50f),
            end = Offset(size.width * 0.43f, size.height * 0.76f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
