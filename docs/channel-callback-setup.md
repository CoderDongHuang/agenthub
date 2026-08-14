# Channel Callback Setup

The local callback base URL is the current cpolar URL. It was verified with HTTP 200 on 2026-08-14. It is temporary and changes when cpolar is restarted.

```text
https://4c7b15ff.r19.vip.cpolar.cn
```

## Feishu

Callback URL:

```text
https://4c7b15ff.r19.vip.cpolar.cn/api/channel/feishu/callback
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

Receive-message URL is intentionally not configured:

```text
WECHAT_CALLBACK_URL=
```

WeCom rejects callback domains owned by third-party tunnel providers. Keep `WECHAT_CALLBACK_URL` empty until an enterprise-owned and WeCom-verified HTTPS domain is available. The Corp ID, Agent ID, Secret, Token, and EncodingAESKey can remain configured locally.

When an eligible domain is available, configure:

- URL: `https://<enterprise-domain>/api/channel/wechat/callback`
- Token: the configured `WECHAT_TOKEN`
- EncodingAESKey: the configured `WECHAT_ENCODING_AES_KEY`
- Message receive enabled for the application
- Application visible to the test user or test department

The server validates `msg_signature`, decrypts `echostr` and encrypted XML with AES-256-CBC, verifies the Corp ID, and returns the decrypted plain-text `echostr`.

For outbound replies, the application must have permission to send messages and the recipient must be within the application's visible scope.

## DingTalk

Callback URL:

```text
https://4c7b15ff.r19.vip.cpolar.cn/api/channel/dingtalk/callback
```

Enable the robot/application event subscription for incoming messages and configure the same signing secret as the local `DINGTALK_SIGN_SECRET`. The robot must be allowed to send messages in the target conversation. The Webhook URL is used for outbound robot messages; it is not the inbound callback URL.

## Local prerequisites

- Docker PostgreSQL/pgvector: `localhost:5432`
- Redis: `localhost:6380`
- Java API: `localhost:8080`
- cpolar tunnel target: `http://localhost:8080`

The `.env` file remains local and must not be committed.

For the complete non-secret configuration inventory and test record, see `docs/全链路验收与配置清单.md`.
