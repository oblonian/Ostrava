import Charts
import SwiftData
import SwiftUI

struct StatsView: View {
    @Query(sort: \Activity.startTime, order: .reverse) private var allActivities: [Activity]
    @Query private var segments: [Segment]
    @Query private var efforts: [SegmentEffort]
    @Environment(\.modelContext) private var context
    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false

    @State private var typeFilter: ActivityType?
    @State private var monthly = false

    private var activities: [Activity] {
        guard let typeFilter else { return allActivities }
        return allActivities.filter { $0.typeRaw == typeFilter.rawValue }
    }

    var body: some View {
        NavigationStack {
            List {
                if allActivities.isEmpty {
                    Section {
                        VStack(spacing: 8) {
                            Text("📈").font(.largeTitle)
                            Text("Record your first activity to see weekly trends, streaks and personal records here.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                    }
                } else {
                    filterSection
                    streakSection
                    chartSection
                    totalsSection
                    if !segments.isEmpty { segmentsSection }
                    recordsSection
                }
            }
            .navigationTitle("Training")
        }
    }

    private var filterSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    Button("All") { typeFilter = nil }
                        .buttonStyle(.bordered)
                        .tint(typeFilter == nil ? .accentColor : .secondary)
                    ForEach(ActivityType.allCases) { type in
                        Button(type.label) { typeFilter = type }
                            .buttonStyle(.bordered)
                            .tint(typeFilter == type ? .accentColor : .secondary)
                    }
                }
            }
        }
    }

    private var streakSection: some View {
        let weeks = Set(allActivities.map { weekIndex(of: $0.startTime) })
        let (current, longest) = weeklyStreaks(
            activeWeekIndices: weeks, currentWeekIndex: weekIndex(of: Date())
        )
        let load = trainingLoad
        return Section {
            HStack {
                VStack(alignment: .leading) {
                    Text("🔥 \(current)-week streak").font(.headline)
                    Text("Longest: \(longest) weeks").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("\(load.thisWeek) min this week").font(.headline)
                    Text(
                        load.warning
                            ? "⚠️ Well above your \(load.avg) min avg"
                            : "4-week avg: \(load.avg) min"
                    )
                    .font(.caption)
                    .foregroundStyle(load.warning ? .red : .secondary)
                }
            }
        }
    }

    private var trainingLoad: (thisWeek: Int, avg: Int, warning: Bool) {
        let currentWeek = weekIndex(of: Date())
        var minutesByWeek: [Int: Int] = [:]
        for activity in allActivities {
            minutesByWeek[weekIndex(of: activity.startTime), default: 0] +=
                Int(activity.movingTime / 60)
        }
        let thisWeek = minutesByWeek[currentWeek] ?? 0
        let avg = (1...4).map { minutesByWeek[currentWeek - $0] ?? 0 }.reduce(0, +) / 4
        return (thisWeek, avg, avg > 0 && Double(thisWeek) > Double(avg) * 1.5)
    }

    private struct Bucket: Identifiable {
        var label: String
        var order: Int
        var distanceKm: Double
        var id: Int { order }
    }

    private var buckets: [Bucket] {
        let calendar = Calendar.current
        let now = Date()
        if monthly {
            return (0..<12).reversed().map { monthsAgo in
                let month = calendar.date(byAdding: .month, value: -monthsAgo, to: now) ?? now
                let interval = calendar.dateInterval(of: .month, for: month)
                let sum = activities
                    .filter { interval?.contains($0.startTime) ?? false }
                    .reduce(0.0) { $0 + $1.distance }
                let formatter = DateFormatter()
                formatter.dateFormat = "MMM"
                return Bucket(
                    label: formatter.string(from: month),
                    order: -monthsAgo,
                    distanceKm: sum / 1000
                )
            }
        }
        var isoCalendar = Calendar(identifier: .iso8601)
        isoCalendar.firstWeekday = 2
        return (0..<12).reversed().map { weeksAgo in
            let week = isoCalendar.date(byAdding: .weekOfYear, value: -weeksAgo, to: now) ?? now
            let interval = isoCalendar.dateInterval(of: .weekOfYear, for: week)
            let sum = activities
                .filter { interval?.contains($0.startTime) ?? false }
                .reduce(0.0) { $0 + $1.distance }
            let formatter = DateFormatter()
            formatter.dateFormat = "d/M"
            return Bucket(
                label: formatter.string(from: interval?.start ?? week),
                order: -weeksAgo,
                distanceKm: sum / 1000
            )
        }
    }

    private var chartSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Picker("Period", selection: $monthly) {
                    Text("12 weeks").tag(false)
                    Text("12 months").tag(true)
                }
                .pickerStyle(.segmented)
                Chart(buckets) { bucket in
                    BarMark(
                        x: .value("Period", bucket.label),
                        y: .value("km", bucket.distanceKm)
                    )
                    .foregroundStyle(
                        bucket.order == 0 ? Color.accentColor : Color.accentColor.opacity(0.5)
                    )
                }
                .chartXAxis {
                    AxisMarks { value in
                        AxisValueLabel().font(.system(size: 8))
                    }
                }
                .frame(height: 140)
            }
            .padding(.vertical, 4)
        } header: {
            Text("Distance")
        }
    }

    private var totalsSection: some View {
        Section("All time") {
            HStack {
                StatTile(label: "Activities", value: "\(activities.count)")
                Spacer()
                StatTile(
                    label: "Distance",
                    value: formatDistanceShort(
                        activities.reduce(0.0) { $0 + $1.distance }, imperial: imperial
                    )
                )
                Spacer()
                StatTile(
                    label: "Time",
                    value: formatDuration(activities.reduce(0.0) { $0 + $1.movingTime })
                )
                Spacer()
                StatTile(
                    label: "Climb",
                    value: formatElevation(
                        activities.reduce(0.0) { $0 + $1.elevationGain }, imperial: imperial
                    )
                )
            }
            .padding(.vertical, 4)
        }
    }

    private var segmentsSection: some View {
        Section("Segments") {
            ForEach(segments) { segment in
                let related = efforts.filter { $0.segmentUid == segment.uid }
                HStack {
                    VStack(alignment: .leading) {
                        Text(segment.name).font(.subheadline)
                        Text(
                            "\(formatDistanceShort(segment.distance, imperial: imperial)) · "
                                + "\(related.count) attempt\(related.count == 1 ? "" : "s")"
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(related.map(\.duration).min().map { formatDuration($0) } ?? "--")
                        .bold()
                        .foregroundStyle(.tint)
                }
            }
            .onDelete { indexSet in
                for index in indexSet {
                    let segment = segments[index]
                    for effort in efforts.filter({ $0.segmentUid == segment.uid }) {
                        context.delete(effort)
                    }
                    context.delete(segment)
                }
                try? context.save()
            }
        }
    }

    private var recordsSection: some View {
        Section("Personal records") {
            if let longest = activities.max(by: { $0.distance < $1.distance }) {
                recordRow(
                    "Longest distance",
                    formatDistance(longest.distance, imperial: imperial),
                    longest.title
                )
            }
            if let longestTime = activities.max(by: { $0.movingTime < $1.movingTime }) {
                recordRow("Longest duration", formatDuration(longestTime.movingTime), longestTime.title)
            }
            if let biggestClimb = activities.max(by: { $0.elevationGain < $1.elevationGain }) {
                recordRow(
                    "Biggest climb",
                    formatElevation(biggestClimb.elevationGain, imperial: imperial),
                    biggestClimb.title
                )
            }
            if let fastest = activities.filter({ $0.distance >= 1000 })
                .max(by: { $0.avgSpeed < $1.avgSpeed }) {
                recordRow(
                    "Fastest average",
                    formatSpeed(fastest.avgSpeed, imperial: imperial),
                    fastest.title
                )
            }
        }
    }

    private func recordRow(_ label: String, _ value: String, _ title: String) -> some View {
        HStack {
            VStack(alignment: .leading) {
                Text(label).font(.subheadline)
                Text(title).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text(value).bold().foregroundStyle(.tint)
        }
    }
}
