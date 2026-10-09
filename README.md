# ☁️ Pareido

An Android app that finds the figures hiding in clouds, walls, floors and other
random textures, and outlines them **using only real edges from the photo**.

## How it stays honest

1. **On the phone:** edge detection traces the real lines in the photo
   (brightness and colour changes) and numbers each one. The photo is never changed.
2. **Claude:** gets the photo plus a copy with the numbered lines and answers
   "which of these lines form a figure, and what is it?"
3. **On the phone:** only the lines Claude picked are drawn, and any line number
   that doesn't exist is thrown away. If nothing fits, it says "no figure found".

## Features

- Take a photo, pick one from the gallery, or use the **live camera** (edges
  shown in real time; freeze a frame to analyze it)
- **Find figures**, with several finds per photo and **"What else could this be?"**
- **I see something:** trace a shape with your finger; it snaps to the real
  edges along your line and Claude says what it sees
- **Challenge mode:** trace and name what you see, then reveal Claude's answer
- **Send to a friend:** share the plain photo and ask what they see
- **My finds** gallery, **daily streak**, **share** the outlined image
- Outline **colour / thickness / style** (solid, glow, chalk)
- **Edge sensitivity** slider
- **Spending tracker** (estimated from token usage)
- **Offline queue:** photos taken offline are analyzed when you're back online

## Install

Every push to `main` builds an APK on GitHub Actions and publishes it under
**Releases**. On your phone, open the latest release, download the `.apk`, and
allow your browser to install apps if asked.

Then open **Settings** in the app and paste an Anthropic API key from
[console.anthropic.com](https://console.anthropic.com). The key stays on the phone.

## Code layout

- `core/`: plain Kotlin (no Android): edge detection, contour tracing,
  stroke snapping, Claude requests (official Anthropic Java SDK), pricing, streaks.
  Unit tested: `PAREIDO_CORE_ONLY=1 ./gradlew :core:test`
- `app/`: Android UI (Jetpack Compose, CameraX, WorkManager)

`debug.keystore` is committed on purpose so every build installs over the last one.
