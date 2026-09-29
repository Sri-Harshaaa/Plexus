package com.plexus.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.room.withTransaction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.plexus.app.ui.theme.PlexusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


private val PlexusBlack =
    Color(0xFF050505)

private val PlexusSurface =
    Color(0xFF101311)

private val PlexusSurfaceRaised =
    Color(0xFF161A17)

private val PlexusGreen =
    Color(0xFF4DFF8B)

private val PlexusSecondary =
    Color(0xFF949A96)

private val PlexusMuted =
    Color(0xFF666D68)

private val PlexusDivider =
    Color(0xFF1D211E)


enum class PlexusScreen {
    HOME,
    PROFILE,
    SCANNER,
    ACCEPTED_CONTACT,
    CHAT
}

internal fun shouldSendReadReceipt(
    activityResumed: Boolean,
    currentScreen: PlexusScreen,
    selectedContactNodeId: String?,
    senderNodeId: String
): Boolean =
    activityResumed &&
        currentScreen == PlexusScreen.CHAT &&
        selectedContactNodeId == senderNodeId


class MainActivity : ComponentActivity() {

    private var serviceBinder by mutableStateOf<PlexusForegroundService.LocalBinder?>(null)
    private var visibleChatContactNodeId: String? = null
    private var serviceBound = false
    private var firstStartAfterRecreation = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            serviceBinder = binder as? PlexusForegroundService.LocalBinder
            serviceBinder?.setVisibleChat(visibleChatContactNodeId)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBinder = null
        }
    }

    private fun setVisibleChat(contactNodeId: String?) {
        visibleChatContactNodeId = contactNodeId
        serviceBinder?.setVisibleChat(contactNodeId)
    }

    private val transport = object {
        fun sendMeshPacket(packet: NearbyMeshPacket) =
            serviceBinder?.sendMeshPacket(packet) ?: false

        fun sendConnectionRequest(packet: ConnectionRequestPacket) =
            serviceBinder?.sendConnectionRequest(packet) ?: false

        fun sendConnectionResponse(packet: ConnectionResponsePacket) =
            serviceBinder?.sendConnectionResponse(packet) ?: false

        fun isNodeVerified(nodeId: String) =
            serviceBinder?.isNodeVerified(nodeId) ?: false

        fun sendBytesToNode(nodeId: String, bytes: ByteArray, priority: NearbyManager.SendPriority) =
            serviceBinder?.sendBytesToNode(nodeId, bytes, priority) ?: false

        fun broadcastBytes(bytes: ByteArray, priority: NearbyManager.SendPriority) {
            serviceBinder?.broadcastBytes(bytes, priority)
        }
    }

    private fun startAndBindTransport() {
        if (!ProfileManager.isSetupComplete(applicationContext) ||
            ProfileManager.getDisplayName(applicationContext).isNullOrBlank()) return
        val intent = Intent(this, PlexusForegroundService::class.java)
            .setAction(PlexusForegroundService.ACTION_START)
            .putExtra(
                PlexusForegroundService.EXTRA_START_REQUESTED_AT,
                SystemClock.elapsedRealtimeNanos()
            )
        try {
            ContextCompat.startForegroundService(this, intent)
            if (!serviceBound) {
                serviceBound = bindService(
                    Intent(this, PlexusForegroundService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            }
        } catch (exception: RuntimeException) {
            Log.e("PlexusService", "Could not start transport service", exception)
        }
    }

    override fun onStart() {
        super.onStart()
        val shouldStart = !firstStartAfterRecreation ||
            PlexusForegroundService.wasEnabled(applicationContext)
        firstStartAfterRecreation = false
        if (shouldStart) startAndBindTransport()
    }

    override fun onStop() {
        setVisibleChat(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        serviceBinder = null
        super.onStop()
    }

    override fun onPause() {
        setVisibleChat(null)
        super.onPause()
    }

    /*
     * Latest Plexus deep link delivered to this Activity.
     *
     * - cold start: populated from onCreate(intent)
     * - already-open app: updated from onNewIntent(intent)
     *
     * Compose observes this state and consumes each URI once.
     */
    private var pendingDeepLinkUri by
    mutableStateOf<Uri?>(
        null
    )

    private var pendingConversationContactId by mutableStateOf<String?>(null)


    override fun onNewIntent(
        intent: Intent
    ) {

        super.onNewIntent(
            intent
        )


        setIntent(
            intent
        )


        pendingDeepLinkUri =
            intent.data
        pendingConversationContactId = conversationContactIdFromIntent(intent)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startAndBindTransport()
        }
    }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)
        firstStartAfterRecreation = savedInstanceState != null


        pendingDeepLinkUri =
            intent?.data
        pendingConversationContactId = conversationContactIdFromIntent(intent)


        setContent {

            PlexusTheme {


                var profileSetupComplete by
                remember {

                    mutableStateOf(
                        ProfileManager
                            .isSetupComplete(
                                applicationContext
                            )
                    )
                }


                var savedDisplayName by
                remember {

                    mutableStateOf(
                        ProfileManager
                            .getDisplayName(
                                applicationContext
                            )
                    )
                }


                if (
                    !profileSetupComplete ||
                    savedDisplayName.isNullOrBlank()
                ) {


                    val onboardingIdentity =
                        remember {

                            IdentityManager
                                .getOrCreateIdentity(
                                    displayName = ""
                                )
                        }


                    PlexusOnboardingScreen(

                        avatarSeed =
                            onboardingIdentity.nodeId,

                        onContinue = { enteredName ->


                            val saved =
                                ProfileManager
                                    .completeInitialSetup(
                                        context =
                                            applicationContext,

                                        displayName =
                                            enteredName
                                    )


                            if (
                                saved
                            ) {


                                savedDisplayName =
                                    ProfileManager
                                        .getDisplayName(
                                            applicationContext
                                        )


                                profileSetupComplete =
                                    true

                                startAndBindTransport()
                            }
                        }
                    )


                } else {


                    val activeDisplayName =
                        savedDisplayName
                            .orEmpty()

                    val identity = remember(activeDisplayName) {
                        IdentityManager.getOrCreateIdentity(displayName = activeDisplayName)
                    }


                    val database =
                        remember {

                            PlexusDatabase
                                .getDatabase(
                                    applicationContext
                                )
                        }


                    val contactDao =
                        remember {

                            database.contactDao()
                        }


                    val messageDao =
                        remember {

                            database.messageDao()
                        }


                    val relayPacketDao =
                        remember {

                            database.relayPacketDao()
                        }


                    val meshRelayStore =
                        remember(relayPacketDao, identity.nodeId) {

                            MeshRelayStore(database, identity)
                        }


                    val connectionRequestDao =
                        remember {

                            database.connectionRequestDao()
                        }


                    val conversationStateDao =
                        remember {

                            database.conversationStateDao()
                        }


                    val hiddenContactIds by
                    conversationStateDao
                        .getHiddenContactIds()
                        .collectAsState(
                            initial = emptyList()
                        )


                    /*
                 * Home must only show fully CONNECTED contacts.
                 */
                    val contacts by
                    contactDao
                        .getAllContacts()
                        .collectAsState(
                            initial = emptyList()
                        )


                    /*
                     * Keep Home conversation metadata alive ABOVE screen
                     * navigation.
                     *
                     * Previously PlexusHomeScreen collected this Flow itself.
                     * Leaving Home disposed that collector, so returning from a
                     * chat briefly recreated Home with an empty summary list.
                     * Rows rendered as "Start a conversation", then Room emitted
                     * the real summaries and the list visibly re-sorted.
                     *
                     * Collecting here means HOME -> CHAT -> HOME reuses the same
                     * already-loaded state instead of flashing an empty snapshot.
                     */
                    val conversationSummaryFlow =
                        remember(
                            messageDao
                        ) {

                            messageDao
                                .getConversationSummaries()
                        }


                    val conversationSummaries by
                    conversationSummaryFlow
                        .collectAsState(
                            initial = emptyList()
                        )


                    val incomingPendingRequests by
                    contactDao
                        .getIncomingPendingRequests()
                        .collectAsState(
                            initial = emptyList()
                        )


                    /*
                 * These peers have already ACCEPTED our outgoing request.
                 *
                 * Trust is established, so the crypto layer can already use
                 * them. Home still hides the normal chat row until we confirm
                 * the local name we want to save them under.
                 */
                    val acceptedContactsNeedingName by
                    contactDao
                        .getAcceptedContactsNeedingName()
                        .collectAsState(
                            initial = emptyList()
                        )


                    val coroutineScope =
                        rememberCoroutineScope()
                    val outgoingSendMutex = remember { Mutex() }
                    val outgoingSessionKeys = remember { mutableMapOf<String, ByteArray>() }
                    var nearbyPermissionsGranted by remember {
                        mutableStateOf(NearbyPermissions.hasAllPermissions(applicationContext))
                    }
                    var nearbyPermissionResolved by remember {
                        mutableStateOf(nearbyPermissionsGranted)
                    }
                    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        nearbyPermissionsGranted = NearbyPermissions.hasAllPermissions(applicationContext)
                        nearbyPermissionResolved = true
                    }
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* Notification denial does not stop networking. */ }
                    LaunchedEffect(nearbyPermissionResolved) {
                        if (nearbyPermissionResolved && android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    LaunchedEffect(nearbyPermissionsGranted) {
                        if (!nearbyPermissionsGranted) {
                            nearbyPermissionLauncher.launch(NearbyPermissions.requiredPermissions())
                        }
                    }
                    LaunchedEffect(nearbyPermissionsGranted, serviceBinder) {
                        if (nearbyPermissionsGranted) serviceBinder?.startNearbyIfPermitted()
                    }


                    var currentScreen by
                    remember {

                        mutableStateOf(
                            PlexusScreen.HOME
                        )
                    }


                    var scannerReturnScreen by
                    remember {

                        mutableStateOf(
                            PlexusScreen.HOME
                        )
                    }


                    var selectedContact by
                    remember {

                        mutableStateOf<ContactEntity?>(
                            null
                        )
                    }


                    var selectedAcceptedContact by
                    remember {

                        mutableStateOf<ContactEntity?>(
                            null
                        )
                    }

                    val lifecycleOwner = this@MainActivity
                    var activityResumed by remember(lifecycleOwner) {
                        mutableStateOf(
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        )
                    }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            activityResumed = event == Lifecycle.Event.ON_RESUME
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            activityResumed = false
                        }
                    }
                    LaunchedEffect(
                        activityResumed,
                        currentScreen,
                        selectedContact?.nodeId,
                        serviceBinder
                    ) {
                        setVisibleChat(
                            if (activityResumed && currentScreen == PlexusScreen.CHAT) {
                                selectedContact?.nodeId
                            } else {
                                null
                            }
                        )
                    }
                    LaunchedEffect(pendingConversationContactId) {
                        val contactNodeId = pendingConversationContactId ?: return@LaunchedEffect
                        val contact = contactDao.getContactById(contactNodeId)
                        if (contact != null) {
                            selectedContact = contact
                            currentScreen = PlexusScreen.CHAT
                        }
                        pendingConversationContactId = null
                    }


                    /*
                     * =============================================================
                     * SHARED CONTACT IDENTITY IMPORT
                     * =============================================================
                     *
                     * QR scans and deep links deliberately converge here.
                     *
                     * The raw identity payload always goes through
                     * IdentityManager.parseAndValidateContact(...), so neither
                     * transport receives a weaker trust path.
                     *
                     * A successful import creates exactly the same durable,
                     * signed OUTGOING_PENDING relationship and offers the same
                     * connection request to both internet and Nearby.
                     */
                    val importContactIdentity:
                                (
                        String,
                        PlexusScreen,
                        String
                    ) -> Unit =
                        importHandler@ {
                                rawIdentityPayload,
                                returnScreen,
                                source ->


                            val importedIdentity =
                                IdentityManager
                                    .parseAndValidateContact(
                                        rawIdentityPayload
                                    )


                            /*
                             * Invalid identity or our own identity:
                             * leave all relationship state untouched.
                             */
                            if (
                                importedIdentity == null
                            ) {


                                Log.e(
                                    "PlexusContact",
                                    "$source IMPORT REJECTED - INVALID IDENTITY"
                                )


                                currentScreen =
                                    returnScreen


                                return@importHandler
                            }


                            if (
                                importedIdentity.nodeId ==
                                identity.nodeId
                            ) {


                                Log.d(
                                    "PlexusContact",
                                    "$source IMPORT IGNORED - SELF IDENTITY"
                                )


                                currentScreen =
                                    returnScreen


                                return@importHandler
                            }


                            coroutineScope.launch {


                                try {


                                    /*
                                     * nodeId is the relationship dedup key.
                                     *
                                     * Re-importing an identity in ANY relationship
                                     * state must not resend or reset anything.
                                     */
                                    val existingRelationship =
                                        contactDao
                                            .getContactById(
                                                importedIdentity.nodeId
                                            )


                                    if (
                                        existingRelationship != null
                                    ) {


                                        if (
                                            existingRelationship.relationshipState ==
                                            ContactRelationshipState.CONNECTED
                                        ) {

                                            /*
                                             * A deleted chat keeps the trusted relationship.
                                             * Re-scanning the same trusted identity therefore
                                             * simply unhides/reopens the existing conversation
                                             * instead of creating another connection request.
                                             */
                                            conversationStateDao
                                                .setConversationHidden(

                                                    contactNodeId =
                                                        existingRelationship.nodeId,

                                                    hidden =
                                                        false
                                                )


                                            selectedContact =
                                                existingRelationship

                                            currentScreen =
                                                PlexusScreen.CHAT


                                            Log.d(
                                                "PlexusContact",
                                                "$source EXISTING CONTACT REOPENED ✅ -> " +
                                                        importedIdentity.nodeId.take(8)
                                            )

                                        } else {

                                            Log.d(
                                                "PlexusContact",
                                                "$source IMPORT IGNORED ✅ -> " +
                                                        importedIdentity.nodeId.take(8) +
                                                        " | state=" +
                                                        existingRelationship.relationshipState
                                            )


                                            currentScreen =
                                                returnScreen
                                        }


                                        return@launch
                                    }


                                    /*
                                     * Create the signed request BEFORE inserting the
                                     * relationship row so pendingRequestId always refers
                                     * to a real durable request.
                                     */
                                    val requestPacket =
                                        ConnectionRequestPacket
                                            .create(

                                                senderIdentity =
                                                    identity,

                                                recipientNodeId =
                                                    importedIdentity.nodeId
                                            )


                                    val insertedRequestId =
                                        connectionRequestDao
                                            .insertRequest(

                                                ConnectionRequestEntity
                                                    .fromPacket(
                                                        requestPacket
                                                    )
                                            )


                                    if (
                                        insertedRequestId ==
                                        -1L
                                    ) {


                                        Log.d(
                                            "PlexusContact",
                                            "$source REQUEST CREATION SKIPPED - DUPLICATE REQUEST -> " +
                                                    requestPacket.requestId.take(8)
                                        )


                                        currentScreen =
                                            returnScreen


                                        return@launch
                                    }


                                    /*
                                     * Keep the imported identity pinned while the
                                     * relationship waits for acceptance.
                                     *
                                     * getAllContacts() hides OUTGOING_PENDING rows,
                                     * therefore no normal chat preview appears yet.
                                     */
                                    contactDao
                                        .insertContact(

                                            ContactEntity(

                                                nodeId =
                                                    importedIdentity.nodeId,

                                                displayName =
                                                    importedIdentity.displayName,

                                                signingPublicKey =
                                                    importedIdentity.signingPublicKey,

                                                agreementPublicKey =
                                                    importedIdentity.agreementPublicKey,

                                                announcedDisplayName =
                                                    importedIdentity.displayName,

                                                relationshipState =
                                                    ContactRelationshipState
                                                        .OUTGOING_PENDING,

                                                pendingRequestId =
                                                    requestPacket.requestId
                                            )
                                        )


                                    /*
                                     * Offer the exact same signed request to both
                                     * transports. requestId gives cross-transport dedup.
                                     */
                                    val sentInternet =
                                        transport.sendConnectionRequest(
                                                requestPacket
                                            )
                                            ?: false


                                    val sentNearby =
                                        transport.sendBytesToNode(

                                                nodeId =
                                                    importedIdentity.nodeId,

                                                bytes =
                                                    requestPacket
                                                        .toBytes(),

                                                priority =
                                                    NearbyManager.SendPriority.CONTROL
                                            )
                                            ?: false


                                    Log.d(
                                        "PlexusContact",
                                        "$source CONNECTION REQUEST CREATED ✅ -> " +
                                                importedIdentity.nodeId.take(8) +
                                                " | request=" +
                                                requestPacket.requestId.take(8) +
                                                " | internet=$sentInternet" +
                                                " | nearby=$sentNearby"
                                    )


                                    /*
                                     * If both transports are unavailable, the durable
                                     * request remains in Room and is retried later.
                                     */
                                    currentScreen =
                                        PlexusScreen.HOME


                                } catch (
                                    exception: Exception
                                ) {


                                    Log.e(
                                        "PlexusContact",
                                        "$source import could not create connection request",
                                        exception
                                    )


                                    currentScreen =
                                        returnScreen
                                }
                            }
                        }


                    /*
                     * =============================================================
                     * DEEP LINK CONSUMER
                     * =============================================================
                     *
                     * Manifest routing gets us only as far as this Activity.
                     * PlexusDeepLink then verifies scheme/host/path and extracts
                     * the original QR payload. From that point onward, deep links
                     * use the exact same importer as camera QR scans.
                     */
                    LaunchedEffect(
                        pendingDeepLinkUri
                    ) {


                        val deepLinkUri =
                            pendingDeepLinkUri
                                ?: return@LaunchedEffect


                        val deepLinkPayload =
                            PlexusDeepLink
                                .extractQrPayload(
                                    deepLinkUri
                                )


                        if (
                            deepLinkPayload == null
                        ) {


                            Log.e(
                                "PlexusContact",
                                "DEEP LINK REJECTED - INVALID PLEXUS CONNECT URI -> " +
                                        deepLinkUri
                            )


                            pendingDeepLinkUri =
                                null


                            return@LaunchedEffect
                        }


                        importContactIdentity(

                            deepLinkPayload,

                            currentScreen,

                            "DEEP LINK"
                        )


                        /*
                         * Consume only after handing the payload to the shared
                         * importer. This avoids cancelling this LaunchedEffect
                         * mid-processing by changing its own key too early.
                         */
                        pendingDeepLinkUri =
                            null
                    }



                    /*
                     * =============================================================
                     * INCOMING REQUEST ACTIONS
                     * =============================================================
                     *
                     * Incoming requests now live directly on Home.
                     *
                     * The UI asks for confirmation inline, then these functions
                     * perform the exact same durable state transitions and signed
                     * response delivery that the old full-screen request page used.
                     */
                    val acceptIncomingRequest:
                            suspend (
                                ContactEntity,
                                String
                            ) -> Boolean =
                        acceptRequest@ { requestContact, localDisplayName ->


                            try {


                                val currentRelationship =
                                    contactDao
                                        .getContactById(
                                            requestContact.nodeId
                                        )


                                if (
                                    currentRelationship == null ||
                                    currentRelationship.relationshipState !=
                                    ContactRelationshipState.INCOMING_PENDING
                                ) {

                                    return@acceptRequest false
                                }


                                val cleanDisplayName =
                                    localDisplayName.trim()


                                if (
                                    cleanDisplayName.isEmpty()
                                ) {

                                    return@acceptRequest false
                                }


                                val requestId =
                                    currentRelationship
                                        .pendingRequestId
                                        ?: return@acceptRequest false


                                val requestEntity =
                                    connectionRequestDao
                                        .getRequestById(
                                            requestId
                                        )
                                        ?: run {

                                            Log.e(
                                                "PlexusContact",
                                                "ACCEPT FAILED - ORIGINAL REQUEST MISSING -> " +
                                                        requestId.take(8)
                                            )

                                            return@acceptRequest false
                                        }


                                val requestPacket =
                                    requestEntity
                                        .toPacket()


                                if (
                                    !requestPacket.verify() ||
                                    requestPacket.recipientNodeId !=
                                    identity.nodeId ||
                                    requestPacket.senderNodeId !=
                                    currentRelationship.nodeId
                                ) {

                                    Log.e(
                                        "PlexusContact",
                                        "ACCEPT FAILED - ORIGINAL REQUEST INVALID -> " +
                                                requestId.take(8)
                                    )

                                    return@acceptRequest false
                                }


                                val responsePacket =
                                    ConnectionResponsePacket
                                        .createAccepted(

                                            request =
                                                requestPacket,

                                            responderIdentity =
                                                identity
                                        )


                                /*
                                 * Acceptance establishes trust NOW.
                                 *
                                 * Keep pendingRequestId so the signed response can
                                 * still be retried after restart if both transports
                                 * are unavailable at this exact moment.
                                 */
                                contactDao
                                    .updateDisplayName(

                                        nodeId =
                                            currentRelationship.nodeId,

                                        displayName =
                                            cleanDisplayName
                                    )


                                contactDao
                                    .updateRelationshipState(

                                        nodeId =
                                            currentRelationship.nodeId,

                                        relationshipState =
                                            ContactRelationshipState.CONNECTED,

                                        pendingRequestId =
                                            requestId
                                    )


                                val sentInternet =
                                    transport.sendConnectionResponse(
                                            responsePacket
                                        )
                                        ?: false


                                val sentNearby =
                                    transport.sendBytesToNode(

                                            nodeId =
                                                currentRelationship.nodeId,

                                            bytes =
                                                responsePacket
                                                    .toBytes(),

                                            priority =
                                                NearbyManager.SendPriority.CONTROL
                                        )
                                        ?: false


                                Log.d(
                                    "PlexusContact",
                                    "CONNECTION ACCEPTED LOCALLY ✅ -> " +
                                            currentRelationship.nodeId.take(8) +
                                            " | internet=$sentInternet" +
                                            " | nearby=$sentNearby"
                                )


                                true


                            } catch (
                                exception: Exception
                            ) {


                                Log.e(
                                    "PlexusContact",
                                    "Could not accept connection request",
                                    exception
                                )


                                false
                            }
                        }


                    val declineIncomingRequest:
                            suspend (
                                ContactEntity
                            ) -> Boolean =
                        declineRequest@ { requestContact ->


                            try {


                                val currentRelationship =
                                    contactDao
                                        .getContactById(
                                            requestContact.nodeId
                                        )


                                if (
                                    currentRelationship == null ||
                                    currentRelationship.relationshipState !=
                                    ContactRelationshipState.INCOMING_PENDING
                                ) {

                                    return@declineRequest false
                                }


                                val requestId =
                                    currentRelationship
                                        .pendingRequestId
                                        ?: return@declineRequest false


                                val requestEntity =
                                    connectionRequestDao
                                        .getRequestById(
                                            requestId
                                        )
                                        ?: return@declineRequest false


                                val requestPacket =
                                    requestEntity
                                        .toPacket()


                                if (
                                    !requestPacket.verify() ||
                                    requestPacket.recipientNodeId !=
                                    identity.nodeId ||
                                    requestPacket.senderNodeId !=
                                    currentRelationship.nodeId
                                ) {

                                    Log.e(
                                        "PlexusContact",
                                        "DECLINE FAILED - ORIGINAL REQUEST INVALID -> " +
                                                requestId.take(8)
                                    )

                                    return@declineRequest false
                                }


                                val responsePacket =
                                    ConnectionResponsePacket
                                        .createDeclined(

                                            request =
                                                requestPacket,

                                            responderIdentity =
                                                identity
                                        )


                                /*
                                 * Remove it from the visible request queue immediately,
                                 * while retaining requestId so the signed decline can be
                                 * retried later if delivery fails right now.
                                 */
                                contactDao
                                    .updateRelationshipState(

                                        nodeId =
                                            currentRelationship.nodeId,

                                        relationshipState =
                                            ContactRelationshipState.DECLINED,

                                        pendingRequestId =
                                            requestId
                                    )


                                val sentInternet =
                                    transport.sendConnectionResponse(
                                            responsePacket
                                        )
                                        ?: false


                                val sentNearby =
                                    transport.sendBytesToNode(

                                            nodeId =
                                                currentRelationship.nodeId,

                                            bytes =
                                                responsePacket
                                                    .toBytes(),

                                            priority =
                                                NearbyManager.SendPriority.CONTROL
                                        )
                                        ?: false


                                Log.d(
                                    "PlexusContact",
                                    "CONNECTION DECLINED LOCALLY -> " +
                                            currentRelationship.nodeId.take(8) +
                                            " | internet=$sentInternet" +
                                            " | nearby=$sentNearby"
                                )


                                true


                            } catch (
                                exception: Exception
                            ) {


                                Log.e(
                                    "PlexusContact",
                                    "Could not decline connection request",
                                    exception
                                )


                                false
                            }
                        }



                    BackHandler(

                        enabled =
                            currentScreen ==
                                    PlexusScreen.PROFILE ||
                                    currentScreen ==
                                    PlexusScreen.SCANNER ||
                                    currentScreen ==
                                    PlexusScreen.ACCEPTED_CONTACT

                    ) {

                        when (
                            currentScreen
                        ) {

                            PlexusScreen.PROFILE -> {

                                currentScreen =
                                    PlexusScreen.HOME
                            }


                            PlexusScreen.SCANNER -> {

                                currentScreen =
                                    scannerReturnScreen
                            }


                            PlexusScreen.ACCEPTED_CONTACT -> {

                                selectedAcceptedContact =
                                    null

                                currentScreen =
                                    PlexusScreen.HOME
                            }


                            else -> Unit
                        }
                    }


                    when (
                        currentScreen
                    ) {


                        PlexusScreen.HOME -> {

                            PlexusHomeScreen(

                                contacts =
                                    contacts,

                                incomingRequests =
                                    incomingPendingRequests,

                                acceptedContactsNeedingName =
                                    acceptedContactsNeedingName,

                                conversationSummaries =
                                    conversationSummaries,

                                hiddenContactIds =
                                    hiddenContactIds,

                                identity =
                                    identity,

                                onIncomingRequestAccept =
                                    acceptIncomingRequest,

                                onIncomingRequestDecline =
                                    declineIncomingRequest,

                                onAcceptedContactClick = { acceptedContact ->

                                    selectedAcceptedContact =
                                        acceptedContact

                                    currentScreen =
                                        PlexusScreen.ACCEPTED_CONTACT
                                },

                                onProfileClick = {

                                    currentScreen =
                                        PlexusScreen.PROFILE
                                },

                                onScanClick = {

                                    scannerReturnScreen =
                                        PlexusScreen.HOME

                                    currentScreen =
                                        PlexusScreen.SCANNER
                                },

                                onContactClick = { contact ->

                                    selectedContact =
                                        contact

                                    currentScreen =
                                        PlexusScreen.CHAT
                                },

                                onDeleteChat = { contact ->

                                    coroutineScope.launch {

                                        /*
                                         * Same local-only delete semantics as the
                                         * in-chat Delete chat action. Trust and keys stay.
                                         */
                                        conversationStateDao
                                            .setConversationHidden(

                                                contactNodeId =
                                                    contact.nodeId,

                                                hidden =
                                                    true
                                            )


                                        messageDao
                                            .deleteMessagesForContact(
                                                contact.nodeId
                                            )
                                    }
                                }
                            )
                        }


                        PlexusScreen.PROFILE -> {

                            PlexusProfileScreen(

                                identity =
                                    identity,

                                onBack = {

                                    currentScreen =
                                        PlexusScreen.HOME
                                },

                                onScanClick = {

                                    scannerReturnScreen =
                                        PlexusScreen.PROFILE

                                    currentScreen =
                                        PlexusScreen.SCANNER
                                },

                                onDisplayNameChanged = { updatedName ->

                                    val cleanName =
                                        updatedName.trim()


                                    if (
                                        cleanName.isNotEmpty() &&
                                        cleanName != activeDisplayName
                                    ) {

                                        val saved =
                                            ProfileManager
                                                .completeInitialSetup(

                                                    context =
                                                        applicationContext,

                                                    displayName =
                                                        cleanName
                                                )


                                        if (
                                            saved
                                        ) {

                                            savedDisplayName =
                                                ProfileManager
                                                    .getDisplayName(
                                                        applicationContext
                                                    )
                                        }
                                    }
                                }
                            )
                        }


                        PlexusScreen.SCANNER -> {

                            QrScannerScreen(

                                onBack = {

                                    currentScreen =
                                        scannerReturnScreen
                                },

                                onQrScanned = { rawQrText ->


                                    importContactIdentity(

                                        rawQrText,

                                        scannerReturnScreen,

                                        "QR"
                                    )
                                }
                            )
                        }



                        PlexusScreen.ACCEPTED_CONTACT -> {

                            val acceptedContact =
                                selectedAcceptedContact


                            if (
                                acceptedContact == null
                            ) {

                                currentScreen =
                                    PlexusScreen.HOME

                            } else {


                                PlexusAcceptedContactScreen(

                                    contact =
                                        acceptedContact,

                                    onBack = {

                                        selectedAcceptedContact =
                                            null

                                        currentScreen =
                                            PlexusScreen.HOME
                                    },

                                    onDone = { localDisplayName ->


                                        coroutineScope.launch {


                                            try {


                                                val currentRelationship =
                                                    contactDao
                                                        .getContactById(
                                                            acceptedContact.nodeId
                                                        )


                                                if (
                                                    currentRelationship == null ||
                                                    currentRelationship.relationshipState !=
                                                    ContactRelationshipState.ACCEPTED_NEEDS_NAME
                                                ) {

                                                    selectedAcceptedContact =
                                                        null

                                                    currentScreen =
                                                        PlexusScreen.HOME

                                                    return@launch
                                                }


                                                val cleanName =
                                                    localDisplayName.trim()


                                                if (
                                                    cleanName.isEmpty()
                                                ) {

                                                    return@launch
                                                }


                                                /*
                                             * Acceptance already established trust.
                                             *
                                             * This step only saves our LOCAL alias and makes
                                             * the normal Home chat row visible.
                                             */
                                                contactDao
                                                    .markConnected(

                                                        nodeId =
                                                            currentRelationship.nodeId,

                                                        displayName =
                                                            cleanName
                                                    )


                                                Log.d(
                                                    "PlexusContact",
                                                    "ACCEPTED CONTACT NAME CONFIRMED ✅ -> " +
                                                            currentRelationship.nodeId.take(8)
                                                )


                                                selectedAcceptedContact =
                                                    null

                                                currentScreen =
                                                    PlexusScreen.HOME


                                            } catch (
                                                exception: Exception
                                            ) {


                                                Log.e(
                                                    "PlexusContact",
                                                    "Could not finalize accepted contact",
                                                    exception
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }


                        PlexusScreen.CHAT -> {

                            val contact =
                                selectedContact


                            if (
                                contact == null
                            ) {

                                currentScreen =
                                    PlexusScreen.HOME

                            } else {

                                val lifecycleOwner =
                                    this@MainActivity


                                var activityResumed by
                                remember(
                                    lifecycleOwner
                                ) {

                                    mutableStateOf(
                                        lifecycleOwner.lifecycle.currentState
                                            .isAtLeast(
                                                Lifecycle.State.RESUMED
                                            )
                                    )
                                }


                                DisposableEffect(
                                    lifecycleOwner
                                ) {

                                    val observer =
                                        LifecycleEventObserver {
                                                _,
                                                event ->

                                            activityResumed =
                                                event ==
                                                        Lifecycle.Event.ON_RESUME
                                        }


                                    lifecycleOwner.lifecycle
                                        .addObserver(
                                            observer
                                        )


                                    onDispose {

                                        lifecycleOwner.lifecycle
                                            .removeObserver(
                                                observer
                                            )

                                        activityResumed =
                                            false
                                    }
                                }


                                val messageFlow =
                                    remember(
                                        contact.nodeId
                                    ) {

                                        messageDao
                                            .getMessagesForContact(
                                                contact.nodeId
                                            )
                                    }


                                val storedMessages by
                                messageFlow
                                    .collectAsState(
                                        initial = emptyList()
                                    )


                                var lastReadReceiptSentUpTo by
                                remember(
                                    contact.nodeId
                                ) {

                                    mutableStateOf(
                                        0L
                                    )
                                }


                                /*
                                 * Keep the open conversation read locally AND tell the peer
                                 * only when the user is actually inside this chat.
                                 *
                                 * Delivery anti-packets still mean only "reached device".
                                 * This separate encrypted control payload is what upgrades
                                 * the sender's double ticks to blue/read ticks.
                                 */
                                LaunchedEffect(
                                    contact.nodeId,
                                    storedMessages.size,
                                    activityResumed
                                ) {

                                    if (!shouldSendReadReceipt(
                                            activityResumed = activityResumed,
                                            currentScreen = currentScreen,
                                            selectedContactNodeId = selectedContact?.nodeId,
                                            senderNodeId = contact.nodeId
                                        )) {

                                        return@LaunchedEffect
                                    }

                                    messageDao
                                        .markConversationRead(
                                            contact.nodeId
                                        )


                                    val latestIncomingTimestamp =
                                        storedMessages
                                            .asSequence()
                                            .filter { message ->

                                                !message.isMine
                                            }
                                            .maxOfOrNull { message ->

                                                message.timestamp
                                            }
                                            ?: 0L


                                    if (
                                        latestIncomingTimestamp <= 0L ||
                                        latestIncomingTimestamp <=
                                        lastReadReceiptSentUpTo
                                    ) {

                                        return@LaunchedEffect
                                    }


                                    val readReceiptSessionKey =
                                        outgoingSessionKeys[
                                            contact.nodeId
                                        ]
                                            ?: try {

                                                PlexusCrypto
                                                    .deriveSessionKey(

                                                        localAgreementPublicKey =
                                                            identity.agreementPublicKey,

                                                        remoteAgreementPublicKey =
                                                            contact.agreementPublicKey,

                                                        localNodeId =
                                                            identity.nodeId,

                                                        remoteNodeId =
                                                            contact.nodeId
                                                    )
                                                    .also { derivedKey ->

                                                        outgoingSessionKeys[
                                                            contact.nodeId
                                                        ] =
                                                            derivedKey
                                                    }


                                            } catch (
                                                exception: Exception
                                            ) {

                                                Log.e(
                                                    "PlexusReadReceipt",
                                                    "READ RECEIPT KEY DERIVATION FAILED -> " +
                                                            contact.nodeId.take(8),
                                                    exception
                                                )

                                                null
                                            }


                                    if (
                                        readReceiptSessionKey == null
                                    ) {

                                        return@LaunchedEffect
                                    }


                                    try {

                                        val encryptedReadReceipt =
                                            NearbyEncryptedChatPacket
                                                .create(

                                                    senderNodeId =
                                                        identity.nodeId,

                                                    recipientNodeId =
                                                        contact.nodeId,

                                                    text =
                                                        ChatWirePayloadCodec
                                                            .encodeReadReceipt(
                                                                latestIncomingTimestamp
                                                            ),

                                                    sessionKey =
                                                        readReceiptSessionKey
                                                )


                                        val readReceiptMeshPacket =
                                            withContext(Dispatchers.IO) {
                                                NearbyMeshPacket.create(encryptedReadReceipt, identity)
                                            }

                                        if (!shouldSendReadReceipt(
                                                activityResumed = activityResumed,
                                                currentScreen = currentScreen,
                                                selectedContactNodeId = selectedContact?.nodeId,
                                                senderNodeId = contact.nodeId
                                            )) {

                                            return@LaunchedEffect
                                        }


                                        val storeResult =
                                            meshRelayStore
                                                .store(
                                                    readReceiptMeshPacket,
                                                    locallyCreated = true
                                                )


                                        if (
                                            storeResult ==
                                            MeshRelayStore.StoreResult.INVALID ||
                                            storeResult ==
                                            MeshRelayStore.StoreResult.EXPIRED
                                        ) {

                                            return@LaunchedEffect
                                        }


                                        transport
                                            .sendMeshPacket(
                                                readReceiptMeshPacket
                                            )


                                        val sentDirectly =
                                            if (
                                                transport
                                                    .isNodeVerified(
                                                        contact.nodeId
                                                    )
                                            ) {

                                                transport
                                                    .sendBytesToNode(

                                                        nodeId =
                                                            contact.nodeId,

                                                        bytes =
                                                            readReceiptMeshPacket
                                                                .toBytes(),

                                                        priority =
                                                            NearbyManager.SendPriority.CONTROL
                                                    )

                                            } else {

                                                false
                                            }


                                        if (
                                            !sentDirectly
                                        ) {

                                            transport
                                                .broadcastBytes(

                                                    bytes =
                                                        readReceiptMeshPacket
                                                            .toBytes(),

                                                    priority =
                                                        NearbyManager.SendPriority.CONTROL
                                                )
                                        }


                                        lastReadReceiptSentUpTo =
                                            latestIncomingTimestamp


                                        Log.d(
                                            "PlexusReadReceipt",
                                            "READ RECEIPT QUEUED 👁️ -> " +
                                                    contact.nodeId.take(8) +
                                                    " | upTo=" +
                                                    latestIncomingTimestamp
                                        )


                                    } catch (
                                        exception: Exception
                                    ) {

                                        Log.e(
                                            "PlexusReadReceipt",
                                            "READ RECEIPT SEND FAILED -> " +
                                                    contact.nodeId.take(8),
                                            exception
                                        )
                                    }
                                }


                                val chatMessages =
                                    storedMessages.map { message ->

                                        ChatMessage(

                                            id =
                                                message.messageId,

                                            text =
                                                message.text,

                                            isMine =
                                                message.isMine,

                                            timestamp =
                                                message.timestamp,

                                            deliveryStatus =
                                                message.deliveryStatus,

                                            transportType =
                                                message.transportType,

                                            replyToMessageId =
                                                message.replyToMessageId
                                        )
                                    }


                                PlexusChatScreen(

                                    contact =
                                        contact,

                                    messages =
                                        chatMessages,

                                    onBack = {

                                        currentScreen =
                                            PlexusScreen.HOME
                                    },

                                    onDeleteMessage = { messageId ->

                                        coroutineScope.launch {

                                            messageDao
                                                .deleteMessageById(
                                                    messageId
                                                )
                                        }
                                    },

                                    onClearChat = {

                                        coroutineScope.launch {

                                            messageDao
                                                .deleteMessagesForContact(
                                                    contact.nodeId
                                                )
                                        }
                                    },

                                    onDeleteChat = {

                                        coroutineScope.launch {

                                            /*
                                             * Hide first so Home never flashes an empty
                                             * "Start a conversation" row while the local
                                             * history is being removed. Trust/keys survive.
                                             */
                                            conversationStateDao
                                                .setConversationHidden(

                                                    contactNodeId =
                                                        contact.nodeId,

                                                    hidden =
                                                        true
                                                )


                                            messageDao
                                                .deleteMessagesForContact(
                                                    contact.nodeId
                                                )


                                            selectedContact =
                                                null

                                            currentScreen =
                                                PlexusScreen.HOME
                                        }
                                    },

                                    onEditContactName = { updatedName ->

                                        val cleanName =
                                            updatedName.trim()


                                        if (
                                            cleanName.isNotEmpty()
                                        ) {

                                            coroutineScope.launch {

                                                contactDao
                                                    .updateDisplayName(

                                                        nodeId =
                                                            contact.nodeId,

                                                        displayName =
                                                            cleanName
                                                    )


                                                /*
                                                 * selectedContact is a snapshot rather than a
                                                 * collected Room row. Refresh it immediately so
                                                 * the chat header changes without leaving/reopening.
                                                 */
                                                selectedContact =
                                                    contact.copy(
                                                        displayName =
                                                            cleanName
                                                    )
                                            }
                                        }
                                    },

                                    onSendMessage = sendMessage@ { text, replyToMessageId, onPersisted ->
                                        var persistenceReported = false
                                        fun reportPersistence(success: Boolean) {
                                            if (!persistenceReported) {
                                                persistenceReported = true
                                                onPersisted(success)
                                            }
                                        }


                                        /*
                                     * =====================================================
                                     * OFFLINE-FIRST SEND
                                     * =====================================================
                                     *
                                     * We no longer require the final recipient to be
                                     * directly connected before composing a message.
                                     *
                                     * The saved contact already contains the recipient's
                                     * agreement public key, so we can derive the E2E key,
                                     * encrypt locally, wrap the ciphertext in a mesh
                                     * envelope, persist it, and let the mesh carry it.
                                     */

                                        val sessionKey =
                                            outgoingSessionKeys[
                                                contact.nodeId
                                            ]
                                                ?: try {


                                                    PlexusCrypto
                                                        .deriveSessionKey(

                                                            localAgreementPublicKey =
                                                                identity
                                                                    .agreementPublicKey,

                                                            remoteAgreementPublicKey =
                                                                contact
                                                                    .agreementPublicKey,

                                                            localNodeId =
                                                                identity.nodeId,

                                                            remoteNodeId =
                                                                contact.nodeId
                                                        )
                                                        .also { derivedKey ->

                                                            outgoingSessionKeys[
                                                                contact.nodeId
                                                            ] =
                                                                derivedKey
                                                        }


                                                } catch (
                                                    exception: Exception
                                                ) {


                                                    Log.e(
                                                        "PlexusNearby",
                                                        "MESH SEND BLOCKED - " +
                                                                "KEY DERIVATION FAILED -> " +
                                                                contact.nodeId.take(8),
                                                        exception
                                                    )


                                                    null
                                                }


                                        if (sessionKey == null) {
                                            reportPersistence(false)
                                            return@sendMessage
                                        }
                                        run {


                                            try {


                                                val encryptedPacket =
                                                    NearbyEncryptedChatPacket
                                                        .create(

                                                            senderNodeId =
                                                                identity.nodeId,

                                                            recipientNodeId =
                                                                contact.nodeId,

                                                            text =
                                                                ChatWirePayloadCodec
                                                                    .encode(
                                                                        text =
                                                                            text,

                                                                        replyToMessageId =
                                                                            replyToMessageId
                                                                    ),

                                                            sessionKey =
                                                                sessionKey
                                                        )


                                                val outgoingMessage =
                                                    MessageEntity(

                                                        messageId =
                                                            encryptedPacket.messageId,

                                                        contactNodeId =
                                                            contact.nodeId,

                                                        text =
                                                            text,

                                                        isMine =
                                                            true,

                                                        timestamp =
                                                            encryptedPacket.timestamp,

                                                        deliveryStatus =
                                                            MessageDeliveryStatus.SENT,

                                                        transportType =
                                                            MessageTransport.UNKNOWN,

                                                        replyToMessageId =
                                                            replyToMessageId
                                                    )


                                                coroutineScope.launch {


                                                    try {
                                                        /*
                                                     * Persist FIRST.
                                                     *
                                                     * If nobody is nearby right now, the
                                                     * packet survives and can be forwarded
                                                     * on a later encounter.
                                                     */
                                                        outgoingSendMutex.lock()
                                                        val (meshPacket, storeResult) = try {
                                                            val packet = withContext(Dispatchers.IO) {
                                                                NearbyMeshPacket.create(encryptedPacket, identity)
                                                            }
                                                            val result = database.withTransaction {
                                                                val stored = meshRelayStore.store(packet, locallyCreated = true)
                                                                if (stored == MeshRelayStore.StoreResult.STORED ||
                                                                    stored == MeshRelayStore.StoreResult.DUPLICATE) {
                                                                    messageDao.insertMessage(outgoingMessage)
                                                                }
                                                                stored
                                                            }
                                                            packet to result
                                                        } finally {
                                                            outgoingSendMutex.unlock()
                                                        }


                                                        if (
                                                            storeResult ==
                                                            MeshRelayStore.StoreResult.INVALID ||
                                                            storeResult ==
                                                            MeshRelayStore.StoreResult.EXPIRED
                                                        ) {

                                                            Log.e(
                                                                "PlexusNearby",
                                                                "MESH SEND FAILED - " +
                                                                        "COULD NOT QUEUE -> " +
                                                                        encryptedPacket.messageId.take(8)
                                                            )

                                                            reportPersistence(false)
                                                            return@launch
                                                        }
                                                        reportPersistence(true)

                                                        /*
                                                     * The user's own conversation history
                                                     * is stored locally immediately.
                                                     *
                                                     * Delivery/read state comes later as a
                                                     * separate protocol concern.
                                                     */


                                                        /*
                                                     * INTERNET PATH
                                                     *
                                                     * Send the exact same encrypted mesh
                                                     * envelope to the authenticated backend.
                                                     *
                                                     * WebSocket enqueue is NOT treated as final
                                                     * delivery. The server keeps its copy until
                                                     * the recipient returns a signed anti-packet.
                                                     */
                                                        val sentToInternet =
                                                            transport
                                                                .sendMeshPacket(
                                                                    meshPacket
                                                                )


                                                        if (
                                                            sentToInternet
                                                        ) {

                                                            Log.d(
                                                                "PlexusInternet",
                                                                "OUTGOING ENCRYPTED PACKET ENQUEUED 🌐 -> " +
                                                                        contact.nodeId.take(8) +
                                                                        " | message=" +
                                                                        encryptedPacket.messageId.take(8)
                                                            )

                                                        } else {

                                                            Log.d(
                                                                "PlexusInternet",
                                                                "INTERNET PATH UNAVAILABLE - " +
                                                                        "LOCAL MESH QUEUE RETAINED 📦 -> " +
                                                                        encryptedPacket.messageId.take(8)
                                                            )
                                                        }


                                                        /*
                                                     * Fast path:
                                                     *
                                                     * if the actual recipient is already
                                                     * authenticated and connected, deliver
                                                     * directly.
                                                     */
                                                        val sentDirectly =

                                                            if (
                                                                transport
                                                                    .isNodeVerified(
                                                                        contact.nodeId
                                                                    )
                                                            ) {

                                                                transport
                                                                    .sendBytesToNode(

                                                                        nodeId =
                                                                            contact.nodeId,

                                                                        bytes =
                                                                            meshPacket
                                                                                .toBytes(),

                                                                        priority =
                                                                            NearbyManager.SendPriority.MESSAGE
                                                                    )

                                                            } else {

                                                                false
                                                            }


                                                        if (
                                                            sentDirectly
                                                        ) {


                                                            Log.d(
                                                                "PlexusNearby",
                                                                "MESH MESSAGE QUEUED FOR NEARBY -> " +
                                                                        contact.nodeId.take(8) +
                                                                        " | message=" +
                                                                        encryptedPacket.messageId.take(8)
                                                            )


                                                        } else {


                                                            /*
                                                         * No direct recipient right now.
                                                         *
                                                         * Spray the opaque mesh envelope to
                                                         * currently connected peers. A peer
                                                         * only accepts it after that Nearby
                                                         * endpoint has passed Plexus auth.
                                                         *
                                                         * If nobody useful is connected, the
                                                         * Room queue still retains it for a
                                                         * future encounter.
                                                         */
                                                            transport
                                                                .broadcastBytes(
                                                                    bytes =
                                                                        meshPacket
                                                                            .toBytes(),

                                                                    priority =
                                                                        NearbyManager.SendPriority.MESSAGE
                                                                )


                                                            Log.d(
                                                                "PlexusNearby",
                                                                "MESH MESSAGE QUEUED 📦 -> " +
                                                                        contact.nodeId.take(8) +
                                                                        " | message=" +
                                                                        encryptedPacket.messageId.take(8)
                                                            )
                                                        }


                                                        /*
                                                         * The UI shows one route icon even though
                                                         * Plexus may race multiple transports.
                                                         *
                                                         * Prefer MESH when the final recipient is
                                                         * directly verified Nearby. Otherwise use
                                                         * CLOUD when the server accepted the packet.
                                                         * If neither immediate path is available,
                                                         * the durable mesh queue remains the route.
                                                         */
                                                        val selectedTransport =
                                                            when {

                                                                sentDirectly -> {

                                                                    MessageTransport.MESH
                                                                }


                                                                sentToInternet -> {

                                                                    MessageTransport.CLOUD
                                                                }


                                                                else -> {

                                                                    MessageTransport.MESH
                                                                }
                                                            }


                                                        messageDao
                                                            .updateMessageTransport(

                                                                messageId =
                                                                    encryptedPacket.messageId,

                                                                transportType =
                                                                    selectedTransport
                                                            )


                                                    } catch (
                                                        exception: Exception
                                                    ) {


                                                        Log.e(
                                                            "PlexusNearby",
                                                            "MESH SEND FAILED -> " +
                                                                    encryptedPacket.messageId.take(8),
                                                            exception
                                                        )
                                                        reportPersistence(false)
                                                    }
                                                }


                                            } catch (
                                                exception: Exception
                                            ) {


                                                Log.e(
                                                    "PlexusNearby",
                                                    "MESH MESSAGE CREATION FAILED -> " +
                                                            contact.nodeId.take(8),
                                                    exception
                                                )
                                                reportPersistence(false)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }


                }
            }
        }
    }
}


@Composable
private fun PlexusOnboardingScreen(
    avatarSeed: String,
    onContinue: (String) -> Unit
) {

    var displayName by
    remember {

        mutableStateOf("")
    }


    val cleanDisplayName =
        displayName.trim()


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                PlexusBlack
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(
                horizontal =
                    24.dp
            ),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {


        Spacer(
            modifier =
                Modifier.height(
                    54.dp
                )
        )


        Text(
            text =
                "Plexus",

            color =
                Color.White,

            fontSize =
                34.sp,

            fontWeight =
                FontWeight.Black
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        Text(
            text =
                "Create your profile",

            color =
                PlexusSecondary,

            fontSize =
                15.sp,

            fontWeight =
                FontWeight.Medium
        )


        Spacer(
            modifier =
                Modifier.height(
                    38.dp
                )
        )


        PlexusAvatar(
            seed =
                avatarSeed,

            size =
                108.dp,

            borderColor =
                Color.White
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        Text(
            text =
                "Your Plexus avatar",

            color =
                PlexusMuted,

            fontSize =
                12.sp
        )


        Spacer(
            modifier =
                Modifier.height(
                    42.dp
                )
        )


        Column(
            modifier =
                Modifier.fillMaxWidth()
        ) {


            Text(
                text =
                    "Your name",

                color =
                    Color.White,

                fontSize =
                    14.sp,

                fontWeight =
                    FontWeight.SemiBold
            )


            Spacer(
                modifier =
                    Modifier.height(
                        9.dp
                    )
            )


            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        54.dp
                    )
                    .clip(
                        RoundedCornerShape(
                            17.dp
                        )
                    )
                    .background(
                        PlexusSurfaceRaised
                    )
                    .border(
                        width =
                            1.dp,

                        color =
                            if (
                                displayName.isNotEmpty()
                            ) {

                                PlexusGreen.copy(
                                    alpha =
                                        0.58f
                                )

                            } else {

                                PlexusDivider
                            },

                        shape =
                            RoundedCornerShape(
                                17.dp
                            )
                    )
                    .padding(
                        horizontal =
                            16.dp
                    ),

                contentAlignment =
                    Alignment.CenterStart
            ) {


                if (
                    displayName.isEmpty()
                ) {


                    Text(
                        text =
                            "Enter your name",

                        color =
                            PlexusMuted,

                        fontSize =
                            15.sp
                    )
                }


                BasicTextField(
                    value =
                        displayName,

                    onValueChange = { newValue ->


                        if (
                            newValue.length <= 40
                        ) {

                            displayName =
                                newValue
                        }
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    singleLine =
                        true,

                    textStyle =
                        TextStyle(

                            color =
                                Color.White,

                            fontSize =
                                15.sp,

                            fontWeight =
                                FontWeight.Medium
                        ),

                    cursorBrush =
                        SolidColor(
                            PlexusGreen
                        ),

                    keyboardOptions =
                        KeyboardOptions(
                            imeAction =
                                ImeAction.Done
                        ),

                    keyboardActions =
                        KeyboardActions(

                            onDone = {


                                if (
                                    cleanDisplayName.isNotEmpty()
                                ) {

                                    onContinue(
                                        cleanDisplayName
                                    )
                                }
                            }
                        )
                )
            }


            Spacer(
                modifier =
                    Modifier.height(
                        10.dp
                    )
            )


            Text(
                text =
                    "This is the name people see when they discover or connect with you.",

                color =
                    PlexusMuted,

                fontSize =
                    12.sp,

                lineHeight =
                    17.sp
            )
        }


        Spacer(
            modifier =
                Modifier.weight(
                    1f
                )
        )


        Button(
            onClick = {

                onContinue(
                    cleanDisplayName
                )
            },

            enabled =
                cleanDisplayName.isNotEmpty(),

            modifier = Modifier
                .fillMaxWidth()
                .height(
                    54.dp
                ),

            colors =
                ButtonDefaults.buttonColors(

                    containerColor =
                        PlexusGreen,

                    contentColor =
                        Color(
                            0xFF001A09
                        )
                ),

            shape =
                RoundedCornerShape(
                    17.dp
                )
        ) {


            Text(
                text =
                    "Continue",

                fontSize =
                    15.sp,

                fontWeight =
                    FontWeight.Bold
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    24.dp
                )
        )
    }
}


@Composable
private fun PlexusAcceptedContactBanner(
    contact: ContactEntity,
    totalCount: Int,
    onClick: () -> Unit
) {

    val announcedName =
        contact
            .announcedDisplayName
            ?.takeIf {

                it.isNotBlank()
            }
            ?: contact.displayName


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal =
                    16.dp
            )
            .clip(
                RoundedCornerShape(
                    18.dp
                )
            )
            .background(
                PlexusSurface
            )
            .clickable {

                onClick()
            }
            .padding(
                horizontal =
                    14.dp,

                vertical =
                    12.dp
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {


        PlexusAvatar(
            seed =
                contact.nodeId,

            size =
                46.dp,

            borderColor =
                Color.Transparent
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
                    "Connection accepted",

                color =
                    PlexusGreen,

                fontSize =
                    12.sp,

                fontWeight =
                    FontWeight.SemiBold
            )


            Spacer(
                modifier =
                    Modifier.height(
                        2.dp
                    )
            )


            Text(
                text =
                    announcedName +
                            " accepted your request",

                color =
                    Color.White,

                fontSize =
                    15.sp,

                fontWeight =
                    FontWeight.SemiBold,

                maxLines =
                    1,

                overflow =
                    TextOverflow.Ellipsis
            )
        }


        if (
            totalCount > 1
        ) {


            Box(
                modifier = Modifier
                    .size(
                        28.dp
                    )
                    .clip(
                        CircleShape
                    )
                    .background(
                        PlexusGreen
                    ),

                contentAlignment =
                    Alignment.Center
            ) {


                Text(
                    text =
                        if (
                            totalCount > 99
                        ) {

                            "99+"

                        } else {

                            totalCount.toString()
                        },

                    color =
                        Color(
                            0xFF001A09
                        ),

                    fontSize =
                        if (
                            totalCount > 99
                        ) {

                            9.sp

                        } else {

                            12.sp
                        },

                    fontWeight =
                        FontWeight.Bold
                )
            }
        }
    }
}


@Composable
private fun PlexusAcceptedContactScreen(
    contact: ContactEntity,
    onBack: () -> Unit,
    onDone: (String) -> Unit
) {

    val announcedName =
        contact
            .announcedDisplayName
            ?.takeIf {

                it.isNotBlank()
            }
            ?: contact.displayName


    var localDisplayName by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(
            announcedName
        )
    }


    val cleanDisplayName =
        localDisplayName.trim()


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                PlexusBlack
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {


        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    64.dp
                )
                .padding(
                    start =
                        8.dp,

                    end =
                        20.dp
                ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {


            IconButton(
                onClick =
                    onBack,

                modifier =
                    Modifier.size(
                        48.dp
                    )
            ) {


                PlexusBackArrow(
                    color =
                        Color.White,

                    modifier =
                        Modifier.size(
                            24.dp
                        )
                )
            }


            Text(
                text =
                    "Connection accepted",

                color =
                    Color.White,

                fontSize =
                    20.sp,

                fontWeight =
                    FontWeight.Bold
            )
        }


        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(
                    1f
                )
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal =
                        24.dp
                ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {


            Spacer(
                modifier =
                    Modifier.height(
                        42.dp
                    )
            )


            PlexusAvatar(
                seed =
                    contact.nodeId,

                size =
                    104.dp,

                borderColor =
                    Color.White
            )


            Spacer(
                modifier =
                    Modifier.height(
                        24.dp
                    )
            )


            Text(
                text =
                    announcedName +
                            " accepted your request",

                color =
                    Color.White,

                fontSize =
                    22.sp,

                fontWeight =
                    FontWeight.Bold,

                textAlign =
                    TextAlign.Center
            )


            Spacer(
                modifier =
                    Modifier.height(
                        9.dp
                    )
            )


            Text(
                text =
                    "You're connected. Choose the name you want to use for this contact.",

                color =
                    PlexusSecondary,

                fontSize =
                    13.sp,

                lineHeight =
                    18.sp,

                textAlign =
                    TextAlign.Center
            )


            Spacer(
                modifier =
                    Modifier.height(
                        42.dp
                    )
            )


            Column(
                modifier =
                    Modifier.fillMaxWidth()
            ) {


                Text(
                    text =
                        "Save as",

                    color =
                        Color.White,

                    fontSize =
                        14.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            9.dp
                        )
                )


                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            54.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                17.dp
                            )
                        )
                        .background(
                            PlexusSurfaceRaised
                        )
                        .border(
                            width =
                                1.dp,

                            color =
                                if (
                                    cleanDisplayName.isNotEmpty()
                                ) {

                                    PlexusGreen.copy(
                                        alpha =
                                            0.58f
                                    )

                                } else {

                                    PlexusDivider
                                },

                            shape =
                                RoundedCornerShape(
                                    17.dp
                                )
                        )
                        .padding(
                            horizontal =
                                16.dp
                        ),

                    contentAlignment =
                        Alignment.CenterStart
                ) {


                    BasicTextField(
                        value =
                            localDisplayName,

                        onValueChange = { newValue ->


                            if (
                                newValue.length <= 40
                            ) {

                                localDisplayName =
                                    newValue
                            }
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine =
                            true,

                        textStyle =
                            TextStyle(

                                color =
                                    Color.White,

                                fontSize =
                                    15.sp,

                                fontWeight =
                                    FontWeight.Medium
                            ),

                        cursorBrush =
                            SolidColor(
                                PlexusGreen
                            ),

                        keyboardOptions =
                            KeyboardOptions(
                                imeAction =
                                    ImeAction.Done
                            ),

                        keyboardActions =
                            KeyboardActions(
                                onDone = {


                                    if (
                                        cleanDisplayName.isNotEmpty()
                                    ) {

                                        onDone(
                                            cleanDisplayName
                                        )
                                    }
                                }
                            )
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            10.dp
                        )
                )


                Text(
                    text =
                        "This name is saved only on your device.",

                    color =
                        PlexusMuted,

                    fontSize =
                        12.sp
                )
            }
        }


        Button(
            onClick = {


                if (
                    cleanDisplayName.isNotEmpty()
                ) {

                    onDone(
                        cleanDisplayName
                    )
                }
            },

            enabled =
                cleanDisplayName.isNotEmpty(),

            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal =
                        20.dp,

                    vertical =
                        18.dp
                )
                .height(
                    52.dp
                ),

            colors =
                ButtonDefaults.buttonColors(

                    containerColor =
                        PlexusGreen,

                    contentColor =
                        Color(
                            0xFF001A09
                        )
                ),

            shape =
                RoundedCornerShape(
                    16.dp
                )
        ) {


            Text(
                text =
                    "Done",

                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}


@Composable
private fun PlexusIncomingRequestCard(
    request: ContactEntity,
    onAccept: suspend (ContactEntity, String) -> Boolean,
    onDecline: suspend (ContactEntity) -> Boolean
) {

    val announcedName =
        request
            .announcedDisplayName
            ?.takeIf {

                it.isNotBlank()
            }
            ?: request.displayName


    var isNaming by
    remember(
        request.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    var localDisplayName by
    remember(
        request.nodeId
    ) {

        mutableStateOf(
            announcedName
        )
    }


    var visible by
    remember(
        request.nodeId
    ) {

        mutableStateOf(
            true
        )
    }


    var actionInProgress by
    remember(
        request.nodeId
    ) {

        mutableStateOf(
            false
        )
    }


    var dismissDirection by
    remember(
        request.nodeId
    ) {

        mutableStateOf(
            1
        )
    }


    val coroutineScope =
        rememberCoroutineScope()


    val cleanDisplayName =
        localDisplayName.trim()


    val decline = {

        if (
            !actionInProgress
        ) {

            actionInProgress =
                true

            dismissDirection =
                -1

            visible =
                false


            coroutineScope.launch {

                delay(
                    240
                )


                val success =
                    onDecline(
                        request
                    )


                if (
                    !success
                ) {

                    visible =
                        true

                    actionInProgress =
                        false
                }
            }
        }
    }


    val save = {

        if (
            !actionInProgress &&
            cleanDisplayName.isNotEmpty()
        ) {

            val nameToSave =
                cleanDisplayName


            actionInProgress =
                true

            dismissDirection =
                1

            visible =
                false


            coroutineScope.launch {

                delay(
                    240
                )


                val success =
                    onAccept(
                        request,
                        nameToSave
                    )


                if (
                    !success
                ) {

                    visible =
                        true

                    actionInProgress =
                        false
                }
            }
        }
    }


    AnimatedVisibility(
        visible =
            visible,

        enter =
            expandVertically() +
                    fadeIn(),

        exit =
            slideOutHorizontally { fullWidth ->

                fullWidth *
                        dismissDirection
            } +
                    shrinkVertically() +
                    fadeOut()
    ) {


        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal =
                        16.dp
                )
                .clip(
                    RoundedCornerShape(
                        20.dp
                    )
                )
                .background(
                    PlexusSurface
                )
                .animateContentSize()
        ) {


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal =
                            14.dp,

                        vertical =
                            13.dp
                    ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {


                PlexusAvatar(
                    seed =
                        request.nodeId,

                    size =
                        48.dp,

                    borderColor =
                        Color.Transparent
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
                            announcedName,

                        color =
                            Color.White,

                        fontSize =
                            16.sp,

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
                            "wants to connect",

                        color =
                            PlexusSecondary,

                        fontSize =
                            13.sp
                    )
                }


                if (
                    !isNaming
                ) {


                    Spacer(
                        modifier =
                            Modifier.width(
                                10.dp
                            )
                    )


                    PlexusRequestCircleActionButton(
                        accepted =
                            false,

                        enabled =
                            !actionInProgress,

                        onClick = {

                            decline()
                        }
                    )


                    Spacer(
                        modifier =
                            Modifier.width(
                                8.dp
                            )
                    )


                    PlexusRequestCircleActionButton(
                        accepted =
                            true,

                        enabled =
                            !actionInProgress,

                        onClick = {

                            isNaming =
                                true
                        }
                    )
                }
            }


            AnimatedVisibility(
                visible =
                    isNaming,

                enter =
                    expandVertically() +
                            fadeIn(),

                exit =
                    shrinkVertically() +
                            fadeOut()
            ) {


                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start =
                                14.dp,

                            end =
                                14.dp,

                            bottom =
                                14.dp
                        )
                ) {


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


                    Spacer(
                        modifier =
                            Modifier.height(
                                12.dp
                            )
                    )


                    Text(
                        text =
                            "Save as",

                        color =
                            Color.White,

                        fontSize =
                            13.sp,

                        fontWeight =
                            FontWeight.SemiBold
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )


                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {


                        Box(
                            modifier = Modifier
                                .weight(
                                    1f
                                )
                                .height(
                                    50.dp
                                )
                                .clip(
                                    RoundedCornerShape(
                                        16.dp
                                    )
                                )
                                .background(
                                    PlexusSurfaceRaised
                                )
                                .border(
                                    width =
                                        1.dp,

                                    color =
                                        if (
                                            cleanDisplayName.isNotEmpty()
                                        ) {

                                            PlexusGreen.copy(
                                                alpha =
                                                    0.58f
                                            )

                                        } else {

                                            PlexusDivider
                                        },

                                    shape =
                                        RoundedCornerShape(
                                            16.dp
                                        )
                                )
                                .padding(
                                    horizontal =
                                        15.dp
                                ),

                            contentAlignment =
                                Alignment.CenterStart
                        ) {


                            BasicTextField(
                                value =
                                    localDisplayName,

                                onValueChange = { newValue ->

                                    if (
                                        newValue.length <= 40
                                    ) {

                                        localDisplayName =
                                            newValue
                                    }
                                },

                                modifier =
                                    Modifier.fillMaxWidth(),

                                singleLine =
                                    true,

                                textStyle =
                                    TextStyle(

                                        color =
                                            Color.White,

                                        fontSize =
                                            15.sp,

                                        fontWeight =
                                            FontWeight.Medium
                                    ),

                                cursorBrush =
                                    SolidColor(
                                        PlexusGreen
                                    ),

                                keyboardOptions =
                                    KeyboardOptions(
                                        imeAction =
                                            ImeAction.Done
                                    ),

                                keyboardActions =
                                    KeyboardActions(
                                        onDone = {

                                            save()
                                        }
                                    )
                            )
                        }


                        Spacer(
                            modifier =
                                Modifier.width(
                                    10.dp
                                )
                        )


                        Button(
                            onClick = {

                                save()
                            },

                            enabled =
                                cleanDisplayName.isNotEmpty() &&
                                        !actionInProgress,

                            modifier =
                                Modifier
                                    .width(
                                        86.dp
                                    )
                                    .height(
                                        50.dp
                                    ),

                            colors =
                                ButtonDefaults.buttonColors(

                                    containerColor =
                                        PlexusGreen,

                                    contentColor =
                                        Color(
                                            0xFF001A09
                                        ),

                                    disabledContainerColor =
                                        PlexusSurfaceRaised,

                                    disabledContentColor =
                                        PlexusMuted
                                ),

                            shape =
                                RoundedCornerShape(
                                    16.dp
                                ),

                            contentPadding =
                                PaddingValues(
                                    horizontal =
                                        10.dp
                                )
                        ) {


                            Text(
                                text =
                                    "Save",

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }


                    Spacer(
                        modifier =
                            Modifier.height(
                                8.dp
                            )
                    )


                    Text(
                        text =
                            "Saved only on this device.",

                        color =
                            PlexusMuted,

                        fontSize =
                            11.5.sp
                    )
                }
            }
        }
    }
}


@Composable
private fun PlexusRequestCircleActionButton(
    accepted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {

    val neonColor =
        if (
            accepted
        ) {

            PlexusGreen

        } else {

            Color(
                0xFFFF5A72
            )
        }


    val backgroundColor =
        neonColor.copy(
            alpha =
                0.14f
        )


    Box(
        modifier = Modifier
            .size(
                42.dp
            )
            .shadow(
                elevation =
                    10.dp,

                shape =
                    CircleShape,

                clip =
                    false
            )
            .clip(
                CircleShape
            )
            .background(
                backgroundColor
            )
            .border(
                width =
                    1.6.dp,

                color =
                    neonColor,

                shape =
                    CircleShape
            )
            .clickable(
                enabled =
                    enabled
            ) {

                onClick()
            },

        contentAlignment =
            Alignment.Center
    ) {


        PlexusRequestActionIcon(
            accepted =
                accepted,

            color =
                neonColor,

            modifier =
                Modifier.size(
                    17.dp
                )
        )
    }
}


@Composable
private fun PlexusRequestActionIcon(
    accepted: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {


    Canvas(
        modifier =
            modifier
    ) {


        val strokeWidth =
            size.minDimension *
                    0.14f


        if (
            accepted
        ) {


            drawLine(
                color =
                    color,

                start =
                    Offset(
                        x =
                            size.width *
                                    0.17f,

                        y =
                            size.height *
                                    0.55f
                    ),

                end =
                    Offset(
                        x =
                            size.width *
                                    0.39f,

                        y =
                            size.height *
                                    0.77f
                    ),

                strokeWidth =
                    strokeWidth,

                cap =
                    StrokeCap.Round
            )


            drawLine(
                color =
                    color,

                start =
                    Offset(
                        x =
                            size.width *
                                    0.39f,

                        y =
                            size.height *
                                    0.77f
                    ),

                end =
                    Offset(
                        x =
                            size.width *
                                    0.84f,

                        y =
                            size.height *
                                    0.28f
                    ),

                strokeWidth =
                    strokeWidth,

                cap =
                    StrokeCap.Round
            )


        } else {


            drawLine(
                color =
                    color,

                start =
                    Offset(
                        x =
                            size.width *
                                    0.23f,

                        y =
                            size.height *
                                    0.23f
                    ),

                end =
                    Offset(
                        x =
                            size.width *
                                    0.77f,

                        y =
                            size.height *
                                    0.77f
                    ),

                strokeWidth =
                    strokeWidth,

                cap =
                    StrokeCap.Round
            )


            drawLine(
                color =
                    color,

                start =
                    Offset(
                        x =
                            size.width *
                                    0.77f,

                        y =
                            size.height *
                                    0.23f
                    ),

                end =
                    Offset(
                        x =
                            size.width *
                                    0.23f,

                        y =
                            size.height *
                                    0.77f
                    ),

                strokeWidth =
                    strokeWidth,

                cap =
                    StrokeCap.Round
            )
        }
    }
}


private fun Modifier.clearFocusAfterTap(
    onClearFocus: () -> Unit
): Modifier =
    pointerInput(onClearFocus) {

        awaitPointerEventScope {

            while (true) {

                val event =
                    awaitPointerEvent(
                        PointerEventPass.Final
                    )


                val released =
                    event.changes.any { change ->

                        change.previousPressed &&
                                !change.pressed
                    }


                if (
                    released
                ) {

                    onClearFocus()
                }
            }
        }
    }


@Composable
fun PlexusHomeScreen(
    contacts: List<ContactEntity>,
    incomingRequests: List<ContactEntity>,
    acceptedContactsNeedingName: List<ContactEntity>,
    conversationSummaries: List<ConversationSummary>,
    hiddenContactIds: List<String>,
    identity: PlexusIdentity,
    onIncomingRequestAccept: suspend (ContactEntity, String) -> Boolean,
    onIncomingRequestDecline: suspend (ContactEntity) -> Boolean,
    onAcceptedContactClick: (ContactEntity) -> Unit,
    onProfileClick: () -> Unit,
    onScanClick: () -> Unit,
    onContactClick: (ContactEntity) -> Unit,
    onDeleteChat: (ContactEntity) -> Unit
) {

    var searchText by
    remember {

        mutableStateOf("")
    }


    val focusManager =
        LocalFocusManager.current


    val clearSearchFocus = {

        focusManager.clearFocus()
    }


    /*
     * Home owns the search query, so it must also own Back while a search
     * is active. Otherwise the Activity receives Back and exits Plexus.
     * Clearing focus also dismisses the keyboard.
     */
    BackHandler(
        enabled =
            searchText.isNotEmpty()
    ) {

        searchText =
            ""

        clearSearchFocus()
    }


    val summaryByNodeId =
        remember(
            conversationSummaries
        ) {

            conversationSummaries
                .associateBy { summary ->

                    summary.contactNodeId
                }
        }


    val hiddenContactIdSet =
        remember(
            hiddenContactIds
        ) {

            hiddenContactIds
                .toHashSet()
        }


    val orderedContacts =
        remember(
            contacts,
            conversationSummaries,
            hiddenContactIdSet
        ) {

            contacts
                .filterNot { contact ->

                    contact.nodeId in
                            hiddenContactIdSet
                }
                .sortedWith(

                    compareByDescending<ContactEntity> { contact ->

                        summaryByNodeId[
                            contact.nodeId
                        ]
                            ?.latestMessageTimestamp
                            ?: Long.MIN_VALUE
                    }
                        .thenBy { contact ->

                            contact.displayName
                                .lowercase(
                                    Locale.getDefault()
                                )
                        }
                )
        }


    val visibleContacts =
        remember(
            orderedContacts,
            searchText
        ) {

            if (
                searchText.isBlank()
            ) {

                orderedContacts

            } else {

                orderedContacts.filter { contact ->

                    contact.displayName.contains(
                        searchText.trim(),
                        ignoreCase = true
                    )
                }
            }
        }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                PlexusBlack
            )
            .statusBarsPadding()
    ) {


        /*
         * =====================================================
         * HEADER
         * =====================================================
         */

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearFocusAfterTap(
                    clearSearchFocus
                )
                .height(
                    72.dp
                )
                .padding(
                    start = 20.dp,
                    end = 16.dp
                ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {


            Text(
                text =
                    "Plexus",

                color =
                    Color.White,

                fontSize =
                    30.sp,

                fontWeight =
                    FontWeight.Black,

                modifier =
                    Modifier.weight(
                        1f
                    )
            )


            IconButton(
                onClick = {

                    clearSearchFocus()
                    onScanClick()
                },

                modifier =
                    Modifier.size(
                        42.dp
                    )
            ) {

                PlexusScannerIcon(
                    color =
                        Color.White,

                    modifier =
                        Modifier.size(
                            24.dp
                        )
                )
            }


            Spacer(
                modifier =
                    Modifier.width(
                        10.dp
                    )
            )


            Box(
                modifier = Modifier
                    .size(
                        42.dp
                    )
                    .clickable {

                        clearSearchFocus()
                        onProfileClick()
                    },

                contentAlignment =
                    Alignment.Center
            ) {

                PlexusAvatar(
                    seed =
                        identity.nodeId,

                    size =
                        42.dp,

                    borderColor =
                        Color.White
                )
            }
        }



        /*
         * =====================================================
         * SLIM SEARCH BAR
         * =====================================================
         */

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal =
                        16.dp
                )
                .height(
                    50.dp
                )
                .clip(
                    RoundedCornerShape(
                        25.dp
                    )
                )
                .background(
                    PlexusSurfaceRaised
                )
                .padding(
                    horizontal =
                        15.dp
                ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {


            PlexusSearchIcon(
                color =
                    PlexusSecondary,

                modifier =
                    Modifier.size(
                        22.dp
                    )
            )


            Spacer(
                modifier =
                    Modifier.width(
                        12.dp
                    )
            )


            Box(
                modifier =
                    Modifier.weight(
                        1f
                    ),

                contentAlignment =
                    Alignment.CenterStart
            ) {


                if (
                    searchText.isEmpty()
                ) {

                    Text(
                        text =
                            "Search chats",

                        color =
                            PlexusMuted,

                        fontSize =
                            15.sp
                    )
                }


                BasicTextField(
                    value =
                        searchText,

                    onValueChange = {

                        searchText =
                            it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    singleLine =
                        true,

                    textStyle =
                        TextStyle(

                            color =
                                Color.White,

                            fontSize =
                                15.sp,

                            fontWeight =
                                FontWeight.Normal
                        ),

                    cursorBrush =
                        SolidColor(
                            PlexusGreen
                        ),

                    keyboardOptions =
                        KeyboardOptions(
                            imeAction =
                                ImeAction.Search
                        ),

                    keyboardActions =
                        KeyboardActions(
                            onSearch = {

                                clearSearchFocus()
                            }
                        )
                )
            }
        }



        Spacer(
            modifier =
                Modifier.height(
                    17.dp
                )
        )


        if (
            acceptedContactsNeedingName.isNotEmpty() &&
            searchText.isBlank()
        ) {


            PlexusAcceptedContactBanner(

                contact =
                    acceptedContactsNeedingName.first(),

                totalCount =
                    acceptedContactsNeedingName.size,

                onClick = {

                    clearSearchFocus()

                    onAcceptedContactClick(
                        acceptedContactsNeedingName.first()
                    )
                }
            )


            Spacer(
                modifier =
                    Modifier.height(
                        15.dp
                    )
            )
        }


        if (
            incomingRequests.isNotEmpty() &&
            searchText.isBlank()
        ) {


            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {


                incomingRequests.forEach { request ->


                    key(
                        request.nodeId
                    ) {


                        PlexusIncomingRequestCard(

                            request =
                                request,

                            onAccept =
                                onIncomingRequestAccept,

                            onDecline =
                                onIncomingRequestDecline
                        )
                    }
                }
            }


            Spacer(
                modifier =
                    Modifier.height(
                        15.dp
                    )
            )
        }


        if (
            contacts.isEmpty()
        ) {


            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clearFocusAfterTap(
                        clearSearchFocus
                    )
                    .padding(
                        horizontal =
                            24.dp
                    ),

                horizontalAlignment =
                    Alignment.CenterHorizontally,

                verticalArrangement =
                    Arrangement.Center
            ) {


                Box(
                    modifier = Modifier
                        .size(
                            88.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                24.dp
                            )
                        )
                        .background(
                            PlexusSurface
                        ),

                    contentAlignment =
                        Alignment.Center
                ) {

                    PlexusScannerIcon(
                        color =
                            PlexusGreen,

                        modifier =
                            Modifier.size(
                                35.dp
                            )
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            22.dp
                        )
                )


                Text(
                    text =
                        "No conversations yet",

                    color =
                        Color.White,

                    fontSize =
                        20.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            8.dp
                        )
                )


                Text(
                    text =
                        "Scan a Plexus identity to start chatting.",

                    color =
                        PlexusSecondary,

                    fontSize =
                        13.5.sp,

                    textAlign =
                        TextAlign.Center
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            24.dp
                        )
                )


                Button(
                    onClick = {

                        clearSearchFocus()
                        onScanClick()
                    },

                    colors =
                        ButtonDefaults.buttonColors(

                            containerColor =
                                PlexusGreen,

                            contentColor =
                                Color(
                                    0xFF001A09
                                )
                        ),

                    shape =
                        RoundedCornerShape(
                            16.dp
                        ),

                    contentPadding =
                        PaddingValues(
                            horizontal =
                                22.dp,

                            vertical =
                                11.dp
                        )
                ) {


                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {


                        PlexusScannerIcon(
                            color =
                                Color(
                                    0xFF001A09
                                ),

                            modifier =
                                Modifier.size(
                                    17.dp
                                )
                        )


                        Spacer(
                            modifier =
                                Modifier.width(
                                    9.dp
                                )
                        )


                        Text(
                            text =
                                "Scan identity",

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }


        } else if (
            visibleContacts.isEmpty()
        ) {


            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearFocusAfterTap(
                        clearSearchFocus
                    )
                    .padding(
                        top =
                            56.dp
                    ),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {


                Text(
                    text =
                        "No chats found",

                    color =
                        Color.White,

                    fontSize =
                        16.sp,

                    fontWeight =
                        FontWeight.Medium
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            6.dp
                        )
                )


                Text(
                    text =
                        "Try another name.",

                    color =
                        PlexusMuted,

                    fontSize =
                        13.sp
                )
            }


        } else {


            Text(
                text =
                    "Chats",

                color =
                    PlexusSecondary,

                fontSize =
                    14.sp,

                fontWeight =
                    FontWeight.Medium,

                modifier =
                    Modifier.padding(
                        start =
                            20.dp,

                        bottom =
                            5.dp
                    )
            )


            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clearFocusAfterTap(
                            clearSearchFocus
                        ),

                contentPadding =
                    PaddingValues(
                        bottom =
                            24.dp
                    )
            ) {


                items(
                    items =
                        visibleContacts,

                    key = { contact ->

                        contact.nodeId
                    }
                ) { contact ->


                    PlexusConversationRow(

                        contact =
                            contact,

                        summary =
                            summaryByNodeId[
                                contact.nodeId
                            ],

                        onClick = {

                            clearSearchFocus()

                            onContactClick(
                                contact
                            )
                        },

                        onDeleteChat = {

                            clearSearchFocus()

                            onDeleteChat(
                                contact
                            )
                        }
                    )
                }
            }
        }
    }
}


@Composable
private fun PlexusConversationRow(
    contact: ContactEntity,
    summary: ConversationSummary?,
    onClick: () -> Unit,
    onDeleteChat: () -> Unit
) {

    var showDeleteDialog by
    remember(
        contact.nodeId
    ) {

        mutableStateOf(false)
    }


    val unreadCount =
        summary
            ?.unreadCount
            ?: 0L


    val hasUnread =
        unreadCount > 0L


    val previewText =
        when {

            summary == null -> {

                "Start a conversation"
            }


            summary.latestMessageIsMine -> {

                "You: ${summary.latestMessageText}"
            }


            else -> {

                summary.latestMessageText
            }
        }


    val timeText =
        remember(
            summary?.latestMessageTimestamp
        ) {

            summary
                ?.let { conversation ->

                    SimpleDateFormat(
                        "h:mm a",
                        Locale.getDefault()
                    )
                        .format(
                            Date(
                                conversation.latestMessageTimestamp
                            )
                        )
                }
                ?: ""
        }


    Column(
        modifier =
            Modifier.fillMaxWidth()
    ) {


        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(

                    onClick = {

                        onClick()
                    },

                    onLongClick = {

                        showDeleteDialog =
                            true
                    }
                )
                .padding(
                    start =
                        15.dp,

                    end =
                        18.dp,

                    top =
                        9.dp,

                    bottom =
                        9.dp
                ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {


            PlexusAvatar(
                seed =
                    contact.nodeId,

                size =
                    48.dp,

                borderColor =
                    Color.Transparent
            )


            Spacer(
                modifier =
                    Modifier.width(
                        13.dp
                    )
            )


            Column(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {


                    Text(
                        text =
                            contact.displayName,

                        color =
                            Color.White,

                        fontSize =
                            16.sp,

                        fontWeight =
                            if (
                                hasUnread
                            ) {

                                FontWeight.Bold

                            } else {

                                FontWeight.SemiBold
                            },

                        maxLines =
                            1,

                        overflow =
                            TextOverflow.Ellipsis,

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    )


                    if (
                        timeText.isNotEmpty()
                    ) {


                        Spacer(
                            modifier =
                                Modifier.width(
                                    10.dp
                                )
                        )


                        Text(
                            text =
                                timeText,

                            color =
                                if (
                                    hasUnread
                                ) {

                                    PlexusGreen

                                } else {

                                    PlexusMuted
                                },

                            fontSize =
                                11.sp,

                            fontWeight =
                                if (
                                    hasUnread
                                ) {

                                    FontWeight.SemiBold

                                } else {

                                    FontWeight.Medium
                                }
                        )
                    }
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            3.dp
                        )
                )


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {


                    Text(
                        text =
                            previewText,

                        color =
                            if (
                                hasUnread
                            ) {

                                Color.White

                            } else {

                                PlexusSecondary
                            },

                        fontSize =
                            13.2.sp,

                        fontWeight =
                            if (
                                hasUnread
                            ) {

                                FontWeight.Medium

                            } else {

                                FontWeight.Normal
                            },

                        maxLines =
                            1,

                        overflow =
                            TextOverflow.Ellipsis,

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    )


                    if (
                        hasUnread
                    ) {


                        Spacer(
                            modifier =
                                Modifier.width(
                                    10.dp
                                )
                        )


                        Box(
                            modifier = Modifier
                                .size(
                                    22.dp
                                )
                                .clip(
                                    CircleShape
                                )
                                .background(
                                    PlexusGreen
                                ),

                            contentAlignment =
                                Alignment.Center
                        ) {


                            Text(
                                text =
                                    if (
                                        unreadCount > 99L
                                    ) {

                                        "99+"

                                    } else {

                                        unreadCount.toString()
                                    },

                                color =
                                    Color(
                                        0xFF001A09
                                    ),

                                fontSize =
                                    if (
                                        unreadCount > 99L
                                    ) {

                                        8.sp

                                    } else {

                                        12.sp
                                    },

                                fontWeight =
                                    FontWeight.Black,

                                lineHeight =
                                    if (
                                        unreadCount > 99L
                                    ) {

                                        8.sp

                                    } else {

                                        12.sp
                                    },

                                textAlign =
                                    TextAlign.Center
                            )
                        }
                    }
                }
            }
        }


        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start =
                        79.dp,

                    end =
                        18.dp
                )
                .height(
                    0.7.dp
                )
                .background(
                    PlexusDivider
                )
        )
    }


    if (
        showDeleteDialog
    ) {

        Dialog(
            onDismissRequest = {

                showDeleteDialog =
                    false
            }
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(
                            22.dp
                        )
                    )
                    .background(
                        PlexusSurfaceRaised
                    )
                    .border(
                        width =
                            1.dp,

                        color =
                            PlexusDivider,

                        shape =
                            RoundedCornerShape(
                                22.dp
                            )
                    )
                    .padding(
                        horizontal =
                            20.dp,

                        vertical =
                            18.dp
                    )
            ) {

                Text(
                    text =
                        "Delete chat?",

                    color =
                        Color.White,

                    fontSize =
                        18.sp,

                    fontWeight =
                        FontWeight.Bold
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            7.dp
                        )
                )


                Text(
                    text =
                        "Delete the local chat with ${contact.displayName}? The trusted contact stays saved, and a future message will make this chat reappear.",

                    color =
                        PlexusSecondary,

                    fontSize =
                        13.sp,

                    lineHeight =
                        18.sp
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            18.dp
                        )
                )


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.End,

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        text =
                            "Cancel",

                        color =
                            PlexusSecondary,

                        fontSize =
                            14.sp,

                        fontWeight =
                            FontWeight.SemiBold,

                        modifier =
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        12.dp
                                    )
                                )
                                .clickable {

                                    showDeleteDialog =
                                        false
                                }
                                .padding(
                                    horizontal =
                                        14.dp,

                                    vertical =
                                        10.dp
                                )
                    )


                    Spacer(
                        modifier =
                            Modifier.width(
                                6.dp
                            )
                    )


                    Text(
                        text =
                            "Delete chat",

                        color =
                            Color(0xFFFF6B6B),

                        fontSize =
                            14.sp,

                        fontWeight =
                            FontWeight.Bold,

                        modifier =
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        12.dp
                                    )
                                )
                                .clickable {

                                    showDeleteDialog =
                                        false

                                    onDeleteChat()
                                }
                                .padding(
                                    horizontal =
                                        14.dp,

                                    vertical =
                                        10.dp
                                )
                    )
                }
            }
        }
    }
}

