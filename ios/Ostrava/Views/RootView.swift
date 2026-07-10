import SwiftUI

struct RootView: View {
    @AppStorage(SettingsKeys.onboardingDone) private var onboardingDone = false

    var body: some View {
        if onboardingDone {
            TabView {
                FeedView()
                    .tabItem { Label("Feed", systemImage: "list.bullet") }
                RecordView()
                    .tabItem { Label("Record", systemImage: "record.circle") }
                StatsView()
                    .tabItem { Label("Stats", systemImage: "chart.bar.fill") }
                ProfileView()
                    .tabItem { Label("Profile", systemImage: "person.fill") }
            }
        } else {
            OnboardingView(onDone: { onboardingDone = true })
        }
    }
}

struct OnboardingView: View {
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("Ostrava")
                .font(.system(size: 44, weight: .bold))
                .foregroundStyle(.tint)
            Text("Track every run, ride, walk and hike — your data stays on your phone.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Spacer().frame(height: 40)

            featureRow(
                icon: "figure.run",
                title: "Live GPS tracking",
                body: "Route map, pace, splits, auto-pause and voice cues while you move."
            )
            featureRow(
                icon: "chart.bar.fill",
                title: "Training insights",
                body: "Best efforts, personal records, streaks and weekly trends."
            )
            featureRow(
                icon: "location.fill",
                title: "Location permission",
                body: "Needed to record your route while the app is in use or in the background."
            )
            featureRow(
                icon: "heart.fill",
                title: "Heart rate sensors",
                body: "Optional Bluetooth straps stream live bpm into your activities."
            )

            Spacer()
            Button {
                LocationTracker.shared.requestPermission()
                onDone()
            } label: {
                Text("Get started")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(32)
    }

    private func featureRow(icon: String, title: String, body bodyText: String) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.bold())
                Text(bodyText).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 10)
    }
}
