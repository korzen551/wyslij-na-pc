"""Odbiornik plikow z telefonu. Zapisuje wszystko do folderu inbox (dwa poziomy wyzej).

Slucha tylko na adresie Tailscale tego komputera i na localhost, wiec pliki moga
wysylac wylacznie urzadzenia z twojej prywatnej sieci Tailscale.

Uruchom: py receive.py   (albo dwuklik na start_odbiornik.bat)
Tylko biblioteka standardowa, dziala na kazdym Pythonie 3.
"""
import datetime
import re
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).parent
INBOX = HERE.parent.parent / "inbox"
APK = HERE.parent / "wysylacz" / "build" / "wysylacz.apk"
TAILSCALE = r"C:\Program Files\Tailscale\tailscale.exe"
PORT = 8765
BUF = 1 << 20


def say(*a) -> None:
    if sys.stdout:  # pod pythonw (autostart) nie ma konsoli
        print(*a, flush=True)


def safe_name(raw: str) -> str:
    name = urllib.parse.unquote(raw or "").replace("\\", "/").split("/")[-1]
    name = re.sub(r'[<>:"|?*\x00-\x1f]', "_", name).strip(". ")
    return name or "plik"


def free_path(name: str) -> Path:
    p = INBOX / name
    i = 1
    while p.exists():
        p = INBOX / f"{Path(name).stem}_{i}{Path(name).suffix}"
        i += 1
    return p


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def reply(self, code: int, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/app.apk" and APK.exists():
            data = APK.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="wysylacz.apk"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.reply(200, "Odbiornik dziala.")

    def read_body(self, out) -> int:
        total = 0
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    return total
                left = size
                while left:
                    chunk = self.rfile.read(min(BUF, left))
                    if not chunk:
                        raise ConnectionError("przerwane")
                    out.write(chunk)
                    left -= len(chunk)
                    total += len(chunk)
                self.rfile.readline()
        left = int(self.headers.get("Content-Length") or 0)
        while left:
            chunk = self.rfile.read(min(BUF, left))
            if not chunk:
                raise ConnectionError("przerwane")
            out.write(chunk)
            left -= len(chunk)
            total += len(chunk)
        return total

    def do_POST(self):
        if self.path != "/upload":
            self.close_connection = True
            return self.reply(404, "nie ma")
        INBOX.mkdir(exist_ok=True)
        dest = free_path(safe_name(self.headers.get("X-Filename")))
        part = dest.with_name(dest.name + ".part")
        try:
            with open(part, "wb") as out:
                total = self.read_body(out)
            part.rename(dest)
        except Exception as e:  # przerwane wysylanie: sprzatamy
            part.unlink(missing_ok=True)
            self.close_connection = True
            say("BLAD", dest.name, e)
            return
        line = f"{datetime.datetime.now():%Y-%m-%d %H:%M:%S}\t{total}\t{dest.name}"
        with open(INBOX / "_log.txt", "a", encoding="utf-8") as f:
            f.write(line + "\n")
        say("ODEBRANO", line)
        self.reply(200, "ok")


def tailscale_ip() -> str | None:
    try:
        out = subprocess.run(
            [TAILSCALE, "ip", "-4"], capture_output=True, text=True, timeout=10,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ).stdout.strip().splitlines()
        return out[0].strip() if out and out[0].startswith("100.") else None
    except Exception:
        return None


def serve(ip: str) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((ip, PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    say(f"Slucham na http://{ip}:{PORT}")
    return srv


if __name__ == "__main__":
    INBOX.mkdir(exist_ok=True)
    say("Pliki trafiaja do", INBOX)
    serve("127.0.0.1")
    current, srv = None, None
    try:
        while True:  # Tailscale moze wstac pozniej niz odbiornik albo zmienic adres
            ip = tailscale_ip()
            if ip != current:
                if srv:
                    srv.shutdown()
                    srv.server_close()
                    srv = None
                if ip:
                    try:
                        srv = serve(ip)
                    except OSError as e:
                        say("Nie moge sluchac na", ip, e)
                        ip = None
                else:
                    say("Tailscale nie dziala, czekam...")
                current = ip
            time.sleep(10)
    except KeyboardInterrupt:
        sys.exit(0)
