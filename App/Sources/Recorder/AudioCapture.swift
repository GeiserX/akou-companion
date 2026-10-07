// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation

/// The microphone: an `AVAudioEngine` input tap converted to 16 kHz mono Float, handed to `sink`
/// on the audio thread.
///
/// The session is `.playAndRecord` with mode `.spokenAudio` and Bluetooth input allowed. With the
/// `audio` background mode in Info.plist it keeps recording while the phone is locked. A phone call
/// or Siri stops the engine; `onInterruption` tells the controller, which pauses and resumes into
/// the same file. A route change (headphones in or out, a Bluetooth headset) restarts the tap on
/// the new input without stopping the recording.
@MainActor
final class AudioCapture {
    enum Failure: Error {
        case noInput
    }

    /// What every akou engine takes.
    nonisolated static let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!

    /// The system interrupted (`began` true) or gave the microphone back (`began` false, with
    /// whether the system suggests resuming).
    var onInterruption: ((_ began: Bool, _ shouldResume: Bool) -> Void)?

    private var engine = AVAudioEngine()
    private let sink: @Sendable ([Float]) -> Void
    private var running = false
    private var observers: [NSObjectProtocol] = []

    init(sink: @escaping @Sendable ([Float]) -> Void) {
        self.sink = sink
    }

    func start() throws {
        try Self.activateSession()
        try installTap()
        engine.prepare()
        try engine.start()
        running = true
        observe()
    }

    func pause() {
        engine.pause()
        running = false
    }

    func resume() throws {
        try AVAudioSession.sharedInstance().setActive(true)
        // The input may have changed while paused (a call can move it to the earpiece route).
        try installTap()
        try engine.start()
        running = true
    }

    func stop() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private static func activateSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.allowBluetoothHFP])
        try session.setActive(true)
    }

    private func installTap() throws {
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0,
              let converter = AVAudioConverter(from: format, to: Self.target) else { throw Failure.noInput }
        converter.downmix = true
        let box = ConverterBox(converter)
        let sink = self.sink
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            if let samples = box.convert(buffer) { sink(samples) }
        }
    }

    private func observe() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let info = note.userInfo ?? [:]
            let type = (info[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            let options = AVAudioSession.InterruptionOptions(rawValue: info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            MainActor.assumeIsolated {
                guard let self, let type else { return }
                switch type {
                case .began:
                    self.running = false
                    self.onInterruption?(true, false)
                case .ended:
                    self.onInterruption?(false, options.contains(.shouldResume))
                @unknown default:
                    break
                }
            }
        })
        // A new input route or format stops the engine; carry on on the new input.
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.running else { return }
                do {
                    try self.installTap()
                    try self.engine.start()
                } catch {
                    self.running = false
                    self.onInterruption?(true, false)
                }
            }
        })
        // The media server restarted: every audio object is gone, so build a new engine.
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let wasRunning = self.running
                self.engine = AVAudioEngine()
                self.running = false
                guard wasRunning else { return }
                do {
                    try Self.activateSession()
                    try self.resume()
                } catch {
                    self.onInterruption?(true, false)
                }
            }
        })
    }
}

/// One converter per tap, used only from that tap's audio thread.
private final class ConverterBox: @unchecked Sendable {
    private let converter: AVAudioConverter

    init(_ converter: AVAudioConverter) {
        self.converter = converter
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> [Float]? {
        let ratio = AudioCapture.target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: AudioCapture.target, frameCapacity: capacity) else { return nil }
        let given = GivenFlag()
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, outStatus in
            if given.done {
                outStatus.pointee = .noDataNow
                return nil
            }
            given.done = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, out.frameLength > 0, let channel = out.floatChannelData?[0] else { return nil }
        return Array(UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
    }
}

private final class GivenFlag: @unchecked Sendable {
    var done = false
}
