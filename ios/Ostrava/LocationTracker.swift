import AVFoundation
import CoreLocation
import Foundation
import SwiftData
import UIKit

/// Records an activity from CoreLocation fixes: distance, moving time, auto-pause,
/// voice cues, haptics, interval phases and heart-rate sampling.
/// Process-wide singleton shared by the record screen and the app entry point.
final class LocationTracker: NSObject, ObservableObject, CLLocationManagerDelegate {

    static let shared = LocationTracker()

    enum Status { case idle, tracking, paused, autoPaused }

    @Published var status: Status = .idle
    @Published var type: ActivityType = .run
    @Published var distance: Double = 0
    @Published var movingTime: TimeInterval = 0
    @Published var currentSpeed: Double = 0
    @Published var maxSpeed: Double = 0
    @Published var elevationGain: Double = 0
    @Published var points: [TrackPoint] = []
    @Published var lastCoordinate: CLLocationCoordinate2D?
    @Published var accuracy: Double?
    @Published var heartRate: Int?
    @Published var intervalPhase: IntervalPhase?
    @Published var lastSavedActivityUid: UUID?

    var container: ModelContainer?

    private let manager = CLLocationManager()
    private let synthesizer = AVSpeechSynthesizer()
    private var timer: Timer?
    private var lastTick = Date()
    private var startWallTime = Date()
    private var currentSegment = 0
    private var lastAcceptedPoint: TrackPoint?
    private var altitudeBaseline: Double?
    private var lowSpeedSince: Date?
    private var lastAnnouncedSplit = 0
    private var lastSplitTime: TimeInterval = 0
    private var hrSamples: [Int] = []
    private var intervalPhases: [(name: String, duration: Int)] = []
    private var lastPhaseIndex = -1
    private var workoutCompleteAnnounced = false

    // Settings captured at start
    private var autoPauseEnabled = true
    private var imperialUnits = false
    private var audioCues = true
    private var haptics = true
    private var weightKg = 70.0

