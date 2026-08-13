# Channel Callback Setup

The local callback base URL is the current ngrok URL. It is temporary and changes when ngrok is restarted.

```text
https://sixfold-fracture-detail.ngrok-free.dev
```

## Feishu

Callback URL:

```text
https://sixfold-fracture-detail.ngrok-free.dev/api/channel/feishu/callback
```

Event subscription settings:

- Enable event subscription.
- Use the encrypted event mode with the configured Verification Token and Encrypt Key.
- Add the event `im.message.receive_v1` (receive messages).
- For message sending, grant the application permission `im:message:send_as_bot`.
- For reading incoming message identity/content, grant the corresponding message receive/read permission shown by the Feishu console, normally `im:message:readonly` and `im:message:receive`.
- Publish the application version after changing permissions or events.

URL verification must return JSON exactly in this shape:

```json
{"challenge":"<challenge-from-feishu>"}
```

## WeCom

Receive-message URL:

```text
https://sixfold-fracture-detail.ngrok-free.dev/api/channel/wechat/callback
```

In WeCom application settings, configure:

- URL: the URL above
- Token: the configured `WECHAT_TOKEN`
- EncodingAESKey: the configured `WECHAT_ENCODING_AES_KEY`
- Message receive enabled for the application
- Application visible to the test user or test department

The server validates `msg_signature`, decrypts `echostr` and encrypted XML with AES-256-CBC, verifies the Corp ID, and returns the decrypted plain-text `echostr`.

For outbound replies, the application must have permission to send messages and the recipient must be within the application's visible scope.

## DingTalk

Callback URL:

```text
https://sixfold-fracture-detail.ngrok-free.dev/api/channel/dingtalk/callback
```

Enable the robot/application event subscription for incoming messages and configure the same signing secret as the local `DINGTALK_SIGN_SECRET`. The robot must be allowed to send messages in the target conversation. The Webhook URL is used for outbound robot messages; it is not the inbound callback URL.

## Local prerequisites

- Docker PostgreSQL/pgvector: `localhost:5432`
- Redis: `localhost:6380`
- Java API: `localhost:8080`
- ngrok tunnel target: `http://localhost:8080`

The `.env` file remains local and must not be committed.
