<p align="center">
  <img src="docs/logo.png" width="150" alt="Halo Launcher logo: a halo over an app grid">
</p>

<h1 align="center">Halo Launcher</h1>

<p align="center">
  A black-and-white home screen for Android that works with <a href="https://github.com/LONERXONE-ops/halo">Halo</a>, the dynamic island.<br>
  Real Liquid Glass over your own picture, iOS-style dark icons, resizable widgets, a floating dock, and your music, badges and notifications straight from the island.
</p>

---

## Download

**[⬇ Get the latest APK](https://github.com/LONERXONE-ops/halo-launcher/releases/latest)**

1. Download `HaloLauncher-x.y.z.apk` from **Assets** and install it.
2. Press the home button and pick **Halo Launcher** (or open it and tap **Make it my home screen**).
3. Updates come from **Settings → Updates** inside the launcher.

For the full experience, install **[Halo](https://github.com/LONERXONE-ops/halo/releases/latest)** too.

## The home screen

Your picture (or your wallpaper) fills the whole screen, and everything on it sits on **Liquid Glass**: panes that bend the picture behind them like a lens, frosted in the middle, refracting toward the rim with a hint of colour fringing, light catching the top-left edge. It runs as a GPU shader on Android 13+ (frosted glass on older phones). Prefer plain dark tiles? Switch the theme to **Solid** in Settings → Theme.

| | |
|---|---|
| **Page 1** | The stacked clock (varsity, dot matrix, bold or thin; digits roll up each minute), the date and weather, and the music player: blurred cover washed in the song's colour, with progress and time. Fed by Halo, works with any player. |
| **Edit mode** | Long-press the wallpaper (or hold any widget) and everything jiggles, like iOS. Drag anything to any spot on any page: the home screen is a 4-column grid like iOS and One UI, gaps allowed. An outline shows where it will land; hold at the screen's edge to carry it to the next page (or a new one). Tap a widget's size pill for Small, Medium or Large, the minus to remove, **+** to add. Done, Home or a tap on empty space to finish. |
| **Page 2** | Halo Launcher's own widgets, built on Apple's widget rules (one idea each, live data, dot-matrix numbers): weather, battery in dots, steps toward your goal, today's events, screen time with your top apps, a note, a photo and a one-tap timer. Each comes in **Small, Medium and Large**, and bigger sizes show more (weather adds the next hours, then the next days; the agenda lists the whole day; screen time lists your apps). Plus any Android widget, also movable and resizable. |
| **Dock** | Floats just above the bottom edge, 4 to 6 apps; the icons resize to fit. Four styles, after One UI 9: glass, solid, clear, or tinted with your wallpaper's colour. |
| **All apps** | Swipe up and the list rises out of the home screen, following your finger. Search, most used, an A–Z grid and a letter strip to jump. Swipe down from the top (or fling) to close. Long-press any app for its shortcuts, dock, hide, info and uninstall. |
| **Icons** | iOS-style dark icons: each app's own symbol in its brand colour on a dark tile, worked out from the real icon so it works with Samsung's icons too. Every symbol is centred and sized the same. Or white line icons, or the originals. iOS-style red badges. |
| **Settings** | A page per topic with live examples on your real background: the theme in glass and solid, your clock in every style, your apps in every icon style, every widget at its size. Going back always returns you to where you were. |

## Gestures

- **Swipe up**: all apps
- **Swipe down**: Halo's notification center (or Android's shade)
- **Double-tap**: lock the screen, through Halo, so fingerprint unlock still works
- **Long-press** the wallpaper: edit the home screen (Settings is in the edit bar)
- **Back** from an app: you land exactly where you were, app list and scroll included
- **Home** while home: closes the app list, then back to page 1

## Works with Halo

Both apps are signed with the same key and talk through a signature-protected link, so no other app can listen in or drive them.

- Halo sends the song playing (with its cover), unread counts per app, the newest notifications and what's switched on.
- The launcher asks Halo to play, pause and skip, open the notification center or a notification, lock the screen, or switch something off.

## Built on what others learned

Studied before writing a line, and again for the redesign (Niagara's single header widget, Nothing's dot-matrix widgets, Apple's widget guidelines): [Kvaesitso](https://github.com/MM2-0/Kvaesitso) (widget hosting, themed icons, weather), [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) (reading the monochrome icon layer, home activity setup), [Olauncher](https://github.com/tanujnotes/Olauncher) and [KISS](https://github.com/Neamar/KISS) (fast app lists through LauncherApps). Halo Launcher's code is its own. Weather by [Open-Meteo](https://open-meteo.com). Fonts: Inter, Doto, Graduate and Anton (SIL Open Font License, see `docs/licenses`).

---

<p align="center">Made by <b>LONERXONE</b></p>
