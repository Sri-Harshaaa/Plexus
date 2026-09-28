import { ApiGatewayManagementApiClient, PostToConnectionCommand } from '@aws-sdk/client-apigatewaymanagementapi';
import { MAX_WIRE_BYTES } from './protocol.js';

let cachedEndpoint;
let cachedClient;

export function management(event) {
    const { domainName, stage } = event.requestContext;
    if (!domainName || !/^[a-zA-Z0-9.-]+$/.test(domainName) || !/^[a-zA-Z0-9_-]+$/.test(stage)) throw new Error('Invalid gateway context');
    // AWS-provided request context, never a client-supplied URL.
    const endpoint = `https://${domainName}/${stage}`;
    if (cachedEndpoint !== endpoint) {
        cachedClient?.destroy();
        cachedClient = new ApiGatewayManagementApiClient({ endpoint, maxAttempts: 2,
            requestHandler: { connectionTimeout: 1000, requestTimeout: 2000 } });
        cachedEndpoint = endpoint;
    }
    const client = cachedClient;
    return async (connectionId, envelope) => {
        const data = Buffer.from(JSON.stringify(envelope));
        if (data.length > MAX_WIRE_BYTES) throw new Error('Outbound frame too large');
        await client.send(new PostToConnectionCommand({ ConnectionId: connectionId, Data: data }));
    };
}
