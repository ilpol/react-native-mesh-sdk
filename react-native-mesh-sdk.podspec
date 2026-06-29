require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-mesh-sdk"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/ilpol/react-native-mesh-sdk"
  s.license      = "GPL-3.0-or-later"
  s.authors      = "happy_development"
  s.platforms    = { :ios => "16.0" }
  s.source       = { :git => "https://github.com/ilpol/react-native-mesh-sdk.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.swift_version = "5.9"

  s.frameworks = "CoreBluetooth", "Combine", "UIKit", "UserNotifications"

  # Подключение React Native (заголовки RCTEventEmitter и т.д.).
  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency "React-Core"
  end
end
