import { createHash, createPublicKey, verify } from 'node:crypto';

export const DAY = 86_400_000;
export const SKEW = 300_000;
export const MAX_WIRE_BYTES = 32 * 1024;
const node = /^[0-9a-f]{64}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const nodeId = value => typeof value === 'string' && node.test(value);
const packetId = value => typeof value === 'string' && uuid.test(value);
const b64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
export class ProtocolError extends Error { constructor(code) { super(code); this.code = code; } }
export function requireProtocol(ok, code = 'INVALID_PACKET') { if (!ok) throw new ProtocolError(code); }
export const digest = data => createHash('sha256').update(data).digest('hex');
// Object member ordering is not part of the Plexus logical packet identity.
const canonicalJson = p => JSON.stringify(p, Object.keys(p).sort());
export function ecKey(encoded) {
    requireProtocol(typeof encoded === 'string' && encoded.length <= 512 && b64.test(encoded), 'INVALID_PUBLIC_KEY');
    let key;
    try { key = createPublicKey({ key: Buffer.from(encoded, 'base64'), format: 'der', type: 'spki' }); }
    catch { throw new ProtocolError('INVALID_PUBLIC_KEY'); }
    requireProtocol(key.asymmetricKeyType === 'ec' && key.asymmetricKeyDetails.namedCurve === 'prime256v1', 'INVALID_PUBLIC_KEY');
    return key;
}
export function identity(nodeId, key) {
    requireProtocol(typeof nodeId === 'string' && node.test(nodeId), 'INVALID_NODE_ID');
    ecKey(key);
    requireProtocol(digest(Buffer.from(key, 'base64')) === nodeId, 'IDENTITY_MISMATCH');
}
export function signature(bytes, sig, key) {
    requireProtocol(typeof sig === 'string' && sig.length <= 256 && b64.test(sig) &&
        verify('sha256', bytes, ecKey(key), Buffer.from(sig, 'base64')), 'INVALID_SIGNATURE');
}
export const authBytes = (n, id, challenge) => Buffer.from(`PLEXUS_SERVER_AUTH_V1|${n}|${id}|${challenge}`, 'utf8');
export const proofBytes = p => Buffer.from(`PLEXUS_DELIVERY_V1|${p.messageId}|${p.sourceNodeId}|${p.recipientNodeId}|${p.deliveredAt}|${p.expiresAt}`, 'utf8');
const prefix = value => `${Buffer.byteLength(String(value), 'utf8')}:${value}|`;
export const originBytes = p => Buffer.from(`PLEXUS_MESH_ORIGIN_V1|${[
    p.packetId, p.sourceNodeId, p.destinationNodeId, p.createdAt, p.expiresAt, p.encryptedPayload
].map(prefix).join('')}`, 'utf8');
export function controlBytes(p) {
    const request = p.type === 'connection_request';
    const fields = request ? ['requestId', 'senderNodeId', 'recipientNodeId', 'senderDisplayName',
        'senderSigningPublicKey', 'senderAgreementPublicKey', 'createdAt', 'expiresAt'] :
        ['requestId', 'responseId', 'requesterNodeId', 'responderNodeId', 'response', 'responderDisplayName',
            'responderSigningPublicKey', 'responderAgreementPublicKey', 'createdAt', 'expiresAt'];
    return Buffer.from(`PLEXUS_CONNECTION_${request ? 'REQUEST' : 'RESPONSE'}_V1|${fields.map(k => prefix(p[k])).join('')}`, 'utf8');
}
function times(created, expires, ttl, now) {
    requireProtocol(Number.isSafeInteger(created) && Number.isSafeInteger(expires) && created > 0 &&
        expires > created && expires - created <= ttl && created <= now + SKEW, 'INVALID_TIMESTAMPS');
    requireProtocol(expires > now, 'PACKET_EXPIRED');
}
function object(p) {
    requireProtocol(p && typeof p === 'object' && !Array.isArray(p) && p.version === 1 &&
        Buffer.byteLength(JSON.stringify(p)) <= MAX_WIRE_BYTES - 512);
}
export function validateProof(p, now) {
    object(p);
    requireProtocol(p.type === 'delivery_anti_packet' && typeof p.messageId === 'string' &&
        packetId(p.messageId) && nodeId(p.sourceNodeId) && nodeId(p.recipientNodeId) &&
        p.sourceNodeId !== p.recipientNodeId && Buffer.byteLength(JSON.stringify(p)) <= 4096);
    times(p.deliveredAt, p.expiresAt, 2 * DAY, now);
    identity(p.recipientNodeId, p.recipientSigningPublicKey);
    signature(proofBytes(p), p.signature, p.recipientSigningPublicKey);
}
export function validatePacket(p, now) {
    object(p);
    if (p.type === 'mesh_message') {
        requireProtocol(packetId(p.packetId) && nodeId(p.sourceNodeId) && nodeId(p.destinationNodeId) &&
            Number.isInteger(p.hopCount) && p.hopCount >= 0 && p.hopCount <= 8 &&
            typeof p.encryptedPayload === 'string' && b64.test(p.encryptedPayload));
        times(p.createdAt, p.expiresAt, DAY, now);
        let inner;
        try { inner = JSON.parse(Buffer.from(p.encryptedPayload, 'base64').toString('utf8')); }
        catch { throw new ProtocolError('INVALID_ENCRYPTED_PAYLOAD'); }
        requireProtocol(inner?.version === 1 && inner.type === 'encrypted_chat' && inner.messageId === p.packetId &&
            inner.senderNodeId === p.sourceNodeId && inner.recipientNodeId === p.destinationNodeId && inner.timestamp === p.createdAt &&
            typeof inner.nonce === 'string' && b64.test(inner.nonce) && Buffer.from(inner.nonce, 'base64').length === 12 &&
            typeof inner.ciphertext === 'string' && b64.test(inner.ciphertext) && inner.ciphertext.length <= 24000 &&
            Buffer.from(inner.ciphertext, 'base64').length >= 16, 'INVALID_ENCRYPTED_PAYLOAD');
        identity(p.sourceNodeId, p.originSigningPublicKey);
        signature(originBytes(p), p.originSignature, p.originSigningPublicKey);
        return { type: 'MESSAGE', id: p.packetId, source: p.sourceNodeId, destination: p.destinationNodeId,
            event: 'internet:packet', priority: 2 };
    }
    requireProtocol(p.type === 'connection_request' || p.type === 'connection_response', 'INVALID_PACKET_TYPE');
    const request = p.type === 'connection_request';
    const source = request ? p.senderNodeId : p.responderNodeId;
    const destination = request ? p.recipientNodeId : p.requesterNodeId;
    const name = request ? p.senderDisplayName : p.responderDisplayName;
    const key = request ? p.senderSigningPublicKey : p.responderSigningPublicKey;
    requireProtocol(nodeId(source) && nodeId(destination) && source !== destination && packetId(p.requestId) &&
        (request || (packetId(p.responseId) && ['ACCEPTED', 'DECLINED'].includes(p.response))) &&
        typeof name === 'string' && name.trim().length > 0 && name.length <= 64);
    times(p.createdAt, p.expiresAt, 30 * DAY, now);
    identity(source, key);
    ecKey(request ? p.senderAgreementPublicKey : p.responderAgreementPublicKey);
    signature(controlBytes(p), p.signature, key);
    return { type: request ? 'CONNECTION_REQUEST' : 'CONNECTION_RESPONSE',
        id: request ? p.requestId : p.responseId, source, destination, priority: 1,
        event: request ? 'internet:connection_request' : 'internet:connection_response' };
}
export function row(p, meta) {
    const fields = {
        MESSAGE: ['packetId', 'sourceNodeId', 'destinationNodeId', 'createdAt', 'expiresAt', 'hopCount', 'encryptedPayload', 'originSigningPublicKey', 'originSignature'],
        DELIVERY_PROOF: ['messageId', 'sourceNodeId', 'recipientNodeId', 'recipientSigningPublicKey', 'deliveredAt', 'expiresAt', 'signature'],
        CONNECTION_REQUEST: ['requestId', 'senderNodeId', 'recipientNodeId', 'senderDisplayName', 'senderSigningPublicKey', 'senderAgreementPublicKey', 'createdAt', 'expiresAt', 'signature'],
        CONNECTION_RESPONSE: ['requestId', 'responseId', 'requesterNodeId', 'responderNodeId', 'response', 'responderDisplayName', 'responderSigningPublicKey', 'responderAgreementPublicKey', 'createdAt', 'expiresAt', 'signature']
    }[meta.type];
    p = Object.fromEntries(['type', 'version', ...fields].map(key => [key, p[key]]));
    const createdAt = p.createdAt ?? p.deliveredAt;
    return { destinationNodeId: meta.destination, packetKey: `${meta.priority}#${String(createdAt).padStart(13, '0')}#${meta.type}#${meta.id}`,
        packetType: meta.type, stableId: meta.id, sourceNodeId: meta.source, event: meta.event,
        payload: p, createdAt, expiresAt: p.expiresAt, ttl: Math.floor(p.expiresAt / 1000),
        digest: digest(meta.type === 'MESSAGE' ? originBytes(p) : canonicalJson(p)) };
}
