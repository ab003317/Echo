# Android 建置 / Android builds

一般使用者直接下載 [Releases APK](https://github.com/ab003317/Echo/releases/latest)。
首次開啟輸入自己的伺服器，不內建開發者的伺服器或 AI key。
Regular users can install the release APK and enter their own server.
No developer server or AI keys are embedded.

## 本機建置 / Local build

需要 JDK 17+、Android SDK platform 36。設定 ANDROID_HOME 或自行建立
android/local.properties 的 sdk.dir。Windows 使用 gradlew.bat。
Requires JDK 17+ and Android SDK platform 36. Set ANDROID_HOME or sdk.dir in
android/local.properties. On Windows use gradlew.bat.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

輸出 / Output: android/app/build/outputs/apk/debug/app-debug.apk.

## 正式簽署 / Release signing

建立自己的 Android keystore，再建立不提交 Git 的 android/signing.properties：
Create your own Android keystore and an untracked android/signing.properties:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=your-store-password
keyAlias=your-alias
keyPassword=your-key-password
```

```bash
./gradlew :app:assembleRelease
```

未提供 signing.properties 時只會產生未簽署的 release APK。
請妥善保存簽署檔；同一套 App 的後續更新需要同一把簽署 key。
Without signing.properties the release APK is unsigned. Keep your signing files:
future updates to the same installed app require the same signing key.

applicationId 保留歷史值 org.dddd010010.serein 以支援既有安裝升級；
畫面名稱只有 Echo。切換伺服器會停止播放，不同伺服器的本機收藏與下載分開存放。
The historical applicationId is retained for upgrade compatibility; the visible
name is Echo. Switching servers stops playback and isolates local favorites and
downloads by server.
