<p align="center">
  <img src="docs/logo.png" width="150" alt="Halo Launcher logo: a halo over an app grid">
</p>

<h1 align="center">Halo Launcher</h1>

<p align="center">
  A black-and-white home screen for Android that works with <a href="https://github.com/LONERXONE-ops/halo">Halo</a>, the dynamic island.<br>
  Poster-style home, solid black sheet, iOS-style dark icons, a notch-shaped dock, and your music, badges and notifications straight from the island.
</p>

---

## Download

**[⬇ Get the latest APK](https://github.com/LONERXONE-ops/halo-launcher/releases/latest)**

1. Download `HaloLauncher-x.y.z.apk` from **Assets** and install it.
2. Press the home button and pick **Halo Launcher** (or open it and tap **Make it my home screen**).
3. Updates come from **Settings → Updates** inside the launcher.

For the full experience, install **[Halo](https://github.com/LONERXONE-ops/halo/releases/latest)** too.

## The home screen

Poster style: your wallpaper (or a picture you pick) across the top, and one black sheet with a single wave running across both pages.

| | |
|---|---|
| **Page 1** | The stacked clock (varsity, dot matrix, bold or thin; digits roll up each minute), the date and weather, and the music player: blurred cover washed in the song's colour, with progress and time. Fed by Halo, works with any player. |
| **Page 2** | Halo Launcher's own widgets, built on Apple's widget rules (one idea each, live data, dot-matrix numbers): weather with the next hours, battery in dots, steps toward your goal, today's events, screen time with your top apps, and a note. Plus any Android widget. |
| **Dock** | A solid tab rising out of the bottom edge, like a notch turned upside down. Long-press it to change the apps. |
| **All apps** | Swipe up: search, most used, and an A–Z grid that ripples in. Long-press any app for its shortcuts, dock, hide, info and uninstall. |
| **Icons** | iOS-style dark icons: each app's own symbol in its brand colour on a dark tile, worked out from the real icon so it works with Samsung's icons too. Or white line icons, or the originals. iOS-style red badges. |
| **Settings** | A page per topic with live examples: your real clock in every style, your own apps in every icon style, the actual widgets, the dock. |

## Gestures

- **Swipe up**: all apps
- **Swipe down**: Halo's notification center (or Android's shade)
- **Double-tap**: lock the screen, through Halo, so fingerprint unlock still works
- **Long-press** an empty spot: settings
- **Home** while home: back to page 1

## Works with Halo

Both apps are signed with the same key and talk through a signature-protected link, so no other app can listen in or drive them.

- Halo sends the song playing (with its cover), unread counts per app, the newest notifications and what's switched on.
- The launcher asks Halo to play, pause and skip, open the notification center or a notification, lock the screen, or switch something off.

## Built on what others learned

Studied before writing a line, and again for the redesign (Niagara's single header widget, Nothing's dot-matrix widgets, Apple's widget guidelines): [Kvaesitso](https://github.com/MM2-0/Kvaesitso) (widget hosting, themed icons, weather), [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) (reading the monochrome icon layer, home activity setup), [Olauncher](https://github.com/tanujnotes/Olauncher) and [KISS](https://github.com/Neamar/KISS) (fast app lists through LauncherApps). Halo Launcher's code is its own. Weather by [Open-Meteo](https://open-meteo.com). Fonts: Inter, Doto, Graduate and Anton (SIL Open Font License, see `docs/licenses`).

---

<p align="center">Made by <b>LONERXONE</b></p>
