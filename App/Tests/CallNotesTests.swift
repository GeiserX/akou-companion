// SPDX-License-Identifier: GPL-3.0-or-later
import XCTest
@testable import akou

/// A call's notes show one bullet each, also when akou's note already starts with a list marker.
@MainActor
final class CallNotesTests: XCTestCase {
    func testANoteThatStartsWithAMarkerGetsOneBullet() {
        XCTAssertEqual(CallDetailView.bulleted("- profile service"), "• profile service")
        XCTAssertEqual(CallDetailView.bulleted("* profile service"), "• profile service")
        XCTAssertEqual(CallDetailView.bulleted("• profile service"), "• profile service")
        XCTAssertEqual(CallDetailView.bulleted("  - profile service"), "• profile service")
        XCTAssertEqual(CallDetailView.bulleted("profile service"), "• profile service")
        XCTAssertEqual(CallDetailView.bulleted("-1 dB is fine"), "• -1 dB is fine")
    }
}
