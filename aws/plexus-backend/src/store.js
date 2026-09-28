import { GetCommand, PutCommand, UpdateCommand, DeleteCommand, QueryCommand, TransactWriteCommand } from '@aws-sdk/lib-dynamodb';

export const conditional = e => e?.name === 'ConditionalCheckFailedException' ||
    (e?.name === 'TransactionCanceledException' && e.CancellationReasons?.some(r => r.Code === 'ConditionalCheckFailed') &&
        e.CancellationReasons.every(r => ['None', 'ConditionalCheckFailed'].includes(r?.Code)));

// Missing reasons also occur in real SDK exceptions. Only the caller's strong
// post-transaction reads can establish whether an expected race actually won.
export const transactionRaceCandidate = e => conditional(e) ||
    (e?.name === 'TransactionCanceledException' &&
        (!e.CancellationReasons?.length || e.CancellationReasons.every(r =>
            !r?.Code || ['None', 'ConditionalCheckFailed', 'TransactionConflict'].includes(r.Code))));

/** No invocation-local state owns identity, pending work, locks, or delivery decisions. */
export class DynamoStore {
    constructor(db, tables) { this.db = db; this.tables = tables; }
    async get(table, key) { return (await this.db.send(new GetCommand({ TableName: table, Key: key, ConsistentRead: true }))).Item; }
    connection(id) { return this.get(this.tables.connections, { connectionId: id }); }
    vaccine(id) { return this.get(this.tables.vaccines, { messageId: id }); }
    pending(node, key) { return this.get(this.tables.pending, { destinationNodeId: node, packetKey: key }); }
    async connect(id, now) {
        try { await this.db.send(new PutCommand({ TableName: this.tables.connections,
            Item: { connectionId: id, authenticated: false, connectedAt: now, expiresAt: now + 120_000, ttl: Math.floor((now + 120_000) / 1000) },
            ConditionExpression: 'attribute_not_exists(connectionId)' })); }
        catch (e) { if (!conditional(e)) throw e; }
    }
    async disconnect(id) { await this.db.send(new DeleteCommand({ TableName: this.tables.connections, Key: { connectionId: id } })); }
    async challenge(id, challenge, now) {
        await this.db.send(new UpdateCommand({ TableName: this.tables.connections, Key: { connectionId: id },
            UpdateExpression: 'SET claimedNodeId = :node, signingPublicKey = :key, challengeId = :id, challenge = :challenge, challengeExpiresAt = :expiry',
            ConditionExpression: 'authenticated = :no AND expiresAt > :now',
            ExpressionAttributeValues: { ':node': challenge.nodeId, ':key': challenge.signingPublicKey,
                ':id': challenge.challengeId, ':challenge': challenge.challenge, ':expiry': challenge.challengeExpiresAt, ':no': false, ':now': now } }));
    }
    async authenticate(id, state, now) {
        const expiry = state.connectedAt + 125 * 60_000;
        await this.db.send(new UpdateCommand({ TableName: this.tables.connections, Key: { connectionId: id },
            UpdateExpression: 'SET authenticated = :yes, nodeId = :node, authenticatedAt = :now, expiresAt = :expiry, #ttl = :ttl REMOVE challengeId, challenge, challengeExpiresAt, claimedNodeId',
            ConditionExpression: 'authenticated = :no AND challengeId = :challengeId AND challengeExpiresAt > :now AND expiresAt > :now',
            ExpressionAttributeNames: { '#ttl': 'ttl' }, ExpressionAttributeValues: { ':yes': true, ':no': false,
                ':node': state.claimedNodeId, ':now': now, ':expiry': expiry, ':ttl': Math.floor(expiry / 1000), ':challengeId': state.challengeId } }));
    }
    async rate(id, now) {
        const window = Math.floor(now / 1000);
        try { await this.db.send(new UpdateCommand({ TableName: this.tables.connections, Key: { connectionId: id },
            UpdateExpression: 'SET rateWindow = :window, rateCount = :one',
            ConditionExpression: 'expiresAt > :now AND (attribute_not_exists(rateWindow) OR rateWindow <> :window)',
            ExpressionAttributeValues: { ':window': window, ':one': 1, ':now': now } })); return true; }
        catch (e) { if (!conditional(e)) throw e; }
        try { await this.db.send(new UpdateCommand({ TableName: this.tables.connections, Key: { connectionId: id },
            UpdateExpression: 'ADD rateCount :one', ConditionExpression: 'expiresAt > :now AND rateWindow = :window AND rateCount < :limit',
            ExpressionAttributeValues: { ':now': now, ':window': window, ':one': 1, ':limit': 20 } })); return true; }
        catch (e) { if (!conditional(e)) throw e; return false; }
    }
    async *online(node) {
        let cursor;
        do {
            const page = await this.db.send(new QueryCommand({ TableName: this.tables.connections,
                IndexName: this.tables.nodeIndex, KeyConditionExpression: 'nodeId = :node',
                ExpressionAttributeValues: { ':node': node }, ExclusiveStartKey: cursor, Limit: 50 }));
            // A KEYS_ONLY index is sufficient; callers re-read base rows strongly.
            for (const item of page.Items ?? []) yield item.connectionId;
            cursor = page.LastEvaluatedKey;
        } while (cursor);
    }
    async queueMessage(item, uploader) {
        const p = item.payload;
        const gateway = uploader && uploader !== p.sourceNodeId;
        await this.db.send(new TransactWriteCommand({ TransactItems: [
            { Update: { TableName: this.tables.vaccines, Key: { messageId: p.packetId },
                UpdateExpression: 'SET sourceNodeId = if_not_exists(sourceNodeId, :source), recipientNodeId = if_not_exists(recipientNodeId, :dest), packetDigest = if_not_exists(packetDigest, :digest), pendingKey = if_not_exists(pendingKey, :key), createdAt = if_not_exists(createdAt, :created), expiresAt = if_not_exists(expiresAt, :expiry), #ttl = if_not_exists(#ttl, :ttl)' + (gateway ? ' ADD gatewayNodeIds :gateways' : ''),
                ConditionExpression: 'attribute_not_exists(proof) AND (attribute_not_exists(sourceNodeId) OR (sourceNodeId = :source AND recipientNodeId = :dest)) AND (attribute_not_exists(packetDigest) OR packetDigest = :digest)' +
                    (gateway ? ' AND (attribute_not_exists(gatewayNodeIds) OR size(gatewayNodeIds) < :maxGateways OR contains(gatewayNodeIds, :uploader))' : ''),
                ExpressionAttributeNames: { '#ttl': 'ttl' }, ExpressionAttributeValues: { ':source': item.sourceNodeId,
                    ':dest': item.destinationNodeId, ':digest': item.digest, ':key': item.packetKey, ':created': item.createdAt,
                    ':expiry': item.expiresAt, ':ttl': item.ttl, ...(gateway ? { ':gateways': new Set([uploader]), ':maxGateways': 16, ':uploader': uploader } : {}) } } },
            { Put: { TableName: this.tables.pending, Item: item,
                ConditionExpression: 'attribute_not_exists(packetKey) OR digest = :digest', ExpressionAttributeValues: { ':digest': item.digest } } }
        ] }));
    }
    async saveProof(item, existing) {
        const p = item.payload;
        const expiry = Math.max(existing?.expiresAt ?? 0, p.expiresAt);
        const values = { ':source': p.sourceNodeId, ':dest': p.recipientNodeId, ':proof': p,
            ':digest': item.digest, ':expiry': expiry, ':ttl': Math.floor(expiry / 1000) };
        const expected = existing?.proof ? 'proofDigest = :old' : 'attribute_not_exists(proof)';
        if (existing?.proof) values[':old'] = existing.proofDigest;
        const gateways = [...(existing?.gatewayNodeIds ?? [])].filter(node => node !== p.sourceNodeId);
        const gatewayCondition = existing?.gatewayNodeIds ? 'gatewayNodeIds = :oldGateways' : 'attribute_not_exists(gatewayNodeIds)';
        if (existing?.gatewayNodeIds) values[':oldGateways'] = existing.gatewayNodeIds;
        await this.db.send(new TransactWriteCommand({ TransactItems: [
            { Update: { TableName: this.tables.vaccines, Key: { messageId: p.messageId },
                UpdateExpression: 'SET sourceNodeId = if_not_exists(sourceNodeId, :source), recipientNodeId = if_not_exists(recipientNodeId, :dest), proof = :proof, proofDigest = :digest, expiresAt = :expiry, #ttl = :ttl',
                ConditionExpression: `(attribute_not_exists(sourceNodeId) OR (sourceNodeId = :source AND recipientNodeId = :dest)) AND (${expected}) AND ${gatewayCondition}`,
                ExpressionAttributeNames: { '#ttl': 'ttl' }, ExpressionAttributeValues: values } },
            { Put: { TableName: this.tables.pending, Item: item } },
            ...gateways.map(node => ({ Put: { TableName: this.tables.pending,
                Item: { ...item, destinationNodeId: node } } }))
        ] }));
    }
    async queueGatewayProof(item, gateway) {
        await this.db.send(new PutCommand({ TableName: this.tables.pending,
            Item: { ...item, destinationNodeId: gateway },
            ConditionExpression: 'attribute_not_exists(packetKey) OR digest = :digest',
            ExpressionAttributeValues: { ':digest': item.digest } }));
    }
    async deletePending(node, key) {
        await this.db.send(new DeleteCommand({ TableName: this.tables.pending, Key: { destinationNodeId: node, packetKey: key } }));
    }
    async saveControl(item, markerKey) {
        const marker = { ...item, packetKey: markerKey, pendingKey: item.packetKey };
        await this.db.send(new TransactWriteCommand({ TransactItems: [
            { Put: { TableName: this.tables.pending, Item: marker, ConditionExpression: 'attribute_not_exists(packetKey)' } },
            { Put: { TableName: this.tables.pending, Item: item, ConditionExpression: 'attribute_not_exists(packetKey)' } }
        ] }));
    }
    async page(node, cursor) {
        const result = await this.db.send(new QueryCommand({ TableName: this.tables.pending, ConsistentRead: true,
            KeyConditionExpression: 'destinationNodeId = :node AND packetKey < :upper',
            ExpressionAttributeValues: { ':node': node, ':upper': '3' }, ExclusiveStartKey: cursor, Limit: 20 }));
        return { items: result.Items ?? [], cursor: result.LastEvaluatedKey };
    }
    async lockSync(id, token, now) {
        try { await this.db.send(new UpdateCommand({ TableName: this.tables.connections, Key: { connectionId: id },
            UpdateExpression: 'SET syncToken = :token, syncUntil = :until',
            ConditionExpression: 'authenticated = :yes AND expiresAt > :now AND (attribute_not_exists(syncUntil) OR syncUntil < :now)',
            ExpressionAttributeValues: { ':token': token, ':until': now + 25_000, ':yes': true, ':now': now } })); return true; }
        catch (e) { if (!conditional(e)) throw e; return false; }
    }
    async unlockSync(id, token) {
        try { await this.db.send(new UpdateCommand({ TableName: this.tables.connections, Key: { connectionId: id },
            UpdateExpression: 'REMOVE syncToken, syncUntil', ConditionExpression: 'syncToken = :token',
            ExpressionAttributeValues: { ':token': token } })); }
        catch (e) { if (!conditional(e)) throw e; }
    }
}
