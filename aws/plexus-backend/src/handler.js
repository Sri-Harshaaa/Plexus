import { randomBytes, randomUUID } from 'node:crypto';
import { ProtocolError, requireProtocol, identity, signature, authBytes, validatePacket, validateProof,
    row, MAX_WIRE_BYTES, SKEW } from './protocol.js';
import { conditional, transactionRaceCandidate } from './store.js';

const proofMeta = p => ({ type: 'DELIVERY_PROOF', id: p.messageId, source: p.recipientNodeId,
    destination: p.sourceNodeId, priority: 0, event: 'internet:anti_packet' });
const sameBinding = (v, source, destination) => !v || (v.sourceNodeId === source && v.recipientNodeId === destination);

export function createHandler({ store, transport, now = Date.now, log = console }) {
    return async event => {
        const ctx = event.requestContext ?? {};
        const id = ctx.connectionId;
        let requestId;
        let post;
        async function send(connectionId, name, data, correlation) {
            try {
                await post(connectionId, { event: name, data, ...(correlation ? { requestId: correlation } : {}) });
                return true;
            } catch (e) {
                if (e.name === 'GoneException' || e.$metadata?.httpStatusCode === 410) {
                    await store.disconnect(connectionId);
                    return false;
                }
                throw e;
            }
        }
        async function live(node, name, payload) {
            const deadline = now() + 10_000;
            for await (const connectionId of store.online(node)) {
                if (now() > deadline) break;
                // GSI is eventually consistent; base-row identity and expiry are authoritative.
                const current = await store.connection(connectionId);
                if (!current?.authenticated || current.nodeId !== node || current.expiresAt <= now()) continue;
                try { await send(connectionId, name, payload); }
                catch { log.warn(JSON.stringify({ event: 'live_deferred' })); }
            }
        }
        async function sync(state, encoded = '') {
            let cursor;
            if (encoded) {
                requireProtocol(typeof encoded === 'string' && encoded.length < 2048, 'INVALID_CURSOR');
                try { cursor = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8')); }
                catch { throw new ProtocolError('INVALID_CURSOR'); }
                requireProtocol(cursor.destinationNodeId === state.nodeId && typeof cursor.packetKey === 'string' &&
                    /^[012]#/.test(cursor.packetKey), 'INVALID_CURSOR');
                cursor = { destinationNodeId: state.nodeId, packetKey: cursor.packetKey };
            }
            const token = randomUUID();
            if (!await store.lockSync(id, token, now())) {
                await send(id, 'sync:complete', { busy: true });
                return;
            }
            try {
                const page = await store.page(state.nodeId, cursor);
                const deadline = now() + 10_000;
                let next = page.cursor;
                for (let i = 0; i < page.items.length; i++) {
                    const item = page.items[i];
                    if (item.expiresAt <= now()) {
                        await store.deletePending(state.nodeId, item.packetKey);
                    } else if (item.packetType === 'MESSAGE') {
                        const vaccine = await store.vaccine(item.stableId);
                        if (vaccine?.proof) await store.deletePending(state.nodeId, item.packetKey);
                        else if (!await send(id, item.event, item.payload)) break;
                    } else if (!await send(id, item.event, item.payload)) break;
                    if (now() > deadline && i + 1 < page.items.length) {
                        next = { destinationNodeId: state.nodeId, packetKey: item.packetKey };
                        break;
                    }
                }
                await send(id, 'sync:complete', { cursor: next ? Buffer.from(JSON.stringify(next)).toString('base64url') : '' });
            } finally { await store.unlockSync(id, token); }
        }
        async function delivered(vaccine, p, uploader, digest) {
            requireProtocol(sameBinding(vaccine, p.sourceNodeId, p.destinationNodeId), 'MESSAGE_ID_CONFLICT');
            requireProtocol(!vaccine?.packetDigest || vaccine.packetDigest === digest, 'MESSAGE_ID_CONFLICT');
            if (!vaccine?.proof) return false;
            // Expired vaccines are never sent. The original message is already expired by then;
            // reusing its ID with a changed timestamp is not a new logical message.
            if (vaccine.proof.expiresAt > now()) {
                if (uploader !== p.sourceNodeId) {
                    const proofItem = row(vaccine.proof, proofMeta(vaccine.proof));
                    await store.queueGatewayProof(proofItem, uploader);
                }
                await send(id, 'internet:anti_packet', vaccine.proof);
            }
            await send(id, 'internet:ack', { accepted: true, deliveredAlready: true }, requestId);
            return true;
        }
        try {
            requireProtocol(typeof id === 'string' && id.length <= 256, 'INVALID_CONNECTION');
            if (ctx.routeKey === '$connect') {
                await store.connect(id, now());
                return { statusCode: 200, body: '' };
            }
            if (ctx.routeKey === '$disconnect') {
                await store.disconnect(id);
                return { statusCode: 200, body: '' };
            }
            post = transport(event);
            requireProtocol(typeof event.body === 'string' && !event.isBase64Encoded &&
                Buffer.byteLength(event.body) <= MAX_WIRE_BYTES, 'INVALID_FRAME');
            let body;
            try { body = JSON.parse(event.body); } catch { throw new ProtocolError('INVALID_JSON'); }
            requireProtocol(body && typeof body === 'object' && !Array.isArray(body), 'INVALID_JSON');
            if (typeof body.requestId === 'string' && body.requestId.length <= 128) requestId = body.requestId;
            requireProtocol(['authInit', 'authProof', 'sendPacket', 'deliveryProof', 'sync'].includes(ctx.routeKey) &&
                body.action === ctx.routeKey, 'INVALID_ACTION');
            const state = await store.connection(id);
            requireProtocol(state && state.expiresAt > now(), 'CONNECTION_EXPIRED');
            requireProtocol(await store.rate(id, now()), 'RATE_LIMITED');
            if (ctx.routeKey === 'authInit') {
                requireProtocol(!state.authenticated, 'INVALID_AUTH_STATE');
                identity(body.nodeId, body.signingPublicKey);
                const challenge = { nodeId: body.nodeId, signingPublicKey: body.signingPublicKey,
                    challengeId: randomUUID(), challenge: randomBytes(32).toString('base64url'), challengeExpiresAt: now() + 120_000 };
                await store.challenge(id, challenge, now());
                await send(id, 'auth:challenge', { challengeId: challenge.challengeId, challenge: challenge.challenge,
                    expiresAt: challenge.challengeExpiresAt });
            } else if (ctx.routeKey === 'authProof') {
                requireProtocol(!state.authenticated && state.challengeId === body.challengeId && state.challengeExpiresAt > now(), 'AUTH_CHALLENGE_EXPIRED');
                identity(state.claimedNodeId, state.signingPublicKey);
                signature(authBytes(state.claimedNodeId, state.challengeId, state.challenge), body.signature, state.signingPublicKey);
                await store.authenticate(id, state, now());
                const authenticated = { ...state, authenticated: true, nodeId: state.claimedNodeId };
                await send(id, 'auth:success', { nodeId: authenticated.nodeId });
                await sync(authenticated);
            } else {
                requireProtocol(state.authenticated && state.nodeId, 'NOT_AUTHENTICATED');
                if (ctx.routeKey === 'sync') await sync(state, body.cursor);
                else if (ctx.routeKey === 'sendPacket') {
                    const packet = body.packet;
                    const meta = validatePacket(packet, now());
                    requireProtocol(meta.type === 'MESSAGE' || meta.source === state.nodeId, 'SOURCE_IDENTITY_MISMATCH');
                    let item = row(packet, meta);
                    if (meta.type === 'MESSAGE') {
                        if (await delivered(await store.vaccine(meta.id), packet, state.nodeId, item.digest)) return { statusCode: 200, body: '' };
                        let queued = false;
                        for (let attempt = 0; attempt < 3; attempt++) {
                            try { await store.queueMessage(item, state.nodeId); queued = true; break; }
                            catch (e) {
                                if (!transactionRaceCandidate(e)) throw e;
                                const current = await store.vaccine(meta.id);
                                if (await delivered(current, packet, state.nodeId, item.digest)) return { statusCode: 200, body: '' };
                                requireProtocol(sameBinding(current, meta.source, meta.destination) &&
                                    (!current?.packetDigest || current.packetDigest === item.digest), 'MESSAGE_ID_CONFLICT');
                                const pending = current?.packetDigest === item.digest &&
                                    await store.pending(meta.destination, item.packetKey);
                                if (current?.pendingKey === item.packetKey && pending?.packetType === 'MESSAGE' &&
                                    pending.stableId === meta.id && pending.sourceNodeId === meta.source &&
                                    pending.destinationNodeId === meta.destination && pending.digest === item.digest) {
                                    // The original packet is durable. Full sets cap fanout, not uploads.
                                    if (state.nodeId === meta.source || current.gatewayNodeIds?.has(state.nodeId) ||
                                        current.gatewayNodeIds?.size >= 16) { queued = true; break; }
                                    // Another gateway won; retry so this gateway joins the proof set.
                                } else if (!conditional(e)) throw e;
                            }
                        }
                        requireProtocol(queued, 'RETRY_REQUIRED');
                        // A proof may win immediately after queueMessage's atomic reservation/write.
                        const vaccine = await store.vaccine(meta.id);
                        if (vaccine?.proof) {
                            await store.deletePending(meta.destination, item.packetKey);
                            await delivered(vaccine, packet, state.nodeId, item.digest);
                            return { statusCode: 200, body: '' };
                        }
                    } else {
                        // Reconnect in existing Android can regenerate responseId/signature. A
                        // request's first signed decision is canonical and cannot be overwritten.
                        const markerKey = `9#${meta.type}#${packet.requestId}`;
                        let marker = await store.pending(meta.destination, markerKey);
                        if (!marker) {
                            try { await store.saveControl(item, markerKey); }
                            catch (e) {
                                if (!transactionRaceCandidate(e)) throw e;
                                marker = await store.pending(meta.destination, markerKey);
                                if (!marker && !conditional(e)) throw e;
                                requireProtocol(marker, 'RETRY_REQUIRED');
                            }
                        }
                        if (marker) {
                            requireProtocol(marker.sourceNodeId === meta.source &&
                                (meta.type === 'CONNECTION_RESPONSE' ? marker.payload.response === packet.response : marker.digest === item.digest), 'CONTROL_ID_CONFLICT');
                            requireProtocol(marker.expiresAt > now(), 'PACKET_EXPIRED');
                            item = { ...marker, packetKey: marker.pendingKey };
                        }
                    }
                    await live(meta.destination, item.event, item.payload);
                    await send(id, 'internet:ack', { accepted: true, stableId: item.stableId }, requestId);
                } else {
                    const packet = body.packet;
                    validateProof(packet, now());
                    requireProtocol(packet.recipientNodeId === state.nodeId && packet.recipientSigningPublicKey === state.signingPublicKey, 'PROOF_IDENTITY_MISMATCH');
                    let stored;
                    let newlyStored = false;
                    for (let attempt = 0; attempt < 3; attempt++) {
                        const previous = await store.vaccine(packet.messageId);
                        requireProtocol(sameBinding(previous, packet.sourceNodeId, packet.recipientNodeId), 'PROOF_IDENTITY_CONFLICT');
                        requireProtocol(!previous?.createdAt || packet.deliveredAt + SKEW >= previous.createdAt, 'PROOF_MESSAGE_MISMATCH');
                        const canonical = previous?.proof ?? packet;
                        requireProtocol(canonical.expiresAt > now(), 'PACKET_EXPIRED');
                        if (previous?.proof) { stored = canonical; break; }
                        const item = row(canonical, proofMeta(canonical));
                        try { await store.saveProof(item, previous); stored = canonical; newlyStored = true; break; }
                        catch (e) {
                            if (!transactionRaceCandidate(e)) throw e;
                            if (!conditional(e)) {
                                const current = await store.vaccine(packet.messageId);
                                requireProtocol(sameBinding(current, packet.sourceNodeId, packet.recipientNodeId), 'PROOF_IDENTITY_CONFLICT');
                                requireProtocol(!current?.createdAt || packet.deliveredAt + SKEW >= current.createdAt, 'PROOF_MESSAGE_MISMATCH');
                                if (current?.proof) { stored = current.proof; break; }
                                const before = previous?.gatewayNodeIds ?? new Set();
                                const after = current?.gatewayNodeIds ?? new Set();
                                const changed = (!previous && !!current) || before.size !== after.size ||
                                    [...before].some(node => !after.has(node));
                                if (!changed) throw e;
                            }
                        }
                    }
                    requireProtocol(stored, 'RETRY_REQUIRED');
                    const vaccine = await store.vaccine(packet.messageId);
                    if (vaccine?.pendingKey) await store.deletePending(packet.recipientNodeId, vaccine.pendingKey);
                    // The transaction already made every proof notification durable.
                    await send(id, 'internet:ack', { accepted: true, messageId: packet.messageId }, requestId);
                    if (newlyStored) {
                        await live(packet.sourceNodeId, 'internet:anti_packet', stored);
                        for (const gateway of vaccine?.gatewayNodeIds ?? []) {
                            if (gateway !== packet.sourceNodeId) await live(gateway, 'internet:anti_packet', stored);
                        }
                    }
                }
            }
            return { statusCode: 200, body: '' };
        } catch (e) {
            const code = e instanceof ProtocolError ? e.code : 'RETRY_REQUIRED';
            log.warn(JSON.stringify({ event: 'request_failed', route: ctx.routeKey, code }));
            if (post && id) {
                try { await send(id, 'internet:error', { error: code }, requestId); } catch { /* client timeout/reconnect repairs */ }
            }
            // Application traffic is always sent with postToConnection, never a route response.
            return { statusCode: ctx.routeKey === '$connect' ? 503 : 200, body: '' };
        }
    };
}
