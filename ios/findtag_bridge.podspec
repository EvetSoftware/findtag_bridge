Pod::Spec.new do |s|
  s.name             = 'findtag_bridge'
  s.version          = '0.1.0'
  s.summary          = 'Flutter bridge for the official FindTag SDK.'
  s.description      = 'Flutter bridge used by the FindTag physical-device integration test app.'
  s.homepage         = 'https://github.com/EvetSoftware/FindTag_iOS_SDK'
  s.license          = { :file => '../IOS_SDK_LICENSE' }
  s.author           = { 'Local integration' => 'local@example.invalid' }
  s.source           = { :path => '.' }
  s.source_files     = 'findtag_bridge/Sources/findtag_bridge/**/*.{swift,h,m}'
  s.public_header_files = 'findtag_bridge/Sources/findtag_bridge/FTSDKAdapter.h'
  s.dependency 'Flutter'
  s.dependency 'FindTagSDK', '1.0.1'
  s.platform = :ios, '15.0'
  s.swift_version = '5.0'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386'
  }
end
