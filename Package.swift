// swift-tools-version: 6.0
// SPDX-License-Identifier: GPL-3.0-or-later
//
// AkouKit: the parts of akou-companion that need no phone, so `swift test` runs them on a Mac.
//   AkouProtocol  the JSON control messages of akou's `GET /v1/live` and the `GET /v1/server` answer
//   AkouOpus      libopus encoding and the Ogg pages the phone writes to its file and sends live
//   AkouClient    the live WebSocket client and the server probe
import PackageDescription

let package = Package(
    name: "AkouKit",
    platforms: [
        .iOS(.v18),
        .macOS(.v14),
        .watchOS(.v11),
    ],
    products: [
        .library(name: "AkouKit", targets: ["AkouProtocol", "AkouOpus", "AkouClient"]),
    ],
    dependencies: [
        // libopus as a Swift package (BSD-3-Clause; libopus itself is BSD-3-Clause too).
        // Pinned exactly: the package is quiet, so an update is a reviewed change, never a drift.
        .package(url: "https://github.com/alta/swift-opus.git", exact: "0.0.2"),
    ],
    targets: [
        .target(name: "AkouProtocol"),
        // opus_encoder_ctl is variadic, which Swift cannot call; this shim wraps the few settings we use.
        .target(
            name: "COpusShim",
            dependencies: [.product(name: "Copus", package: "swift-opus")]
        ),
        .target(
            name: "AkouOpus",
            dependencies: ["COpusShim", .product(name: "Copus", package: "swift-opus")]
        ),
        .target(name: "AkouClient", dependencies: ["AkouProtocol"]),
        .testTarget(
            name: "AkouKitTests",
            dependencies: ["AkouProtocol", "AkouOpus", "AkouClient"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
