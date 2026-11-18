#!/usr/bin/env python3
"""FireCasa setup: a page on this computer for the TV's address, the API keys and the cleanup,
so nobody has to type into Terminal. Standard library only.

It listens on 127.0.0.1 only. The keys it saves go to app/assets, the same files the app reads,
and nothing is sent anywhere except what firecasa.sh already does with the TV over your Wi-Fi.
"""
import json
import re
import shutil
import subprocess
import threading
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
KEYS_DIR = HERE / "keys"  # the API keys, kept on this computer only (gitignored); firecasa.sh sends them to the TV
LOCAL = HERE / "local.json"  # the TV's address, remembered on this computer only (gitignored)
PORT = 8765
KEYS = {"tmdb": "tmdb_token.txt", "omdb": "omdb_key.txt", "unsplash": "unsplash_key.txt"}
IP_RE = re.compile(r"^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$")
RELEASES = "https://api.github.com/repos/chancechime/FireCasa/releases/latest"
busy = threading.Lock()  # one build or TV job at a time: a build deletes out/


def debloat_groups():
    """Group names from debloat.txt, except "safe", which is always on."""
    found = set()
    for line in (ROOT / "debloat.txt").read_text().splitlines():
        parts = line.strip().split()
        if parts and not parts[0].startswith("#"):
            found.add(parts[0])
    return sorted(found - {"safe"})


def key_state():
    out = {}
    for name, filename in KEYS.items():
        path = KEYS_DIR / filename
        value = path.read_text().strip() if path.exists() else ""
        out[name] = {"set": bool(value)}  # never send the key itself back to the page
    return out


