import test from 'node:test';
import assert from 'node:assert/strict';
import { setup, identity, message, proof, request, response, signed } from './fixtures.js';
import { authBytes, proofBytes, controlBytes, originBytes, row, DAY } from '../src/protocol.js';
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
test('durable message triggers a sync wake even when a socket write succeeds', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) }); assert.deepEqual(s.pushes, [b.nodeId]);
    await s.auth('b', b); await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) }); assert.deepEqual(s.pushes, [b.nodeId, b.nodeId]);
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
test('sync has a bounded message head start followed by proofs and controls; repeat delivery keeps stable IDs', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('b', b);
    await s.call('b', 'sendPacket', { packet: message(b, a, s.now()) });
    await s.call('b', 'sendPacket', { packet: request(b, a, s.now()) });
    await s.call('b', 'deliveryProof', { packet: proof(message(a, b, s.now()), b, s.now()) });
    s.events.length = 0; await s.auth('a', a);
    const expected = ['internet:packet', 'internet:anti_packet', 'internet:connection_request'];
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
    const packets = s.events.filter(e => e.event === 'internet:packet'); assert.equal(packets.length, 30); // Separate lanes may redeliver stable IDs.
    assert.equal(new Set(packets.map(e => e.data.packetId)).size, 25);
});
test('unknown fields are never persisted and JSON member order does not change idempotency', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const p = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet: { ...p, unrelated: 'ignored' } });
    await s.call('a', 'sendPacket', { packet: Object.fromEntries(Object.entries(p).reverse()) });
    assert.equal(s.error(), undefined); assert.equal(s.store.packets.size, 1);
    assert.equal([...s.store.packets.values()][0].payload.unrelated, undefined);
});


test('peer blocking is authenticated and cannot bind a policy to another recipient', async () => {
    const s = setup(), a = identity(), b = identity();
    await s.call('a', '$connect');
    await s.call('a', 'setPeerBlocked', { peerNodeId: b.nodeId, blocked: true, revision: 1 });
    assert.equal(s.error(), 'NOT_AUTHENTICATED');
    await s.auth('a', a);
    await s.call('a', 'setPeerBlocked', { nodeId: b.nodeId, peerNodeId: b.nodeId, blocked: true, revision: 1 });
    assert.equal(await s.store.peerBlocked(a.nodeId, b.nodeId), true);
    assert.equal(await s.store.peerBlocked(b.nodeId, a.nodeId), false);
});
test('blocked origin messages and requests are suppressed without wakes or delivery proofs', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    s.events.length = 0;
    await s.call('a', 'sendPacket', { packet: message(a, b, s.now()) });
    await s.call('a', 'sendPacket', { packet: request(a, b, s.now()) });
    assert.equal((await s.store.page(b.nodeId)).items.length, 0); assert.equal(s.pushes.length, 0);
    assert.equal(s.events.some(e => ['internet:packet', 'internet:connection_request', 'internet:anti_packet'].includes(e.event)), false);
    assert.equal([...s.store.vaccines.values()].some(v => v.proof), false);
});
test('blocking cleans pending backlog with discard tombstones, not proofs; unblocking cannot resurrect it', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const packet = message(a, b, s.now()); const req = request(a, b, s.now());
    await s.call('a', 'sendPacket', { packet }); await s.call('a', 'sendPacket', { packet: req });
    await s.auth('b', b);
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    s.events.length = 0;
    await s.call('b', 'sync'); await s.call('b', 'sync');
    assert.deepEqual((await s.store.page(b.nodeId)).items, []);
    assert.equal(s.store.vaccines.get(packet.packetId).discarded, true);
    assert.equal(s.store.vaccines.get(packet.packetId).proof, undefined);
    assert.equal(s.events.some(e => ['internet:packet', 'internet:connection_request', 'internet:anti_packet'].includes(e.event)), false);
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: false, revision: 2 });
    await s.call('a', 'sendPacket', { packet });
    await s.call('a', 'sendPacket', { packet: req });
    assert.deepEqual((await s.store.page(b.nodeId)).items, []);
    assert.equal(s.events.some(e => e.event === 'internet:anti_packet'), false);
    await s.call('a', 'sendPacket', { packet: request(a, b, s.now()) });
    assert.equal((await s.store.page(b.nodeId)).items.length, 1);
});
test('blocked discard requires the authenticated destination and blocked source', async () => {
    const s = setup(), a = identity(), b = identity(), attacker = identity();
    await s.auth('a', a); await s.auth('attacker', attacker);
    const packet = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet });
    const pending = (await s.store.page(b.nodeId)).items[0];
    await s.call('attacker', 'discardBlockedPacket', { nodeId: b.nodeId, packetKey: pending.packetKey, peerNodeId: a.nodeId });
    assert.equal(s.error(), 'PEER_NOT_BLOCKED');
    await s.call('attacker', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    await s.call('attacker', 'discardBlockedPacket', { nodeId: b.nodeId, packetKey: pending.packetKey, peerNodeId: a.nodeId });
    assert.equal((await s.store.page(b.nodeId)).items.length, 1);
    await s.auth('b', b);
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    await s.call('b', 'discardBlockedPacket', { packetKey: pending.packetKey, peerNodeId: a.nodeId });
    assert.equal((await s.store.page(b.nodeId)).items.length, 0);
    assert.equal(s.store.vaccines.get(packet.packetId).proof, undefined);
});
test('stale policy retries cannot re-block after an explicit unblock', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('b', b);
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: false, revision: 2 });
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    assert.equal(await s.store.peerBlocked(b.nodeId, a.nodeId), false);
});
test('gateway upload blocking binds to original sender, never uploader', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(); await s.auth('b', b); await s.auth('c', c);
    await s.call('c', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    await s.call('b', 'sendPacket', { packet: message(a, c, s.now()) });
    assert.equal((await s.store.page(c.nodeId)).items.length, 0);
    assert.equal(s.pushes.length, 0);
});

