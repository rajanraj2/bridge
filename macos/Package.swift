// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "BridgeMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(name: "BridgeDomain", targets: ["BridgeDomain"]),
        .library(name: "BridgeProtocol", targets: ["BridgeProtocol"]),
        .library(name: "BridgeSecurity", targets: ["BridgeSecurity"]),
        .library(name: "BridgeNetwork", targets: ["BridgeNetwork"]),
        .library(name: "BridgeNotifications", targets: ["BridgeNotifications"]),
        .library(name: "BridgeClipboard", targets: ["BridgeClipboard"]),
        .library(name: "BridgeTransfers", targets: ["BridgeTransfers"]),
        .executable(name: "BridgeApp", targets: ["BridgeApp"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.26.0")
    ],
    targets: [
        .target(
            name: "BridgeDomain",
            dependencies: [],
            path: "Sources/BridgeDomain"
        ),
        .target(
            name: "BridgeProtocol",
            dependencies: [
                "BridgeDomain",
                .product(name: "SwiftProtobuf", package: "swift-protobuf")
            ],
            path: "Sources/BridgeProtocol"
        ),
        .target(
            name: "BridgeSecurity",
            dependencies: ["BridgeDomain"],
            path: "Sources/BridgeSecurity"
        ),
        .target(
            name: "BridgeNetwork",
            dependencies: ["BridgeDomain", "BridgeProtocol", "BridgeSecurity"],
            path: "Sources/BridgeNetwork"
        ),
        .target(
            name: "BridgeNotifications",
            dependencies: ["BridgeDomain", "BridgeProtocol", "BridgeSecurity"],
            path: "Sources/BridgeNotifications"
        ),
        .target(
            name: "BridgeClipboard",
            dependencies: ["BridgeDomain", "BridgeProtocol", "BridgeSecurity"],
            path: "Sources/BridgeClipboard"
        ),
        .target(
            name: "BridgeTransfers",
            dependencies: ["BridgeDomain", "BridgeProtocol", "BridgeSecurity"],
            path: "Sources/BridgeTransfers"
        ),
        .executableTarget(
            name: "BridgeApp",
            dependencies: [
                "BridgeDomain",
                "BridgeProtocol",
                "BridgeSecurity",
                "BridgeNetwork",
                "BridgeNotifications",
                "BridgeClipboard",
                "BridgeTransfers"
            ],
            path: "Sources/BridgeApp"
        ),
        .testTarget(
            name: "BridgeTests",
            dependencies: [
                "BridgeDomain",
                "BridgeProtocol",
                "BridgeSecurity",
                "BridgeNetwork",
                "BridgeNotifications",
                "BridgeClipboard",
                "BridgeTransfers"
            ],
            path: "Tests/BridgeTests"
        )
    ]
)
