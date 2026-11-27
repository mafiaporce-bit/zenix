# Zenix changelog

## 2.90

Merged from the v2.90 package (written with ChatGPT on top of v2.72) into the existing app.
The new features were taken. The package's "safety build" changes that switched off things already in daily use were not.

### New
- **Smart Brain** (Settings → Zenix Intelligence, off by default). Picks the quick or the stronger model per question.
  If the chosen brain fails before it has said a word or used a tool, the question goes once to the other brain, when a key for it is saved.
  After a word was spoken or a tool ran nothing is retried, so no action can happen twice. The saved model choice is never changed.
- **Deep Memory**. Recall now also finds words that mean the same ("spouse" finds "wife") and keeps a small index of every saved fact
  with its kind and the day it was saved. Optional (off by default): the brain picks the best-fitting facts; that sends up to 60 saved facts to the brain provider.
  This is word matching plus an optional cloud ranking. It is not an embedding or vector database.
- **Autopilot**. "Remind me … when the internet is back / is gone". Checked in the background about every 15 minutes by Android (it may delay this),
  and every minute while Zenix is open. It only shows a reminder; it never acts by itself. A reminder added while the internet works waits for a real change.
- **Task history**. Every action is recorded as started / came back / error. An action cut off by closing the app is marked interrupted.
  Nothing is replayed or undone automatically.
- **Live Vision**. One still picture (camera or a picked screenshot) is sent to the brain and answered in the chat. No continuous screen watching.
- **Orb 2.0**. Larger sphere, finer silk lines and a dust ring on the main orb; the floating orb over other apps has flowing silver bands and particles.
- **Safer keys**. In the Android app the four API keys are kept encrypted by the phone's key store. Keys from older versions move over by themselves.
- **Alarms and reminders survive a restart** and an app update (they are set again at boot).
- Backups use a stronger lock (old backups still restore). Calendar events report when the calendar did not confirm saving.
  Hours and minutes are range-checked. Typed messages to the ElevenLabs agent queue up instead of replacing each other.
  Web links tapped inside Zenix open in the phone's browser; no other page can load inside the app.

### Kept as it was, on purpose (the package changed these; the owner chose to keep them)
- The signing key, so every new build installs over the old one without losing data.
- The GitHub download link and the in-app update check.
- Direct calls, auto-send after the spoken "yes", search / tap in other apps, voice replies to notifications.
- Alarms and reminders from the lock screen. The existing fingerprint check for private actions on the lock screen stays.
- No confirmation pop-up before every action (the package blocked all actions while Zenix was behind another app).
- The problem diary, the personal prompt and name defaults, backups that include the keys, keys remembered in the browser version.
- The phone's "connected" flag stays unused. It caused the false "offline" bug in v2.55 to v2.62; the Autopilot background check uses a real request instead.

### Not done / limits
- No continuous screen sharing (Android MediaProjection) and no background AI actions.
- Smart Brain uses simple wording rules to judge "hard" questions.
- Nothing here was tested on a real phone by the build; see the test notes in each release message.

## 2.71 – 2.72
Online/offline failover: ears, brain, voice and phone control each look after themselves. Live status screen.

## Opus 5.5 brain choice
- Settings → Brain → Claude now has a third choice: Opus · smartest (claude-opus-5-5), next to Haiku and Sonnet.
- With Smart Brain on and Opus picked, quick questions still go to Haiku so she answers fast; hard ones go to Opus.
- Cost counter uses Opus prices ($4 in / $20 out per million words-pieces).
- The top Settings card now updates straight away when you change the Claude model.

## Weather and internet search on the Claude brain
- New weather tool (Open-Meteo, free, no key): "what's the weather" works on every brain, for Abu Dhabi or any city, up to 7 days.
- If Anthropic refuses Claude's own internet search, searches go through Grok (when a Grok key is saved), otherwise she opens a Google results page. She no longer uses the phone's in-app search box to look things up.
- Check Zenix has a new "Internet search (Claude)" line that shows Anthropic's exact reason when search is refused. The check does not spend a search.

## She finishes her sentences (part 1 of the voice fix)
- Phone voice: each sentence has its own id, so a late "done" signal from an earlier sentence can no longer cut the current one off. Her time limit no longer cuts her off while the phone says she is still talking.
- If the phone voice breaks mid-sentence (often a network voice on weak data), the phone's offline voice says the rest from where she stopped.
- Online and Kokoro voices: if the sound is blocked, errors, stalls for 3.5 s, or is paused by the phone, she tries once more and then the phone voice says the rest of that sentence instead of dropping it.
- "Stop her by voice" is stricter: "pause" and "end" need the listener to be sure of the word, and "stop" must be heard steadily for about a third of a second, so noise or her own voice can't cut her off.
- Live status has a new line: "Last time her voice was cut", with the reason.

## She hears you faster (part 2 of the voice fix)
- Deepgram Flux waits at most 3 s (was 4 s) for the end of a sentence before handing it over.
- On a connection that keeps dropping, she retries the faster online ears every 25 s at most (was up to 60 s), and as soon as the cloud brain answers she knows the internet works and tries the online ears right after she finishes speaking.
- Live status has a new line: "How fast she hears you": the wait after your last word for the last sentence and on average, which listener heard you, and how long the phone listener took to start.
