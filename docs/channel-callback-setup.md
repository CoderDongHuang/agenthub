# Channel and callback setup

This document describes production channel endpoints without storing secrets. Keep all credential values in the local `.env` file or a production secret manager.

## Hostname plan

`agentmesh.asia` is registered and delegated to Alibaba Cloud DNS. Reserve these names after a real public ingress exists:

- Site and console: `https://agentmesh.asia`, `https://console.agentmesh.asia`
- REST API: `https://api.agentmesh.asia`
- HTTP callbacks: `https://callbacks.agentmesh.asia/api/channel/{provider}/callback`

Do not publish A or CNAME records until the load balancer, server, or managed hosting target is reachable and has a valid TLS certificate. Temporary tunnels are not production configuration.

## Persistent routing

Every provider account must have an enabled `channel_binding` mapping:

```text
provider + external account ID -> tenant -> Agent
```

The callback is rejected when the account is not bound. Bindings are tenant-owned and cannot be claimed by another tenant.

## DingTalk Stream

DingTalk Stream is the recommended integration because it requires no public callback URL.

- Enterprise internal application: `AgentMesh`
- Robot code / Client ID: `dingck36tmfhta4qbvko`
- Secret variables: `DINGTALK_CLIENT_ID`, `DINGTALK_CLIENT_SECRET`
- Runtime: Python maintains the authenticated Stream connection and forwards events to Java over the internal authenticated API.

Enable the robot's messaging capabilities and publish an application version. The Client Secret must never appear in Git, logs, screenshots, or this document. The legacy signed HTTP callback remains supported for deployments that explicitly need it.

## Feishu

Required local secrets:

- `FEISHU_APP_ID`
- `FEISHU_APP_SECRET`
- `FEISHU_ENCRYPT_KEY`
- `FEISHU_VERIFICATION_TOKEN`

After public ingress and TLS are ready, configure:

```text
https://callbacks.agentmesh.asia/api/channel/feishu/callback
```

Subscribe to `im.message.receive_v1`, grant receive/read/send-as-bot permissions, publish the application version, and bind the Feishu app/account ID to a tenant and Agent. Production events require the expected signature fields and a valid timestamp window.

## WeCom

Required local secrets:

- `WECHAT_CORP_ID`
- `WECHAT_AGENT_ID`
- `WECHAT_APP_SECRET`
- `WECHAT_TOKEN`
- `WECHAT_ENCODING_AES_KEY`

Keep `WECHAT_CALLBACK_URL=` empty until an enterprise-owned, WeCom-verifiable HTTPS domain is available. Then configure:

```text
https://callbacks.agentmesh.asia/api/channel/wechat/callback
```

WeCom validates domain ownership and cannot be completed with localhost or an unrelated tunnel domain. Callback XML is parsed with DOCTYPE, external entities, external DTDs, and entity expansion disabled.

## Acceptance checklist

1. The public endpoint presents a trusted TLS certificate and is reachable from the provider.
2. Missing, stale, replayed, or invalid signatures are rejected.
3. The external account has exactly one enabled tenant/Agent binding.
4. The callback returns quickly after durable enqueue.
5. The worker produces an outbound reply or a traceable dead-letter record.
6. Credentials and payload logs contain no secret values.

See `docs/全链路验收与配置清单.md` for current non-secret status.
