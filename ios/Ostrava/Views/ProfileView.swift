import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct ProfileView: View {
    @Environment(\.modelContext) private var context

    @AppStorage(SettingsKeys.userName) private var userName = "Athlete"
    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false
    @AppStorage(SettingsKeys.weightKg) private var weightKg = 70.0
    @AppStorage(SettingsKeys.autoPause) private var autoPause = true
    @AppStorage(SettingsKeys.weeklyGoalKm) private var weeklyGoalKm = 25.0
    @AppStorage(SettingsKeys.maxHeartRate) private var maxHeartRate = 190
    @AppStorage(SettingsKeys.audioCues) private var audioCues = true
    @AppStorage(SettingsKeys.keepScreenOn) private var keepScreenOn = true
    @AppStorage(SettingsKeys.haptics) private var haptics = true

    @State private var showRestoreImporter = false
    @State private var showGpxImporter = false
    @State private var statusMessage: String?

    private let gpxType = UTType(filenameExtension: "gpx") ?? .xml

    var body: some View {
        NavigationStack {
            Form {
                Section("Athlete") {
                    TextField("Name", text: $userName)
                    VStack(alignment: .leading) {
                        Text("Weight: \(Int(weightKg)) kg")
                        Slider(value: $weightKg, in: 40...150, step: 1)
                        Text("Used for calorie estimates")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Recording") {
                    Toggle("Imperial units", isOn: $imperial)
                    Toggle("Auto-pause", isOn: $autoPause)
                    Toggle("Audio cues", isOn: $audioCues)
                    Toggle("Keep screen on", isOn: $keepScreenOn)
                    Toggle("Vibration feedback", isOn: $haptics)
                }

                Section("Training") {
                    VStack(alignment: .leading) {
                        Text("Weekly distance goal: \(Int(weeklyGoalKm)) km")
                        Slider(value: $weeklyGoalKm, in: 0...200, step: 5)
                    }
                    VStack(alignment: .leading) {
                        Text("Max heart rate: \(maxHeartRate) bpm")
                        Slider(
                            value: Binding(
                                get: { Double(maxHeartRate) },
                                set: { maxHeartRate = Int($0) }
                            ),
                            in: 140...220, step: 1
                        )
                    }
                    ForEach(heartRateZones(maxHr: maxHeartRate)) { zone in
                        HStack {
                            Text("Z\(zone.index) \(zone.name)")
                            Spacer()
                            Text("\(zone.fromBpm)–\(zone.toBpm) bpm")
                                .foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                    }
                }

                Section {
                    if let url = BackupManager.exportURL(context: context) {
                        ShareLink(item: url) {
                            Label("Back up all data", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button {
                        showRestoreImporter = true
                    } label: {
                        Label("Restore from backup", systemImage: "square.and.arrow.down")
                    }
                    Button {
                        showGpxImporter = true
                    } label: {
                        Label("Import GPX", systemImage: "doc.badge.plus")
                    }
                    if let statusMessage {
                        Text(statusMessage).font(.caption).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Data")
                } footer: {
                    Text("Your training history lives only on this phone. Back it up regularly.")
                }

                Section("About") {
                    Text(
                        "Ostrava — open activity tracker. GPS tracking, splits, best efforts, "
                            + "segments, personal records, training analytics and GPX export."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Profile")
            .fileImporter(
                isPresented: $showRestoreImporter,
                allowedContentTypes: [.json]
            ) { result in
                if case .success(let url) = result {
                    let count = BackupManager.restore(from: url, context: context)
                    statusMessage = "Restored \(count) activities"
                }
            }
            .fileImporter(
                isPresented: $showGpxImporter,
                allowedContentTypes: [gpxType, .xml]
            ) { result in
                if case .success(let url) = result {
                    let ok = BackupManager.importGpx(from: url, context: context)
                    statusMessage = ok ? "GPX imported" : "Nothing to import (empty or duplicate file)"
                }
            }
        }
    }
}
