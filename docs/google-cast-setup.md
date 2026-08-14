# Google Cast — Custom Receiver Registration (AirCast)

AirCast now ships a full Cast sink implementation: device discovery,
`RECEIVER_QUERY` responses, a sender-auth gate, and playback through the same
ExoPlayer pipeline used by DLNA and AirPlay. Two registration paths are
supported, and the receiver app id is selected at runtime from settings.

## Quick summary

| Scenario | Receiver app id | Setup needed |
|---|---|---|
| Default styled receiver | `CC1AD845` (hard-coded fallback) | None — works out of the box |
| Custom receiver on your own URL | Your registered app id | Register in Cast Developer Console + paste the id into Settings → Network → *Google Cast app id* |

## Registering a Custom Receiver

Open the [Google Cast SDK Developer Console](https://cast.google.com/publish/)
with the Google account that owns the sending devices (same account must be on
the casting phone and the receiver).

1. **Create the app.** *New Receiver Application* → **Custom Receiver** — a
   receiver you host yourself, loaded from a URL you control.
2. **Name** — anything recognisable, e.g. `AirCast TV`.
3. **Receiver Application URL** — the HTTPS endpoint the Cast platform loads
   when a sender launches the app. Point it at AirCast's own TLS port so a
   sender on the same network gets the branded screen:

   ```
   https://<receiver-device-ip>:8322/cast/receiver.html
   ```

   The sender resolves the device IP itself, so the URL only needs to be
   *syntactically valid and reachable for the short verification handshake*;
   actual media playback streams through the local HTTP receiver as usual.
4. **Guest Mode** — leave off (AirCast accepts senders through its own gate).
5. **Google Cast for Audio** — leave off unless you want audio-only targets.
6. **Android TV Application Details** — leave blank (no Android TV build yet).
7. Press **Save** and note the generated **Application ID**.

The console keeps new apps in *Pending review* for roughly 15–30 minutes
before they go live. During that window Cast senders cannot discover the app.

## Wiring the app id into AirCast

On the receiving device: **Settings → Network → Google Cast app id**, paste the
id and save. AirCast answers `RECEIVER_STATUS` with that id and the platform
launches it for any sender targeting the same app id. Leave the field empty to
fall back to the default styled receiver (`CC1AD845`).

Sender-side apps (YouTube, BubbleUPnP, or your own Web Sender) must request
the *same* app id for the session to land on this device.

## Tips

- The verification flow only needs the URL to be reachable once; the cast page
  itself is served from the local TLS port, so no public hosting is required.
- If you later publish an Android TV build, register the package name under
  *Android TV Application Details* — it does not affect the phone receiver.
- Cast security mode (*Settings → Security → Google Cast security*, `ask`)
  applies regardless of which receiver app id is used.
