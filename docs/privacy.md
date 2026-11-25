# Privacy

- Training data is stored only in the app's local database. It leaves the device only through exports and backups
  you create, and the reminder relay you configure (which carries only a time, never health data).
- The app makes no network calls except to the configured relay.
- The public repository holds no personal data. Your routine lives in a private pack outside the repository and is
  injected at build time (`routineCoach.packDir` in `local.properties`, which is git-ignored).

## Automatic check

`scripts/check-private-data.sh` blocks commits that contain:

- local configuration (`local.properties`, `.env`), backups, databases, documents, signing keys or `private-pack/`;
- tokens (Telegram, GitHub, AWS, ntfy), private keys and real relay topics;
- e-mail addresses (except no-reply/example addresses);
- any regex listed in your local `.private-patterns` file (e.g. your name or phone number). That file is git-ignored.

Enable it as a pre-commit hook once per clone:

```bash
git config core.hooksPath .githooks
```

CI runs the same script on every push and pull request, plus Gitleaks.