def tools():
    """adb talks to the TV, always needed. Java and the Android SDK only matter for building your own copy."""
    adb = shutil.which("adb") or next(
        (p for p in ["/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
                     str(Path.home() / "Library/Android/sdk/platform-tools/adb")] if Path(p).exists()), None)
    java = shutil.which("javac") or next(
        (p for p in ["/opt/homebrew/opt/openjdk@17/bin/javac", "/usr/local/opt/openjdk@17/bin/javac"]
         if Path(p).exists()), None)
    sdk = next((d for d in ["/opt/homebrew/share/android-commandlinetools",
                            str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")]
                if (Path(d) / "platforms/android-34/android.jar").exists()), None)
    return {"adb": bool(adb), "java": bool(java), "sdk": bool(sdk)}


def can_build():
    t = tools()
    return t["java"] and t["sdk"]


def download_prebuilt():
    """The newest ready-made APK from the GitHub release, so nobody needs the build tools."""
    req = urllib.request.Request(RELEASES, headers={"User-Agent": "FireCasa-setup", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        release = json.load(r)
    asset = next((a for a in release.get("assets", []) if a.get("name", "").endswith(".apk")), None)
    if not asset:
        raise RuntimeError("The newest FireCasa release has no APK yet. Build your own copy instead.")
    out = ROOT / "out"
    out.mkdir(exist_ok=True)
    part = out / "firecasa.apk.part"
    with urllib.request.urlopen(asset["browser_download_url"], timeout=300) as r, open(part, "wb") as f:
        shutil.copyfileobj(r, f)
    part.replace(out / "firecasa.apk")


def state():
    ip = ""
    if LOCAL.exists():
        try:
            ip = json.loads(LOCAL.read_text()).get("ip", "")
        except ValueError:
            pass
    return {"ip": ip, "keys": key_state(), "groups": debloat_groups(), "tools": tools()}


def save_keys(values):
    KEYS_DIR.mkdir(exist_ok=True)
    for name, value in values.items():
        if name not in KEYS:
            raise ValueError("Unknown key: " + str(name))
        value = str(value).strip()
        if len(value) > 300 or any(c.isspace() for c in value):
            raise ValueError("That key doesn't look right. Paste it again, with no spaces.")
        path = KEYS_DIR / KEYS[name]
        if value:
            path.write_text(value + "\n")
        elif path.exists():
            path.unlink()  # blank means "remove this key"


def valid_ip(ip):
    m = IP_RE.match(ip)
    return bool(m) and all(int(p) <= 255 for p in m.groups())


def run_steps(steps):
    output, ok = [], True
    for args in steps:
        proc = subprocess.run(["/bin/sh"] + args, cwd=ROOT, capture_output=True, text=True,
                              stdin=subprocess.DEVNULL, timeout=900)
        output.append(proc.stdout + proc.stderr)
        if proc.returncode != 0:
            ok = False
            break
    return {"ok": ok, "output": "".join(output).strip() or "Done."}


def run_action(body):
    ip = str(body.get("ip", "")).strip()
    if not valid_ip(ip):
        raise ValueError("That doesn't look like an IP address, like 192.168.1.50.")
    groups = [g for g in body.get("groups", []) if g in debloat_groups()]
    also = ["--also", ",".join(groups)] if groups else []
    action = body.get("action")
    prebuilt = False
    if action == "install":
        # Build your own copy if the build tools are here, otherwise take the ready-made APK from the release.
        prebuilt = not can_build()
        steps = [["firecasa.sh", "install", ip]] if prebuilt else [["build.sh"], ["firecasa.sh", "install", ip]]
    elif action == "keys":
        steps = [["firecasa.sh", "keys", ip]]
    elif action == "preview":
        steps = [["firecasa.sh", "debloat", ip, "--dry-run"] + also]
    elif action == "cleanup":
        steps = [["firecasa.sh", "debloat", ip] + also]
    elif action == "memory":
        steps = [["firecasa.sh", "memory", ip]]
    elif action == "undo":
        steps = [["firecasa.sh", "undo", ip]]
    elif action == "remove":
        steps = [["firecasa.sh", "undo", ip, "--uninstall"]]
    else:
        raise ValueError("Unknown action: " + str(action))

    LOCAL.write_text(json.dumps({"ip": ip}))
    if not busy.acquire(blocking=False):
        return {"ok": False, "output": "Something else is still running. Wait for it to finish, then try again."}
    try:
        if prebuilt:
            try:
                download_prebuilt()
            except Exception as e:  # no network, or no release yet
                return {"ok": False, "output": "Couldn't download the ready-made app: " + str(e)}
        return run_steps(steps)
    finally:
        busy.release()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep the Terminal window quiet

    def host_ok(self):
        return self.headers.get("Host") in (f"127.0.0.1:{PORT}", f"localhost:{PORT}")

    def origin_ok(self):
        origin = self.headers.get("Origin")  # browsers send it on POST; other sites' pages can't pass this
        return origin is None or origin in (f"http://127.0.0.1:{PORT}", f"http://localhost:{PORT}")

    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self.host_ok():
            return self.send(403, {"error": "Open the setup page from the address the setup window shows."})
        if self.path in ("/", "/index.html"):
            return self.send(200, (HERE / "index.html").read_bytes(), "text/html; charset=utf-8")
        if self.path == "/api/state":
            return self.send(200, state())
        self.send(404, {"error": "Not found"})

    def do_POST(self):
        if not (self.host_ok() and self.origin_ok()):
            return self.send(403, {"error": "Blocked: this page can only be used from the setup window."})
        length = int(self.headers.get("Content-Length") or 0)
        if length > 100_000:
            return self.send(413, {"error": "Too big."})
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
            if self.path == "/api/keys":
                save_keys(body)
                return self.send(200, state())
            if self.path == "/api/run":
                return self.send(200, run_action(body))
        except ValueError as e:
            return self.send(400, {"error": str(e)})
        self.send(404, {"error": "Not found"})


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    url = f"http://127.0.0.1:{PORT}/"
    print(f"FireCasa setup is open at {url}\nKeep this window open while you use it. Press Control-C to stop.")
    webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nSetup stopped.")


if __name__ == "__main__":
    main()
