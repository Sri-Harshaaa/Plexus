package com.plexus.app

import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.plexus.app.ui.theme.PlexusTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RelationshipNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val identity = PlexusIdentity("Local", "local", "test-signing-key", "test-agreement-key", "public-test-identity")

    @Test fun printerQrRevealCompletesEarlierBandsAndOnlyPartiallyExposesCurrentBand() {
        var progress by mutableFloatStateOf(8.5f / 32f)
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(256.dp).background(androidx.compose.ui.graphics.Color.White)
            ) {
                androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()
                    .testTag("QR reveal").printerQrReveal { progress }) {
                    drawRect(androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        fun assertPixel(x: Float, y: Float, black: Boolean) {
            val pixels = compose.onNodeWithTag("QR reveal").captureToImage().toPixelMap()
            val pixel = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
            assertTrue(if (black) pixel.red < 0.1f else pixel.red > 0.9f)
        }
        assertPixel(0.9f, 7.5f / 32f, true) // Earlier rows are complete, including their right edge.
        assertPixel(0.25f, 8.5f / 32f, true)
        assertPixel(0.75f, 8.5f / 32f, false) // Only the current band sweeps horizontally.
        assertPixel(0.1f, 9.5f / 32f, false)
        compose.runOnIdle { progress = 1f }
        assertPixel(0.9f, 0.99f, true) // Completion exposes the entire source, unchanged.
        compose.runOnIdle { progress = 0f }
        assertPixel(0.1f, 0.01f, false)
    }

    @Test fun profileDropdownOpensBlockedUsersAndBackReturnsStraightHome() {
        compose.setContent {
            PlexusTheme {
                var screen by remember { mutableStateOf(PlexusScreen.HOME) }
                when (screen) {
                    PlexusScreen.BLOCKED_USERS -> PlexusBlockedUsersScreen(emptyList(),
                        onBack = { screen = PlexusScreen.HOME }, onUnblock = {})
                    PlexusScreen.PROFILE -> Text("Profile destination")
                    else -> PlexusHomeScreen(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), identity,
                        onIncomingRequestAccept = { _, _ -> false }, onIncomingRequestDecline = { false },
                        onAcceptedContactClick = {}, onProfileClick = { screen = PlexusScreen.PROFILE },
                        onBlockedUsersClick = { screen = PlexusScreen.BLOCKED_USERS }, onBlockUser = {},
                        onScanClick = {}, onContactClick = {}, onDeleteChat = {})
                }
            }
        }
        compose.onNodeWithContentDescription("Profile menu").performClick()
        compose.onNodeWithText("Blocked users").performClick()
        compose.onNodeWithText("No blocked users").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Profile menu").assertIsDisplayed().performClick()
        compose.onNodeWithText("View profile").performClick()
        compose.onNodeWithText("Profile destination").assertIsDisplayed()
    }

    @Test fun profileMenuItemsHighlightOnTouchDownAndNavigateOnRelease() {
        var profiles = 0
        var blocked = 0
        compose.setContent {
            PlexusTheme { PlexusHomeScreen(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), identity,
                onIncomingRequestAccept = { _, _ -> false }, onIncomingRequestDecline = { false },
                onAcceptedContactClick = {}, onProfileClick = { profiles++ },
                onBlockedUsersClick = { blocked++ }, onBlockUser = {},
                onScanClick = {}, onContactClick = {}, onDeleteChat = {}) }
        }
        for (label in listOf("View profile", "Blocked users")) {
            compose.onNodeWithContentDescription("Profile menu").performClick()
            val item = compose.onNodeWithText(label)
            val before = item.captureToImage().toPixelMap()
            val baseline = before[2, before.height / 2].red
            item.performTouchInput { down(center) }
            compose.mainClock.advanceTimeByFrame()
            val held = item.captureToImage().toPixelMap()
            assertTrue("Pressed row must visibly lighten on touch-down", held[2, held.height / 2].red > baseline)
            compose.runOnIdle { assertTrue(profiles + blocked < if (label == "View profile") 1 else 2) }
            item.performTouchInput { up() }
        }
        compose.runOnIdle { assertTrue(profiles == 1 && blocked == 1) }
    }

    @Test fun blockedListUnblockButtonUpdatesToEmptyState() {
        compose.setContent {
            var contacts by remember { mutableStateOf(listOf(ContactEntity("peer", "Peer", "key", "key",
                relationshipState = ContactRelationshipState.BLOCKED))) }
            PlexusTheme { PlexusBlockedUsersScreen(contacts, onBack = {}, onUnblock = { contacts = emptyList() }) }
        }
        compose.onNodeWithText("Peer").assertIsDisplayed()
        compose.onNodeWithText("Unblock").performClick()
        compose.onNodeWithText("No blocked users").assertIsDisplayed()
    }

    @Test fun backgroundActivityCardFollowsScanButton() {
        compose.setContent { PlexusTheme { PlexusProfileScreen(identity, {}, {}, {}) } }
        compose.onNodeWithText("Background activity").performScrollTo().assertIsDisplayed()
        val helper = compose.onNodeWithText("Background activity").fetchSemanticsNode().boundsInRoot
        val scan = compose.onNodeWithText("Scan someone").fetchSemanticsNode().boundsInRoot
        assertTrue(helper.top > scan.bottom)
    }
}
