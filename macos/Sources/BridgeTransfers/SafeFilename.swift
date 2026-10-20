import Foundation

public struct SafeFilename {
    public static let maxFilenameLength: Int = 255
    public static let defaultFallbackName: String = "unnamed_file"

    /// Sanitizes an untrusted sender-supplied filename to prevent directory traversal and filesystem attacks.
    public static func sanitize(_ rawName: String) -> String {
        // Normalize backslashes to forward slashes first
        let normalized = rawName.replacingOccurrences(of: "\\", with: "/")

        // Extract filename component (strips any path prefixes)
        var name = (normalized as NSString).lastPathComponent

        // Remove path separators and control characters
        name = name.replacingOccurrences(of: "/", with: "_")
        name = name.components(separatedBy: CharacterSet.controlCharacters).joined()

        // Strip directory traversal sequences
        while name.contains("..") {
            name = name.replacingOccurrences(of: "..", with: "")
        }

        // Strip leading dots to prevent creating hidden files
        while name.hasPrefix(".") {
            name.removeFirst()
        }

        name = name.trimmingCharacters(in: .whitespacesAndNewlines)

        if name.isEmpty || name == "_" {
            name = defaultFallbackName
        }

        // Enforce max filename length preserving extension
        if name.count > maxFilenameLength {
            let ext = (name as NSString).pathExtension
            let base = (name as NSString).deletingPathExtension
            if ext.isEmpty {
                name = String(name.prefix(maxFilenameLength))
            } else {
                let allowedBaseLen = maxFilenameLength - ext.count - 1
                name = String(base.prefix(max(1, allowedBaseLen))) + "." + ext
            }
        }

        return name
    }

    /// Resolves a non-colliding destination file URL in the given directory by appending ` (1)`, ` (2)`, etc.
    public static func uniqueDestinationURL(in directory: URL, filename: String) -> URL {
        let cleanName = sanitize(filename)
        var destination = directory.appendingPathComponent(cleanName)

        if !FileManager.default.fileExists(atPath: destination.path) {
            return destination
        }

        let baseName = (cleanName as NSString).deletingPathExtension
        let ext = (cleanName as NSString).pathExtension
        var counter = 1

        while FileManager.default.fileExists(atPath: destination.path) {
            let newName: String
            if ext.isEmpty {
                newName = "\(baseName) (\(counter))"
            } else {
                newName = "\(baseName) (\(counter)).\(ext)"
            }
            destination = directory.appendingPathComponent(newName)
            counter += 1
        }

        return destination
    }
}
