import MapKit
import SwiftData
import SwiftUI

struct RecordView: View {
    @ObservedObject private var tracker = LocationTracker.shared
    @ObservedObject private var hrMonitor = HeartRateMonitor.shared

    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false
    @AppStorage(SettingsKeys.keepScreenOn) private var keepScreenOn = true

    @State private var selectedType: ActivityType = .run
    @State private var intervals: IntervalConfig?
    @State private var countdown = -1
    @State private var camera: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var showFinishSheet = false
    @State private var showDiscardAlert = false
    @State private var showIntervalSheet = false
    @State private var showHrSheet = false
    @State private var navigateTarget: NavTarget?

    private struct NavTarget: Identifiable, Hashable {
        let id: UUID
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                mapSection
                controlSection
            }
            .navigationBarHidden(true)
            .navigationDestination(item: $navigateTarget) { target in
                SavedActivityLoader(uid: target.id)
            }
        }
        .onChange(of: tracker.status) { _, status in
            UIApplication.shared.isIdleTimerDisabled = status != .idle && keepScreenOn
        }
        .onChange(of: tracker.lastSavedActivityUid) { _, uid in
            if let uid {
                tracker.lastSavedActivityUid = nil
                navigateTarget = NavTarget(id: uid)
            }
        }
        .task(id: countdown) {
            guard countdown >= 0 else { return }
            if countdown > 0 {
                try? await Task.sleep(for: .seconds(1))
                if countdown > 0 { countdown -= 1 }
            } else {
                countdown = -1
                tracker.start(type: selectedType, intervals: intervals)
            }
        }
        .sheet(isPresented: $showFinishSheet) {
            FinishSheet { title, feel in
                tracker.finish(title: title, feel: feel)
            }
        }
        .sheet(isPresented: $showIntervalSheet) {
            IntervalSheet(config: $intervals)
        }
        .sheet(isPresented: $showHrSheet) {
            HeartRateSheet()
        }
        .alert("Discard activity?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { tracker.discard() }
            Button("Keep recording", role: .cancel) {}
        } message: {
            Text("The recording will be thrown away permanently.")
        }
    }

    private var mapSection: some View {
        ZStack {
            Map(position: $camera) {
                UserAnnotation()
                ForEach(routePaths(from: tracker.points)) { path in
                    MapPolyline(coordinates: path.coordinates)
                        .stroke(Color.accentColor, lineWidth: 4)
                }
            }
            if let phase = tracker.intervalPhase {
                VStack {
                    Text("\(phase.name) · \(formatDuration(TimeInterval(phase.remainingSec))) left (\(phase.index)/\(phase.total))")
                        .font(.subheadline.bold())
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                        .padding(.top, 8)
                    Spacer()
                }
            }
            if countdown > 0 {
                Color.black.opacity(0.6)
                Text("\(countdown)")
                    .font(.system(size: 120, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
    }

    private var controlSection: some View {
        VStack(spacing: 12) {
            HStack {
                gpsStatus
                Spacer()
                Button {
                    showHrSheet = true
                    hrMonitor.startScan()
                } label: {
                    Label(
                        tracker.heartRate.map { "\($0) bpm" }
                            ?? hrMonitor.bpm.map { "\($0) bpm" } ?? "HR sensor",
                        systemImage: "heart.fill"
                    )
                    .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            if !tracker.isActive {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(ActivityType.allCases) { type in
                            Button {
                                selectedType = type
                            } label: {
                                Label(type.label, systemImage: type.systemImage)
                                    .font(.subheadline)
                            }
                            .buttonStyle(.bordered)
                            .tint(selectedType == type ? .accentColor : .secondary)
                        }
                        Button {
                            showIntervalSheet = true
                        } label: {
                            Label(
                                intervals.map { "Intervals: \($0.summary)" } ?? "Intervals",
                                systemImage: "timer"
                            )
                            .font(.subheadline)
                        }
                        .buttonStyle(.bordered)
                        .tint(intervals != nil ? .accentColor : .secondary)
                    }
                }
            }

            HStack(spacing: 32) {
                VStack {
                    Text(formatDuration(tracker.movingTime))
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                    Text("TIME").font(.caption2).foregroundStyle(.secondary)
                }
                VStack {
                    Text(formatDistance(tracker.distance, imperial: imperial))
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                    Text("DISTANCE").font(.caption2).foregroundStyle(.secondary)
                }
            }
            HStack {
                let activeType = tracker.isActive ? tracker.type : selectedType
                if activeType.usesPace {
                    StatTile(label: "Pace", value: formatPace(speedMps: tracker.avgSpeed, imperial: imperial))
                } else {
                    StatTile(label: "Speed", value: formatSpeed(tracker.currentSpeed, imperial: imperial))
                }
                Spacer()
                StatTile(label: "Climb", value: formatElevation(tracker.elevationGain, imperial: imperial))
                Spacer()
                StatTile(label: "Heart rate", value: tracker.heartRate.map { "\($0) bpm" } ?? "--")
            }

            buttons
        }
        .padding()
    }

    @ViewBuilder
    private var buttons: some View {
        switch tracker.status {
        case .idle:
            Button {
                LocationTracker.shared.requestPermission()
                countdown = 3
            } label: {
                Text(countdown > 0 ? "Starting…" : "Start \(selectedType.label)")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(countdown >= 0)
        case .tracking:
            Button {
                tracker.pause(manual: true)
            } label: {
                Text("Pause")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(.secondary)
        case .paused, .autoPaused:
            VStack(spacing: 8) {
                if tracker.status == .autoPaused {
                    Text("Auto-paused — move to resume")
                        .font(.caption.bold())
                        .foregroundStyle(.tint)
                }
                HStack {
                    Button {
                        tracker.resume()
                    } label: {
                        Text("Resume").frame(maxWidth: .infinity).padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    Button {
                        showFinishSheet = true
                    } label: {
                        Text("Finish").frame(maxWidth: .infinity).padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.secondary)
                    Button {
                        showDiscardAlert = true
                    } label: {
                        Text("Discard").padding(.vertical, 12)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var gpsStatus: some View {
        let (label, color): (String, Color) = {
            guard let accuracy = tracker.accuracy else {
                return tracker.isActive ? ("GPS: searching…", .red) : ("GPS: waiting", .secondary)
            }
            if accuracy <= 10 { return ("GPS: strong (±\(Int(accuracy)) m)", .accentColor) }
            if accuracy <= 25 { return ("GPS: ok (±\(Int(accuracy)) m)", .secondary) }
            return ("GPS: weak (±\(Int(accuracy)) m)", .red)
        }()
        return Text(label).font(.caption).foregroundStyle(color)
    }
}

/// Post-activity save sheet: name it, rate how it felt.
private struct FinishSheet: View {
    let onSave: (String, String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = defaultActivityTitle(type: LocationTracker.shared.type, start: Date())
    @State private var feel: String?

    var body: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $title)
                Section("How did it feel?") {
                    HStack {
                        ForEach(Feel.options, id: \.value) { option in
                            Button {
                                feel = feel == option.value ? nil : option.value
                            } label: {
                                Text(option.label).font(.subheadline)
                            }
                            .buttonStyle(.bordered)
                            .tint(feel == option.value ? .accentColor : .secondary)
                        }
                    }
                }
            }
            .navigationTitle("Save activity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(title.trimmingCharacters(in: .whitespaces), feel)
                        dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct IntervalSheet: View {
    @Binding var config: IntervalConfig?
    @Environment(\.dismiss) private var dismiss

    @State private var warmupMin = 5.0
    @State private var workSec = 120.0
    @State private var restSec = 60.0
    @State private var repeats = 6.0
    @State private var cooldownMin = 5.0

    var body: some View {
        NavigationStack {
            Form {
                slider("Warm-up: \(Int(warmupMin)) min", value: $warmupMin, range: 0...30)
                slider("Work: \(formatDuration(workSec))", value: $workSec, range: 15...600, step: 15)
                slider("Rest: \(formatDuration(restSec))", value: $restSec, range: 0...300, step: 15)
                slider("Repeats: \(Int(repeats))", value: $repeats, range: 1...20)
                slider("Cool-down: \(Int(cooldownMin)) min", value: $cooldownMin, range: 0...30)
            }
            .navigationTitle("Interval workout")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use workout") {
                        config = IntervalConfig(
                            warmupSec: Int(warmupMin) * 60,
                            workSec: Int(workSec),
                            restSec: Int(restSec),
                            repeats: Int(repeats),
                            cooldownSec: Int(cooldownMin) * 60
                        )
                        dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear") {
                        config = nil
                        dismiss()
                    }
                }
            }
            .onAppear {
                if let config {
                    warmupMin = Double(config.warmupSec / 60)
                    workSec = Double(config.workSec)
                    restSec = Double(config.restSec)
                    repeats = Double(config.repeats)
                    cooldownMin = Double(config.cooldownSec / 60)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func slider(
        _ label: String, value: Binding<Double>,
        range: ClosedRange<Double>, step: Double = 1
    ) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.subheadline)
            Slider(value: value, in: range, step: step)
        }
    }
}

private struct HeartRateSheet: View {
    @ObservedObject private var monitor = HeartRateMonitor.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                switch monitor.state {
                case .connected:
                    Text("Connected to \(monitor.deviceName ?? "sensor")")
                    Text(monitor.bpm.map { "♥ \($0) bpm" } ?? "Waiting for data…")
                        .font(.title2.bold())
                        .foregroundStyle(.tint)
                    Button("Disconnect", role: .destructive) { monitor.disconnect() }
                case .connecting:
                    HStack {
                        ProgressView()
                        Text("Connecting…").padding(.leading, 8)
                    }
                default:
                    if monitor.state == .scanning {
                        HStack {
                            ProgressView()
                            Text("Scanning for sensors…").padding(.leading, 8)
                        }
                    } else {
                        Button("Scan") { monitor.startScan() }
                    }
                    ForEach(monitor.foundDevices) { device in
                        Button(device.name) { monitor.connect(id: device.id) }
                    }
                }
            }
            .navigationTitle("Heart rate sensor")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        monitor.stopScan()
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Resolves a saved activity uid to its detail view after the tracker finishes.
private struct SavedActivityLoader: View {
    let uid: UUID
    @Environment(\.modelContext) private var context
    @State private var activity: Activity?

    var body: some View {
        Group {
            if let activity {
                DetailView(activity: activity)
            } else {
                ProgressView()
            }
        }
        .task {
            var descriptor = FetchDescriptor<Activity>(predicate: #Predicate { $0.uid == uid })
            descriptor.fetchLimit = 1
            activity = try? context.fetch(descriptor).first
        }
    }
}
