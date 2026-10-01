import { createHash, generateKeyPairSync, sign, randomUUID } from 'node:crypto';
import { authBytes, proofBytes, controlBytes, originBytes, DAY } from '../src/protocol.js';
import { DynamoStore } from '../src/store.js';
import { createHandler } from '../src/handler.js';

export function identity() {
    const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const der = publicKey.export({ type: 'spki', format: 'der' });
    return { privateKey, signingPublicKey: der.toString('base64'), nodeId: createHash('sha256').update(der).digest('hex') };
}
export const signed = (bytes, who) => sign('sha256', bytes, who.privateKey).toString('base64');
export function message(a, b, now) {
    const packetId = randomUUID();
    const inner = { version: 1, type: 'encrypted_chat', messageId: packetId, senderNodeId: a.nodeId,
        recipientNodeId: b.nodeId, timestamp: now, nonce: Buffer.alloc(12).toString('base64'), ciphertext: Buffer.alloc(32).toString('base64') };
    const result = { version: 1, type: 'mesh_message', packetId, sourceNodeId: a.nodeId, destinationNodeId: b.nodeId,
        createdAt: now, expiresAt: now + DAY, hopCount: 0, encryptedPayload: Buffer.from(JSON.stringify(inner)).toString('base64'),
        originSigningPublicKey: a.signingPublicKey };
    result.originSignature = signed(originBytes(result), a);
    return result;
}
export function proof(p, receiver, now) {
    const result = { version: 1, type: 'delivery_anti_packet', messageId: p.packetId, sourceNodeId: p.sourceNodeId,
        recipientNodeId: p.destinationNodeId, recipientSigningPublicKey: receiver.signingPublicKey, deliveredAt: now, expiresAt: now + 2 * DAY };
    result.signature = signed(proofBytes(result), receiver);
    return result;
}
export function request(a, b, now) {
    const p = { version: 1, type: 'connection_request', requestId: randomUUID(), senderNodeId: a.nodeId,
        recipientNodeId: b.nodeId, senderDisplayName: 'Harsha 🪷', senderSigningPublicKey: a.signingPublicKey,
        senderAgreementPublicKey: a.signingPublicKey, createdAt: now, expiresAt: now + 30 * DAY };
    p.signature = signed(controlBytes(p), a); return p;
}
export function response(req, b, now) {
    const p = { version: 1, type: 'connection_response', requestId: req.requestId, responseId: randomUUID(),
        requesterNodeId: req.senderNodeId, responderNodeId: b.nodeId, response: 'ACCEPTED', responderDisplayName: 'Arshad',
        responderSigningPublicKey: b.signingPublicKey, responderAgreementPublicKey: b.signingPublicKey, createdAt: now, expiresAt: now + 30 * DAY };
    p.signature = signed(controlBytes(p), b); return p;
}
const copy = value => value === undefined ? value : structuredClone(value);
function conflict() { const e = new Error('conditional'); e.name = 'ConditionalCheckFailedException'; throw e; }
export class MemoryStore {
    connections = new Map(); vaccines = new Map(); packets = new Map(); push = new Map(); peerPolicies = new Map(); trace = [];
    key(node, key) { return `${node}/${key}`; }
    async connection(id) { return copy(this.connections.get(id)); }
    async vaccine(id) { return copy(this.vaccines.get(id)); }
    async pending(node, key) { return copy(this.packets.get(this.key(node, key))); }
    async connect(id, now) { if (!this.connections.has(id)) this.connections.set(id, { connectionId: id,
        authenticated: false, connectedAt: now, expiresAt: now + 120_000, ttl: Math.floor((now + 120_000) / 1000) }); }
    async disconnect(id) { this.connections.delete(id); }
    async challenge(id, c) { Object.assign(this.connections.get(id), c, { claimedNodeId: c.nodeId }); delete this.connections.get(id).nodeId; }
    async authenticate(id, state, now) {
        const current = this.connections.get(id);
        if (current.authenticated || current.challengeId !== state.challengeId) conflict();
        Object.assign(current, { authenticated: true, nodeId: state.claimedNodeId, authenticatedAt: now, expiresAt: now + 7_500_000 });
        delete current.challenge; delete current.challengeId; delete current.challengeExpiresAt;
    }
    async rate() { return true; }
    async *online(node) { for (const [id, state] of this.connections) if (state.nodeId === node) yield id; }
    async queueMessage(item, uploader) {
        await this.beforeQueue?.();
        const old = this.vaccines.get(item.stableId);
        if (old?.proof || old?.discarded || (old && (old.sourceNodeId !== item.sourceNodeId || old.recipientNodeId !== item.destinationNodeId || old.packetDigest !== item.digest))) conflict();
        const gateways = new Set(old?.gatewayNodeIds ?? []);
        if (uploader && uploader !== item.sourceNodeId) {
            if (!gateways.has(uploader) && gateways.size >= 16) conflict();
            gateways.add(uploader);
        }
        this.vaccines.set(item.stableId, { sourceNodeId: item.sourceNodeId, recipientNodeId: item.destinationNodeId,
            packetDigest: item.digest, pendingKey: item.packetKey, createdAt: item.createdAt, expiresAt: item.expiresAt, ttl: item.ttl,
            gatewayNodeIds: gateways });
        this.packets.set(this.key(item.destinationNodeId, item.packetKey), copy(item));
        this.trace.push('persist');
        await this.afterQueue?.();
    }
    async saveProof(item, previous) {
        const p = item.payload;
        await this.beforeSaveProof?.();
        const old = this.vaccines.get(p.messageId);
        if (old && (old.sourceNodeId !== p.sourceNodeId || old.recipientNodeId !== p.recipientNodeId || old.proofDigest !== previous?.proofDigest ||
            JSON.stringify([...(old.gatewayNodeIds ?? [])].sort()) !== JSON.stringify([...(previous?.gatewayNodeIds ?? [])].sort()))) conflict();
        this.vaccines.set(p.messageId, { ...old, sourceNodeId: p.sourceNodeId, recipientNodeId: p.recipientNodeId,
            proof: copy(p), proofDigest: item.digest, expiresAt: p.expiresAt, ttl: item.ttl });
        this.packets.set(this.key(item.destinationNodeId, item.packetKey), copy(item));
        for (const gateway of old?.gatewayNodeIds ?? []) {
            if (gateway !== p.sourceNodeId) this.packets.set(this.key(gateway, item.packetKey), { ...copy(item), destinationNodeId: gateway });
        }
        await this.afterSaveProof?.();
    }
    async queueGatewayProof(item, gateway) {
        this.packets.set(this.key(gateway, item.packetKey), { ...copy(item), destinationNodeId: gateway });
    }
    async deletePending(node, key) { await this.beforeDelete?.(); this.packets.delete(this.key(node, key)); }
    async registerPushToken(node, token) {
        for (const oldToken of await this.pushTokens(node)) if (oldToken !== token) await this.deletePushToken(node, oldToken);
        this.push.set(this.key(node, token), token);
    }
    async pushTokens(node) { return [...this.push.entries()].filter(([key]) => key.startsWith(`${node}/`)).map(([, token]) => token); }
    async deletePushToken(node, token) { this.push.delete(this.key(node, token)); }
    async peerBlocked(node, peer) { return this.peerPolicies.get(this.key(node, peer))?.blocked === true; }
    async setPeerBlocked(node, peer, blocked, revision) {
        const key = this.key(node, peer);
        if ((this.peerPolicies.get(key)?.revision ?? 0) <= revision) this.peerPolicies.set(key, { blocked, revision });
    }
    async discardBlocked(item) {
        if (item.packetType === 'MESSAGE') this.vaccines.set(item.stableId, {
            ...this.vaccines.get(item.stableId), sourceNodeId: item.sourceNodeId, recipientNodeId: item.destinationNodeId,
            packetDigest: item.digest, createdAt: item.createdAt, expiresAt: item.expiresAt, ttl: item.ttl, discarded: true
        });
        else if (item.packetType === 'CONNECTION_REQUEST') this.packets.set(this.key(item.destinationNodeId, `9#CONNECTION_REQUEST#${item.stableId}`),
            { ...copy(item), packetKey: `9#CONNECTION_REQUEST#${item.stableId}`, pendingKey: item.packetKey, blockedDiscard: true });
        await this.deletePending(item.destinationNodeId, item.packetKey);
    }
    async saveControl(item, markerKey) {
        await this.beforeSaveControl?.();
        const key = this.key(item.destinationNodeId, markerKey);
        if (this.packets.has(key)) conflict();
        this.packets.set(key, { ...copy(item), packetKey: markerKey, pendingKey: item.packetKey });
        this.packets.set(this.key(item.destinationNodeId, item.packetKey), copy(item));
    }
    async page(node, cursor) {
        const items = [...this.packets.values()].filter(p => p.destinationNodeId === node && p.packetKey < '3' &&
            (!cursor || p.packetKey > cursor.packetKey)).sort((a, b) => a.packetKey.localeCompare(b.packetKey));
        return { items: copy(items.slice(0, 20)), cursor: items.length > 20 ? { destinationNodeId: node, packetKey: items[19].packetKey } : undefined };
    }
    syncProgress = new Map();
    async messagePage(node, cursor) {
        const items = [...this.packets.values()].filter(p => p.destinationNodeId === node && p.packetKey.startsWith('2#') &&
            (!cursor || p.packetKey > cursor.packetKey)).sort((a, b) => a.packetKey.localeCompare(b.packetKey));
        return { items: copy(items.slice(0, 1)), cursor: items.length > 1 ? { destinationNodeId: node, packetKey: items[0].packetKey } : undefined };
    }
    async claimSyncPage(node, messages, now, visit) {
        // Exercise the production checkpoint algorithm, including conditional writes.
        const memory = this;
        return DynamoStore.prototype.claimSyncPage.call({
            tables: { pending: 'pending' },
            pending: async (n, k) => copy(memory.syncProgress.get(memory.key(n, k))),
            page: (...args) => memory.page(...args), messagePage: (...args) => memory.messagePage(...args),
            db: { async send(command) {
                const { Item: item, ExpressionAttributeValues: values } = command.input;
                const key = memory.key(item.destinationNodeId, item.packetKey), old = memory.syncProgress.get(key);
                if (values ? old?.revision !== values[':revision'] : old) conflict();
                memory.syncProgress.set(key, copy(item));
            } }
        }, node, messages, now, visit);
    }
    async lockSync(id, token) { const c = this.connections.get(id); if (!c || c.lock) return false; c.lock = token; return true; }
    async unlockSync(id, token) { const c = this.connections.get(id); if (c?.lock === token) delete c.lock; }
}
export function setup({ pushFailure = false } = {}) {
    const store = new MemoryStore();
    const events = [];
    const pushes = [];
    const diagnostics = [];
    let clock = 1_800_000_000_000;
    const gone = new Set();
    const handler = createHandler({ store, now: () => clock, log: { warn() {}, info(record) { diagnostics.push(JSON.parse(record)); } }, push: async node => { pushes.push(node); if (pushFailure) throw new Error('push unavailable'); },
        transport: () => async (id, envelope) => {
            if (gone.has(id)) { const e = new Error(); e.name = 'GoneException'; throw e; }
            store.trace.push('post'); events.push({ id, ...copy(envelope) });
            await store.afterPost?.(id, envelope);
        } });
    async function call(id, action, body = {}) {
        return handler({ requestContext: { routeKey: action, connectionId: id, domainName: 'unit.invalid', stage: 'production' },
            body: JSON.stringify({ action, requestId: 'correlation', ...body }) });
    }
    async function auth(id, who) {
        await call(id, '$connect');
        await call(id, 'authInit', { nodeId: who.nodeId, signingPublicKey: who.signingPublicKey });
        const challenge = events.filter(e => e.id === id && e.event === 'auth:challenge').at(-1).data;
        await call(id, 'authProof', { challengeId: challenge.challengeId,
            signature: signed(authBytes(who.nodeId, challenge.challengeId, challenge.challenge), who) });
    }
    return { store, events, pushes, diagnostics, handler, call, auth, gone, now: () => clock, advance: delta => { clock += delta; },
        error: () => events.filter(e => e.event === 'internet:error').at(-1)?.data.error };
}
