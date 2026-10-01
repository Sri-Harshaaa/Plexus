import { SecretsManagerClient, GetSecretValueCommand } from '@aws-sdk/client-secrets-manager';
import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';

let appPromise;
const secrets = new SecretsManagerClient({ maxAttempts: 2 });

async function messaging(secretId) {
    if (!appPromise) {
        const starting = (async () => {
        const result = await secrets.send(new GetSecretValueCommand({ SecretId: secretId }));
        const value = result.SecretString ?? Buffer.from(result.SecretBinary ?? '', 'base64').toString('utf8');
        const credentials = JSON.parse(value);
        return getMessaging(getApps()[0] ?? initializeApp({ credential: cert(credentials) }));
        })();
        const cached = starting.catch(error => {
            if (appPromise === cached) appPromise = undefined;
            throw error;
        });
        appPromise = cached;
    }
    return appPromise;
}

const invalidToken = code => code === 'messaging/registration-token-not-registered' ||
    code === 'messaging/invalid-registration-token';

export function createFcmPush({ store, secretId = process.env.FIREBASE_ADMIN_SECRET_ID ?? 'plexus/firebase-admin', log = console }) {
    return async (nodeId, correlation) => {
        const diagnostic = (event, warning = false) => {
            const record = JSON.stringify({ event,
                ...(typeof correlation === 'string' && /^[0-9a-f]{12}$/.test(correlation) ? { id: correlation } : {}) });
            if (warning) log.warn(record); else log.info?.(record);
        };
        const tokens = await store.pushTokens(nodeId);
        if (!tokens.length) { diagnostic('FCM_NO_REGISTRATION'); return; }
        let client;
        try { client = await messaging(secretId); }
        catch { diagnostic('fcm_unavailable', true); return; }
        await Promise.all(tokens.map(async token => {
            try {
                await client.send({ token, data: { type: 'plexus_sync' }, android: { priority: 'high' } });
                diagnostic('FCM_WAKE_SENT');
            }
            catch (e) {
                if (invalidToken(e?.code)) { diagnostic('FCM_TOKEN_INVALID', true); await store.deletePushToken(nodeId, token); }
                else diagnostic('fcm_send_failed', true);
            }
        }));
    };
}
