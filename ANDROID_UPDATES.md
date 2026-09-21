# Android update signing

Android only installs a new APK over an existing app when all of these remain compatible:

- the `applicationId` / package name is unchanged (`com.example.aichatroom`),
- the new `versionCode` is higher, and
- the APK is signed by the **same signing certificate** as the installed app.

The old GitHub workflow produced debug APKs. A GitHub-hosted runner is disposable, so its debug signing key is not a reliable permanent update key. If the currently installed APK was signed by a different debug key and that key is no longer available, Android will report an app/signature conflict. That installation needs to be removed once before installing the first stable-signed build.

After the one-time migration below, keep the same keystore forever and future APKs can install as normal updates without uninstalling the app.

## 1. Create the permanent release keystore once

Run on a trusted local computer with JDK installed:

```bash
keytool -genkeypair -v -keystore ai-chatroom-release.jks -alias aichatroom -keyalg RSA -keysize 4096 -validity 10000
```

Use strong passwords and back up `ai-chatroom-release.jks` securely. Do not commit it to GitHub. Losing this file/key means future builds cannot update installations signed with it.

## 2. Convert the keystore to Base64

PowerShell:

```powershell
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\ai-chatroom-release.jks"))
$base64 | Set-Clipboard
```

## 3. Add GitHub Actions repository secrets

Open **GitHub repository → Settings → Secrets and variables → Actions → New repository secret** and add:

- `ANDROID_KEYSTORE_BASE64` — the Base64 text copied above
- `ANDROID_KEYSTORE_PASSWORD` — the keystore password
- `ANDROID_KEY_ALIAS` — `aichatroom` unless you chose another alias
- `ANDROID_KEY_PASSWORD` — the key password

The workflow never writes these values into the repository.

## 4. Build updates

After these secrets are configured, pushes to `main` produce a release APK signed with the same permanent key. The workflow also creates increasing version codes from the GitHub Actions run number.

The release summary says `stable-signed` when permanent signing was used. If it says `debug`, do not treat that APK as the long-term update channel.

## One-time migration from the existing conflicting APK

If Android currently says the new APK conflicts with the installed version, and you do not have the exact signing key used for that installed version:

1. Export or note any data you need from the old app.
2. Uninstall the old app **once**.
3. Install the first `stable-signed` APK.
4. For every later release, install the new stable-signed APK directly over it; no uninstall should be required.

Do not change the package name to work around the conflict unless you intentionally want Android to treat it as a completely separate app.