for (const type of ['message', 'request']) test(`discarded ${type} cannot resurrect after interrupted deletion and unblock`, async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const packet = type === 'message' ? message(a, b, s.now()) : request(a, b, s.now());
    await s.call('a', 'sendPacket', { packet }); await s.auth('b', b);
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: true, revision: 1 });
    s.store.beforeDelete = () => { throw new Error('interrupted deletion'); };
    await s.call('b', 'sync'); assert.equal(s.error(), 'RETRY_REQUIRED');
    assert.equal((await s.store.page(b.nodeId)).items.length, 1);
    s.store.beforeDelete = undefined;
    await s.call('b', 'setPeerBlocked', { peerNodeId: a.nodeId, blocked: false, revision: 2 });
    s.events.length = 0; await s.call('b', 'sync');
    assert.deepEqual((await s.store.page(b.nodeId)).items, []);
    assert.equal(s.events.some(e => ['internet:packet', 'internet:connection_request', 'internet:anti_packet'].includes(e.event)), false);
});
test('block arriving during persistence suppresses live delivery and FCM without a proof', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a); await s.auth('b', b);
    s.store.afterQueue = async () => s.store.setPeerBlocked(b.nodeId, a.nodeId, true, 1);
    s.events.length = 0;
    const packet = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet });
    assert.deepEqual((await s.store.page(b.nodeId)).items, []);
    assert.equal(s.store.vaccines.get(packet.packetId).proof, undefined);
    assert.equal(s.pushes.length, 0);
    assert.equal(s.events.some(e => ['internet:packet', 'internet:anti_packet'].includes(e.event)), false);
});
test('FCM failure never removes the durable packet or acknowledges destination delivery', async () => {
    const s = setup({ pushFailure: true }), a = identity(), b = identity(); await s.auth('a', a);
    const packet = message(a, b, s.now()); await s.call('a', 'sendPacket', { packet });
    assert.equal((await s.store.page(b.nodeId)).items.length, 1);
    assert.equal(s.store.vaccines.get(packet.packetId).proof, undefined);
    assert.equal(s.events.some(e => e.event === 'internet:anti_packet'), false);
    assert.equal(s.events.filter(e => e.event === 'internet:ack').at(-1).data.accepted, true);
    await s.auth('b', b);
    assert.equal(s.events.some(e => e.id === 'b' && e.event === 'internet:packet'), true);
});

function seedSyncBacklog(s, sender, recipient, messageCount) {
    for (let i = 0; i < 200; i++) {
        const p = proof(message(recipient, sender, s.now() + i), sender, s.now() + i);
        const item = row(p, { type: 'DELIVERY_PROOF', id: p.messageId, source: sender.nodeId,
            destination: recipient.nodeId, priority: 0, event: 'internet:anti_packet' });
        s.store.packets.set(s.store.key(recipient.nodeId, item.packetKey), item);
    }
    const messages = Array.from({ length: messageCount }, (_, i) => message(sender, recipient, s.now() + i));
    for (const p of messages) {
        const item = row(p, { type: 'MESSAGE', id: p.packetId, source: sender.nodeId,
            destination: recipient.nodeId, priority: 2, event: 'internet:packet' });
        s.store.packets.set(s.store.key(recipient.nodeId, item.packetKey), item);
    }
    const req = request(sender, recipient, s.now());
    const item = row(req, { type: 'CONNECTION_REQUEST', id: req.requestId, source: sender.nodeId,
        destination: recipient.nodeId, priority: 1, event: 'internet:connection_request' });
    s.store.packets.set(s.store.key(recipient.nodeId, item.packetKey), item);
    return messages;
}

