# Official FindTag iOS SDK, taken directly from
# https://github.com/findtag/FindTag_iOS_SDK. The repository has no podspec or
# release tags, so this recipe pins a commit and packages its XCFramework.
#
# Consumers reference it from their Podfile through the Flutter plugin symlink:
#   pod 'FindTagSDK', :podspec => '.symlinks/plugins/findtag_bridge/ios/FindTagSDK.podspec'
Pod::Spec.new do |s|
  s.name             = 'FindTagSDK'
  s.version          = '1.0.1'
  s.summary          = 'Official FindTag iOS SDK (V1_0828) binary distribution.'
  s.homepage         = 'https://github.com/findtag/FindTag_iOS_SDK'
  s.license          = { :type => 'MIT', :file => 'LICENSE' }
  s.author           = { 'FindTag' => 'https://github.com/findtag' }
  s.source           = {
    :git => 'https://github.com/findtag/FindTag_iOS_SDK.git',
    :commit => '44b9967d675d4cf6ea08a703bba7cb666076675b'
  }
  s.platform         = :ios, '15.0'
  s.swift_version    = '5.0'
  # The upstream bundle is named sdk-findtag-release.xcframework while the
  # framework inside is TagSdk.framework; CocoaPods links the framework by the
  # XCFramework name, so it is renamed after download.
  s.prepare_command  = 'mv libs/sdk-findtag-release.xcframework libs/TagSdk.xcframework'
  s.vendored_frameworks = 'libs/TagSdk.xcframework'
end
