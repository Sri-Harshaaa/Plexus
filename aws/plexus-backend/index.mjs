import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';
import { DynamoStore } from './src/store.js';
import { createHandler } from './src/handler.js';
import { management } from './src/websocket.js';
import { createFcmPush } from './src/fcm.js';

const db = DynamoDBDocumentClient.from(new DynamoDBClient({ maxAttempts: 3 }),
    { marshallOptions: { removeUndefinedValues: true } });
const store = new DynamoStore(db, {
    connections: process.env.CONNECTIONS_TABLE,
    pending: process.env.PENDING_PACKETS_TABLE,
    vaccines: process.env.DELIVERY_VACCINES_TABLE,
    nodeIndex: process.env.CONNECTIONS_NODE_INDEX ?? 'nodeId-index'
});
export const handler = createHandler({ store, transport: management, push: createFcmPush({ store }) });
