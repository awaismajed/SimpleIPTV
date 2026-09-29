# Simple IPTV Android

Minimal Android IPTV app: loads one M3U URL, lists channels, plays them with Media3, and exposes a Google Cast button.

## Change playlist
Edit:
`app/src/main/java/com/spel/simpleiptv/PlaylistConfig.kt`

Replace `M3U_URL` with your playlist URL.

## Build
Open this folder in Android Studio, let Gradle sync, then Run on an Android phone (API 24+).
For an APK: Build > Build Bundle(s) / APK(s) > Build APK(s).

## Chromecast
Phone and Chromecast/Google TV should be on the same Wi-Fi. The stream URL itself must be reachable and playable by the Cast receiver.

Use only streams you are authorized to access.
