import test from 'node:test';
import assert from 'node:assert/strict';
import { setup, identity, message, proof, request, response, signed } from './fixtures.js';
import { authBytes, proofBytes, controlBytes, originBytes, DAY } from '../src/protocol.js';
import { TransactionCanceledException } from '@aws-sdk/client-dynamodb';

const canceled = reasons => new TransactionCanceledException({ $metadata: { httpStatusCode: 400 },
    message: 'Transaction cancelled', ...(reasons ? { CancellationReasons: reasons } : {}) });

test('$connect is unauthenticated, idempotent, and TTL is seconds', async () => {
    const s = setup(); await s.call('a', '$connect'); await s.call('a', '$connect');
    const c = await s.store.connection('a'); assert.equal(c.authenticated, false); assert.equal(c.nodeId, undefined);
    assert.equal(c.ttl, Math.floor(c.expiresAt / 1000)); assert.equal(s.events.length, 0);
});
test('authInit stores a random challenge, without an authoritative nodeId', async () => {
    const s = setup(), a = identity(); await s.call('a', '$connect');
    await s.call('a', 'authInit', a); const c = await s.store.connection('a');
    assert.equal(c.challenge.length, 43); assert.equal(c.nodeId, undefined); assert.equal(c.claimedNodeId, a.nodeId);
    assert.equal(c.challengeExpiresAt, s.now() + 120000);
});
test('forged nodeId/public key binding is rejected', async () => {
    const s = setup(); await s.call('a', '$connect');
    await s.call('a', 'authInit', { ...identity(), nodeId: 'a'.repeat(64) }); assert.equal(s.error(), 'IDENTITY_MISMATCH');
});
test('valid ECDSA authentication succeeds and retires challenge', async () => {
    const s = setup(), a = identity(); await s.auth('a', a);
    const c = await s.store.connection('a'); assert.equal(c.authenticated, true); assert.equal(c.nodeId, a.nodeId);
    assert.equal(c.challenge, undefined); assert.ok(s.events.some(e => e.event === 'sync:complete'));
});
test('invalid ECDSA proof is rejected', async () => {
    const s = setup(), a = identity(); await s.call('a', '$connect'); await s.call('a', 'authInit', a);
    const c = await s.store.connection('a'); await s.call('a', 'authProof', { challengeId: c.challengeId, signature: 'AAAA' });
    assert.equal(s.error(), 'INVALID_SIGNATURE'); assert.equal((await s.store.connection('a')).authenticated, false);
});
test('expired authentication challenge is rejected at the boundary', async () => {
    const s = setup(), a = identity(); await s.call('a', '$connect'); await s.call('a', 'authInit', a);
    const c = await s.store.connection('a'); s.advance(120000);
    await s.call('a', 'authProof', { challengeId: c.challengeId, signature: signed(authBytes(a.nodeId, c.challengeId, c.challenge), a) });
    assert.ok(s.error().includes('EXPIRED')); assert.equal((await s.store.connection('a')).authenticated, false);
});
test('authentication proof cannot be replayed on another connection', async () => {
    const s = setup(), a = identity(); await s.call('a', '$connect'); await s.call('a', 'authInit', a);
    const c = await s.store.connection('a'); await s.call('b', '$connect'); await s.call('b', 'authInit', a);
    await s.call('b', 'authProof', { challengeId: c.challengeId, signature: signed(authBytes(a.nodeId, c.challengeId, c.challenge), a) });
    assert.equal((await s.store.connection('b')).authenticated, false);
});
test('unauthenticated send, sync and proof are rejected', async () => {
    const s = setup(); await s.call('a', '$connect');
    for (const action of ['sendPacket', 'sync', 'deliveryProof']) { await s.call('a', action); assert.equal(s.error(), 'NOT_AUTHENTICATED'); }
});
test('push token is bound only to the authenticated connection identity', async () => {
    const s = setup(), a = identity(); await s.call('a', '$connect');
    await s.call('a', 'registerPushToken', { token: 'x'.repeat(40) }); assert.equal(s.error(), 'NOT_AUTHENTICATED');
    await s.auth('a', a); await s.call('a', 'registerPushToken', { token: 'x'.repeat(40) });
    assert.deepEqual(await s.store.pushTokens(a.nodeId), ['x'.repeat(40)]);
    await s.call('a', 'registerPushToken', { token: 'y'.repeat(40) });
    assert.deepEqual(await s.store.pushTokens(a.nodeId), ['y'.repeat(40)]);
});
test('offline durable message triggers a sync wake but live delivery does not', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) }); assert.deepEqual(s.pushes, [b.nodeId]);
    await s.auth('b', b); await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) }); assert.deepEqual(s.pushes, [b.nodeId]);
});
test('offline message is durably queued before accepted acknowledgement', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); s.store.trace.length = 0;
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: p });
    assert.deepEqual(s.store.trace, ['persist', 'post']); assert.equal(s.store.packets.size, 1);
    assert.equal(s.events.at(-1).event, 'internet:ack');
});
test('uploader cannot impersonate an origin with the wrong public key', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    await s.call('a', 'sendPacket', { packet: { ...message(b, a, s.now()), originSigningPublicKey: a.signingPublicKey } });
    assert.equal(s.error(), 'IDENTITY_MISMATCH'); assert.equal(s.store.packets.size, 0);
});
test('persistence precedes live send and network success retains ciphertext', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    s.store.trace.length = 0; await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) });
    assert.equal(s.store.trace[0], 'persist'); assert.ok(s.events.some(e => e.id === 'b' && e.event === 'internet:packet'));
    assert.equal(s.store.packets.size, 1); assert.equal([...s.store.vaccines.values()][0].proof, undefined);
});
test('failed persistence never live-sends or acknowledges acceptance', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    s.events.length = 0; s.store.beforeQueue = () => { throw new Error('DynamoDB unavailable'); };
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) });
    assert.equal(s.events.some(e => e.event === 'internet:packet' || e.event === 'internet:ack'), false);
});
test('duplicate message is idempotent and conflicting ID cannot replace it', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); const p = message(a, b, s.now());
    await s.call('a', 'sendPacket', { packet: p }); await s.call('a', 'sendPacket', { packet: p });
    assert.equal(s.store.packets.size, 1);
    await s.call('a', 'sendPacket', { packet: { ...p, expiresAt: p.expiresAt - 1 } }); assert.equal(s.error(), 'INVALID_SIGNATURE');
});
test('authenticated courier uploads signed origin packet without changing the source', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(); await s.auth('b', b);
    const p = message(a, c, s.now()); await s.call('b', 'sendPacket', { packet: p });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal([...s.store.packets.values()][0].sourceNodeId, a.nodeId);
    assert.deepEqual([...s.store.vaccines.get(p.packetId).gatewayNodeIds], [b.nodeId]);
});
test('origin canonical bytes match Android including explicit UTF-8 lengths', () => {
    const sample = { packetId: 'id', sourceNodeId: 'a', destinationNodeId: 'b', createdAt: 1,
        expiresAt: 10000, encryptedPayload: 'YWJj', hopCount: 7 };
    assert.equal(originBytes(sample).toString(), 'PLEXUS_MESH_ORIGIN_V1|2:id|1:a|1:b|1:1|5:10000|4:YWJj|');
});
test('origin proof rejects forgery and every immutable-field alteration, but permits hops', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(); await s.auth('b', b);
    const p = message(a, c, s.now());
    for (const changed of [
        { ...p, originSignature: 'AAAA' },
        { ...p, originSigningPublicKey: b.signingPublicKey },
        { ...p, destinationNodeId: b.nodeId },
        { ...p, encryptedPayload: p.encryptedPayload.slice(0, -4) + 'AAAA' },
        { ...p, createdAt: p.createdAt + 1 },
        { ...p, expiresAt: p.expiresAt - 1 }
    ]) {
        await s.call('b', 'sendPacket', { packet: changed });
        assert.ok(['INVALID_SIGNATURE', 'IDENTITY_MISMATCH', 'INVALID_ENCRYPTED_PAYLOAD', 'INVALID_TIMESTAMPS'].includes(s.error()));
    }
    assert.equal(s.store.packets.size, 0);
    await s.call('b', 'sendPacket', { packet: { ...p, hopCount: 5 } });
    assert.equal(s.events.at(-1).event, 'internet:ack');
});
test('multiple gateways converge on one durable packet despite different hop counts', async () => {
    const s = setup(), a = identity(), b = identity(), d = identity(), c = identity();
    await s.auth('b', b); await s.auth('d', d);
    const p = message(a, c, s.now());
    await s.call('b', 'sendPacket', { packet: { ...p, hopCount: 1 } });
    await s.call('d', 'sendPacket', { packet: { ...p, hopCount: 2 } });
    await s.call('b', 'sendPacket', { packet: p });
    assert.equal(s.store.packets.size, 1);
    assert.deepEqual(new Set(s.store.vaccines.get(p.packetId).gatewayNodeIds), new Set([b.nodeId, d.nodeId]));
    assert.equal(s.events.at(-1).event, 'internet:ack');
});
test('two valid signatures for identical origin bytes converge without a message conflict', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity();
    await s.auth('a', a); await s.auth('b', b);
    const p = message(a, c, s.now());
    let alternate = signed(originBytes(p), a);
    while (alternate === p.originSignature) alternate = signed(originBytes(p), a);
    await s.call('a', 'sendPacket', { packet: p });
    await s.call('b', 'sendPacket', { packet: { ...p, originSignature: alternate, hopCount: 2 } });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.packets.size, 1);
    assert.deepEqual([...s.store.vaccines.get(p.packetId).gatewayNodeIds], [b.nodeId]);
});
test('gateway 17+ converges without growing the bounded proof-recipient set', async () => {
    const s = setup(), a = identity(), c = identity(), p = message(a, c, s.now());
    const gateways = Array.from({ length: 18 }, () => identity());
    for (let i = 0; i < gateways.length; i++) {
        await s.auth(`gateway-${i}`, gateways[i]);
        await s.call(`gateway-${i}`, 'sendPacket', { packet: p });
        assert.equal(s.events.at(-1).event, 'internet:ack');
    }
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.size, 16);
    assert.equal(s.store.packets.size, 1);
    // The two excess gateways are accepted, but are not promised proactive proof fanout.
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.has(gateways[17].nodeId), false);
});
test('SDK cancellation with conditional reasons resolves a full gateway set from durable state', async () => {
    const s = setup(), a = identity(), c = identity(), p = message(a, c, s.now());
    const gateways = Array.from({ length: 17 }, () => identity());
    for (let i = 0; i < 16; i++) {
        await s.auth(`g${i}`, gateways[i]); await s.call(`g${i}`, 'sendPacket', { packet: p });
    }
    await s.auth('g16', gateways[16]);
    s.store.beforeQueue = () => { throw canceled([{ Code: 'ConditionalCheckFailed' }, { Code: 'None' }]); };
    s.events.length = 0;
    await s.call('g16', 'sendPacket', { packet: p });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.size, 16);
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.has(gateways[16].nodeId), false);
});
test('SDK cancellation without reasons accepts gateway 17 but preserves the fanout cap and later proof', async () => {
    const s = setup(), a = identity(), c = identity(), p = message(a, c, s.now());
    const gateways = Array.from({ length: 17 }, () => identity());
    await s.auth('c', c);
    for (let i = 0; i < 16; i++) {
        await s.auth(`g${i}`, gateways[i]); await s.call(`g${i}`, 'sendPacket', { packet: p });
    }
    await s.auth('g16', gateways[16]);
    s.store.beforeQueue = () => { throw canceled(); };
    s.events.length = 0;
    await s.call('g16', 'sendPacket', { packet: p });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.size, 16);
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.has(gateways[16].nodeId), false);
    s.store.beforeQueue = undefined;
    await s.call('c', 'deliveryProof', { packet: proof(p, c, s.now()) });
    s.events.length = 0;
    await s.call('g16', 'sendPacket', { packet: p });
    assert.deepEqual(s.events.filter(e => e.id === 'g16').map(e => e.event), ['internet:anti_packet', 'internet:ack']);
});
test('SDK cancellation without reasons retries a concurrent gateway update', async () => {
    const s = setup(), a = identity(), b = identity(), d = identity(), c = identity();
    await s.auth('b', b); await s.auth('d', d);
    const p = message(a, c, s.now()); await s.call('b', 'sendPacket', { packet: p });
    let attempts = 0;
    s.store.beforeQueue = () => {
        attempts++;
        s.store.beforeQueue = undefined;
        s.store.vaccines.get(p.packetId).gatewayNodeIds.add(identity().nodeId);
        throw canceled();
    };
    s.events.length = 0;
    await s.call('d', 'sendPacket', { packet: p });
    assert.equal(attempts, 1);
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.vaccines.get(p.packetId).gatewayNodeIds.has(d.nodeId), true);
});
test('SDK cancellation without reasons resolves an already-durable identical packet', async () => {
    const s = setup(), a = identity(), c = identity(); await s.auth('a', a);
    const p = message(a, c, s.now());
    s.store.afterQueue = () => { throw canceled(); };
    await s.call('a', 'sendPacket', { packet: p });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.packets.size, 1);
    assert.equal(s.store.vaccines.get(p.packetId).packetDigest.length, 64);
});
test('ambiguous SDK cancellation rejects inconsistent message state', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(); await s.auth('b', b);
    const p = message(a, c, s.now()); await s.call('b', 'sendPacket', { packet: p });
    s.store.beforeQueue = () => {
        s.store.vaccines.get(p.packetId).packetDigest = 'different';
        throw canceled();
    };
    s.events.length = 0;
    await s.call('b', 'sendPacket', { packet: p });
    assert.equal(s.error(), 'MESSAGE_ID_CONFLICT');
    assert.equal(s.events.some(e => e.event === 'internet:ack'), false);
});
test('unrelated SDK cancellation and service errors retain retry failure behavior', async () => {
    const s = setup(), a = identity(), c = identity(); await s.auth('a', a);
    const p = message(a, c, s.now());
    for (const error of [canceled(), canceled([{ Code: 'ValidationError', Message: 'bad expression' }]),
        Object.assign(new Error('service unavailable'), { name: 'InternalServerError' })]) {
        s.store.beforeQueue = () => { throw error; };
        s.events.length = 0;
        await s.call('a', 'sendPacket', { packet: p });
        assert.equal(s.error(), 'RETRY_REQUIRED');
        assert.equal(s.events.some(e => e.event === 'internet:ack'), false);
        assert.equal(s.store.packets.size, 0);
    }
});
test('non-conditional SDK cancellation is not hidden by an existing identical packet', async () => {
    const s = setup(), a = identity(), c = identity(); await s.auth('a', a);
    const p = message(a, c, s.now()); await s.call('a', 'sendPacket', { packet: p });
    s.store.beforeQueue = () => { throw canceled([{ Code: 'ConditionalCheckFailed' }, { Code: 'ValidationError' }]); };
    s.events.length = 0;
    await s.call('a', 'sendPacket', { packet: p });
    assert.equal(s.error(), 'RETRY_REQUIRED');
    assert.equal(s.events.some(e => e.event === 'internet:ack'), false);
    assert.equal(s.store.packets.size, 1);
});
test('ambiguous control transaction uses its existing durable marker', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const p = request(a, b, s.now()); await s.call('a', 'sendPacket', { packet: p });
    const pending = s.store.pending.bind(s.store);
    s.store.pending = async (...args) => { s.store.pending = pending; return undefined; };
    s.store.beforeSaveControl = () => { throw canceled(); };
    s.events.length = 0;
    await s.call('a', 'sendPacket', { packet: p });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.packets.size, 2);
});
test('proof racing the over-cap duplicate still returns the vaccine to that gateway', async () => {
    const s = setup(), a = identity(), c = identity(), p = message(a, c, s.now());
    const gateways = Array.from({ length: 17 }, () => identity());
    await s.auth('c', c);
    for (let i = 0; i < 16; i++) {
        await s.auth(`gateway-${i}`, gateways[i]);
        await s.call(`gateway-${i}`, 'sendPacket', { packet: p });
    }
    await s.auth('gateway-16', gateways[16]);
    const pending = s.store.pending.bind(s.store);
    s.store.pending = async (...args) => {
        s.store.pending = pending;
        await s.call('c', 'deliveryProof', { packet: proof(p, c, s.now()) });
        return pending(...args);
    };
    await s.call('gateway-16', 'sendPacket', { packet: p });
    assert.ok(s.events.some(e => e.id === 'gateway-16' && e.event === 'internet:anti_packet'));
    assert.equal(s.events.at(-1).event, 'internet:ack');
});
test('recipient proof reaches gateways and remains durable for offline origin', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(); await s.auth('b', b); await s.auth('c', c);
    const p = message(a, c, s.now()), v = proof(p, c, s.now());
    await s.call('b', 'sendPacket', { packet: p });
    await s.call('c', 'deliveryProof', { packet: v });
    assert.ok(s.store.vaccines.get(p.packetId).proof);
    assert.equal([...s.store.packets.values()].filter(x => x.packetType === 'MESSAGE').length, 0);
    assert.deepEqual(new Set([...s.store.packets.values()].map(x => x.destinationNodeId)), new Set([a.nodeId, b.nodeId]));
    assert.ok(s.events.some(e => e.id === 'b' && e.event === 'internet:anti_packet' && e.data.messageId === p.packetId));
    s.events.length = 0; await s.call('b', 'sync');
    assert.ok(s.events.some(e => e.id === 'b' && e.event === 'internet:anti_packet'));
});
test('duplicate recipient proof does not rebroadcast to prior gateways; late uploader still gets vaccine', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(), d = identity();
    await s.auth('b', b); await s.auth('c', c); await s.auth('d', d);
    const p = message(a, c, s.now()), v = proof(p, c, s.now());
    await s.call('b', 'sendPacket', { packet: p });
    await s.call('c', 'deliveryProof', { packet: v });
    s.events.length = 0;
    s.store.beforeSaveProof = () => { throw new Error('duplicate proof must not rewrite fanout'); };
    await s.call('c', 'deliveryProof', { packet: v });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.events.filter(e => e.event === 'internet:anti_packet').length, 0);
    await s.call('d', 'sendPacket', { packet: p });
    assert.ok(s.events.some(e => e.id === 'd' && e.event === 'internet:anti_packet'));
    assert.ok([...s.store.packets.values()].some(x => x.packetType === 'DELIVERY_PROOF' && x.destinationNodeId === d.nodeId));
});
test('proof retries when a concurrent gateway joins after the vaccine read', async () => {
    const s = setup(), a = identity(), b = identity(), d = identity(), c = identity();
    await s.auth('b', b); await s.auth('c', c);
    const p = message(a, c, s.now()); await s.call('b', 'sendPacket', { packet: p });
    s.store.beforeSaveProof = () => {
        s.store.beforeSaveProof = undefined;
        s.store.vaccines.get(p.packetId).gatewayNodeIds.add(d.nodeId);
    };
    await s.call('c', 'deliveryProof', { packet: proof(p, c, s.now()) });
    assert.deepEqual(new Set([...s.store.packets.values()].filter(x => x.packetType === 'DELIVERY_PROOF').map(x => x.destinationNodeId)),
        new Set([a.nodeId, b.nodeId, d.nodeId]));
});
test('SDK cancellation without reasons retries proof against a concurrent gateway update', async () => {
    const s = setup(), a = identity(), b = identity(), d = identity(), c = identity();
    await s.auth('b', b); await s.auth('c', c);
    const p = message(a, c, s.now()); await s.call('b', 'sendPacket', { packet: p });
    s.store.beforeSaveProof = () => {
        s.store.beforeSaveProof = undefined;
        s.store.vaccines.get(p.packetId).gatewayNodeIds.add(d.nodeId);
        throw canceled();
    };
    s.events.length = 0;
    await s.call('c', 'deliveryProof', { packet: proof(p, c, s.now()) });
    assert.ok(s.events.some(e => e.id === 'c' && e.event === 'internet:ack'));
    assert.deepEqual(new Set([...s.store.packets.values()].filter(x => x.packetType === 'DELIVERY_PROOF').map(x => x.destinationNodeId)),
        new Set([a.nodeId, b.nodeId, d.nodeId]));
});
test('SDK cancellation without reasons accepts a concurrently stored proof', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity();
    await s.auth('b', b); await s.auth('c', c);
    const p = message(a, c, s.now()), v = proof(p, c, s.now());
    await s.call('b', 'sendPacket', { packet: p });
    s.store.afterSaveProof = () => { throw canceled(); };
    s.events.length = 0;
    await s.call('c', 'deliveryProof', { packet: v });
    assert.equal(s.events.at(-1).event, 'internet:ack');
    assert.equal(s.store.vaccines.get(p.packetId).proof.signature, v.signature);
});
test('gateway arriving after a Nearby proof gets a durable proof, not resurrected ciphertext', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(); await s.auth('b', b); await s.auth('c', c);
    const p = message(a, c, s.now()); await s.call('c', 'deliveryProof', { packet: proof(p, c, s.now()) });
    await s.call('b', 'sendPacket', { packet: p });
    assert.equal([...s.store.packets.values()].filter(x => x.packetType === 'MESSAGE').length, 0);
    assert.ok([...s.store.packets.values()].some(x => x.packetType === 'DELIVERY_PROOF' && x.destinationNodeId === b.nodeId));
    assert.ok(s.events.some(e => e.id === 'b' && e.event === 'internet:anti_packet'));
});
test('multiple authenticated connections for the same node receive live traffic', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b1', b); await s.auth('b2', b);
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) });
    assert.deepEqual(s.events.filter(e => e.event === 'internet:packet').map(e => e.id), ['b1', 'b2']);
});
test('GoneException removes only the stale connection and retains the packet', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b); s.gone.add('b');
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) });
    assert.equal(await s.store.connection('b'), undefined); assert.ok(await s.store.connection('a')); assert.equal(s.store.packets.size, 1);
});
test('GSI stale identity/expired rows are never used for live delivery', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    s.store.online = async function* () { yield 'b'; }; s.store.connections.get('b').expiresAt = s.now();
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) });
    assert.equal(s.events.some(e => e.event === 'internet:packet'), false);
});
test('valid proof stores vaccine, suppresses ciphertext, and queues offline sender notification', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: p }); await s.call('a', '$disconnect');
    await s.call('b', 'deliveryProof', { packet: proof(p, b, s.now()) });
    assert.ok((await s.store.vaccine(p.packetId)).proof);
    assert.deepEqual([...s.store.packets.values()].map(p => p.packetType), ['DELIVERY_PROOF']);
});
test('invalid proof and wrong authenticated submitter cannot delete ciphertext', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: p }); const v = proof(p, b, s.now());
    await s.call('b', 'deliveryProof', { packet: { ...v, signature: 'AAAA' } }); assert.equal(s.error(), 'INVALID_SIGNATURE');
    await s.call('a', 'deliveryProof', { packet: v }); assert.equal(s.error(), 'PROOF_IDENTITY_MISMATCH');
    assert.equal(s.store.packets.size, 1); assert.equal((await s.store.vaccine(p.packetId)).proof, undefined);
});
test('proof cannot change the source binding for an existing message ID', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: p });
    const v = proof({ ...p, sourceNodeId: identity().nodeId }, b, s.now()); await s.call('b', 'deliveryProof', { packet: v });
    assert.equal(s.error(), 'PROOF_IDENTITY_CONFLICT'); assert.equal(s.store.packets.size, 1);
});
test('vaccinated message cannot be resurrected and proof retries coalesce', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); const v = proof(p, b, s.now());
    await s.call('b', 'deliveryProof', { packet: v }); await s.call('b', 'deliveryProof', { packet: proof(p, b, s.now() + 1) });
    await s.call('a', 'sendPacket', { packet: p });
    assert.equal(s.events.at(-1).data.deliveredAlready, true); assert.equal(s.store.packets.size, 1);
});
test('Nearby proof winning before the Internet transaction prevents enqueue', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); s.store.beforeQueue = async () => {
        s.store.beforeQueue = undefined; await s.call('b', 'deliveryProof', { packet: proof(p, b, s.now()) });
    };
    await s.call('a', 'sendPacket', { packet: p });
    assert.equal([...s.store.packets.values()].some(p => p.packetType === 'MESSAGE'), false);
});
test('proof immediately after enqueue suppresses subsequent live send', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); s.events.length = 0;
    s.store.afterQueue = async () => { await s.call('b', 'deliveryProof', { packet: proof(p, b, s.now()) }); };
    await s.call('a', 'sendPacket', { packet: p });
    assert.equal(s.events.some(e => e.event === 'internet:packet'), false);
});
test('termination after atomic proof write leaves notification durable and sync suppresses old ciphertext', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: p });
    s.store.beforeDelete = () => { throw new Error('simulated termination'); };
    await s.call('b', 'deliveryProof', { packet: proof(p, b, s.now()) });
    assert.ok([...s.store.packets.values()].some(p => p.packetType === 'DELIVERY_PROOF'));
    s.store.beforeDelete = undefined; s.events.length = 0; await s.call('b', 'sync');
    assert.equal(s.events.some(e => e.event === 'internet:packet'), false);
});
test('sync skips and cleans expired pending rows independent of DynamoDB TTL deletion', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); const p = message(a, b, s.now());
    await s.call('a', 'sendPacket', { packet: p }); s.advance(DAY); await s.auth('b', b);
    assert.equal(s.events.some(e => e.event === 'internet:packet'), false); assert.equal(s.store.packets.size, 0);
});
test('sync ordering is proofs, controls, then messages and repeat sync is logically idempotent', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('b', b);
    await s.call('b', 'sendPacket', { packet: message(b, a, s.now()) });
    await s.call('b', 'sendPacket', { packet: request(b, a, s.now()) });
    await s.call('b', 'deliveryProof', { packet: proof(message(a, b, s.now()), b, s.now()) });
    s.events.length = 0; await s.auth('a', a);
    const expected = ['internet:anti_packet', 'internet:connection_request', 'internet:packet'];
    assert.deepEqual(s.events.filter(e => e.id === 'a' && expected.includes(e.event)).map(e => e.event), expected);
    s.events.length = 0; await s.call('a', 'sync');
    assert.deepEqual(s.events.filter(e => expected.includes(e.event)).map(e => e.event), expected);
});
test('offline connection request and response survive; regenerated responses coalesce', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const req = request(a, b, s.now()); await s.call('a', 'sendPacket', { packet: req }); await s.call('a', '$disconnect');
    await s.auth('b', b); assert.ok(s.events.some(e => e.event === 'internet:connection_request'));
    await s.call('b', 'sendPacket', { packet: response(req, b, s.now()) });
    await s.call('b', 'sendPacket', { packet: response(req, b, s.now() + 1) });
    await s.auth('a2', a); assert.equal(s.events.filter(e => e.id === 'a2' && e.event === 'internet:connection_response').length, 1);
});
test('forged connection-control signatures are rejected', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    await s.call('a', 'sendPacket', { packet: { ...request(a, b, s.now()), senderDisplayName: 'tampered' } });
    assert.equal(s.error(), 'INVALID_SIGNATURE');
});
test('$disconnect is idempotent and isolates multiple sessions', async () => {
    const s = setup(), a = identity(); await s.auth('a', a); await s.auth('a2', a);
    await s.call('a', '$disconnect'); await s.call('a', '$disconnect'); assert.ok(await s.store.connection('a2'));
});
test('malformed/default routes and oversized data return structured errors without stacks', async () => {
    const s = setup(); await s.call('a', '$connect');
    await s.call('a', '$default'); assert.equal(s.error(), 'INVALID_ACTION');
    await s.handler({ requestContext: { connectionId: 'a', routeKey: 'sendPacket' }, body: '{' }); assert.equal(s.error(), 'INVALID_JSON');
    await s.call('a', 'sendPacket', { padding: 'x'.repeat(33000) }); assert.equal(s.error(), 'INVALID_FRAME');
    assert.equal(JSON.stringify(s.events).includes('stack'), false);
});
test('cursor cannot select another node partition', async () => {
    const s = setup(), a = identity(); await s.auth('a', a);
    await s.call('a', 'sync', { cursor: Buffer.from(JSON.stringify({ destinationNodeId: identity().nodeId, packetKey: '2#x' })).toString('base64url') });
    assert.equal(s.error(), 'INVALID_CURSOR');
});
test('expired, excessive-hop and malformed mesh packets are rejected', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    for (const change of [{ hopCount: 9 }, { hopCount: -1 }, { encryptedPayload: 'garbage' }, { expiresAt: s.now() }]) {
        await s.call('a', 'sendPacket', { packet: { ...message(a, b, s.now()), ...change } });
        assert.ok(s.error());
    }
    assert.equal(s.store.packets.size, 0);
});
test('canonical ECDSA payloads preserve the existing V1 strings and UTF-8 lengths', () => {
    assert.equal(authBytes('node', 'id', 'challenge').toString(), 'PLEXUS_SERVER_AUTH_V1|node|id|challenge');
    const a = identity(), b = identity(), r = request(a, b, 1800000000000);
    assert.ok(controlBytes(r).toString().includes('11:Harsha 🪷|'));
    const v = proof(message(a, b, 1800000000000), b, 1800000000000);
    assert.equal(proofBytes(v).toString(), `PLEXUS_DELIVERY_V1|${v.messageId}|${a.nodeId}|${b.nodeId}|${v.deliveredAt}|${v.expiresAt}`);
    assert.equal(controlBytes({ type: 'connection_request', requestId: 'id', senderNodeId: 'a', recipientNodeId: 'b',
        senderDisplayName: 'é|🪷', senderSigningPublicKey: 'sign', senderAgreementPublicKey: 'agree', createdAt: 1000, expiresAt: 2000 }).toString(),
    'PLEXUS_CONNECTION_REQUEST_V1|2:id|1:a|1:b|7:é|🪷|4:sign|5:agree|4:1000|4:2000|');
    assert.equal(controlBytes({ type: 'connection_response', requestId: 'id', responseId: 'reply', requesterNodeId: 'a', responderNodeId: 'b', response: 'ACCEPTED',
        responderDisplayName: 'é|🪷', responderSigningPublicKey: 'sign', responderAgreementPublicKey: 'agree', createdAt: 1000, expiresAt: 2000 }).toString(),
    'PLEXUS_CONNECTION_RESPONSE_V1|2:id|5:reply|1:a|1:b|8:ACCEPTED|7:é|🪷|4:sign|5:agree|4:1000|4:2000|');
});

test('sync pagination resumes after the exact prior key without dropping packets', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    for (let i = 0; i < 25; i++) await s.call('a', 'sendPacket', { packet: message(a, b, s.now() + i) });
    s.events.length = 0; await s.auth('b', b);
    const cursor = s.events.at(-1).data.cursor; assert.ok(cursor);
    assert.equal(s.events.filter(e => e.event === 'internet:packet').length, 20);
    await s.call('b', 'sync', { cursor });
    const packets = s.events.filter(e => e.event === 'internet:packet'); assert.equal(packets.length, 25);
    assert.equal(new Set(packets.map(e => e.data.packetId)).size, 25);
});
test('unknown fields are never persisted and JSON member order does not change idempotency', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: { ...p, unrelated: 'ignored' } });
    await s.call('a', 'sendPacket', { packet: Object.fromEntries(Object.entries(p).reverse()) });
    assert.equal(s.error(), undefined); assert.equal(s.store.packets.size, 1);
    assert.equal([...s.store.packets.values()][0].payload.unrelated, undefined);
});
