#!/usr/bin/env python3
"""Call the Kiosk Satellite Remote Admin command API on the Show 5.

Usage: scripts/ks.py <command> [json-params] | install <zip> | get <key>... | set <json>
Address and password come from LOCAL.md (gitignored): lines `KS_ADMIN_URL: ...` and
`KS_ADMIN_AUTH: ...`. Neither value is ever printed.
"""
import base64, json, pathlib, re, sys, urllib.error, urllib.request

LOCAL = pathlib.Path(__file__).resolve().parent.parent / "LOCAL.md"


def local(key):
    m = re.search(rf"^\s*(?:-\s*)?{key}:\s*(\S+)", LOCAL.read_text(), re.M)
    if not m:
        sys.exit(f"{key} missing from LOCAL.md")
    return m.group(1).rstrip("/")


def call(path, body=None, token=None, method="POST"):
    req = urllib.request.Request(
        local("KS_ADMIN_URL") + path,
        data=json.dumps(body).encode() if body is not None else b"",
        method=method,
        headers={"Authorization": f"Bearer {token}"} if token else {},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} from {path}")  # body withheld: may echo request content


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    token = call("/api/login", {"password": local("KS_ADMIN_AUTH")})["token"]
    cmd = sys.argv[1]
    if cmd == "set":  # scripts/ks.py set '{"screensaver.schedule_enabled": false}'
        print(json.dumps(call("/api/settings", json.loads(sys.argv[2]), token, "PATCH")))
        return
    if cmd == "get":  # scripts/ks.py get key1 key2 ... : print current values
        req = urllib.request.Request(local("KS_ADMIN_URL") + "/api/settings", headers={"Authorization": f"Bearer {token}"})
        have = {x["key"]: x["value"] for x in json.load(urllib.request.urlopen(req, timeout=60))["settings"]}
        print(json.dumps({k: have.get(k) for k in sys.argv[2:]}))
        return
    if cmd == "install":
        data = base64.b64encode(pathlib.Path(sys.argv[2]).read_bytes()).decode()
        params = {"data": data, "trusted": True}
        cmd = "installPlugin"
    else:
        params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    print(json.dumps(call(f"/api/commands/{cmd}", params, token), indent=1))


main()
