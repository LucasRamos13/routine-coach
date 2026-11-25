# Reminders: phone, bot and PC

```
  phone (exact local alarm) ──not acknowledged after N min──▶ ntfy <topic> ──▶ desktop notifier (Linux/Windows)
        ▲                                                          │      └──▶ Telegram bridge
        └──────────── polls <topic>-ack ◀──── "ack:<id>:done|snooze:<channel>" ◀──┘
```

- **Single state** per reminder: `PENDING`, `SNOOZED`, `ACKED` (or `CANCELLED` if the session is gone). Answering on
  any channel publishes an ack; the others clear their copy.
- **Order**: the phone first; bot and PC only after the escalation delay (Settings › Reminders).
- **Volume**: quiet hours, a daily limit and snooze length are configurable.
- **Offline**: phone reminders are local and work in airplane mode. Relay messages are queued by WorkManager and sent
  when the network is back; ntfy keeps messages so the PC and bot receive them when they reconnect.
- **Privacy**: relay messages contain only "Training session at HH:MM" — no health data. Use a long random topic, or
  a self-hosted ntfy server with access control.

## Phone

Settings › Bot and PC relay: server (default `https://ntfy.sh`) and topic. "Send test" publishes a test reminder.
Allow notifications and exact alarms, and exempt the app from battery optimization (Settings › Permissions); the app
warns when reminders cannot fire exactly.

## Desktop (Linux, e.g. CachyOS)

```bash
mkdir -p ~/.local/share/routine-coach ~/.config/routine-coach
cp companion/routine_coach_companion.py ~/.local/share/routine-coach/
cp companion/companion.env.example ~/.config/routine-coach/companion.env   # edit the topic
cp companion/routine-coach-desktop.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now routine-coach-desktop
```

Notifications use `notify-send` (libnotify) with Done/Snooze actions.

## Desktop (Windows)

```powershell
py -m pip install win11toast   # optional, enables the Done/Snooze buttons
py routine_coach_companion.py --config %USERPROFILE%\routine-coach\companion.env desktop
```

Start it at login with Task Scheduler ("At log on", action `pythonw.exe` with the same arguments). Without
`win11toast` a balloon notification without buttons is shown.

## Telegram

1. Create a bot with @BotFather and copy the token.
2. Send any message to the bot, then open `https://api.telegram.org/bot<token>/getUpdates` to find your chat id.
3. Put `RC_TELEGRAM_TOKEN` and `RC_TELEGRAM_CHAT_ID` in the config file (never in the repository) and run
   `python3 routine_coach_companion.py --config ... telegram` on an always-on machine.

The bridge sends each relayed reminder with Done/Snooze buttons and edits the message when another channel answers.
