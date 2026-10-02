# Filmarks TV

Android TV / Google TV app (built for the onn 4K box) for the
[Filmarks](https://github.com/hbar137/filmarks) archive: browse the Japanese
(Filmarks) and English (TMDB) catalogs, your Trakt watchlist and history,
and play Real-Debrid links and seedbox downloads.

Talks to the server's device API (`/api/kodi/*`, documented in the server's
`docs/kodi-api.md`), the same one the Kodi add-on uses. The password is
entered on the TV; nothing secret is in this repo.

## Install on the TV

1. Settings → System → About → click **Android TV OS build** 7 times
   (developer mode), then Settings → Apps → Security → allow
   **Downloader** to install unknown apps.
2. Install **Downloader** (AFTVnews) from the Play Store.
3. In Downloader, enter the code **4007968** (AFTVnews short code for
   `https://filmarks.eulerai.net/api/kodi/tv`, which redirects to the
   latest release APK) and install. The long form also works:
   `https://github.com/hbar137/filmarks-tv/releases/latest/download/filmarks-tv.apk`

## Build

GitHub Actions builds a signed release APK on every push to `main` and
publishes it as a release (`.github/workflows/build.yml`). The signing key
is the `KEYSTORE_B64` / `KEYSTORE_PASS` secrets (kept on the server in
`~/secrets/filmarks-tv/`); every build must use it, or Android refuses to
install it over the previous one.

Stack: Kotlin, Compose for TV, Media3 ExoPlayer.
