import Charts
import SwiftData
import SwiftUI

struct StatsView: View {
    @Query(sort: \Activity.startTime, order: .reverse) private var allActivities: [Activity]
    @Query private var segments: [Segment]
    @Query private var efforts: [SegmentEffort]
    @Query private var bestEfforts: [BestEffortRecord]
    @Environment(\.modelContext) private var context
    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false

    @State private var typeFilter: ActivityType?
    @State private var monthly = false
    /// Sport picked on the records card; nil follows the page filter, then the most frequent sport.
    @State private var recordsSportChoice: ActivityType?

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
                    Button("All") {
                        typeFilter = nil
                        recordsSportChoice = nil
                    }
                        .buttonStyle(.bordered)
                        .tint(typeFilter == nil ? .accentColor : .secondary)
                    ForEach(ActivityType.allCases) { type in
                        Button(type.label) {
                            typeFilter = type
                            recordsSportChoice = nil
                        }
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

    // MARK: - Personal records

    private var availableSports: [ActivityType] {
        ActivityType.allCases.filter { type in allActivities.contains { $0.typeRaw == type.rawValue } }
    }

    private var recordsSport: ActivityType? {
        let available = availableSports
        if let chosen = recordsSportChoice ?? typeFilter, available.contains(chosen) { return chosen }
        // Default to the sport the athlete does most.
        return available.max { a, b in
            allActivities.filter { $0.typeRaw == a.rawValue }.count
                < allActivities.filter { $0.typeRaw == b.rawValue }.count
        }
    }

    private struct EffortRecord: Identifiable {
        var label: String
        var distance: Double
        var duration: TimeInterval
        var activityUid: UUID
        var achievedAt: Date
        /// How much faster than the best effort before it; nil for a first-ever effort.
        var improvement: TimeInterval?
        var id: String { label }
    }

    private func effortRecords(for sport: ActivityType) -> [EffortRecord] {
        let sameSport = bestEfforts.filter { $0.activityTypeRaw == sport.rawValue }
        return Dictionary(grouping: sameSport, by: { $0.label }).values.compactMap { sameDistance in
            guard let best = sameDistance.min(by: { $0.duration < $1.duration }) else { return nil }
            let previousBest = sameDistance
                .filter { $0.achievedAt < best.achievedAt }
                .map(\.duration)
                .min()
            return EffortRecord(
                label: best.label,
                distance: best.distance,
                duration: best.duration,
                activityUid: best.activityUid,
                achievedAt: best.achievedAt,
                improvement: previousBest.map { $0 - best.duration }
            )
        }
        .sorted { $0.distance < $1.distance }
    }

    /// Records set within the last 30 days are flagged as new.
    private func isRecent(_ date: Date) -> Bool {
        Date().timeIntervalSince(date) < 30 * 24 * 3600
    }

    @ViewBuilder
    private var recordsSection: some View {
        if let sport = recordsSport {
            let sportActivities = allActivities.filter { $0.typeRaw == sport.rawValue }
            let effortRows = effortRecords(for: sport)

            Section {
                if availableSports.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(availableSports) { type in
                                Button {
                                    recordsSportChoice = type
                                } label: {
                                    Label(type.label, systemImage: type.systemImage)
                                }
                                .buttonStyle(.bordered)
                                .tint(type == sport ? .accentColor : .secondary)
                            }
                        }
                    }
                }
                ForEach(effortRows) { effort in
                    let speed = effort.distance / effort.duration
                    recordRow(
                        label: effort.label,
                        detail: detail(forActivity: effort.activityUid, date: effort.achievedAt),
                        highlight: effort.improvement.flatMap { gain in
                            gain > 0 ? "▲ \(formatDuration(gain)) faster than your previous best" : nil
                        },
                        value: formatDuration(effort.duration),
                        valueDetail: sport.usesPace
                            ? formatPace(speedMps: speed, imperial: imperial)
                            : formatSpeed(speed, imperial: imperial),
                        recent: isRecent(effort.achievedAt),
                        activityUid: effort.activityUid
                    )
                }
            } header: {
                Text("Personal records · \(sport.label)")
            } footer: {
                if effortRows.isEmpty {
                    Text("Best efforts appear once an activity covers a benchmark distance.")
                }
            }

