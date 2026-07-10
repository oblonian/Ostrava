import UIKit

/// Renders a social-media summary card (route + stats) and returns a temp PNG URL.
enum ShareImage {

    private static let width: CGFloat = 1080
    private static let height: CGFloat = 1350
    private static let orange = UIColor(red: 252 / 255, green: 82 / 255, blue: 0, alpha: 1)
    private static let navy = UIColor(red: 27 / 255, green: 39 / 255, blue: 51 / 255, alpha: 1)

    static func renderURL(activity: Activity, imperial: Bool) -> URL? {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let image = renderer.image { rendererContext in
            let context = rendererContext.cgContext
            navy.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))

            draw(text: activity.title, at: CGPoint(x: 64, y: 72), size: 64, weight: .bold, color: .white)
            draw(
                text: "\(activity.type.label) · \(formatDate(activity.startTime))",
                at: CGPoint(x: 64, y: 150), size: 40, weight: .regular,
                color: UIColor.white.withAlphaComponent(0.7)
            )

            drawRoute(activity.points, in: CGRect(x: 64, y: 240, width: width - 128, height: 600), context: context)

            var stats: [(String, String)] = [
                (formatDistance(activity.distance, imperial: imperial), "Distance"),
                (formatDuration(activity.movingTime), "Moving time"),
            ]
            if activity.type.usesPace {
                stats.append((formatPace(speedMps: activity.avgSpeed, imperial: imperial), "Avg pace"))
            } else {
                stats.append((formatSpeed(activity.avgSpeed, imperial: imperial), "Avg speed"))
            }
            stats.append((formatElevation(activity.elevationGain, imperial: imperial), "Climb"))

            let columnWidth = (width - 128) / 2
            for (index, stat) in stats.enumerated() {
                let x = 64 + CGFloat(index % 2) * columnWidth
                let y = 920 + CGFloat(index / 2) * 170
                draw(text: stat.0, at: CGPoint(x: x, y: y), size: 72, weight: .bold, color: .white)
                draw(
                    text: stat.1, at: CGPoint(x: x, y: y + 88), size: 36, weight: .regular,
                    color: UIColor.white.withAlphaComponent(0.6)
                )
            }
            draw(text: "OSTRAVA", at: CGPoint(x: 64, y: height - 110), size: 44, weight: .bold, color: orange)
        }

        guard let data = image.pngData() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ostrava-share-\(activity.uid.uuidString.prefix(8)).png")
        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    private static func draw(
        text: String, at point: CGPoint, size: CGFloat,
        weight: UIFont.Weight, color: UIColor
    ) {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: size, weight: weight),
            .foregroundColor: color,
        ]
        (text as NSString).draw(at: point, withAttributes: attributes)
    }

    private static func drawRoute(_ points: [TrackPoint], in bounds: CGRect, context: CGContext) {
        guard points.count >= 2 else { return }
        let minLat = points.map(\.latitude).min()!
        let maxLat = points.map(\.latitude).max()!
        let minLon = points.map(\.longitude).min()!
        let maxLon = points.map(\.longitude).max()!
        let lonScale = cos((minLat + maxLat) / 2 * .pi / 180)

        let spanX = max((maxLon - minLon) * lonScale, 1e-6)
        let spanY = max(maxLat - minLat, 1e-6)
        let scale = min(bounds.width / spanX, bounds.height / spanY)
        let offsetX = bounds.minX + (bounds.width - spanX * scale) / 2
        let offsetY = bounds.minY + (bounds.height - spanY * scale) / 2

        context.setStrokeColor(orange.cgColor)
        context.setLineWidth(12)
        context.setLineCap(.round)
        context.setLineJoin(.round)

        let segments = Dictionary(grouping: points, by: { $0.segment })
        for key in segments.keys.sorted() {
            let segment = segments[key] ?? []
            guard segment.count >= 2 else { continue }
            context.beginPath()
            for (i, point) in segment.enumerated() {
                let x = offsetX + (point.longitude - minLon) * lonScale * scale
                let y = offsetY + (maxLat - point.latitude) * scale
                if i == 0 {
                    context.move(to: CGPoint(x: x, y: y))
                } else {
                    context.addLine(to: CGPoint(x: x, y: y))
                }
            }
            context.strokePath()
        }
    }
}
