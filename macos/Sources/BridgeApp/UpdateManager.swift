import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
public struct UpdateInfo: Sendable {
    public let tagName: String
    public let version: String
    public let releaseName: String
    public let releaseNotes: String
    public let releaseUrl: String
    public let dmgDownloadUrl: String?

    public init(tagName: String, version: String, releaseName: String, releaseNotes: String, releaseUrl: String, dmgDownloadUrl: String?) {
        self.tagName = tagName
        self.version = version
        self.releaseName = releaseName
        self.releaseNotes = releaseNotes
        self.releaseUrl = releaseUrl
        self.dmgDownloadUrl = dmgDownloadUrl
    }
}

public final class UpdateManager: Sendable {
    public static let shared = UpdateManager()
    private let releasesUrl = URL(string: "https://api.github.com/repos/rajanraj2/bridge/releases/latest")!

    public func checkForUpdates(currentVersion: String) async -> UpdateInfo? {
        var request = URLRequest(url: releasesUrl)
        request.setValue("Bridge-macOS-App", forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 8.0

        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let httpRes = response as? HTTPURLResponse, httpRes.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tagName = json["tag_name"] as? String else {
            return nil
        }

        let remoteVersion = tagName.trimmingCharacters(in: CharacterSet(charactersIn: "vV "))
        guard isNewer(remote: remoteVersion, local: currentVersion) else { return nil }

        let name = json["name"] as? String ?? "Bridge \(tagName)"
        let body = json["body"] as? String ?? ""
        let htmlUrl = json["html_url"] as? String ?? "https://github.com/rajanraj2/bridge/releases"

        var dmgUrl: String? = nil
        if let assets = json["assets"] as? [[String: Any]] {
            for asset in assets {
                if let aName = asset["name"] as? String, aName.hasSuffix(".dmg") {
                    dmgUrl = asset["browser_download_url"] as? String
                    break
                }
            }
        }

        return UpdateInfo(
            tagName: tagName,
            version: remoteVersion,
            releaseName: name,
            releaseNotes: body,
            releaseUrl: htmlUrl,
            dmgDownloadUrl: dmgUrl ?? htmlUrl
        )
    }

    public func isNewer(remote: String, local: String) -> Bool {
        let rParts = remote.split(separator: ".").compactMap { Int($0) }
        let lParts = local.split(separator: ".").compactMap { Int($0) }
        let maxCount = max(rParts.count, lParts.count)
        for i in 0..<maxCount {
            let r = i < rParts.count ? rParts[i] : 0
            let l = i < lParts.count ? lParts[i] : 0
            if r > l { return true }
            if r < l { return false }
        }
        return false
    }
}
