# Android debug server — curl quickstart

Port **5321** (iOS uses 8321). Plaintext JSON-RPC 2.0; no /pair, no envelope.

```bash
# Loopback via adb — no token needed
adb forward tcp:5321 tcp:5321
curl -s localhost:5321/ -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}'

# LAN (non-loopback) — token required
TOK=$(adb shell run-as com.openminis.app cat files/debug_server_token)
curl -s http://<device-ip>:5321/ \
     -H "X-Minis-Token: $TOK" \
     -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}'
```

Fetch this skill off the device (no auth on loopback):

```bash
curl -H 'Accept: text/markdown' localhost:5321/skill        # the manual
curl localhost:5321/skill                                   # JSON + inlined clients
curl localhost:5321/skill/examples/python > minis_rpc_android.py
```
