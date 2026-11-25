# Zenix

Jim's personal voice assistant for Android (Samsung Galaxy S24 Ultra), with a browser version of the same screen.

- **Install on the phone:** download `ZeniX.apk` from the [latest release](https://github.com/mafiaporce-bit/zenix/releases/tag/latest) and tap it. It installs over the old version; nothing is lost.
- **Build:** every push to `main` runs the checks in `tests/`, builds the APK with GitHub Actions and publishes it to the `latest` release.
- **Browser version:** `netlify.toml` publishes `app/src/main/assets`. Phone-only features (calls, wake word, alarms, control of other apps, offline ears) do not run in a browser.
- **Keys:** API keys are pasted into the app and stay on the device. None are in this repository.
- **Changes:** see [CHANGELOG.md](CHANGELOG.md).

Checks: `python3 tests/check-source.py` and `node --test tests/intelligence.test.mjs`.
