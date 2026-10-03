#!/usr/bin/env python3
"""Minis ANDROID debug server client (stdlib only).

Usage:
    python3 minis_rpc_android.py [--host HOST:PORT] [--token TOKEN] <method> [params-json]

Examples:
    adb forward tcp:5321 tcp:5321
    python3 minis_rpc_android.py debug.appInfo '{}'
    python3 minis_rpc_android.py --host 10.0.0.5:5321 --token "$TOK" debug.logs.list '{}'

Transport notes (Android differs from iOS!):
  - Port is 5321 (iOS uses 8321).
  - Payloads are PLAINTEXT JSON-RPC 2.0 — there is no encrypted envelope and no
    /pair step. Do not use the iOS protocol-v1 clients against this server.
  - Auth: loopback (via `adb forward`) needs no token. NON-loopback (LAN)
    clients must send the per-install device token:
        adb shell run-as com.openminis.app cat files/debug_server_token
    Passed as X-Minis-Token (Authorization: Bearer also accepted).
    Or set MINIS_DEBUG_TOKEN in the environment.
"""

import json
import os
import sys
import urllib.error
import urllib.request


def call(host: str, method: str, params: dict, token: str | None) -> dict:
    body = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": method, "params": params,
    }).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Minis-Token"] = token
    req = urllib.request.Request(f"http://{host}/", data=body,
                                headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        payload = e.read().decode(errors="replace")
        if e.code == 401:
            raise SystemExit(
                "401 Unauthorized — this is a non-loopback (LAN) connection, so a token is\n"
                "required. Read it with:\n"
                "  adb shell run-as com.openminis.app cat files/debug_server_token\n"
                "then pass --token / MINIS_DEBUG_TOKEN. (Over `adb forward` no token is needed.)\n"
                f"server said: {payload}"
            )
        raise SystemExit(f"HTTP {e.code}: {payload}")


def main() -> None:
    args = sys.argv[1:]
    host = "localhost:5321"
    token = os.environ.get("MINIS_DEBUG_TOKEN")
    while args and args[0].startswith("--"):
        if args[0] == "--host":
            host = args[1]; args = args[2:]
        elif args[0] == "--token":
            token = args[1]; args = args[2:]
        else:
            raise SystemExit(f"unknown flag {args[0]}")
    if not args:
        raise SystemExit(__doc__)
    method = args[0]
    params = json.loads(args[1]) if len(args) > 1 else {}
    print(json.dumps(call(host, method, params, token), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
