import Foundation
import SwiftData

/// Full-fidelity JSON backup of activities (with GPS tracks) and segments.
/// Segment efforts are recomputed on restore by re-matching each activity.
enum BackupManager {

    struct ActivityDTO: Codable {
        var type: String
        var title: String
        var startTime: TimeInterval
        var endTime: TimeInterval
        var movingTime: TimeInterval
        var distance: Double
        var avgSpeed: Double
        var maxSpeed: Double
        var elevationGain: Double
        var calories: Int
        var avgHeartRate: Int?
        var maxHeartRate: Int?
        var feel: String?
        var points: [TrackPoint]
    }

    struct SegmentDTO: Codable {
        var name: String
        var activityType: String
        var startLat: Double
        var startLon: Double
        var endLat: Double
        var endLon: Double
        var distance: Double
        var createdAt: TimeInterval
    }

    struct BackupFile: Codable {
        var version: Int
        var app: String
        var activities: [ActivityDTO]
        var segments: [SegmentDTO]
    }

    /// Writes the backup to a temp file and returns its URL for ShareLink.
    static func exportURL(context: ModelContext) -> URL? {
        guard
            let activities = try? context.fetch(FetchDescriptor<Activity>()),
            let segments = try? context.fetch(FetchDescriptor<Segment>())
        else { return nil }

        let file = BackupFile(
            version: 1,
            app: "ostrava",
            activities: activities.map { activity in
                ActivityDTO(
                    type: activity.typeRaw,
                    title: activity.title,
                    startTime: activity.startTime.timeIntervalSince1970,
                    endTime: activity.endTime.timeIntervalSince1970,
                    movingTime: activity.movingTime,
                    distance: activity.distance,
                    avgSpeed: activity.avgSpeed,
                    maxSpeed: activity.maxSpeed,
                    elevationGain: activity.elevationGain,
                    calories: activity.calories,
                    avgHeartRate: activity.avgHeartRate,
                    maxHeartRate: activity.maxHeartRate,
                    feel: activity.feel,
                    points: activity.points
                )
            },
            segments: segments.map { segment in
                SegmentDTO(
                    name: segment.name,
                    activityType: segment.activityTypeRaw,
                    startLat: segment.startLat,
                    startLon: segment.startLon,
                    endLat: segment.endLat,
                    endLon: segment.endLon,
                    distance: segment.distance,
                    createdAt: segment.createdAt.timeIntervalSince1970
                )
            }
        )
        guard let data = try? JSONEncoder().encode(file) else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ostrava-backup.json")
        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /// Returns the number of activities imported (duplicates by startTime are skipped).
    static func restore(from url: URL, context: ModelContext) -> Int {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard
            let data = try? Data(contentsOf: url),
            let file = try? JSONDecoder().decode(BackupFile.self, from: data)
        else { return 0 }

        let existingActivities = (try? context.fetch(FetchDescriptor<Activity>())) ?? []
        let existingStartTimes = Set(existingActivities.map { $0.startTime.timeIntervalSince1970 })
        var segments = (try? context.fetch(FetchDescriptor<Segment>())) ?? []

        for dto in file.segments {
            let createdAt = Date(timeIntervalSince1970: dto.createdAt)
            if !segments.contains(where: { $0.createdAt == createdAt && $0.name == dto.name }) {
                let segment = Segment(
                    name: dto.name,
                    activityType: ActivityType(rawValue: dto.activityType) ?? .run,
                    startLat: dto.startLat,
                    startLon: dto.startLon,
                    endLat: dto.endLat,
                    endLon: dto.endLon,
                    distance: dto.distance,
                    createdAt: createdAt
                )
                context.insert(segment)
                segments.append(segment)
            }
        }

        var imported = 0
        for dto in file.activities where !existingStartTimes.contains(dto.startTime) {
            let activity = Activity(
                type: ActivityType(rawValue: dto.type) ?? .run,
                title: dto.title,
                startTime: Date(timeIntervalSince1970: dto.startTime),
                endTime: Date(timeIntervalSince1970: dto.endTime),
                movingTime: dto.movingTime,
                distance: dto.distance,
                avgSpeed: dto.avgSpeed,
                maxSpeed: dto.maxSpeed,
                elevationGain: dto.elevationGain,
                calories: dto.calories,
                avgHeartRate: dto.avgHeartRate,
                maxHeartRate: dto.maxHeartRate,
                feel: dto.feel,
                points: dto.points
            )
            context.insert(activity)
            recordEfforts(for: activity, segments: segments, context: context)
            imported += 1
        }
        try? context.save()
        return imported
    }

    /// Parses a GPX file into an activity. Returns true when something was imported.
    static func importGpx(from url: URL, context: ModelContext) -> Bool {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let parsed = GpxParser.parse(url: url), parsed.points.count >= 2 else { return false }

        let stats = computeActivityStats(points: parsed.points)
        guard stats.distance >= 10 else { return false }

        let startTime = parsed.points[0].time
        let existing = (try? context.fetch(FetchDescriptor<Activity>())) ?? []
        guard !existing.contains(where: { $0.startTime.timeIntervalSince1970 == startTime }) else {
            return false
        }

        let weightKg = UserDefaults.standard.object(forKey: SettingsKeys.weightKg) as? Double ?? 70.0
        let type = parsed.activityType
        let activity = Activity(
            type: type,
            title: parsed.title.isEmpty ? "Imported \(type.label)" : parsed.title,
            startTime: Date(timeIntervalSince1970: startTime),
            endTime: Date(timeIntervalSince1970: parsed.points[parsed.points.count - 1].time),
            movingTime: stats.movingTime,
            distance: stats.distance,
            avgSpeed: stats.avgSpeed,
            maxSpeed: stats.maxSpeed,
            elevationGain: stats.elevationGain,
            calories: estimateCalories(
                type: type, avgSpeedMps: stats.avgSpeed, movingTime: stats.movingTime, weightKg: weightKg
            ),
            points: parsed.points
        )
        context.insert(activity)
        let segments = (try? context.fetch(FetchDescriptor<Segment>())) ?? []
        recordEfforts(for: activity, segments: segments, context: context)
        try? context.save()
        return true
    }

    private static func recordEfforts(for activity: Activity, segments: [Segment], context: ModelContext) {
        let trackPoints = activity.points
        for segment in segments where segment.activityTypeRaw == activity.typeRaw {
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
}
