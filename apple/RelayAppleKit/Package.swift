// swift-tools-version: 5.9
import PackageDescription

// The package is deliberately a library.  An operational iPhone app still
// needs an Apple-signed Xcode host, permissions, and the verified regional
// shelter directory provisioned by the operator.
let package = Package(
    name: "RelayAppleKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "RelayAppleKit", targets: ["RelayAppleKit"]),
    ],
    targets: [
        .target(name: "RelayAppleKit"),
        .testTarget(name: "RelayAppleKitTests", dependencies: ["RelayAppleKit"]),
    ]
)
