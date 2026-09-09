# Gmail Ad Remover

An LSPosed/Xposed module that hides sponsored rows in Gmail's Promotions and Social inboxes.

The module is deliberately scoped only to `com.google.android.gm`. It recognizes Gmail's
rendered sponsored label, finds the containing RecyclerView item, and collapses that item.
It does not block networking or modify messages.

## Build

Requirements: JDK 17+, Android SDK Platform 35, and Gradle 8.9.

```powershell
gradle :app:assembleDebug
```

The APK will be written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

1. Install the APK.
2. In LSPosed, enable **Gmail Ad Remover**. Gmail is the recommended and only declared scope.
3. Force-stop Gmail, then open it again.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.gm
```

If a Gmail update changes the sponsored label or list implementation, capture an LSPosed log
and a UI hierarchy while the sponsored row is visible so the matcher can be updated.

## Privacy

No permissions are requested. The module does not access the network, accounts, or message
storage. Matching happens only against text already rendered in Gmail's conversation list.
