#!/usr/bin/env python3
"""Routine Coach companion: desktop notifications and a Telegram bridge for reminders relayed through ntfy.

The phone publishes reminders to <topic> and acknowledgements to <topic>-ack as "ack:<id>:<done|snooze>:<channel>".
Both companions listen to the two topics: they show a reminder, publish the user's answer, and clear their copy as
soon as any other channel acknowledges it.

Standard library only. Configuration comes from environment variables or a config file that is never committed:

    RC_NTFY_SERVER   default https://ntfy.sh
    RC_NTFY_TOPIC    required, the same topic configured in the app
    RC_TELEGRAM_TOKEN, RC_TELEGRAM_CHAT_ID   for the telegram command

Usage:
    python3 routine_coach_companion.py desktop
    python3 routine_coach_companion.py telegram
    python3 routine_coach_companion.py --config ~/.config/routine-coach/companion.env desktop
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "routine-coach-companion/0.1"
ACK_RE = re.compile(r"^ack:(\d+):(done|snooze):(\w+)$")


# --------------------------------------------------------------------------------------------------------------
# Config and ntfy helpers
# --------------------------------------------------------------------------------------------------------------

def load_config(path: str | None) -> dict:
    cfg = {}
    if path:
        with open(os.path.expanduser(path), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    cfg[k.strip()] = v.strip().strip('"')
    for k in ("RC_NTFY_SERVER", "RC_NTFY_TOPIC", "RC_TELEGRAM_TOKEN", "RC_TELEGRAM_CHAT_ID"):
        if os.environ.get(k):
            cfg[k] = os.environ[k]
    cfg.setdefault("RC_NTFY_SERVER", "https://ntfy.sh")
    if not cfg.get("RC_NTFY_TOPIC"):
        sys.exit("RC_NTFY_TOPIC is not set")
    return cfg


class Ntfy:
    def __init__(self, server: str, topic: str):
        self.base = server.rstrip("/")
        self.topic = topic
        self.ack_topic = topic + "-ack"

    def publish_ack(self, reminder_id: str, kind: str, channel: str) -> None:
        req = urllib.request.Request(f"{self.base}/{self.ack_topic}", data=f"ack:{reminder_id}:{kind}:{channel}".encode(),
                                     method="POST", headers={"User-Agent": USER_AGENT})
        urllib.request.urlopen(req, timeout=15).read()

    def stream(self, on_message, since: str = "10m") -> None:
        """Subscribes to both topics forever, reconnecting with backoff. Reminders queued while offline are replayed."""
        delay = 2
        last = since
        while True:
            url = f"{self.base}/{self.topic},{self.ack_topic}/json?since={urllib.parse.quote(str(last))}"
            try:
                req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=90) as resp:
                    delay = 2
                    for raw in resp:
                        msg = json.loads(raw)
                        if msg.get("event") != "message":
                            continue
                        last = msg.get("id", last)
                        on_message(msg)
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                print(f"[ntfy] connection lost ({e}); retrying in {delay}s", file=sys.stderr)
                time.sleep(delay)
                delay = min(delay * 2, 120)


def parse(msg: dict, ack_topic: str):
    """Returns ("reminder", id, text) or ("ack", id, kind, channel) or None."""
    if msg.get("topic") == ack_topic:
        m = ACK_RE.match(msg.get("message", "").strip())
        return ("ack", m.group(1), m.group(2), m.group(3)) if m else None
    for tag in msg.get("tags", []) or []:
        if tag.startswith("rc-"):
            return ("reminder", tag[3:], msg.get("message", "Training reminder"))
    return None


# --------------------------------------------------------------------------------------------------------------
# Desktop notifications (Linux: notify-send with actions; Windows: win11toast if installed, else PowerShell)
# --------------------------------------------------------------------------------------------------------------

class Desktop:
    def __init__(self, ntfy: Ntfy):
        self.ntfy = ntfy
        self.open: dict[str, subprocess.Popen] = {}
        self.acked: set[str] = set()
        self.system = platform.system()

    def on_message(self, msg: dict) -> None:
        p = parse(msg, self.ntfy.ack_topic)
        if not p:
            return
        if p[0] == "ack":
            self.acked.add(p[1])
            self.close(p[1])
        elif p[1] not in self.acked:
            threading.Thread(target=self.show, args=(p[1], p[2]), daemon=True).start()

    def show(self, rid: str, text: str) -> None:
        if self.system == "Linux":
            self.show_linux(rid, text)
        elif self.system == "Windows":
            self.show_windows(rid, text)
        else:
            print(f"Reminder {rid}: {text}")

    def answer(self, rid: str, kind: str) -> None:
        if rid == "0":  # test reminder from the app
            return
        try:
            self.ntfy.publish_ack(rid, kind, "desktop")
        except Exception as e:  # noqa: BLE001
            print(f"[desktop] could not send answer: {e}", file=sys.stderr)

    def show_linux(self, rid: str, text: str) -> None:
        if not shutil.which("notify-send"):
            print(f"Reminder {rid}: {text} (install libnotify for desktop notifications)")
            return
        proc = subprocess.Popen(
            ["notify-send", "--app-name=Routine Coach", "--urgency=normal", "--wait",
             "--action=done=Done", "--action=snooze=Snooze", "Training reminder", text],
            stdout=subprocess.PIPE, text=True)
        self.open[rid] = proc
        out, _ = proc.communicate()
        self.open.pop(rid, None)
        choice = (out or "").strip()
        if choice in ("done", "snooze") and rid not in self.acked:
            self.answer(rid, choice)

    def show_windows(self, rid: str, text: str) -> None:
        try:
            from win11toast import toast  # type: ignore
            result = toast("Training reminder", text, buttons=["Done", "Snooze"], app_id="Routine Coach")
            choice = (result or {}).get("arguments", "") if isinstance(result, dict) else ""
            if "Done" in choice:
                self.answer(rid, "done")
            elif "Snooze" in choice:
                self.answer(rid, "snooze")
        except ImportError:
            # Fallback without buttons: a balloon notification through PowerShell.
            ps = ("Add-Type -AssemblyName System.Windows.Forms;"
                  "$n=New-Object System.Windows.Forms.NotifyIcon;$n.Icon=[System.Drawing.SystemIcons]::Information;"
                  "$n.Visible=$true;$n.ShowBalloonTip(10000,'Training reminder',$env:RC_TEXT,'Info');Start-Sleep 11;$n.Dispose()")
            subprocess.run(["powershell", "-NoProfile", "-Command", ps], env={**os.environ, "RC_TEXT": text}, check=False)

    def close(self, rid: str) -> None:
        proc = self.open.pop(rid, None)
        if proc and proc.poll() is None:
            proc.terminate()


# --------------------------------------------------------------------------------------------------------------
# Telegram bridge
# --------------------------------------------------------------------------------------------------------------

class Telegram:
    def __init__(self, ntfy: Ntfy, token: str, chat_id: str):
        self.ntfy = ntfy
        self.api = f"https://api.telegram.org/bot{token}"
        self.chat_id = chat_id
        self.sent: dict[str, int] = {}  # reminder id -> telegram message id
        self.acked: set[str] = set()

    def call(self, method: str, **params):
        data = json.dumps(params).encode()
        req = urllib.request.Request(f"{self.api}/{method}", data=data,
                                     headers={"Content-Type": "application/json", "User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=70) as r:
            return json.loads(r.read())["result"]

    def on_message(self, msg: dict) -> None:
        p = parse(msg, self.ntfy.ack_topic)
        if not p:
            return
        if p[0] == "reminder" and p[1] not in self.sent and p[1] not in self.acked:
            keyboard = {"inline_keyboard": [[{"text": "Done", "callback_data": f"done:{p[1]}"},
                                             {"text": "Snooze", "callback_data": f"snooze:{p[1]}"}]]}
            res = self.call("sendMessage", chat_id=self.chat_id, text=f"🏋️ {p[2]}", reply_markup=keyboard)
            self.sent[p[1]] = res["message_id"]
        elif p[0] == "ack":
            self.acked.add(p[1])
            mid = self.sent.pop(p[1], None)
            if mid:
                label = "Done" if p[2] == "done" else "Snoozed"
                try:
                    self.call("editMessageText", chat_id=self.chat_id, message_id=mid, text=f"{label} (via {p[3]})")
                except urllib.error.HTTPError:
                    pass

    def poll_updates(self) -> None:
        offset = 0
        while True:
            try:
                for u in self.call("getUpdates", offset=offset, timeout=60, allowed_updates=["callback_query"]):
                    offset = u["update_id"] + 1
                    cb = u.get("callback_query")
                    if not cb or str(cb["message"]["chat"]["id"]) != str(self.chat_id):
                        continue
                    kind, rid = cb["data"].split(":", 1)
                    if rid != "0":
                        self.ntfy.publish_ack(rid, kind, "telegram")
                    self.call("answerCallbackQuery", callback_query_id=cb["id"], text="Snoozed" if kind == "snooze" else "Done")
            except Exception as e:  # noqa: BLE001
                print(f"[telegram] {e}; retrying", file=sys.stderr)
                time.sleep(5)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", help="KEY=VALUE file with the settings (keep it out of git)")
    ap.add_argument("command", choices=["desktop", "telegram"])
    args = ap.parse_args()
    cfg = load_config(args.config)
    ntfy = Ntfy(cfg["RC_NTFY_SERVER"], cfg["RC_NTFY_TOPIC"])
    if args.command == "desktop":
        print(f"Listening for reminders on {ntfy.base}/<topic> ({platform.system()})")
        ntfy.stream(Desktop(ntfy).on_message)
    else:
        if not cfg.get("RC_TELEGRAM_TOKEN") or not cfg.get("RC_TELEGRAM_CHAT_ID"):
            sys.exit("RC_TELEGRAM_TOKEN and RC_TELEGRAM_CHAT_ID are required for the telegram bridge")
        bot = Telegram(ntfy, cfg["RC_TELEGRAM_TOKEN"], cfg["RC_TELEGRAM_CHAT_ID"])
        threading.Thread(target=bot.poll_updates, daemon=True).start()
        print("Telegram bridge running")
        ntfy.stream(bot.on_message)


if __name__ == "__main__":
    main()
