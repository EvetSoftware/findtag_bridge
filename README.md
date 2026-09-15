# findtag_bridge

Flutter bridge for the FindTag Android and iOS SDKs.

## Installation

```yaml
dependencies:
  findtag_bridge:
    git:
      url: https://github.com/EvetSoftware/findtag_bridge.git
      ref: 0.1.0
```

The native SDK versions used by this plugin are:

- Android: `com.github.EvetSoftware:FindTag_Android_SDK:1.0.0` via JitPack
- iOS: `FindTagSDK` `1.0.1` from
  `https://github.com/EvetSoftware/FindTag_iOS_SDK.git`

For CocoaPods consumers, add the iOS SDK source to the application `Podfile`:

```ruby
pod 'FindTagSDK',
    :git => 'https://github.com/EvetSoftware/FindTag_iOS_SDK.git',
    :tag => '1.0.1'
```

The plugin does not store either SDK binary in its repository.