test('200 retained proofs cannot starve one pending message on first authentication without FGS', async () => {
    const s = setup(), a = identity(), b = identity();
    const [p] = seedSyncBacklog(s, a, b, 1);
    await s.auth('b', b);
    const delivered = s.events.filter(e => e.event.startsWith('internet:') && e.event !== 'internet:error');
    assert.equal(delivered[0].data.packetId, p.packetId);
    assert.equal(delivered.filter(e => e.event === 'internet:anti_packet').length, 20);
    assert.equal(delivered.filter(e => e.event === 'internet:packet').length, 1);
});

test('interrupted reconnects rotate message batches and proof/control traversal despite newer live traffic', async () => {
    const s = setup(), a = identity(), b = identity(); await s.auth('a', a);
    const old = seedSyncBacklog(s, a, b, 13);
    const seen = new Set(), proofs = new Set();
    for (let i = 0; i < 11; i++) {
        s.events.length = 0;
        await s.auth(`b${i}`, b); // Never follow the returned pagination cursor.
        const packets = s.events.filter(e => e.event === 'internet:packet');
        for (const e of packets) seen.add(e.data.packetId);
        for (const e of s.events.filter(e => e.event === 'internet:anti_packet')) proofs.add(e.data.messageId);
        if (i < 3) assert.ok(packets.length <= 5);
        if (i === 2) assert.ok(old.every(p => seen.has(p.packetId)));
        if (i === 10) assert.ok(s.events.some(e => e.event === 'internet:connection_request'));
        const fresh = message(a, b, s.now() + 1000 + i);
        await s.call('a', 'sendPacket', { packet: fresh });
        assert.ok(s.events.some(e => e.id === `b${i}` && e.data.packetId === fresh.packetId));
        await s.call(`b${i}`, '$disconnect');
    }
    assert.equal(proofs.size, 200);
});

test('socket loss mid-lane advances scheduling without deleting skipped messages; wraparound recovers them', async () => {
    const s = setup(), a = identity(), b = identity(); const pending = seedSyncBacklog(s, a, b, 12);
    s.store.afterPost = async (id, frame) => {
        if (id === 'b0' && frame.event === 'internet:packet') s.gone.add(id);
    };
    await s.auth('b0', b);
    assert.equal(s.events.filter(e => e.event === 'internet:packet').length, 1);
    s.store.afterPost = undefined;
    for (let i = 1; i <= 3; i++) { await s.auth(`b${i}`, b); await s.call(`b${i}`, '$disconnect'); }
    const seen = new Set(s.events.filter(e => e.event === 'internet:packet').map(e => e.data.packetId));
    assert.ok(pending.every(p => seen.has(p.packetId)));
    assert.equal([...s.store.packets.values()].filter(p => p.packetType === 'MESSAGE').length, 12);
});

test('busy continuation preserves checkpoints and duplicate sync stops returning ciphertext after signed proof', async () => {
    const s = setup(), a = identity(), b = identity(); const pending = seedSyncBacklog(s, a, b, 12);
    await s.auth('b', b);
    const cursor = s.events.at(-1).data.cursor;
    s.store.connections.get('b').lock = 'other';
    await s.call('b', 'sync', { cursor });
    assert.deepEqual(s.events.at(-1).data, { busy: true, cursor });
    delete s.store.connections.get('b').lock;
    s.events.length = 0; await s.call('b', 'sync'); // Even losing the client hint cannot reset progress.
    assert.deepEqual(s.events.filter(e => e.event === 'internet:packet').map(e => e.data.packetId), pending.slice(5, 10).map(p => p.packetId));
    await s.call('b', 'deliveryProof', { packet: proof(pending[0], b, s.now()) });
    s.events.length = 0;
    for (let i = 0; i < 4; i++) await s.call('b', 'sync');
    assert.equal(s.events.some(e => e.event === 'internet:packet' && e.data.packetId === pending[0].packetId), false);
    assert.ok(s.events.some(e => e.event === 'internet:packet' && e.data.packetId === pending[1].packetId));
});

