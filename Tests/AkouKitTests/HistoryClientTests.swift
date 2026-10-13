// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import AkouProtocol
import Foundation
import XCTest

/// Serves akou's history routes from fixed bodies, checking the key and its scope the way akou's
/// guard does (src/main/api/guard.ts `scopeRule`: 401 without a valid key, 403 `forbidden` when
/// the key's scope does not reach the route).
final class FakeHistoryHTTP: URLProtocol, @unchecked Sendable {
    /// The keys this server knows, by their scopes.
    static let keys: [String: [String]] = [
        "ak_read": ["read"],
        "ak_admin": ["admin"],
        "ak_jobs": ["jobs"],
        "ak_both": ["jobs", "read"],
    ]

    private static let lock = NSLock()
    nonisolated(unsafe) private static var _routes: [String: String] = [:]
    nonisolated(unsafe) private static var _seen: [URLRequest] = []

    static func reset(_ routes: [String: String]) { lock.withLock { _routes = routes; _seen = [] } }
    static var seen: [URLRequest] { lock.withLock { _seen } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeHistoryHTTP.self]
        c.urlCache = nil
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let path = request.url!.path
        let route = Self.lock.withLock { () -> String? in
            Self._seen.append(request)
            return Self._routes[path]
        }
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        guard auth.hasPrefix("Bearer "), let scopes = Self.keys[String(auth.dropFirst(7))] else {
            return answer(401, #"{"error":"unauthorized","message":"a valid bearer token is required"}"#)
        }
        // Every history route needs read or admin; /v1/keys/me answers any key.
        if path != "/v1/keys/me", !(scopes.contains("read") || scopes.contains("admin")) {
            return answer(403, #"{"error":"forbidden","message":"this key's scope does not reach GET \#(path); it needs read"}"#)
        }
        if path == "/v1/keys/me" {
            let list = scopes.map { "\"\($0)\"" }.joined(separator: ",")
            return answer(200, #"{"id":"k_test","name":"phone","scopes":[\#(list)],"created_at":1760000000000}"#)
        }
        guard let route else { return answer(404, #"{"error":"not_found","message":"no such call"}"#) }
        answer(200, route)
    }

    private func answer(_ status: Int, _ body: String) {
        let r = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

/// The fixtures are akou's real answers with made-up content. Where each shape comes from, in the
/// akou repository, is noted on each one.
enum HistoryFixtures {
    /// `GET /v1/calls`: src/main/api/routes/calls.ts `calls.list`, rows of `CallSummary`
    /// (src/main/call/recovery.ts) with `dir` renamed `folder`.
    static let calls = #"""
    {"calls":[
      {"id":"01JCALLB0000000000000000002","workspace":"Work","title":"Weekly sync","createdAt":1760335200000,"state":"ended","endedAt":1760338800000,"parts":2,"updatedAt":1760339000000,"folder":"/Users/someone/akou/Work/2026-10-13 Weekly sync"},
      {"id":"01JCALLA0000000000000000001","workspace":"","title":"","createdAt":1760248800000,"state":"recording","endedAt":null,"parts":1,"updatedAt":1760248900000,"folder":"/Users/someone/akou/Inbox/x"}
    ]}
    """#

    /// `GET /v1/calls/{id}`: src/main/api/routes/calls.ts `callDetail`.
    static let call = #"""
    {"id":"01JCALLB0000000000000000002","title":"Weekly sync","workspace":"Work","template":null,"user":"Someone","tz":"Europe/Madrid","folder":"/Users/someone/akou/Work/x","state":"ended","live":false,"muted":false,
     "createdAt":1760335200000,"startedAt":1760335201000,"startedLocal":"08:00:01","endedAt":1760338800000,"endedReason":"stop","failure":null,
     "parts":[
       {"part":1,"file":"audio/part-001.opus","wallStart":1760335201000,"mic":"Built-in Microphone","call":"system","capture":"ok","ended":{"reason":"restart","fileSeconds":1200.5},"pauses":0,"gaps":0,"finalDone":true},
       {"part":2,"file":"audio/part-002.opus","wallStart":1760336500000,"mic":"Built-in Microphone","call":"system","capture":"ok","ended":{"reason":"stop","fileSeconds":2299.5},"pauses":1,"gaps":0,"finalDone":true}
     ],
     "roster":[{"spk":"you","label":"You"},{"spk":"c2","label":"Ana","name":"Ana","namedBy":"human"}],
     "health":[],"lag":null,
     "final":{"state":"done","partsDone":2,"warning":null,"error":null,"model":"qwen3-asr","progress":null},
     "cursor":812,"now":1760339100000}
    """#

    /// `GET /v1/calls/{id}/transcript?format=json`: src/main/api/routes/follow.ts `calls.transcript`.
    static let transcript = #"""
    {"call":"01JCALLB0000000000000000002","state":"ended","live":false,"layer":"best","tz":"Europe/Madrid","zone":"Times are local, Europe/Madrid (GMT+2).","cursor":812,"memoStale":false,"provisional":[],
     "total":3,"omitted":0,"more":0,"offset":0,"nextOffset":null,
     "lines":[
       {"id":"s1","seq":10,"time":"08:00:05","w0":1760335205000,"w1":1760335209000,"part":1,"ch":"mic","spk":"you","speaker":"You","text":"Morning, shall we start with the release?","layer":"final"},
       {"id":"s2","seq":11,"time":"08:00:10","w0":1760335210000,"w1":1760335214000,"part":1,"ch":"call","spk":"c2","speaker":"Ana","text":"Yes, the build went out last night.","heard":"Yes, the bild went out last night.","annotated":"Yes, the build (heard: \"bild\") went out last night.","layer":"final"},
       {"id":"s3","seq":40,"time":"08:21:42","w0":1760336502000,"w1":1760336504500,"part":2,"ch":"call","spk":"c2","speaker":"Ana","text":"Back again after the restart.","layer":"live"}
     ]}
    """#

    /// `GET /v1/calls/{id}/enhanced`: src/main/api/routes/post-call.ts `enhanced.get`.
    static let enhanced = #"""
    {"call":"01JCALLB0000000000000000002","enhanced":{"rev":2,"template":"meeting","coversSeq":800,"by":"app","model":"claude","cites":[],"markdown":"## Summary\n- The build went out.\n- **Next:** the release notes."},
     "revisions":[{"rev":1,"template":"meeting","by":"app","model":"claude"},{"rev":2,"template":"meeting","by":"app","model":"claude"}],"reEnhance":null}
    """#

    /// The same route for a call with no summary yet.
    static let noEnhanced = #"{"call":"01JCALLA0000000000000000001","enhanced":null,"revisions":[],"reEnhance":null}"#

    /// `GET /v1/calls/{id}/notes`: src/main/api/routes/notes.ts `notes.list`, `NoteView` in src/core/log/fold.ts.
    static let notes = #"""
    {"call":"01JCALLB0000000000000000002","notes":[
      {"id":"n1","rev":1,"text":"Ask about the notes","w":1760335300000,"afterSeq":11,"by":"human","author":"human","seq":20},
      {"id":"n2","rev":1,"text":"Fixed: bild -> build","w":1760335400000,"afterSeq":12,"by":"agent:fix","author":"agent","client":"fix","from":"fix","seq":21}
    ]}
    """#

    /// `GET /v1/dictations`: src/main/api/routes/dictation.ts `dictations.list` and `dictationBody`.
    static let dictations = #"""
    {"items":[
      {"id":"dct_02","at":1760340000000,"state":"inserted","by":"key","app":"com.example.notes","app_name":"Notes","seconds":4.2,"text":"Buy milk and call the plumber.","raw":"buy milk and call the plumber","language":"en",
       "words":[{"w":"buy","s":0.1,"e":0.3,"c":0.98},{"w":"milk","s":0.35,"e":0.6,"c":0.97}],"engine":"fast","model":"parakeet-tdt-0.6b-v3","ms":180,"formatted":true},
      {"id":"dct_01","at":1760330000000,"state":"failed","by":"key","app":null,"app_name":null,"seconds":null,"text":null,"raw":null,"language":null,"words":[],"engine":"best","model":null,"ms":null,"error":"the model is missing"}
    ],"next_cursor":"dct_01"}
    """#

    /// The page after it: the last one.
    static let dictationsLast = #"{"items":[],"next_cursor":null}"#

    /// `GET /v1/jobs` for a read key: src/main/api/routes/jobs.ts `jobs.list`, every key's jobs.
    static let jobs = #"""
    {"jobs":[
      {"id":"job_b","title":"Interview.m4a","status":"done","key_id":"k_desktop","created_at":"2026-10-12T09:00:00.000Z","started_at":"2026-10-12T09:00:01.000Z","finished_at":"2026-10-12T09:03:00.000Z","preset":"best","model":"qwen3-asr","model_source":"preset","priority":0,"interactive":false,"keep_audio":true,"language":"auto","languages":[],"diarize":true,"metadata":null,"links":{"self":"/v1/jobs/job_b","result":"/v1/jobs/job_b/result","events":"/v1/events","audio":"/v1/jobs/job_b/audio"}},
      {"id":"job_a","title":"Standup","status":"done","key_id":"k_phone","created_at":"2026-10-11T08:00:00.000Z","started_at":null,"finished_at":null,"preset":"fast","model":"parakeet-tdt-0.6b-v3","model_source":"preset","priority":0,"interactive":false,"keep_audio":true,"language":"auto","languages":[],"diarize":false,"metadata":{"companion":1,"recording_id":"rec-1","workspace":"Work"},"links":{"self":"/v1/jobs/job_a","result":"/v1/jobs/job_a/result","events":"/v1/events"}}
    ],"cursor":null}
    """#
}

final class HistoryClientTests: XCTestCase {
    static let base = URL(string: "https://akou.example.com")!

    override func setUp() {
        FakeHistoryHTTP.reset([
            "/v1/calls": HistoryFixtures.calls,
            "/v1/calls/01JCALLB0000000000000000002": HistoryFixtures.call,
            "/v1/calls/01JCALLB0000000000000000002/transcript": HistoryFixtures.transcript,
            "/v1/calls/01JCALLB0000000000000000002/enhanced": HistoryFixtures.enhanced,
            "/v1/calls/01JCALLA0000000000000000001/enhanced": HistoryFixtures.noEnhanced,
            "/v1/calls/01JCALLB0000000000000000002/notes": HistoryFixtures.notes,
            "/v1/dictations": HistoryFixtures.dictations,
            "/v1/jobs": HistoryFixtures.jobs,
        ])
    }

    func client(_ key: String = "ak_read") -> HistoryClient {
        HistoryClient(baseURL: Self.base, key: key, session: FakeHistoryHTTP.session())
    }

    func probe(_ key: String) -> ServerProbe {
        ServerProbe(baseURL: Self.base, key: key, session: FakeHistoryHTTP.session())
    }

    func testTheKeysScopesDecideWhatThePhoneMayDo() async throws {
        let jobs = try await probe("ak_jobs").keyInfo()
        XCTAssertTrue(jobs.canUpload)
        XCTAssertFalse(jobs.seesHistory, "a jobs key sees only its own recordings")
        let read = try await probe("ak_read").keyInfo()
        XCTAssertFalse(read.canUpload, "read writes nothing, so it cannot be Record to")
        XCTAssertTrue(read.seesHistory)
        let admin = try await probe("ak_admin").keyInfo()
        XCTAssertTrue(admin.canUpload)
        XCTAssertTrue(admin.seesHistory)
        let both = try await probe("ak_both").keyInfo()
        XCTAssertEqual(both.scopes, ["jobs", "read"])
        XCTAssertTrue(both.canUpload && both.seesHistory)
    }

    func testTheCallsListReadsAkousRows() async throws {
        let calls = try await client().calls()
        XCTAssertEqual(calls.map(\.id), ["01JCALLB0000000000000000002", "01JCALLA0000000000000000001"])
        XCTAssertEqual(calls[0].title, "Weekly sync")
        XCTAssertEqual(calls[0].workspace, "Work")
        XCTAssertEqual(calls[0].parts, 2)
        XCTAssertEqual(calls[0].endedAt, 1760338800000)
        XCTAssertFalse(calls[0].isLive)
        XCTAssertTrue(calls[1].isLive, "a recording call with no end is live")
        let req = try XCTUnwrap(FakeHistoryHTTP.seen.last)
        XCTAssertEqual(req.url?.query, "limit=200")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer ak_read")
    }

    func testACallsDetailHasItsPartsAndTheirTimes() async throws {
        let call = try await client().call("01JCALLB0000000000000000002")
        XCTAssertEqual(call.parts.map(\.part), [1, 2])
        XCTAssertEqual(call.parts[1].wallStart, 1760336500000)
        XCTAssertEqual(call.seconds, 3500)
        XCTAssertEqual(call.final?.state, "done")
        XCTAssertEqual(call.tz, "Europe/Madrid")
    }

    func testTheTranscriptKeepsSpeakersTimesAndParts() async throws {
        let t = try await client().transcript("01JCALLB0000000000000000002")
        XCTAssertEqual(t.lines.map(\.speaker), ["You", "Ana", "Ana"])
        XCTAssertEqual(t.lines.map(\.time), ["08:00:05", "08:00:10", "08:21:42"])
        XCTAssertEqual(t.lines.map(\.part), [1, 1, 2])
        XCTAssertEqual(t.lines[1].text, "Yes, the build went out last night.", "the corrected text, not the annotated one")
        XCTAssertEqual(t.zone, "Times are local, Europe/Madrid (GMT+2).")
        // The best layer, answered at once: no wait for a second pass on a phone.
        let q = try XCTUnwrap(FakeHistoryHTTP.seen.last?.url?.query)
        XCTAssertTrue(q.contains("layer=best") && q.contains("review=skip") && q.contains("format=json"), q)
    }

    func testTheSummaryIsThereOrNil() async throws {
        let s = try await client().summary("01JCALLB0000000000000000002")
        XCTAssertEqual(s?.rev, 2)
        XCTAssertTrue(s?.markdown.hasPrefix("## Summary") ?? false)
        let none = try await client().summary("01JCALLA0000000000000000001")
        XCTAssertNil(none)
    }

    func testTheNotepadLines() async throws {
        let notes = try await client().notes("01JCALLB0000000000000000002")
        XCTAssertEqual(notes.map(\.text), ["Ask about the notes", "Fixed: bild -> build"])
        XCTAssertEqual(notes.map(\.author), ["human", "agent"])
    }

    func testDictationsPageOnWithTheirCursor() async throws {
        let first = try await client().dictations()
        XCTAssertEqual(first.items.map(\.id), ["dct_02", "dct_01"])
        XCTAssertEqual(first.nextCursor, "dct_01")
        XCTAssertEqual(first.items[0].appName, "Notes")
        XCTAssertEqual(first.items[0].text, "Buy milk and call the plumber.")
        XCTAssertEqual(first.items[0].words.first?.w, "buy")
        XCTAssertNil(first.items[1].text)
        XCTAssertEqual(first.items[1].error, "the model is missing")
        FakeHistoryHTTP.reset(["/v1/dictations": HistoryFixtures.dictationsLast])
        let next = try await client().dictations(cursor: "dct_01")
        XCTAssertNil(next.nextCursor)
        XCTAssertEqual(FakeHistoryHTTP.seen.last?.url?.query, "limit=50&cursor=dct_01")
    }

    func testEveryJobWithAnotherKeysToo() async throws {
        let page = try await client().jobs()
        XCTAssertEqual(page.jobs.map(\.id), ["job_b", "job_a"])
        XCTAssertNil(page.jobs[0].metadata, "another client's job carries no companion metadata")
        XCTAssertEqual(page.jobs[1].metadata?.workspace, "Work")
        XCTAssertNil(page.cursor)
    }

    func testAJobsKeyCannotSeeHistory() async throws {
        do {
            _ = try await client("ak_jobs").calls()
            XCTFail("a jobs key read the calls")
        } catch let f as HistoryClient.Failure {
            XCTAssertEqual(f, .cannotSeeHistory(status: 403))
        }
        do {
            _ = try await client("ak_revoked").dictations()
            XCTFail("an unknown key read the dictations")
        } catch let f as HistoryClient.Failure {
            XCTAssertEqual(f, .cannotSeeHistory(status: 401))
        }
    }

    func testAnotherRefusalKeepsAkousCode() async throws {
        do {
            _ = try await client().call("01JNOSUCHCALL")
            XCTFail("a missing call answered")
        } catch let f as HistoryClient.Failure {
            XCTAssertEqual(f, .status(404, code: "not_found", message: "no such call"))
        }
    }

    func testAnIdNeverAddsAPathSegment() async throws {
        _ = try? await client().call("../keys")
        XCTAssertEqual(FakeHistoryHTTP.seen.last?.url?.path, "/v1/calls/..keys")
        XCTAssertEqual(HistoryClient.callAudioPath("a/b", part: 1), "/v1/calls/ab/audio/1")
    }

    func testTheKeyIsNeverInAnAudioURL() throws {
        let r = try client().audioRequest(path: HistoryClient.callAudioPath("01JCALLB0000000000000000002", part: 2))
        XCTAssertEqual(r.url?.absoluteString, "https://akou.example.com/v1/calls/01JCALLB0000000000000000002/audio/2")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer ak_read")
    }
}
