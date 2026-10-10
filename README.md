# Mzansi Trivia — Android TV

A colourful, local-Wi-Fi, two-player South African trivia game. No gameplay title bar, "versus mode" label, or round-number banner. The centre of the TV displays a spoken question and four colourful options, with five animated health segments on each side.

## Current implementation

- Kotlin + Jetpack Compose TV interface with animated health depletion, transitions and pulsing reveal
- 10 random questions per match drawn from an initial 15 original sample questions
- South African geography, heritage, music, arts, cities and international entertainment
- Android Text-to-Speech, with South African English requested and English fallback
- Two browser-based phone controllers via QR code or room code
- Embedded NanoHTTPD HTTP server on port 8080 and WebSocket server on 8081
- Phone answer locking, reveal when both players answer, 15-second timeout
- Wrong answer or unanswered question costs one of five health segments
- Final score determined by remaining health, then correct-answer count
- Local Wi-Fi gameplay, no external backend

## Run

1. Open the repository in Android Studio (JDK 17, Android SDK 35).
2. Sync Gradle and run the app on an Android TV device/emulator.
3. Connect the TV and both phones to the same Wi-Fi. Ensure the router allows device-to-device communication.
4. Scan the TV's QR code with each phone. Alternatively, open the TV's local URL in the phone browser and enter the room code.
5. When both players show connected, select **START GAME** on the TV.
6. Watch the question on TV and answer privately on each phone.

## Notes and next work

This is a source-code first playable prototype, not a verified release APK. It has not yet been built on an Android SDK-enabled CI runner or tested on physical TV hardware.

- Expand and fact-check the bank to 1,000+ original questions; do not reproduce copyrighted 30 Seconds cards.
- Add authored Rive/Lottie animation assets, particle effects, confetti, music and richer scene transitions.
- Improve TTS synchronization: currently the timer starts with narration, rather than after its completion.
- Add explicit six-digit room code display on TV and a phone join landing page; the QR currently contains the full join URL with code.
- Add game restart, reconnect/rejoin UX and a proper tie-breaker.
- Add HTTPS/security hardening if the controller is ever exposed beyond trusted LAN.
- Improve game lifecycle: avoid stopping a match early when a player loses all five health segments, if a full ten-question match is desired.
- Add unit/integration tests, CI builds and a downloadable APK.
- A browser cannot discover a TV from only a six-digit code without knowing its address or using a discovery service. Manual joining currently requires opening the TV URL first; QR joining works on the same Wi-Fi.

## Architecture

`GameEngine.kt`: synchronized rules and observable state.

`LocalHost.kt`: local HTTP/WebSocket host and browser controllers.

`MainActivity.kt`: colourful Compose UI, voice narration and QR display.

No advertising or monetization SDK is included.
