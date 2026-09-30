# Android 建置 / Android builds

下載 [Release APK](https://github.com/ab003317/Echo/releases/latest)，
首次開啟時輸入伺服器位址。AI key 設定於伺服器的 .env。
Download the release APK and enter the server address on first launch.
AI keys are configured in the server .env.

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

applicationId 為 `org.dddd010010.serein`，保留此值以維持既有安裝的升級相容性。
The applicationId is `org.dddd010010.serein`, retained for upgrade compatibility.
