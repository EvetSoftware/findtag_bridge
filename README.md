# findtag_bridge

Flutter bridge for the official FindTag Android and iOS SDKs.

## Installation

```yaml
dependencies:
  findtag_bridge:
    git:
      url: https://github.com/EvetSoftware/findtag_bridge.git
      ref: 0.3.0
```

Both native SDKs are taken directly from the official FindTag repositories,
pinned to a commit. The plugin does not store either SDK binary.

| Platform | Source | Version |
| --- | --- | --- |
| Android | [findtag/FindTag_Android_SDK](https://github.com/findtag/FindTag_Android_SDK) `libs/sdk-findtag-release_V1_0930.aar` (tag `V1_260930`) | V1_0930 |
| iOS | [findtag/FindTag_iOS_SDK](https://github.com/findtag/FindTag_iOS_SDK) `libs/sdk-findtag-release.xcframework` (commit `44b9967`) | V1_0828 |

### Android

Nothing to add. The plugin registers an Ivy repository that downloads the AAR
from `raw.githubusercontent.com/findtag/FindTag_Android_SDK/<commit>/libs/`.
The SDK requires `minSdk 29`.

### iOS

The official iOS repository has no podspec, so the plugin ships one. Add it to
the application `Podfile` (inside the `Runner` target):

```ruby
pod 'FindTagSDK', :podspec => '.symlinks/plugins/findtag_bridge/ios/FindTagSDK.podspec'
```

Add `NSBluetoothAlwaysUsageDescription` to the app's `Info.plist`.

## Location history

- `getLocationData(savedTagId, preset)` — latest, or the last 1/2/4/6 hours.
- `getLocationDataInRange(savedTagId, start, end)` — custom range (SDK
  `CUSTOM` query). The longest allowed range and data retention are set by
  FindTag.

## Updating the SDKs

- Android: change `findTagSdkCommit` and `findTagSdkArtifact` in
  `android/build.gradle`.
- iOS: change `:commit` in `ios/FindTagSDK.podspec` (and `s.version` together
  with the dependency in `ios/findtag_bridge.podspec`).
