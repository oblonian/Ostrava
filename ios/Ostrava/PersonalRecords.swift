import Foundation
import SwiftData

/// An activity's best effort with its all-time rank for that sport and distance.
struct RankedEffort: Identifiable {
    var label: String
    var distance: Double
    var duration: TimeInterval
    /// 1 = personal record.
    var rank: Int
    var id: String { label }
}

/// Caches each activity's best efforts so all-time records can be ranked without
/// re-scanning every GPS track.
enum PersonalRecords {

    /// Bump when the benchmark distances change so cached efforts are recomputed.
    static let effortsVersion = 1

    /// Recomputes and stores the best efforts for one activity. The caller saves the context.
    static func storeEfforts(for activity: Activity, in context: ModelContext) {
        let activityUid = activity.uid
        let existing = FetchDescriptor<BestEffortRecord>(
            predicate: #Predicate { $0.activityUid == activityUid }
        )
        for record in (try? context.fetch(existing)) ?? [] {
            context.delete(record)
        }
        for effort in bestEffortsFor(type: activity.type, points: activity.points) {
            context.insert(
                BestEffortRecord(
                    activityUid: activity.uid,
                    activityType: activity.type,
                    label: effort.label,
                    distance: effort.distance,
                    duration: effort.duration,
                    achievedAt: activity.startTime
                )
            )
        }
        activity.effortsVersion = effortsVersion
    }

    /// Computes efforts for activities recorded before the cache existed.
    static func backfill(context: ModelContext) {
        guard let activities = try? context.fetch(FetchDescriptor<Activity>()) else { return }
        let stale = activities.filter { ($0.effortsVersion ?? 0) < effortsVersion }
        guard !stale.isEmpty else { return }
        for activity in stale {
            storeEfforts(for: activity, in: context)
        }
        try? context.save()
    }

    static func deleteEfforts(forActivity activityUid: UUID, in context: ModelContext) {
        let descriptor = FetchDescriptor<BestEffortRecord>(
            predicate: #Predicate { $0.activityUid == activityUid }
        )
        for record in (try? context.fetch(descriptor)) ?? [] {
            context.delete(record)
        }
    }

    /// The activity's efforts, each ranked against every effort of the same sport and distance.
    static func rankedEfforts(for activity: Activity, in context: ModelContext) -> [RankedEffort] {
        if (activity.effortsVersion ?? 0) < effortsVersion {
            storeEfforts(for: activity, in: context)
            try? context.save()
        }
        let typeRaw = activity.typeRaw
        let descriptor = FetchDescriptor<BestEffortRecord>(
            predicate: #Predicate { $0.activityTypeRaw == typeRaw }
        )
        let sameSport = (try? context.fetch(descriptor)) ?? []
        return sameSport
            .filter { $0.activityUid == activity.uid }
            .map { mine in
                let faster = sameSport.filter { $0.label == mine.label && $0.duration < mine.duration }
                return RankedEffort(
                    label: mine.label,
                    distance: mine.distance,
                    duration: mine.duration,
                    rank: faster.count + 1
                )
            }
            .sorted { $0.distance < $1.distance }
    }
}