@Composable
fun PlexusProfileScreen(
    identity: PlexusIdentity,
    onBack: () -> Unit,
    onScanClick: () -> Unit,
    onDisplayNameChanged: (String) -> Unit
) {

    val context =
        LocalContext.current


    val scrollState =
        rememberScrollState()


    var showEditNameDialog by
    remember {

        mutableStateOf(false)
    }


    var editedDisplayName by
    remember(
        identity.displayName
    ) {

        mutableStateOf(
            identity.displayName
        )
    }


    val qrBitmap =
        remember(
            identity.qrPayload
        ) {

            generateQrCode(
                content =
                    identity.qrPayload,

                size =
                    900
            )
        }


    val shareableLink =
        remember(
            identity.qrPayload
        ) {

            PlexusDeepLink
                .buildShareLink(
                    identity.qrPayload
                )
        }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                PlexusBlack
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(
                scrollState
            )
            .padding(
                horizontal =
                    20.dp
            ),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {


        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    72.dp
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

                PlexusBackArrow(
                    color =
                        Color.White,

                    modifier =
                        Modifier.size(
                            22.dp
                        )
                )
            }


            Spacer(
                modifier =
                    Modifier.width(
                        6.dp
                    )
            )


            Text(
                text =
                    "Your Plexus",

                color =
                    Color.White,

                fontSize =
                    20.sp,

                fontWeight =
                    FontWeight.SemiBold
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        PlexusAvatar(
            seed =
                identity.nodeId,

            size =
                84.dp,

            borderColor =
                Color.White
        )


        Spacer(
            modifier =
                Modifier.height(
                    13.dp
                )
        )


        Row(
            verticalAlignment =
                Alignment.CenterVertically,

            horizontalArrangement =
                Arrangement.Center
        ) {

            Text(
                text =
                    identity.displayName,

                color =
                    Color.White,

                fontSize =
                    22.sp,

                fontWeight =
                    FontWeight.Bold
            )


            Spacer(
                modifier =
                    Modifier.width(
                        9.dp
                    )
            )


            Text(
                text =
                    "Edit",

                color =
                    PlexusGreen,

                fontSize =
                    12.5.sp,

                fontWeight =
                    FontWeight.SemiBold,

                modifier =
                    Modifier
                        .clip(
                            RoundedCornerShape(
                                12.dp
                            )
                        )
                        .background(
                            PlexusGreen.copy(
                                alpha =
                                    0.10f
                            )
                        )
                        .border(
                            width =
                                1.dp,

                            color =
                                PlexusGreen.copy(
                                    alpha =
                                        0.32f
                                ),

                            shape =
                                RoundedCornerShape(
                                    12.dp
                                )
                        )
                        .clickable {

                            editedDisplayName =
                                identity.displayName

                            showEditNameDialog =
                                true
                        }
                        .padding(
                            horizontal =
                                10.dp,

                            vertical =
                                5.dp
                        )
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    8.dp
                )
        )


        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        50.dp
                    )
                )
                .background(
                    PlexusSurfaceRaised
                )
                .border(
                    width =
                        1.dp,

                    color =
                        PlexusGreen.copy(
                            alpha = 0.45f
                        ),

                    shape =
                        RoundedCornerShape(
                            50.dp
                        )
                )
                .padding(
                    horizontal =
                        13.dp,

                    vertical =
                        6.dp
                )
        ) {


            Text(
                text =
                    shortenNodeId(
                        identity.nodeId
                    ),

                color =
                    PlexusSecondary,

                fontSize =
                    11.sp
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    22.dp
                )
        )


        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        22.dp
                    )
                )
                .background(
                    PlexusSurface
                )
                .border(
                    width =
                        1.5.dp,

                    color =
                        PlexusGreen.copy(
                            alpha = 0.65f
                        ),

                    shape =
                        RoundedCornerShape(
                            22.dp
                        )
                )
                .padding(
                    horizontal =
                        18.dp,

                    vertical =
                        20.dp
                )
        ) {


            Column(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {


                Box(
                    modifier = Modifier
                        .size(
                            232.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                24.dp
                            )
                        )
                        .background(
                            Color.White
                        )
                        .padding(
                            14.dp
                        ),

                    contentAlignment =
                        Alignment.Center
                ) {


                    Image(
                        bitmap =
                            qrBitmap
                                .asImageBitmap(),

                        contentDescription =
                            "Your Plexus identity QR",

                        modifier =
                            Modifier.fillMaxSize()
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            18.dp
                        )
                )


                Text(
                    text =
                        "Your identity QR",

                    color =
                        Color.White,

                    fontSize =
                        14.5.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            4.dp
                        )
                )


                Text(
                    text =
                        "Let another Plexus user scan this code.",

                    color =
                        PlexusSecondary,

                    fontSize =
                        12.sp,

                    textAlign =
                        TextAlign.Center
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            20.dp
                        )
                )


                Text(
                    text =
                        "Shareable link",

                    color =
                        Color.White,

                    fontSize =
                        13.sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    modifier =
                        Modifier.align(
                            Alignment.Start
                        )
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            8.dp
                        )
                )


                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                15.dp
                            )
                        )
                        .background(
                            PlexusSurfaceRaised
                        )
                        .border(
                            width =
                                1.dp,

                            color =
                                PlexusGreen.copy(
                                    alpha = 0.35f
                                ),

                            shape =
                                RoundedCornerShape(
                                    15.dp
                                )
                        )
                        .padding(
                            horizontal =
                                13.dp,

                            vertical =
                                11.dp
                        )
                ) {


                    Text(
                        text =
                            shareableLink,

                        color =
                            PlexusSecondary,

                        fontSize =
                            11.sp,

                        lineHeight =
                            15.sp,

                        maxLines =
                            3,

                        overflow =
                            TextOverflow.Ellipsis
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            14.dp
                        )
                )


                Button(
                    onClick = {


                        val sendIntent =
                            Intent(
                                Intent.ACTION_SEND
                            )
                                .apply {


                                    type =
                                        "text/plain"


                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        "Connect with me on Plexus"
                                    )


                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Connect with me on Plexus:\n" +
                                                shareableLink
                                    )
                                }


                        context.startActivity(

                            Intent.createChooser(
                                sendIntent,
                                "Share Plexus link"
                            )
                        )
                    },

                    colors =
                        ButtonDefaults.buttonColors(

                            containerColor =
                                PlexusGreen,

                            contentColor =
                                Color(
                                    0xFF001A09
                                )
                        ),

                    shape =
                        RoundedCornerShape(
                            16.dp
                        ),

                    contentPadding =
                        PaddingValues(
                            horizontal =
                                24.dp,

                            vertical =
                                11.dp
                        )
                ) {


                    Text(
                        text =
                            "Share link",

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )


        Text(
            text =
                "Only your public identity is shared.",

            color =
                PlexusMuted,

            fontSize =
                11.sp
        )


        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )


        Button(
            onClick =
                onScanClick,

            colors =
                ButtonDefaults.buttonColors(

                    containerColor =
                        PlexusGreen,

                    contentColor =
                        Color(
                            0xFF001A09
                        )
                ),

            shape =
                RoundedCornerShape(
                    16.dp
                ),

            contentPadding =
                PaddingValues(
                    horizontal =
                        22.dp,

                    vertical =
                        11.dp
                )
        ) {


            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {


                PlexusScannerIcon(
                    color =
                        Color(
                            0xFF001A09
                        ),

                    modifier =
                        Modifier.size(
                            17.dp
                        )
                )


                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )


                Text(
                    text =
                        "Scan someone",

                    fontWeight =
                        FontWeight.Bold
                )
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    24.dp
                )
        )
    }



    if (
        showEditNameDialog
    ) {

        Dialog(
            onDismissRequest = {

                showEditNameDialog =
                    false
            }
        ) {

            val cleanName =
                editedDisplayName.trim()


            val saveName = {

                if (
                    cleanName.isNotEmpty()
                ) {

                    onDisplayNameChanged(
                        cleanName
                    )

                    showEditNameDialog =
                        false
                }
            }


            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(
                            22.dp
                        )
                    )
                    .background(
                        PlexusSurfaceRaised
                    )
                    .border(
                        width =
                            1.dp,

                        color =
                            PlexusDivider,

                        shape =
                            RoundedCornerShape(
                                22.dp
                            )
                    )
                    .padding(
                        horizontal =
                            20.dp,

                        vertical =
                            18.dp
                    )
            ) {

                Text(
                    text =
                        "Edit your name",

                    color =
                        Color.White,

                    fontSize =
                        18.sp,

                    fontWeight =
                        FontWeight.Bold
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            6.dp
                        )
                )


                Text(
                    text =
                        "This changes your Plexus profile name. Your node identity, keys, contacts and existing chats stay the same.",

                    color =
                        PlexusSecondary,

                    fontSize =
                        12.5.sp,

                    lineHeight =
                        18.sp
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            15.dp
                        )
                )


                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            50.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                15.dp
                            )
                        )
                        .background(
                            PlexusBlack
                        )
                        .border(
                            width =
                                1.dp,

                            color =
                                if (
                                    cleanName.isNotEmpty()
                                ) {

                                    PlexusGreen.copy(
                                        alpha =
                                            0.55f
                                    )

                                } else {

                                    PlexusDivider
                                },

                            shape =
                                RoundedCornerShape(
                                    15.dp
                                )
                        )
                        .padding(
                            horizontal =
                                14.dp
                        ),

                    contentAlignment =
                        Alignment.CenterStart
                ) {

                    BasicTextField(
                        value =
                            editedDisplayName,

                        onValueChange = { newValue ->

                            if (
                                newValue.length <= 40
                            ) {

                                editedDisplayName =
                                    newValue
                            }
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine =
                            true,

                        textStyle =
                            TextStyle(

                                color =
                                    Color.White,

                                fontSize =
                                    15.sp,

                                fontWeight =
                                    FontWeight.Medium
                            ),

                        cursorBrush =
                            SolidColor(
                                PlexusGreen
                            ),

                        keyboardOptions =
                            KeyboardOptions(
                                imeAction =
                                    ImeAction.Done
                            ),

                        keyboardActions =
                            KeyboardActions(
                                onDone = {

                                    saveName()
                                }
                            )
                    )
                }


                Spacer(
                    modifier =
                        Modifier.height(
                            16.dp
                        )
                )


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.End,

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        text =
                            "Cancel",

                        color =
                            PlexusSecondary,

                        fontSize =
                            14.sp,

                        fontWeight =
                            FontWeight.SemiBold,

                        modifier =
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        12.dp
                                    )
                                )
                                .clickable {

                                    showEditNameDialog =
                                        false
                                }
                                .padding(
                                    horizontal =
                                        14.dp,

                                    vertical =
                                        10.dp
                                )
                    )


                    Spacer(
                        modifier =
                            Modifier.width(
                                6.dp
                            )
                    )


                    Text(
                        text =
                            "Save",

                        color =
                            if (
                                cleanName.isNotEmpty()
                            ) {

                                PlexusGreen

                            } else {

                                PlexusMuted
                            },

                        fontSize =
                            14.sp,

                        fontWeight =
                            FontWeight.Bold,

                        modifier =
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        12.dp
                                    )
                                )
                                .clickable(
                                    enabled =
                                        cleanName.isNotEmpty()
                                ) {

                                    saveName()
                                }
                                .padding(
                                    horizontal =
                                        14.dp,

                                    vertical =
                                        10.dp
                                )
                    )
                }
            }
        }
    }
}