    var isActive: Bool { status != .idle }
    var avgSpeed: Double { movingTime > 0 ? distance / movingTime : 0 }

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.activityType = .fitness
        manager.distanceFilter = kCLDistanceFilterNone
    }

    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    func start(type: ActivityType, intervals: IntervalConfig?) {
        guard !isActive else { return }
        let defaults = UserDefaults.standard
        autoPauseEnabled = defaults.object(forKey: SettingsKeys.autoPause) as? Bool ?? true
        imperialUnits = defaults.bool(forKey: SettingsKeys.imperialUnits)
        audioCues = defaults.object(forKey: SettingsKeys.audioCues) as? Bool ?? true
        haptics = defaults.object(forKey: SettingsKeys.haptics) as? Bool ?? true
        weightKg = defaults.object(forKey: SettingsKeys.weightKg) as? Double ?? 70.0

        self.type = type
        status = .tracking
        distance = 0
        movingTime = 0
        currentSpeed = 0
        maxSpeed = 0
        elevationGain = 0
        points = []
        heartRate = nil
        intervalPhase = nil
        lastSavedActivityUid = nil
        startWallTime = Date()
        lastTick = Date()
        currentSegment = 0
        lastAcceptedPoint = nil
        altitudeBaseline = nil
        lowSpeedSince = nil
        lastAnnouncedSplit = 0
        lastSplitTime = 0
        hrSamples = []
        intervalPhases = intervals?.phases() ?? []
        lastPhaseIndex = -1
        workoutCompleteAnnounced = false

        try? AVAudioSession.sharedInstance().setCategory(
            .playback, options: [.duckOthers, .mixWithOthers]
        )
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()

        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.tick()
        }
        vibrate(.success)
        speak("\(type.label) started")
    }

    func pause(manual: Bool) {
        guard status == .tracking else { return }
        status = manual ? .paused : .autoPaused
        vibrate(.warning)
        if !manual { speak("Auto paused") }
    }

    func resume() {
        guard status == .paused || status == .autoPaused else { return }
        let wasAuto = status == .autoPaused
        currentSegment += 1
        lastAcceptedPoint = nil
        lowSpeedSince = nil
        lastTick = Date()
        status = .tracking
        vibrate(.success)
        if wasAuto { speak("Resumed") }
    }

    func finish(title: String, feel: String?) {
        guard isActive, let container else { return }
        stopUpdates()

        guard points.count >= 2, distance >= 10 else {
            reset()
            return
        }

        let activity = Activity(
            type: type,
            title: title.isEmpty ? defaultActivityTitle(type: type, start: startWallTime) : title,
            startTime: startWallTime,
            endTime: Date(),
            movingTime: movingTime,
            distance: distance,
            avgSpeed: avgSpeed,
            maxSpeed: maxSpeed,
            elevationGain: elevationGain,
            calories: estimateCalories(
                type: type, avgSpeedMps: avgSpeed, movingTime: movingTime, weightKg: weightKg
            ),
            avgHeartRate: hrSamples.isEmpty ? nil : hrSamples.reduce(0, +) / hrSamples.count,
            maxHeartRate: hrSamples.max(),
            feel: feel,
            points: points
        )
        // Save on the main context: @Query-driven views don't reliably see
        // changes saved through a sibling ModelContext on iOS 17.
        MainActor.assumeIsolated {
            let context = container.mainContext
            context.insert(activity)
            recordSegmentEfforts(for: activity, in: context)
            try? context.save()
        }
        vibrate(.success)
        lastSavedActivityUid = activity.uid
        reset()
    }

    func discard() {
        stopUpdates()
        reset()
    }

    /// Times the activity against every saved segment of the same sport.
    private func recordSegmentEfforts(for activity: Activity, in context: ModelContext) {
        let typeRaw = activity.typeRaw
        let descriptor = FetchDescriptor<Segment>(
            predicate: #Predicate { $0.activityTypeRaw == typeRaw }
        )
        guard let segments = try? context.fetch(descriptor) else { return }
        let trackPoints = activity.points
        for segment in segments {
            if let match = matchSegment(
                startLat: segment.startLat, startLon: segment.startLon,
                endLat: segment.endLat, endLon: segment.endLon,
                segmentDistance: segment.distance,
                points: trackPoints
            ) {
                context.insert(
                    SegmentEffort(
                        segmentUid: segment.uid,
                        activityUid: activity.uid,
                        duration: match.duration,
                        startTime: Date(timeIntervalSince1970: match.startTime)
                    )
                )
            }
        }
    }

    private func stopUpdates() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        timer?.invalidate()
        timer = nil
    }

    private func reset() {
        status = .idle
        intervalPhase = nil
        points = []
        distance = 0
        movingTime = 0
        currentSpeed = 0
        elevationGain = 0
        accuracy = nil
    }

    private func tick() {
        let now = Date()
        if status == .tracking {
            movingTime += now.timeIntervalSince(lastTick)
            if let bpm = HeartRateMonitor.shared.bpm, bpm > 30 { hrSamples.append(bpm) }
            heartRate = HeartRateMonitor.shared.bpm
            updateIntervalPhase()
        } else if isActive {
            heartRate = HeartRateMonitor.shared.bpm
        }
        lastTick = now
    }

    private func updateIntervalPhase() {
        guard !intervalPhases.isEmpty else {
            intervalPhase = nil
            return
        }
        let elapsed = Int(movingTime)
        var boundary = 0
        for (index, phase) in intervalPhases.enumerated() {
            boundary += phase.duration
            if elapsed < boundary {
                if index != lastPhaseIndex {
                    lastPhaseIndex = index
                    speak(String(phase.name.split(separator: "/").first ?? ""))
                    vibrate(.warning)
                }
                intervalPhase = IntervalPhase(
                    name: phase.name,
                    index: index + 1,
                    total: intervalPhases.count,
                    remainingSec: boundary - elapsed
                )
                return
            }
        }
        if !workoutCompleteAnnounced {
            workoutCompleteAnnounced = true
            speak("Workout complete. Great job.")
            vibrate(.success)
        }
        intervalPhase = nil
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, isActive else { return }

        let fix = TrackPoint(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            altitude: location.altitude,
            time: location.timestamp.timeIntervalSince1970,
            speed: max(location.speed, 0),
            segment: currentSegment
        )
        lastCoordinate = location.coordinate
        currentSpeed = max(location.speed, 0)
        accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil

        let accuracyOk = location.horizontalAccuracy >= 0 && location.horizontalAccuracy <= 25

        if status == .tracking && accuracyOk {
            if let last = lastAcceptedPoint {
                let displacement = distance(from: last, to: fix)
                if displacement >= 2 {
                    elevationGain += elevationGainDelta(newAltitude: fix.altitude)
                    lastAcceptedPoint = fix
                    points.append(fix)
                    distance += displacement
                    maxSpeed = max(maxSpeed, fix.speed)
                    announceSplitIfCrossed()
                }
            } else {
                lastAcceptedPoint = fix
                altitudeBaseline = fix.altitude
                points.append(fix)
            }
        }
        handleAutoPause(speed: fix.speed)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {}

    private func distance(from a: TrackPoint, to b: TrackPoint) -> Double {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    /// Hysteresis filter so altitude noise doesn't inflate climb.
    private func elevationGainDelta(newAltitude: Double) -> Double {
        let baseline = altitudeBaseline ?? newAltitude
        if newAltitude - baseline >= 2 {
            altitudeBaseline = newAltitude
            return newAltitude - baseline
        }
        if newAltitude < baseline {
            altitudeBaseline = newAltitude
        }
        return 0
    }

    private func handleAutoPause(speed: Double) {
        guard autoPauseEnabled else { return }
        let isRide = type == .ride
        let pauseBelow = isRide ? 0.8 : 0.4
        let resumeAbove = isRide ? 1.6 : 0.9

        switch status {
        case .tracking:
            if speed < pauseBelow {
                if let since = lowSpeedSince {
                    if Date().timeIntervalSince(since) >= 5 {
                        pause(manual: false)
                        lowSpeedSince = nil
                    }
                } else {
                    lowSpeedSince = Date()
                }
            } else {
                lowSpeedSince = nil
            }
        case .autoPaused:
            if speed > resumeAbove { resume() }
        default:
            break
        }
    }

    private func announceSplitIfCrossed() {
        guard audioCues else { return }
        let splitLength = imperialUnits ? metersPerMile : 1000.0
        let completed = Int(distance / splitLength)
        guard completed > lastAnnouncedSplit else { return }
        lastAnnouncedSplit = completed
        let lap = movingTime - lastSplitTime
        lastSplitTime = movingTime
        let unit = imperialUnits ? "mile" : "kilometer"
        let plural = completed == 1 ? unit : "\(unit)s"
        speak(
            "\(completed) \(plural). Total time \(spokenDuration(movingTime)). "
                + "Last \(unit) \(spokenDuration(lap))."
        )
    }

    private func speak(_ text: String) {
        guard audioCues else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
        synthesizer.speak(utterance)
    }

    private func vibrate(_ kind: UINotificationFeedbackGenerator.FeedbackType) {
        guard haptics else { return }
        UINotificationFeedbackGenerator().notificationOccurred(kind)
    }
}
