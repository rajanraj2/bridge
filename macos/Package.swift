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
        .executable(name: "BridgeApp", targets: ["BridgeApp"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "BridgeDomain",
            dependencies: [],
            path: "Sources/BridgeDomain"
        ),
        .target(
            name: "BridgeProtocol",
            dependencies: ["BridgeDomain"],
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
        .executableTarget(
            name: "BridgeApp",
            dependencies: ["BridgeDomain", "BridgeProtocol", "BridgeSecurity", "BridgeNetwork"],
            path: "Sources/BridgeApp"
        ),
        .testTarget(
            name: "BridgeTests",
            dependencies: ["BridgeDomain", "BridgeProtocol", "BridgeSecurity", "BridgeNetwork"],
            path: "Tests/BridgeTests"
        )
    ]
)
