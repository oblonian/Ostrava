import CoreLocation
import SwiftUI

struct StatTile: View {
    let label: String
    let value: String
    var emphasized = false

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(emphasized ? .title2.bold() : .headline)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

/// Minimal route silhouette drawn straight from track points — no map tiles.
struct RouteThumbnail: View {
    let points: [TrackPoint]

    var body: some View {
        Canvas { context, size in
            guard points.count >= 2 else { return }
            let minLat = points.map(\.latitude).min()!
            let maxLat = points.map(\.latitude).max()!
            let minLon = points.map(\.longitude).min()!
            let maxLon = points.map(\.longitude).max()!
            let lonScale = cos((minLat + maxLat) / 2 * .pi / 180)

            let spanX = max((maxLon - minLon) * lonScale, 1e-6)
            let spanY = max(maxLat - minLat, 1e-6)
            let pad: CGFloat = 4
            let scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            let offsetX = pad + (size.width - 2 * pad - spanX * scale) / 2
            let offsetY = pad + (size.height - 2 * pad - spanY * scale) / 2

            let groups = Dictionary(grouping: points, by: { $0.segment })
            for key in groups.keys.sorted() {
                let segment = groups[key] ?? []
                guard segment.count >= 2 else { continue }
                var path = Path()
                for (i, point) in segment.enumerated() {
                    let x = offsetX + (point.longitude - minLon) * lonScale * scale
                    let y = offsetY + (maxLat - point.latitude) * scale
                    if i == 0 {
                        path.move(to: CGPoint(x: x, y: y))
                    } else {
                        path.addLine(to: CGPoint(x: x, y: y))
                    }
                }
                context.stroke(
                    path,
                    with: .color(.accentColor),
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round)
                )
            }
        }
    }
}

/// Identifiable coordinate run for MapPolyline building.
struct RoutePath: Identifiable {
    let id: Int
    let coordinates: [CLLocationCoordinate2D]
}

func routePaths(from points: [TrackPoint]) -> [RoutePath] {
    Dictionary(grouping: points, by: { $0.segment })
        .sorted { $0.key < $1.key }
        .compactMap { key, segment in
            guard segment.count >= 2 else { return nil }
            return RoutePath(
                id: key,
                coordinates: segment.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
            )
        }
}

func downsampled(_ points: [TrackPoint], to maxCount: Int = 80) -> [TrackPoint] {
    guard points.count > maxCount else { return points }
    let step = Double(points.count) / Double(maxCount)
    return (0..<maxCount).map { points[Int(Double($0) * step)] }
}