@Composable
private fun PlexusAvatar(
    seed: String,
    size: Dp,
    borderColor: Color
) {

    /*
     * The avatar is fully deterministic from the Plexus nodeId.
     *
     * The same identity therefore gets the same avatar on every
     * device without transferring or synchronizing an image.
     *
     * More palettes + multiple blob layouts keep contacts from
     * looking like minor variations of the same green avatar.
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
            .then(

                if (
                    borderColor ==
                    Color.Transparent
                ) {

                    Modifier

                } else {

                    Modifier.border(
                        width =
                            1.5.dp,

                        color =
                            borderColor.copy(
                                alpha = 0.90f
                            ),

                        shape =
                            CircleShape
                    )
                }
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
private fun PlexusSearchIcon(
    color: Color,
    modifier: Modifier = Modifier
) {

    Canvas(
        modifier =
            modifier
    ) {

        val stroke =
            2.2.dp.toPx()


        val radius =
            size.minDimension * 0.27f


        val centerPoint =
            Offset(
                size.width * 0.41f,
                size.height * 0.41f
            )


        drawCircle(
            color =
                color,

            radius =
                radius,

            center =
                centerPoint,

            style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                    width =
                        stroke
                )
        )


        drawLine(
            color =
                color,

            start =
                Offset(
                    size.width * 0.61f,
                    size.height * 0.61f
                ),

            end =
                Offset(
                    size.width * 0.85f,
                    size.height * 0.85f
                ),

            strokeWidth =
                stroke,

            cap =
                StrokeCap.Round
        )
    }
}


@Composable
private fun PlexusScannerIcon(
    color: Color,
    modifier: Modifier = Modifier
) {

    Canvas(
        modifier =
            modifier
    ) {

        val stroke =
            2.dp.toPx()


        val inset =
            stroke


        val arm =
            size.minDimension * 0.29f


        drawLine(
            color = color,
            start = Offset(
                inset,
                inset + arm
            ),
            end = Offset(
                inset,
                inset
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                inset,
                inset
            ),
            end = Offset(
                inset + arm,
                inset
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                size.width - inset - arm,
                inset
            ),
            end = Offset(
                size.width - inset,
                inset
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                size.width - inset,
                inset
            ),
            end = Offset(
                size.width - inset,
                inset + arm
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                inset,
                size.height - inset - arm
            ),
            end = Offset(
                inset,
                size.height - inset
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                inset,
                size.height - inset
            ),
            end = Offset(
                inset + arm,
                size.height - inset
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                size.width - inset - arm,
                size.height - inset
            ),
            end = Offset(
                size.width - inset,
                size.height - inset
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawLine(
            color = color,
            start = Offset(
                size.width - inset,
                size.height - inset
            ),
            end = Offset(
                size.width - inset,
                size.height - inset - arm
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )


        drawCircle(
            color =
                color,

            radius =
                size.minDimension * 0.075f,

            center =
                center
        )
    }
}


@Composable
private fun PlexusBackArrow(
    color: Color,
    modifier: Modifier = Modifier
) {

    Canvas(
        modifier =
            modifier
    ) {

        val stroke =
            2.2.dp.toPx()


        drawLine(
            color =
                color,

            start =
                Offset(
                    size.width * 0.16f,
                    size.height * 0.50f
                ),

            end =
                Offset(
                    size.width * 0.86f,
                    size.height * 0.50f
                ),

            strokeWidth =
                stroke,

            cap =
                StrokeCap.Round
        )


        drawLine(
            color =
                color,

            start =
                Offset(
                    size.width * 0.16f,
                    size.height * 0.50f
                ),

            end =
                Offset(
                    size.width * 0.43f,
                    size.height * 0.24f
                ),

            strokeWidth =
                stroke,

            cap =
                StrokeCap.Round
        )


        drawLine(
            color =
                color,

            start =
                Offset(
                    size.width * 0.16f,
                    size.height * 0.50f
                ),

            end =
                Offset(
                    size.width * 0.43f,
                    size.height * 0.76f
                ),

            strokeWidth =
                stroke,

            cap =
                StrokeCap.Round
        )
    }
}


fun generateQrCode(
    content: String,
    size: Int
): Bitmap {

    val writer =
        QRCodeWriter()


    val matrix =
        writer.encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size
        )


    val bitmap =
        Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.RGB_565
        )


    for (
    x in 0 until size
    ) {

        for (
        y in 0 until size
        ) {

            bitmap.setPixel(
                x,
                y,

                if (
                    matrix[x, y]
                ) {

                    android.graphics.Color.BLACK

                } else {

                    android.graphics.Color.WHITE
                }
            )
        }
    }


    return bitmap
}


fun shortenNodeId(
    nodeId: String
): String {

    if (
        nodeId.length <= 18
    ) {

        return nodeId
    }


    return nodeId.take(9) +
            "..." +
            nodeId.takeLast(9)
}
