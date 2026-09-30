import test from 'node:test';
import assert from 'node:assert/strict';
import { DynamoStore } from '../src/store.js';
import { row } from '../src/protocol.js';
import { identity, message, proof } from './fixtures.js';
const tables = { connections: 'connections', pending: 'pending', vaccines: 'vaccines', nodeIndex: 'nodeId-index' };
function setup(replies = []) {
    const calls = [];
    const store = new DynamoStore({ async send(command) { calls.push(command); return replies.shift() ?? {}; } }, tables);
    return { store, calls };
}
test('base-table reads are strong; GSI query paginates without ConsistentRead', async () => {
    const s = setup([{ Item: { connectionId: 'c' } }, { Item: { messageId: 'm' } },
        { Items: [{ connectionId: 'a' }], LastEvaluatedKey: { connectionId: 'a' } }, { Items: [{ connectionId: 'b' }] }]);
    await s.store.connection('c'); await s.store.vaccine('m');
    const ids = []; for await (const id of s.store.online('node')) ids.push(id);
    assert.deepEqual(ids, ['a', 'b']); assert.equal(s.calls[0].input.ConsistentRead, true);
    assert.equal(s.calls[1].input.ConsistentRead, true); assert.equal(s.calls[2].input.ConsistentRead, undefined);
    assert.equal(s.calls[2].input.IndexName, 'nodeId-index');
    assert.deepEqual(s.calls[3].input.ExclusiveStartKey, { connectionId: 'a' });
});
test('message transaction atomically gates on absence of proof and immutable identity/digest', async () => {
    const s = setup(), a = identity(), b = identity(), p = message(a, b, Date.now());
    const item = row(p, { id: p.packetId, type: 'MESSAGE', source: a.nodeId, destination: b.nodeId, priority: 2, event: 'internet:packet' });
    await s.store.queueMessage(item);
    const command = s.calls[0]; assert.equal(command.constructor.name, 'TransactWriteCommand');
    const [vaccine, pending] = command.input.TransactItems;
    assert.match(vaccine.Update.ConditionExpression, /attribute_not_exists\(proof\)/);
    assert.match(vaccine.Update.ConditionExpression, /packetDigest = :digest/);
    assert.match(vaccine.Update.ConditionExpression, /recipientNodeId = :dest/);
    assert.deepEqual(pending.Put.Item, item); assert.equal(item.ttl, Math.floor(p.expiresAt / 1000));
});
test('courier admission records bounded proof recipients in the same transaction', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(), p = message(a, c, Date.now());
    const item = row(p, { id: p.packetId, type: 'MESSAGE', source: a.nodeId, destination: c.nodeId, priority: 2, event: 'internet:packet' });
    await s.store.queueMessage(item, b.nodeId);
    const update = s.calls[0].input.TransactItems[0].Update;
    assert.match(update.UpdateExpression, /ADD gatewayNodeIds :gateways/);
    assert.match(update.ConditionExpression, /size\(gatewayNodeIds\) < :maxGateways/);
    assert.deepEqual(update.ExpressionAttributeValues[':gateways'], new Set([b.nodeId]));
    assert.equal(update.ExpressionAttributeValues[':maxGateways'], 16);
});
test('proof and sender notification are in one atomic write and preserve message reservation metadata', async () => {
    const s = setup(), a = identity(), b = identity(), v = proof(message(a, b, Date.now()), b, Date.now());
    const item = row(v, { id: v.messageId, type: 'DELIVERY_PROOF', source: b.nodeId, destination: a.nodeId, priority: 0, event: 'internet:anti_packet' });
    await s.store.saveProof(item, undefined);
    const operations = s.calls[0].input.TransactItems;
    assert.equal(operations.length, 2); assert.equal(operations[0].Update.TableName, 'vaccines');
    assert.match(operations[0].Update.ConditionExpression, /attribute_not_exists\(proof\)/);
    assert.doesNotMatch(operations[0].Update.UpdateExpression, /pendingKey/);
    assert.equal(operations[1].Put.Item.destinationNodeId, a.nodeId);
});
test('proof transaction atomically queues each gateway and checks the observed gateway set', async () => {
    const s = setup(), a = identity(), b = identity(), c = identity(), p = message(a, c, Date.now());
    const v = proof(p, c, Date.now());
    const item = row(v, { id: v.messageId, type: 'DELIVERY_PROOF', source: c.nodeId, destination: a.nodeId, priority: 0, event: 'internet:anti_packet' });
    await s.store.saveProof(item, { sourceNodeId: a.nodeId, recipientNodeId: c.nodeId, gatewayNodeIds: new Set([b.nodeId]) });
    const operations = s.calls[0].input.TransactItems;
    assert.match(operations[0].Update.ConditionExpression, /gatewayNodeIds = :oldGateways/);
    assert.equal(operations.length, 3);
    assert.deepEqual(new Set(operations.slice(1).map(x => x.Put.Item.destinationNodeId)), new Set([a.nodeId, b.nodeId]));
});
test('sync query is strongly consistent, bounded, ordered and excludes metadata markers', async () => {
    const s = setup(); await s.store.page('node', undefined);
    const input = s.calls[0].input;
    assert.equal(input.ConsistentRead, true); assert.equal(input.Limit, 20);
    assert.equal(input.ExpressionAttributeValues[':upper'], '3');
    assert.equal(input.ScanIndexForward, undefined); assert.equal(input.IndexName, undefined);
});
test('pending page excludes a registered PUSH_TOKEN row in the same recipient partition', async () => {
    const registration = setup();
    await registration.store.registerPushToken('node', 'test-push-token', Date.now());
    const push = registration.calls[0].input.Item;
    assert.equal(push.packetType, 'PUSH_TOKEN');
    assert.match(push.packetKey, /^8#PUSH#/);
    const packets = ['0#proof', '1#request', '2#message'].map(packetKey => ({ destinationNodeId: 'node', packetKey }));
    const rows = [...packets, push, { destinationNodeId: 'other-node', packetKey: '2#other' }];
    const store = new DynamoStore({ async send(command) {
        assert.equal(command.constructor.name, 'QueryCommand');
        assert.equal(command.input.TableName, registration.calls[0].input.TableName);
        assert.equal(command.input.ConsistentRead, true);
        assert.equal(command.input.KeyConditionExpression, 'destinationNodeId = :node AND packetKey < :upper');
        const values = command.input.ExpressionAttributeValues;
        assert.equal(values[':node'], 'node');
        assert.equal(values[':upper'], '3');
        // Apply DynamoDB's actual partition/range predicate to mixed stored rows.
        return { Items: rows.filter(item => item.destinationNodeId === values[':node'] && item.packetKey < values[':upper']) };
    } }, tables);
    const page = await store.page('node', undefined);
    assert.deepEqual(page.items, packets);
    assert.equal(page.items.some(item => item.packetType === 'PUSH_TOKEN'), false);
});
test('auth success condition consumes the exact unexpired challenge and removes it', async () => {
    const s = setup(); await s.store.authenticate('c', { connectedAt: 1000, claimedNodeId: 'node', challengeId: 'challenge' }, 2000);
    const input = s.calls[0].input;
    assert.match(input.ConditionExpression, /challengeId = :challengeId AND challengeExpiresAt > :now/);
    assert.match(input.UpdateExpression, /REMOVE challengeId, challenge, challengeExpiresAt, claimedNodeId/);
    assert.equal(input.ExpressionAttributeValues[':node'], 'node');
});
test('conditional lease and rate limits live in DynamoDB, not local maps', async () => {
    const s = setup(); await s.store.rate('c', 1000); await s.store.lockSync('c', 'token', 1000); await s.store.unlockSync('c', 'token');
    assert.match(s.calls[0].input.ConditionExpression, /rateWindow/);
    assert.match(s.calls[1].input.ConditionExpression, /syncUntil < :now/);
    assert.equal(s.calls[2].input.ConditionExpression, 'syncToken = :token');
});
