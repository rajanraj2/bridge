cask "bridge" do
  version "0.1.18"
  sha256 :no_check

  url "https://github.com/rajanraj2/bridge/releases/download/v#{version}/Bridge-v#{version}.dmg"
  name "Bridge"
  desc "Local-first continuity application connecting Android and macOS"
  homepage "https://github.com/rajanraj2/bridge"

  depends_on macos: :ventura

  app "Bridge.app"

  uninstall quit: "dev.bridge.BridgeApp"

  zap trash: [
    "~/Library/Application Support/dev.bridge.BridgeApp",
    "~/Library/Preferences/dev.bridge.BridgeApp.plist",
  ]
end