test('concurrent sync claims use conditional durable progress and expired progress safely restarts', async () => {
    const s = setup(), a = identity(), b = identity(); const pending = seedSyncBacklog(s, a, b, 12);
    const pages = await Promise.all([s.store.claimSyncPage(b.nodeId, true, s.now()), s.store.claimSyncPage(b.nodeId, true, s.now())]);
    assert.equal(new Set(pages.flatMap(p => p.items.map(i => i.stableId))).size, 2);
    const page = await s.store.claimSyncPage(b.nodeId, true, s.now() + 8 * DAY);
    assert.deepEqual(page.items.map(i => i.stableId), pending.slice(0, 1).map(p => p.packetId));
});

test('repeated connections dying after one ciphertext post do not permanently skip message batch tails', async () => {
    const s = setup(), a = identity(), b = identity(); const pending = seedSyncBacklog(s, a, b, 10);
    s.store.afterPost = async (id, frame) => { if (frame.event === 'internet:packet') s.gone.add(id); };
    for (let i = 0; i < 10; i++) await s.auth(`b${i}`, b);
    const seen = new Set(s.events.filter(e => e.event === 'internet:packet').map(e => e.data.packetId));
    assert.ok(pending.every(p => seen.has(p.packetId)));
    assert.equal([...s.store.packets.values()].filter(p => p.packetType === 'MESSAGE').length, 10);
});

test('interrupted normal traversal checkpoints the last posted proof, not the end of its unread page', async () => {
    const s = setup(), a = identity(), b = identity(); seedSyncBacklog(s, a, b, 0);
    s.store.afterPost = async (id, frame) => { if (frame.event === 'internet:anti_packet') s.gone.add(id); };
    await s.auth('b0', b); await s.auth('b1', b);
    const proofs = s.events.filter(e => e.event === 'internet:anti_packet');
    const expected = [...s.store.packets.values()].filter(p => p.packetType === 'DELIVERY_PROOF')
        .sort((a, b) => a.packetKey.localeCompare(b.packetKey)).slice(0, 2).map(p => p.stableId);
    assert.deepEqual(proofs.map(e => e.data.messageId), expected);
});

test('late concurrent post cannot roll back a newer durable message checkpoint', async () => {
    const s = setup(), a = identity(), b = identity(); const pending = seedSyncBacklog(s, a, b, 5);
    let release, entered;
    const held = new Promise(resolve => { release = resolve; });
    const started = new Promise(resolve => { entered = resolve; });
    const slow = s.store.claimSyncPage(b.nodeId, true, s.now(), async () => { entered(); await held; return true; });
    await started;
    await s.store.claimSyncPage(b.nodeId, true, s.now(), async () => true);
    await s.store.claimSyncPage(b.nodeId, true, s.now(), async () => true);
    release(); await slow;
    const next = await s.store.claimSyncPage(b.nodeId, true, s.now(), async () => true);
    assert.equal(next.items[0].stableId, pending[2].packetId);
});

test('diagnostic lifecycle correlates authenticated message persistence, sync and ACK without raw identity or packet data', async () => {
    const { createHash } = await import('node:crypto');
    const short = v => createHash('sha256').update(v).digest('hex').slice(0, 12);
    const s = setup(), a = identity(), b = identity(); await s.auth('sender-private-connection', a);
    const p = message(a, b, s.now());
    await s.call('sender-private-connection', 'sendPacket', { packet: p });
    await s.auth('recipient-private-connection', b);
    const events = s.diagnostics.filter(d => d.id === short(p.packetId)).map(d => d.event);
    for (const expected of ['SEND_PACKET_VALIDATED', 'MESSAGE_PERSIST_BEGIN', 'PENDING_MESSAGE_STORED',
        'LIVE_FORWARD_RESULT', 'FCM_WAKE_ATTEMPT', 'SEND_PACKET_ACK_POSTED', 'SYNC_MESSAGE_SELECTED', 'SYNC_MESSAGE_POST_RESULT']) {
        assert.ok(events.includes(expected), expected);
    }
    for (const expected of ['WS_CONNECTED', 'AUTH_INIT_VALIDATED', 'AUTH_CHALLENGE_POSTED',
        'AUTH_PROOF_VERIFIED', 'AUTH_SUCCESS_POSTED', 'SYNC_BEGIN', 'MESSAGE_LANE_BEGIN', 'SYNC_COMPLETE_POSTED']) {
        assert.ok(s.diagnostics.some(d => d.event === expected), expected);
    }
    const logs = JSON.stringify(s.diagnostics);
    for (const raw of [p.packetId, a.nodeId, b.nodeId, a.signingPublicKey, b.signingPublicKey,
        p.originSignature, p.encryptedPayload, 'sender-private-connection', 'recipient-private-connection']) {
        assert.equal(logs.includes(raw), false);
    }
    assert.ok(s.diagnostics.every(d => d.connection.match(/^[a-f0-9]{12}$/)));
});
