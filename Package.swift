// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "KodifChat",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "KodifChat", targets: ["KodifChat"]),
    ],
    targets: [
        .target(name: "KodifChat", path: "ios/Sources/KodifChat"),
        .testTarget(name: "KodifChatTests", dependencies: ["KodifChat"], path: "ios/Tests/KodifChatTests"),
    ]
)
