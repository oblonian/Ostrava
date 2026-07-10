import SwiftData
import SwiftUI

struct FeedView: View {
    @Query(sort: \Activity.startTime, order: .reverse) private var activities: [Activity]
    @AppStorage(SettingsKeys.userName) private var userName = "Athlete"
    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false
    @AppStorage(SettingsKeys.weeklyGoalKm) private var weeklyGoalKm = 25.0

    var body: some View {
        NavigationStack {
            List {
                Section {
                    weeklySummary
                }
                if activities.isEmpty {
                    Section {
                        VStack(spacing: 8) {
                            Text("No activities yet").font(.headline)
                            Text("Head outside and record your first run, ride, walk or hike.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                    }
                }
                ForEach(activities) { activity in
                    NavigationLink(value: activity.uid) {
                        ActivityRow(activity: activity, imperial: imperial)
                    }
                }
            }
            .navigationTitle("Hi, \(userName)")
            .navigationDestination(for: UUID.self) { uid in
                if let activity = activities.first(where: { $0.uid == uid }) {
                    DetailView(activity: activity)
                }
            }
        }
    }

    private var thisWeek: [Activity] {
        var calendar = Calendar(identifier: .iso8601)
        calendar.firstWeekday = 2
        guard let weekStart = calendar.dateInterval(of: .weekOfYear, for: Date())?.start else {
            return []
        }
        return activities.filter { $0.startTime >= weekStart }
    }

    private var weeklySummary: some View {
        let week = thisWeek
        let distance = week.reduce(0.0) { $0 + $1.distance }
        let time = week.reduce(0.0) { $0 + $1.movingTime }
        let climb = week.reduce(0.0) { $0 + $1.elevationGain }
        let progress = weeklyGoalKm > 0 ? min(distance / 1000 / weeklyGoalKm, 1.0) : 0

        return VStack(alignment: .leading, spacing: 12) {
            Text("This week").font(.headline)
            HStack {
                StatTile(label: "Distance", value: formatDistanceShort(distance, imperial: imperial))
                Spacer()
                StatTile(label: "Time", value: formatDuration(time))
                Spacer()
                StatTile(label: "Activities", value: "\(week.count)")
                Spacer()
                StatTile(label: "Climb", value: formatElevation(climb, imperial: imperial))
            }
            if weeklyGoalKm > 0 {
                ProgressView(value: progress)
                Text(
                    "Goal: \(formatDistanceShort(distance, imperial: imperial)) of "
                        + formatDistanceShort(weeklyGoalKm * 1000, imperial: imperial)
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ActivityRow: View {
    let activity: Activity
    let imperial: Bool

    @State private var thumbnailPoints: [TrackPoint] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                Image(systemName: activity.type.systemImage)
                    .font(.title3)
                    .foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(activity.title).font(.headline)
                        if let feelLabel = Feel.label(activity.feel) {
                            Text(String(feelLabel.prefix(2)))
                        }
                    }
                    Text(formatDateTime(activity.startTime))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if thumbnailPoints.count >= 2 {
                    RouteThumbnail(points: thumbnailPoints)
                        .frame(width: 64, height: 64)
                }
            }
            HStack {
                StatTile(label: "Distance", value: formatDistance(activity.distance, imperial: imperial))
                Spacer()
                StatTile(label: "Time", value: formatDuration(activity.movingTime))
                Spacer()
                if activity.type.usesPace {
                    StatTile(label: "Pace", value: formatPace(speedMps: activity.avgSpeed, imperial: imperial))
                } else {
                    StatTile(label: "Speed", value: formatSpeed(activity.avgSpeed, imperial: imperial))
                }
            }
        }
        .padding(.vertical, 4)
        .task(id: activity.uid) {
            thumbnailPoints = downsampled(activity.points)
        }
    }
}
