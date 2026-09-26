# Plexus v1 stabilization

## Delivery contract

Room commits the visible outgoing message and encrypted relay envelope atomically before transport attempts. The composer clears the submitted draft only after this commit. A single tick means locally persisted, not delivered. Nearby request/transfer success and Socket.IO enqueue are not delivery. A verified recipient-signed anti-packet authorizes delivered status; authenticated read receipts authorize read status.

## Nearby recovery

Every in-flight BYTES request has a 12-second watchdog, armed before calling sendPayload. The current outbox object, connection session, transfer object and payload ID identify the operation. Terminal callbacks, request failures, disconnect and disposal clean up watchdogs. Stale callbacks cannot complete another operation. Timeout invalidates the endpoint before cancellation/disconnection and invokes debounced recovery instead of draining into a suspect connection.

Each endpoint has separately reserved CONTROL/MESSAGE/BACKGROUND queues (32/128/64 items; 128 KiB/1 MiB/256 KiB). Queued/in-flight duplicates coalesce; messages precede background history. Room retries every five seconds after authentication; successful transport offers are briefly suppressed, never deleted as delivered. Historical anti-packets have a five-minute per-peer suppression window, while explicit proof repair bypasses it. Acknowledged queued ciphertext is removed.

Mesh hop count is 0..8; local origin is zero, each relay increments once, and hop-eight packets may be delivered but not forwarded. TTL independently expires ciphertext after at most 24 hours and delivery tombstones after at most 48 hours. Android and backend validate these bounds.

## Intentional v1 limitations

- No foreground service or guaranteed relay when the process is stopped.
- No Perfect Forward Secrecy: encryption uses identity-based static ECDH, HKDF-SHA256 and AES-256-GCM with random 96-bit nonces and routing associated data. Private identity keys remain in Android Keystore. No home-made ratchet was added.
- Room chat history is local application data, not an encrypted database. Backup/transfer are disabled because Keystore private keys cannot be restored with the database.
- Backend offline retention depends on deployment storage configuration; local Room remains the sender's retry source.
- TTL expiration ends transport retries; it does not imply delivery.
- Radio/OEM recovery latency and three-phone routing require physical-device validation. Compilation and deterministic tests do not prove radio handoff reliability.
- Release output is unsigned. Use the existing release signing process and deploy the matching backend; no signing keys were generated or changed.

## Device acceptance gate

Use debug builds to inspect PlexusNearby/PlexusInternet logs. No physical Logcat input files were available for this pass; the supplied missing-terminal-callback reproduction was the runtime evidence.

1. Connect two phones and send normally. Remove Wi-Fi during a transfer, send more messages, then restore connectivity. If a callback is missing, expect PAYLOAD WATCHDOG TIMEOUT within 12 seconds, endpoint recovery, reauthentication and retransmission. Queue size must remain bounded and messages must not duplicate.
2. Test no Internet with Wi-Fi radio and Bluetooth available; then test removing only Internet while Nearby remains healthy.
3. Test offline-to-online, a 20–50-message burst, recipient disappearance/return, and sender process restart after a pending message is visible.
4. Deliver over Internet and Nearby simultaneously. Confirm one visible row, grey double ticks only after the signed proof and blue double ticks only after reading.
5. With three phones, test A–B–C relay and a triangle/cycle. Confirm bounded hops, deduplication and anti-packet return.

Expected debug events include OUTBOUND ENQUEUED, PAYLOAD DISPATCHED TO NEARBY, PAYLOAD TRANSFER SUCCESS and, only if required, PAYLOAD WATCHDOG TIMEOUT. A queued/dispatched/transferred log is never proof of destination delivery.
