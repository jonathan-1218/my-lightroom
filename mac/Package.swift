// swift-tools-version:5.9
import PackageDescription

// Lumen for Mac: SwiftUI + Core Image, no dependencies. Build the app bundle with ./build.sh
let package = Package(
    name: "Lumen",
    platforms: [.macOS(.v14)],
    targets: [.executableTarget(name: "Lumen", path: "Sources/Lumen")]
)
