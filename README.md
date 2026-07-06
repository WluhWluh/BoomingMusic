<div align="center">

<img src="metadata/en-US/images/icon.png" width="160" height="160" alt="Booming SS icon">

# 🎵 Booming SS

### A Booming Music fork with local source separation playback.

[![Latest Release](https://img.shields.io/github/v/release/WluhWluh/BoomingSS?style=for-the-badge&label=SS%20Release&logo=github)](https://github.com/WluhWluh/BoomingSS/releases/latest)
[![Upstream](https://img.shields.io/badge/Upstream-Booming%20Music-blue?style=for-the-badge&logo=github)](https://github.com/mardous/BoomingMusic)
[![License: GPL v3](https://img.shields.io/github/license/WluhWluh/BoomingSS?style=for-the-badge&color=orange&label=License&logo=gnu)](LICENSE.txt)
[![Contributor Covenant](https://img.shields.io/badge/Contributor_Covenant-2.1-4baaaa.svg?style=for-the-badge&logo=contributorcovenant)](CODE_OF_CONDUCT.md)

<a href="https://github.com/WluhWluh/BoomingSS/releases"><img src="assets/badge-github.png" height="60" alt="Booming SS GitHub Release"></a>

</div>

> [!IMPORTANT]
> Booming SS is an unofficial source-separation build based on
> [Booming Music](https://github.com/mardous/BoomingMusic). It is not affiliated
> with or endorsed by the upstream maintainers unless explicitly stated. The fork
> keeps the original local music player experience and adds experimental local
> stem separation features.

## 🗂️ Table of Contents

- [🎚️ Source Separation Fork](#-source-separation-fork)
- [✨ Key Features](#-key-features)
- [📸 Screenshots](#-screenshots)
- [📥 Download & Install](#-download--install)
- [💻 Tech Stack](#-tech-stack)
- [🧩 Roadmap](#-roadmap)
- [🔗 Useful Links](#-useful-links)
- [🤝 Contributing](#-contributing)
- [💖 Support Development](#-support-development)
- [🙌 Credits](#-credits)
- [⚖️ License](#-license)

## 🎚️ Source Separation Fork

Booming SS uses the package name `com.wluhwluh.booming.sourcesep`, so it can be
installed alongside the original Booming Music app.

What this fork adds:

- **Separated playback** for local tracks, with vocals/instrumental blend controls.
- **Per-song blend memory**, quick lyrics-overlay controls, and a dedicated source separation panel.
- **On-device ONNX processing** for the currently supported UVR MDX-Net style model.
- **Model management** inside the app: download from a preset URL, download from a custom URL, or import a local ONNX file.
- **Separated cache management** with progress, reuse, cleanup, and fallback handling.

Model files are **not bundled** in the APK. When no local model is installed, the
app asks the user to download or import one. The app displays a SHA-256 comparison
for the preset model as guidance; a mismatch is shown to the user but does not
block use. Users are responsible for checking the license and suitability of any
model file they download or import.

## ✨ Key Features

- 🎚️ **Local Source Separation Playback** – Split supported songs into separated playback caches and blend between original/stem output.
- 🎼 **Automatic Lyrics Download & Editing** – Automatically fetch, sync, and edit lyrics with ease.
- 💬 **Word-by-Word Synced Lyrics** – Enjoy immersive real-time lyric playback with word-level timing.
- 🌍 **Translated Lyrics Support** – Display dual-language lyrics via TTML or LRC with translations.
- 🔊 **Built-in Equalizer** – Powerful EQ with up to 15 fully configurable bands and customizable profiles.
- 🎧 **AutoEq Support** – Import professionally tuned headphone correction profiles for the most accurate sound possible.
- 🔄 **Gapless Playback** – Smooth transitions between songs with zero interruption.
- 🧠 **Smart Playlists** – Auto-generated lists like *Recently Played*, *Most Played*, and *History*.
- 📈 **Native Scrobbling** – Seamlessly sync your listening history with **Last.fm** and **ListenBrainz**.
- 🎧 **Bluetooth & Headset Controls** – Manage playback easily via connected devices.
- 🚗 **Android Auto Integration** – Full hands-free experience on the road.
- 🎨 **Material You Design** – Dynamic theming for a modern and personal interface.
- 📂 **Folder Browsing** – Play songs directly from any folder.
- ⏰ **Sleep Timer** – Automatically stop playback after a set time.
- 🧩 **Widgets** – Lock screen and home screen controls for quick access.
- 🔖 **Tag Editor** – Edit song metadata such as title, artist, and album info.
- 🔉 **ReplayGain Support** – Maintain consistent volume across all tracks.
- 🖼️ **Automatic Artist Images** – Download artist artwork for a polished library look.
- 🚫 **Library Filtering** – Easily exclude or include folders with blacklist/whitelist options.

## 📸 Screenshots

<div align="center">
<table>
<tr>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/1.jpg" alt="For You" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/2.jpg" alt="Songs" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/3.jpg" alt="Albums" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/4.jpg" alt="Album View" width="180"/></td>
</tr>
<tr>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/5.jpg" alt="Search" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/6.jpg" alt="Normal" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/7.jpg" alt="Full" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/8.jpg" alt="Gradient" width="180"/></td>
</tr>
<tr>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/9.jpg" alt="Plain" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/10.jpg" alt="M3" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/11.jpg" alt="Expressive" width="180"/></td>
<td align="center" width="25%"><img src="metadata/en-US/images/phoneScreenshots/12.jpg" alt="Peek" width="180"/></td>
</tr>
</table>
</div>

## 📥 Download & Install

Booming SS builds are published from this fork only. Use the fork's GitHub
releases for installable APKs, or GitHub Actions artifacts for CI builds.

<div align="center">

| Source | Details |
|:------:|:--------|
| [<img src="assets/badge-github.png" alt="GitHub Releases" height="35">](https://github.com/WluhWluh/BoomingSS/releases/latest) | Direct Booming SS APK downloads |

</div>

## 💻 Tech Stack

| Layer                   | Technology                                                      |
|:------------------------|:----------------------------------------------------------------|
| 🎧 Audio Engine         | [Media3 ExoPlayer](https://developer.android.com/media/media3)  |
| 🧱 Architecture         | MVVM + Repository Pattern                                       |
| 💾 Persistence          | Room Database + DataStore + SharedPreferences                   |
| ⚙️ Dependency Injection | [Koin](https://insert-koin.io/)                                 |
| 🧵 Async                | Kotlin Coroutines & Flow                                        |
| 🧩 UI                   | Android Views + Jetpack Compose (hybrid)                        |
| 🖼️ Image Loading       | [Coil 3](https://coil-kt.github.io/coil/)                       |
| 🎨 Design               | Material 3 / Material You                                       |
| 🎚️ Source Separation    | [ONNX Runtime](https://onnxruntime.ai/)                         |
| 🗣️ Language            | Kotlin                                                          |

## 🧩 Roadmap

- [ ] 📦 Independent library scanner (no MediaStore dependency)
- [ ] 🎨 Multi-artist support (split & index properly)
- [ ] 🎵 Improved genre handling
- [ ] 🔁 Last.fm integration (import/export playback data)
- [ ] 💿 Enhanced artist pages (separate albums and singles visually)
- [ ] 🌐 Jellyfin & Navidrome integration

## 🔗 Useful Links

- 🔐 **[Requested Permissions](https://github.com/mardous/BoomingMusic/wiki/Advanced-Info#-permissions)**  
  What the app needs and why

- 🚘 **[Android Auto Setup](https://github.com/mardous/BoomingMusic/wiki/Advanced-Info#-android-auto-setup)**  
  How to enable and troubleshoot

- 🎧 **[Supported Formats](https://github.com/mardous/BoomingMusic/wiki/Advanced-Info#-supported-formats)**  
  Compatible audio formats

- 💬 **[Community](https://github.com/mardous/BoomingMusic/wiki/Community)**  
  Users and contributors

- 🌐 **[Translations](https://hosted.weblate.org/projects/booming-music/)**  
  Help us translate Booming Music into your language

- ❓ **[FAQ](https://github.com/mardous/BoomingMusic/wiki/FAQ)**  
  Common questions

## 🤝 Contributing

Booming SS is maintained as a source-separation flavored fork of Booming Music.
Source-separation issues and pull requests should be opened in this fork. For
bugs that reproduce in the original app without source-separation changes, please
also consider reporting them upstream.

Booming Music is open-source — contributions are **always welcome!**
Check the [Contributing Guide](CONTRIBUTING.md) for details.

If you enjoy the app or want to support its development, give the repo a ⭐ — it really helps!
You can also:
- Open issues
- Submit pull requests
- Suggest new ideas

**Translations:** Managed on [Hosted Weblate](https://hosted.weblate.org/projects/booming-music/).

[![Translation Status](https://hosted.weblate.org/widget/booming-music/horizontal-auto.svg)](https://hosted.weblate.org/projects/booming-music/)

## 💖 Support Development

The original Booming Music project is an open-source app developed and maintained by
Christians Martínez Alvarado with care in his spare time. If you enjoy Booming Music
or this source-separation fork, please consider supporting the original author to help
cover development costs and make continued work on Booming Music sustainable.

Support for the original author is greatly appreciated and helps keep Booming Music moving forward.

<div align="center">

<a href="https://ko-fi.com/christiaam" target="_blank">
<img src="https://storage.ko-fi.com/cdn/brandasset/v2/support_me_on_kofi_red.png" alt="Support Christians Martínez Alvarado on Ko-fi" style="border: 0px; height: 40px;" />
</a>

### ❤️ Supporters

<table>
  <tr>
    <td>
      <b>mbeezy</b><br/>
      <b><a href="https://github.com/Qoojoe">KKTweex</a></b><br/>
      <b><a href="https://github.com/FabiRich">FabiRich</a></b><br/>
      <b><a href="https://github.com/Bloodaxe95">Bloodaxe</a></b><br/>
      <b>Bernhard</b>
    </td>
    <td>
      <b>Andreas Hirth</b><br/>
      <b>Revolver327</b><br/>
      <b>Peter Smith</b><br/>
      <b>Michele Simoncelli</b>
    </td>
  </tr>
</table>

</div>

## 🙌 Credits

Booming SS is based on [Booming Music](https://github.com/mardous/BoomingMusic)
by Christians Martínez Alvarado and contributors.

Inspired by [Retro Music Player](https://github.com/RetroMusicPlayer/RetroMusicPlayer).
Also thanks to:

- [AMLV](https://github.com/dokar3/amlv)
- [LRCLib](https://lrclib.net/)
- [Better Lyrics](https://better-lyrics.boidu.dev/)
- [Lyrically API](https://lyrics.paxsenix.org/) (by [Alex](https://github.com/Paxsenix0))

The preset source separation model URL references the `UVR_MDXNET_9482.onnx`
asset from the `k2-fsa/sherpa-onnx` source-separation-models release. The model
file is not redistributed in this repository's APK builds.

## ⚖️ License

```
GNU General Public License - Version 3

Copyright (C) 2025 Christians Martínez Alvarado

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
```

---

<p align="center"><a href="#readme">⬆️ Back to top</a></p>
