package com.plexus.app

import org.junit.Assert.*
import org.junit.Test

class PeerRoutesTest {
    @Test fun publicIdentityClaimCannotDisplaceAuthenticatedPeer() {
        val routes = PeerRoutes()
        routes.claim("trusted", "node")
        routes.promote("trusted")
        routes.claim("impostor", "node")
        assertEquals("trusted", routes.verified["node"])
        routes.remove("impostor")
        assertEquals("trusted", routes.verified["node"])
    }

    @Test fun lateDisconnectCannotRemoveAuthenticatedReplacement() {
        val routes = PeerRoutes()
        routes.claim("old", "node")
        routes.promote("old")
        routes.claim("new", "node")
        assertEquals("old", routes.promote("new"))
        routes.remove("old")
        assertEquals("new", routes.verified["node"])
    }

    @Test fun identityChangeInvalidatesOnlyItsOwnRoute() {
        val routes = PeerRoutes()
        routes.claim("one", "a")
        routes.promote("one")
        routes.claim("two", "a")
        routes.claim("two", "b")
        assertEquals("one", routes.verified["a"])
        assertNull(routes.verified["b"])
    }
}