            Section("Milestones") {
                milestone("Longest distance", sportActivities.max { $0.distance < $1.distance }) {
                    formatDistance($0.distance, imperial: imperial)
                }
                milestone("Longest duration", sportActivities.max { $0.movingTime < $1.movingTime }) {
                    formatDuration($0.movingTime)
                }
                milestone(
                    "Most climbing",
                    sportActivities.filter { $0.elevationGain >= 1 }.max { $0.elevationGain < $1.elevationGain }
                ) { formatElevation($0.elevationGain, imperial: imperial) }
                // Fastest average over a meaningful distance (>= 1 km) so short blips don't win.
                milestone(
                    sport.usesPace ? "Fastest average pace" : "Fastest average speed",
                    sportActivities.filter { $0.distance >= 1000 }.max { $0.avgSpeed < $1.avgSpeed }
                ) {
                    sport.usesPace
                        ? formatPace(speedMps: $0.avgSpeed, imperial: imperial)
                        : formatSpeed($0.avgSpeed, imperial: imperial)
                }
                milestone("Top speed", sportActivities.filter { $0.maxSpeed > 0 }.max { $0.maxSpeed < $1.maxSpeed }) {
                    formatSpeed($0.maxSpeed, imperial: imperial)
                }
                if let week = biggestPeriod(sportActivities, component: .weekOfYear) {
                    recordRow(
                        label: "Biggest week",
                        detail: "Week of \(formatDate(week.start)) · \(week.count) activit\(week.count == 1 ? "y" : "ies")",
                        highlight: nil,
                        value: formatDistance(week.distance, imperial: imperial),
                        valueDetail: nil,
                        recent: week.containsToday,
                        activityUid: nil
                    )
                }
                if let month = biggestPeriod(sportActivities, component: .month) {
                    recordRow(
                        label: "Biggest month",
                        detail: "\(monthName(month.start)) · \(month.count) activit\(month.count == 1 ? "y" : "ies")",
                        highlight: nil,
                        value: formatDistance(month.distance, imperial: imperial),
                        valueDetail: nil,
                        recent: month.containsToday,
                        activityUid: nil
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func milestone(_ label: String, _ activity: Activity?, value: (Activity) -> String) -> some View {
        if let activity {
            recordRow(
                label: label,
                detail: "\(activity.title) · \(formatDate(activity.startTime))",
                highlight: nil,
                value: value(activity),
                valueDetail: nil,
                recent: isRecent(activity.startTime),
                activityUid: activity.uid
            )
        }
    }

    private func detail(forActivity uid: UUID, date: Date) -> String {
        let title = allActivities.first { $0.uid == uid }?.title
        return [title, formatDate(date)].compactMap { $0 }.joined(separator: " · ")
    }

    private struct Period {
        var start: Date
        var distance: Double
        var count: Int
        var containsToday: Bool
    }

    /// The calendar week or month with the most distance.
    private func biggestPeriod(_ activities: [Activity], component: Calendar.Component) -> Period? {
        var calendar = Calendar(identifier: .iso8601)
        calendar.firstWeekday = 2
        calendar.timeZone = .current
        let grouped = Dictionary(grouping: activities) { activity in
            calendar.dateInterval(of: component, for: activity.startTime)?.start ?? activity.startTime
        }
        guard let best = grouped.max(by: { a, b in
            a.value.reduce(0) { $0 + $1.distance } < b.value.reduce(0) { $0 + $1.distance }
        }) else { return nil }
        let currentStart = calendar.dateInterval(of: component, for: Date())?.start
        return Period(
            start: best.key,
            distance: best.value.reduce(0) { $0 + $1.distance },
            count: best.value.count,
            containsToday: best.key == currentStart
        )
    }

    private func monthName(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMMM yyyy"
        return formatter.string(from: date)
    }

    @ViewBuilder
    private func recordRow(
        label: String, detail: String, highlight: String?, value: String,
        valueDetail: String?, recent: Bool, activityUid: UUID?
    ) -> some View {
        let row = HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(label).font(.subheadline.weight(.semibold))
                    if recent {
                        Text("NEW")
                            .font(.caption2.bold())
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 4))
                            .foregroundStyle(.white)
                    }
                }
                Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if let highlight {
                    Text(highlight).font(.caption).foregroundStyle(.tint)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(value).font(.headline).foregroundStyle(.tint)
                if let valueDetail {
                    Text(valueDetail).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        if let activityUid, let activity = allActivities.first(where: { $0.uid == activityUid }) {
            NavigationLink {
                DetailView(activity: activity)
            } label: {
                row
            }
        } else {
            row
        }
    }
}
