import SwiftData
import SwiftUI

@main
struct OstravaApp: App {

    let container: ModelContainer

    init() {
        do {
            container = try ModelContainer(
                for: Activity.self, Segment.self, SegmentEffort.self
            )
        } catch {
            fatalError("Failed to create model container: \(error)")
        }
        LocationTracker.shared.container = container
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(Color(red: 252 / 255, green: 82 / 255, blue: 0))
        }
        .modelContainer(container)
    }
}
