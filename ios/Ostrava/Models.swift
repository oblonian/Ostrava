import Foundation
import SwiftData

enum ActivityType: String, CaseIterable, Codable, Identifiable {
    case run = "RUN"
    case ride = "RIDE"
    case walk = "WALK"
    case hike = "HIKE"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .run: return "Run"
        case .ride: return "Ride"
        case .walk: return "Walk"
        case .hike: return "Hike"
        }
    }

    var systemImage: String {
        switch self {
        case .run: return "figure.run"
        case .ride: return "bicycle"
        case .walk: return "figure.walk"
        case .hike: return "figure.hiking"
        }
    }

    /// Foot sports read pace (min/km); rides read speed.
    var usesPace: Bool { self != .ride }
}

/// One recorded GPS fix. `segment` increments across pauses so routes draw as separate lines.
struct TrackPoint: Codable, Equatable {
    var latitude: Double
    var longitude: Double
    var altitude: Double
    var time: TimeInterval // epoch seconds
    var speed: Double      // m/s
    var segment: Int
}

@Model
final class Activity {
    var uid: UUID = UUID()
    var typeRaw: String = ActivityType.run.rawValue
    var title: String = ""
    var startTime: Date = Date.distantPast
    var endTime: Date = Date.distantPast
    var movingTime: TimeInterval = 0
    var distance: Double = 0        // meters
    var avgSpeed: Double = 0        // m/s
    var maxSpeed: Double = 0
    var elevationGain: Double = 0   // meters
    var calories: Int = 0
    var avgHeartRate: Int?
    var maxHeartRate: Int?
    var feel: String?
    @Attribute(.externalStorage) var pointsData: Data = Data()

    init(
        type: ActivityType,
        title: String,
        startTime: Date,
        endTime: Date,
        movingTime: TimeInterval,
        distance: Double,
        avgSpeed: Double,
        maxSpeed: Double,
        elevationGain: Double,
        calories: Int,
        avgHeartRate: Int? = nil,
        maxHeartRate: Int? = nil,
        feel: String? = nil,
        points: [TrackPoint]
    ) {
        self.uid = UUID()
        self.typeRaw = type.rawValue
        self.title = title
        self.startTime = startTime
        self.endTime = endTime
        self.movingTime = movingTime
        self.distance = distance
        self.avgSpeed = avgSpeed
        self.maxSpeed = maxSpeed
        self.elevationGain = elevationGain
        self.calories = calories
        self.avgHeartRate = avgHeartRate
        self.maxHeartRate = maxHeartRate
        self.feel = feel
        self.pointsData = (try? JSONEncoder().encode(points)) ?? Data()
    }

    var type: ActivityType { ActivityType(rawValue: typeRaw) ?? .run }

    var points: [TrackPoint] {
        (try? JSONDecoder().decode([TrackPoint].self, from: pointsData)) ?? []
    }
}

@Model
final class Segment {
    var uid: UUID = UUID()
    var name: String = ""
    var activityTypeRaw: String = ActivityType.run.rawValue
    var startLat: Double = 0
    var startLon: Double = 0
    var endLat: Double = 0
    var endLon: Double = 0
    var distance: Double = 0
    var createdAt: Date = Date.distantPast

    init(
        name: String,
        activityType: ActivityType,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        distance: Double,
        createdAt: Date
    ) {
        self.uid = UUID()
        self.name = name
        self.activityTypeRaw = activityType.rawValue
        self.startLat = startLat
        self.startLon = startLon
        self.endLat = endLat
        self.endLon = endLon
        self.distance = distance
        self.createdAt = createdAt
    }
}

@Model
final class SegmentEffort {
    var uid: UUID = UUID()
    var segmentUid: UUID = UUID()
    var activityUid: UUID = UUID()
    var duration: TimeInterval = 0
    var startTime: Date = Date.distantPast

    init(segmentUid: UUID, activityUid: UUID, duration: TimeInterval, startTime: Date) {
        self.uid = UUID()
        self.segmentUid = segmentUid
        self.activityUid = activityUid
        self.duration = duration
        self.startTime = startTime
    }
}

enum Feel {
    static let easy = "EASY"
    static let moderate = "MODERATE"
    static let hard = "HARD"

    static let options: [(value: String, label: String)] = [
        (easy, "😌 Easy"),
        (moderate, "😤 Moderate"),
        (hard, "🥵 Hard"),
    ]

    static func label(_ value: String?) -> String? {
        options.first { $0.value == value }?.label
    }
}

/// Structured interval workout, all durations in seconds.
struct IntervalConfig: Codable, Equatable {
    var warmupSec: Int
    var workSec: Int
    var restSec: Int
    var repeats: Int
    var cooldownSec: Int

    /// Ordered (name, durationSec) phases; no rest after the final work bout.
    func phases() -> [(name: String, duration: Int)] {
        var result: [(String, Int)] = []
        if warmupSec > 0 { result.append(("Warm-up", warmupSec)) }
        for i in 1...max(repeats, 1) {
            result.append(("Work \(i)/\(repeats)", workSec))
            if restSec > 0 && i < repeats { result.append(("Rest \(i)/\(repeats)", restSec)) }
        }
        if cooldownSec > 0 { result.append(("Cool-down", cooldownSec)) }
        return result
    }

    var summary: String {
        let work = formatDuration(TimeInterval(workSec))
        return restSec > 0 ? "\(repeats) × \(work) / \(formatDuration(TimeInterval(restSec)))"
            : "\(repeats) × \(work)"
    }
}

struct IntervalPhase: Equatable {
    var name: String
    var index: Int
    var total: Int
    var remainingSec: Int
}

struct HeartRateZone: Identifiable {
    var index: Int
    var name: String
    var fromBpm: Int
    var toBpm: Int
    var id: Int { index }
}

func heartRateZones(maxHr: Int) -> [HeartRateZone] {
    let bounds = [0.50, 0.60, 0.70, 0.80, 0.90, 1.0]
    let names = ["Recovery", "Endurance", "Tempo", "Threshold", "VO2 Max"]
    return names.enumerated().map { i, name in
        HeartRateZone(
            index: i + 1,
            name: name,
            fromBpm: Int(bounds[i] * Double(maxHr)),
            toBpm: Int(bounds[i + 1] * Double(maxHr))
        )
    }
}

/// UserDefaults keys shared between SwiftUI @AppStorage and the tracker.
enum SettingsKeys {
    static let userName = "userName"
    static let imperialUnits = "imperialUnits"
    static let weightKg = "weightKg"
    static let autoPause = "autoPause"
    static let weeklyGoalKm = "weeklyGoalKm"
    static let maxHeartRate = "maxHeartRate"
    static let audioCues = "audioCues"
    static let keepScreenOn = "keepScreenOn"
    static let haptics = "haptics"
    static let onboardingDone = "onboardingDone"
}
