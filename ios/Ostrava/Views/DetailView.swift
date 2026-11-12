import Charts
import MapKit
import SwiftData
import SwiftUI

struct DetailView: View {
    let activity: Activity

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false

    @State private var points: [TrackPoint] = []
    @State private var splits: [Split] = []
    @State private var rankedEfforts: [RankedEffort] = []
    @State private var efforts: [(name: String, duration: TimeInterval, isBest: Bool)] = []
    @State private var showDeleteAlert = false
    @State private var showRenameAlert = false
    @State private var showSegmentAlert = false
    @State private var renameText = ""
    @State private var segmentName = ""
    @State private var gpxURL: URL?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Map {
                    ForEach(routePaths(from: points)) { path in
                        MapPolyline(coordinates: path.coordinates)
                            .stroke(Color.accentColor, lineWidth: 4)
                    }
                }
                .frame(height: 240)
                .allowsHitTesting(false)

                VStack(alignment: .leading, spacing: 16) {
                    header
                    actions
                    if rankedEfforts.contains(where: { $0.rank == 1 }) { recordBanner }
                    statGrid
                    if !efforts.isEmpty { segmentEffortsCard }
                    if !rankedEfforts.isEmpty { bestEffortsCard }
                    if !splits.isEmpty { splitsCard }
                    if points.count >= 2 { charts }
                }
                .padding(.horizontal)
            }
        }
        .navigationTitle(activity.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { load() }
        .alert("Delete activity?", isPresented: $showDeleteAlert) {
            Button("Delete", role: .destructive) {
                // Leave the screen before deleting: re-rendering this view with a
                // deleted @Model can crash on invalidated backing data.
                dismiss()
                DispatchQueue.main.async { deleteActivity() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\"\(activity.title)\" and its GPS track will be removed permanently.")
        }
        .alert("Rename activity", isPresented: $showRenameAlert) {
            TextField("Title", text: $renameText)
            Button("Save") {
                if !renameText.trimmingCharacters(in: .whitespaces).isEmpty {
                    activity.title = renameText.trimmingCharacters(in: .whitespaces)
                    try? context.save()
                    regenerateExports()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Create segment", isPresented: $showSegmentAlert) {
            TextField("Segment name", text: $segmentName)
            Button("Create") { createSegment() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Future \(activity.type.label.lowercased())s covering this route will be timed automatically.")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(activity.title).font(.title2.bold())
            Text("\(activity.type.label) · \(formatDateTime(activity.startTime))")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var actions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                NavigationLink {
                    ShareStudioView(activity: activity)
                } label: {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.borderedProminent)
                if let url = gpxURL {
                    ShareLink(item: url) {
                        Label("GPX", systemImage: "doc.text")
                    }
                    .buttonStyle(.bordered)
                }
                Button {
                    segmentName = "\(activity.title) segment"
                    showSegmentAlert = true
                } label: {
                    Label("Make segment", systemImage: "flag.checkered")
                }
                .buttonStyle(.bordered)
                Button {
                    renameText = activity.title
                    showRenameAlert = true
                } label: {
                    Label("Rename", systemImage: "pencil")
                }
                .buttonStyle(.bordered)
                Button(role: .destructive) {
                    showDeleteAlert = true
                } label: {
                    Label("Delete", systemImage: "trash")
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var statGrid: some View {
        VStack(spacing: 16) {
            HStack {
                StatTile(label: "Distance", value: formatDistance(activity.distance, imperial: imperial))
                Spacer()
                StatTile(label: "Moving time", value: formatDuration(activity.movingTime))
                Spacer()
                if activity.type.usesPace {
                    StatTile(label: "Avg pace", value: formatPace(speedMps: activity.avgSpeed, imperial: imperial))
                } else {
                    StatTile(label: "Avg speed", value: formatSpeed(activity.avgSpeed, imperial: imperial))
                }
            }
            HStack {
                StatTile(label: "Climb", value: formatElevation(activity.elevationGain, imperial: imperial))
                Spacer()
                StatTile(label: "Max speed", value: formatSpeed(activity.maxSpeed, imperial: imperial))
                Spacer()
                StatTile(label: "Calories", value: "\(activity.calories) kcal")
            }
            if activity.avgHeartRate != nil || activity.feel != nil {
                HStack {
                    StatTile(label: "Avg HR", value: activity.avgHeartRate.map { "\($0) bpm" } ?? "--")
                    Spacer()
                    StatTile(label: "Max HR", value: activity.maxHeartRate.map { "\($0) bpm" } ?? "--")
                    Spacer()
                    StatTile(label: "Felt", value: Feel.label(activity.feel) ?? "--")
                }
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var segmentEffortsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Segments").font(.headline)
            ForEach(efforts.indices, id: \.self) { index in
                let effort = efforts[index]
                HStack {
                    Text(effort.name)
                    Spacer()
                    Text(formatDuration(effort.duration)).bold()
                    if effort.isBest {
                        Text("PR 🏆").foregroundStyle(.tint)
                    }
                }
                .font(.subheadline)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var recordBanner: some View {
        let records = rankedEfforts.filter { $0.rank == 1 }
        return HStack(spacing: 12) {
            Text("🏆").font(.largeTitle)
            VStack(alignment: .leading, spacing: 2) {
                Text(records.count == 1 ? "Personal record" : "\(records.count) personal records")
                    .font(.headline)
                Text("Your fastest \(records.map(\.label).joined(separator: ", "))")
                    .font(.subheadline)
            }
            Spacer()
        }
        .padding()
        .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
    }

    private var bestEffortsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Best efforts").font(.headline)
            ForEach(rankedEfforts) { effort in
                let speed = effort.distance / effort.duration
                HStack {
                    Text(medal(for: effort.rank)).frame(width: 28, alignment: .leading)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(effort.label)
                        if let rankLabel = rankLabel(for: effort.rank) {
                            Text(rankLabel).font(.caption).foregroundStyle(.tint)
                        }
                    }
                    Spacer()
                    Text(formatDuration(effort.duration)).bold()
                    Text(
                        activity.type.usesPace
                            ? formatPace(speedMps: speed, imperial: imperial)
                            : formatSpeed(speed, imperial: imperial)
                    )
                    .foregroundStyle(.secondary)
                }
                .font(.subheadline)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func medal(for rank: Int) -> String {
        switch rank {
        case 1: return "🥇"
        case 2: return "🥈"
        case 3: return "🥉"
        default: return ""
        }
    }

    private func rankLabel(for rank: Int) -> String? {
        switch rank {
        case 1: return "Personal record"
        case 2: return "2nd fastest ever"
        case 3: return "3rd fastest ever"
        default: return nil
        }
    }

    private var splitsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Splits").font(.headline)
            let fastest = splits.map(\.paceSecondsPerKm).filter { $0 > 0 }.min() ?? 1
            ForEach(splits) { split in
                HStack {
                    Text("\(split.index) \(imperial ? "mi" : "km")")
                        .frame(width: 48, alignment: .leading)
                    Text(splitPace(split))
                        .bold()
                        .frame(width: 80, alignment: .leading)
                    GeometryReader { geometry in
                        // Faster splits get longer bars.
                        let fraction = min(max(fastest / max(split.paceSecondsPerKm, 1), 0.05), 1)
                        RoundedRectangle(cornerRadius: 3)
                            .fill(Color.accentColor)
                            .frame(width: geometry.size.width * fraction)
                    }
                    .frame(height: 14)
                    Text(formatElevation(split.elevationDelta, imperial: imperial))
                        .foregroundStyle(.secondary)
                        .frame(width: 52, alignment: .trailing)
                }
                .font(.caption)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var charts: some View {
        VStack(alignment: .leading, spacing: 16) {
            let elevations = downsampled(points, to: 200).map(\.altitude)
            VStack(alignment: .leading, spacing: 8) {
                Text("Elevation").font(.headline)
                Chart(Array(elevations.enumerated()), id: \.offset) { item in
                    LineMark(x: .value("Index", item.offset), y: .value("m", item.element))
                        .foregroundStyle(Color.accentColor)
                }
                .chartXAxis(.hidden)
                .frame(height: 120)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func splitPace(_ split: Split) -> String {
        let unitMeters = imperial ? metersPerMile : 1000.0
        return formatPaceSeconds(split.duration / (split.distance / unitMeters), imperial: imperial)
    }

    private func load() {
        let trackPoints = activity.points
        points = trackPoints
        splits = computeSplits(points: trackPoints, splitLength: imperial ? metersPerMile : 1000)
        rankedEfforts = PersonalRecords.rankedEfforts(for: activity, in: context)
        loadEfforts()
        regenerateExports()
    }

    /// Writing the GPX is too expensive for `body`, so it runs once here
    /// (and again after a rename changes the title).
    private func regenerateExports() {
        gpxURL = GpxExporter.exportURL(activity: activity)
    }

    private func loadEfforts() {
        let activityUid = activity.uid
        let descriptor = FetchDescriptor<SegmentEffort>(
            predicate: #Predicate { $0.activityUid == activityUid }
        )
        guard let mine = try? context.fetch(descriptor) else { return }
        let segments = (try? context.fetch(FetchDescriptor<Segment>())) ?? []
        let allEfforts = (try? context.fetch(FetchDescriptor<SegmentEffort>())) ?? []
        efforts = mine.compactMap { effort in
            guard let segment = segments.first(where: { $0.uid == effort.segmentUid }) else {
                return nil
            }
            let best = allEfforts
                .filter { $0.segmentUid == effort.segmentUid }
                .map(\.duration)
                .min() ?? effort.duration
            return (segment.name, effort.duration, effort.duration <= best)
        }
    }

    private func createSegment() {
        let name = segmentName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, points.count >= 2 else { return }
        let first = points[0]
        let last = points[points.count - 1]
        let segment = Segment(
            name: name,
            activityType: activity.type,
            startLat: first.latitude,
            startLon: first.longitude,
            endLat: last.latitude,
            endLon: last.longitude,
            distance: activity.distance,
            createdAt: Date()
        )
        context.insert(segment)
        context.insert(
            SegmentEffort(
                segmentUid: segment.uid,
                activityUid: activity.uid,
                duration: activity.movingTime,
                startTime: activity.startTime
            )
        )
        try? context.save()
        loadEfforts()
    }

    private func deleteActivity() {
        let activityUid = activity.uid
        let descriptor = FetchDescriptor<SegmentEffort>(
            predicate: #Predicate { $0.activityUid == activityUid }
        )
        for effort in (try? context.fetch(descriptor)) ?? [] {
            context.delete(effort)
        }
        PersonalRecords.deleteEfforts(forActivity: activityUid, in: context)
        context.delete(activity)
        try? context.save()
    }
}
