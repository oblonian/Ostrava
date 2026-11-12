import UIKit

enum ShareTemplate: String, CaseIterable, Identifiable {
    case photo, route, minimal

    var id: String { rawValue }

    var label: String {
        switch self {
        case .photo: return "Photo"
        case .route: return "Route"
        case .minimal: return "Minimal"
        }
    }
}

enum ShareAspect: String, CaseIterable, Identifiable {
    case story, post, square

    var id: String { rawValue }

    var label: String {
        switch self {
        case .story: return "Story 9:16"
        case .post: return "Post 4:5"
        case .square: return "Square"
        }
    }

    var size: CGSize {
        switch self {
        case .story: return CGSize(width: 1080, height: 1920)
        case .post: return CGSize(width: 1080, height: 1350)
        case .square: return CGSize(width: 1080, height: 1080)
        }
    }
}

/// Gradient themes for the route template.
enum ShareTheme: String, CaseIterable, Identifiable {
    case midnight, sunset, forest

    var id: String { rawValue }

    var label: String {
        switch self {
        case .midnight: return "Midnight"
        case .sunset: return "Sunset"
        case .forest: return "Forest"
        }
    }

    var start: UIColor {
        switch self {
        case .midnight: return UIColor(rgb: 0x22303F)
        case .sunset: return UIColor(rgb: 0xFC5200)
        case .forest: return UIColor(rgb: 0x14524F)
        }
    }

    var end: UIColor {
        switch self {
        case .midnight: return UIColor(rgb: 0x0B1117)
        case .sunset: return UIColor(rgb: 0x6A1B9A)
        case .forest: return UIColor(rgb: 0x071A18)
        }
    }

    var accent: UIColor {
        switch self {
        case .midnight: return UIColor(rgb: 0xFC5200)
        case .sunset: return .white
        case .forest: return UIColor(rgb: 0x7CFFB2)
        }
    }

    /// Text colour that reads on top of `accent`.
    var onAccent: UIColor {
        self == .midnight ? .white : UIColor(rgb: 0x1B2733)
    }
}

enum ShareStat: Int, CaseIterable, Identifiable {
    case distance, time, pace, climb, calories, heartRate, maxSpeed

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .distance: return "Distance"
        case .time: return "Time"
        case .pace: return "Pace"
        case .climb: return "Elev gain"
        case .calories: return "Calories"
        case .heartRate: return "Avg HR"
        case .maxSpeed: return "Max speed"
        }
    }
}

struct ShareSpec: Equatable {
    var template: ShareTemplate = .route
    var aspect: ShareAspect = .story
    var theme: ShareTheme = .midnight
    var stats: [ShareStat] = [.distance, .pace, .time]
    var showRoute = true
    var showPrBadge = true
    /// Extra darkening over the photo, 0 (none) to 0.7.
    var photoDim: Double = 0.2
}

struct ShareStatValue {
    var stat: ShareStat
    var value: String
    var label: String
}

/// Everything about the activity the card can show; `stats` holds every stat that is available.
struct ShareContent {
    var title: String
    var subtitle: String
    var stats: [ShareStatValue]
    var points: [TrackPoint]
    var prText: String?
}

/// Draws a shareable activity card. The same code produces the on-screen preview
/// (at a reduced `scale`) and the exported image. Layout mirrors the Android renderer.
enum ShareCardRenderer {

    static let maxStats = 6

    private static let margin: CGFloat = 72
    private static let rowHeight: CGFloat = 164
    private static let minRouteSize: CGFloat = 120
    private static let orange = UIColor(rgb: 0xFC5200)
    private static let navy = UIColor(rgb: 0x1B2733)
    private static let paper = UIColor(rgb: 0xF6F3EE)

    private struct Palette {
        var text: UIColor
        var secondary: UIColor
        var accent: UIColor
        var route: UIColor
        var onAccent: UIColor
        var shadow: Bool
    }

    static func render(spec: ShareSpec, content: ShareContent, photo: UIImage?, scale: CGFloat = 1) -> UIImage {
        let size = spec.aspect.size
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: size.width * scale, height: size.height * scale),
            format: format
        )
        return renderer.image { rendererContext in
            let context = rendererContext.cgContext
            context.scaleBy(x: scale, y: scale)

            let usePhoto = spec.template == .photo && photo != nil
            let palette: Palette
            if usePhoto {
                palette = Palette(
                    text: .white, secondary: UIColor.white.withAlphaComponent(0.8),
                    accent: orange, route: .white, onAccent: .white, shadow: true
                )
            } else if spec.template == .minimal {
                palette = Palette(
                    text: navy, secondary: navy.withAlphaComponent(0.6),
                    accent: orange, route: orange, onAccent: .white, shadow: false
                )
            } else {
                // The photo template without a photo falls back to the Midnight gradient.
                let theme = spec.template == .photo ? ShareTheme.midnight : spec.theme
                palette = Palette(
                    text: .white, secondary: UIColor.white.withAlphaComponent(0.72),
                    accent: theme.accent, route: theme.accent, onAccent: theme.onAccent, shadow: false
                )
            }

            drawBackground(context, spec: spec, photo: photo, size: size)

            let selected = Array(
                spec.stats.compactMap { stat in content.stats.first { $0.stat == stat } }.prefix(maxStats)
            )
            let hero = spec.template == .minimal ? selected.first : nil
            let gridStats = hero != nil ? Array(selected.dropFirst()) : selected

            let textTop = drawTextBlock(
                content: content,
                stats: gridStats,
                prText: spec.showPrBadge ? content.prText : nil,
                size: size,
                palette: palette
            )

            drawText(
                "OSTRAVA", x: margin, baseline: margin + 30,
                font: .systemFont(ofSize: 34, weight: .bold), color: palette.accent,
                kern: 34 * 0.18, shadow: palette.shadow
            )
            var contentTop = margin + 70
            let wantsRoute = spec.showRoute && content.points.count >= 2
            if let hero {
                let height = heroHeight(hero, width: size.width)
                let roomForRoute = textTop - 64 - (contentTop + height + 24)
                // With no route below it, the hero number sits in the middle of the free space.
                let top = wantsRoute && roomForRoute >= minRouteSize
                    ? contentTop
                    : contentTop + max((textTop - contentTop - height) / 2 - 20, 0)
                contentTop = drawHero(hero, width: size.width, top: top, palette: palette)
            }

            if wantsRoute {
                let rect: CGRect
                if usePhoto {
                    // Over a photo the route is a small overlay so it doesn't fight the picture.
                    let side = min(size.width * 0.36, textTop - contentTop - 40)
                    rect = CGRect(x: size.width - margin - side, y: margin, width: side, height: side)
                } else {
                    rect = CGRect(
                        x: margin + 24, y: contentTop + 24,
                        width: size.width - 2 * margin - 48, height: textTop - 64 - contentTop - 24
                    )
                }
                if rect.width >= minRouteSize && rect.height >= minRouteSize {
                    drawRoute(context, points: content.points, bounds: rect, palette: palette, thin: usePhoto)
                }
            }
        }
    }

    private static func drawBackground(_ context: CGContext, spec: ShareSpec, photo: UIImage?, size: CGSize) {
        let full = CGRect(origin: .zero, size: size)
        if spec.template == .minimal {
            paper.setFill()
            context.fill(full)
        } else if spec.template == .photo, let photo {
            // Aspect-fill the photo.
            let scale = max(size.width / photo.size.width, size.height / photo.size.height)
            let drawSize = CGSize(width: photo.size.width * scale, height: photo.size.height * scale)
            photo.draw(
                in: CGRect(
                    x: (size.width - drawSize.width) / 2, y: (size.height - drawSize.height) / 2,
                    width: drawSize.width, height: drawSize.height
                )
            )
            let dim = min(max(spec.photoDim, 0), 0.7)
            if dim > 0 {
                UIColor.black.withAlphaComponent(dim).setFill()
                context.fill(full)
            }
            // Scrims keep the text legible whatever the photo looks like.
            drawGradient(
                context,
                colors: [UIColor.black.withAlphaComponent(0), UIColor.black.withAlphaComponent(0.82)],
                from: CGPoint(x: 0, y: size.height * 0.38), to: CGPoint(x: 0, y: size.height),
                clip: CGRect(x: 0, y: size.height * 0.38, width: size.width, height: size.height * 0.62)
            )
            drawGradient(
                context,
                colors: [UIColor.black.withAlphaComponent(0.45), UIColor.black.withAlphaComponent(0)],
                from: .zero, to: CGPoint(x: 0, y: 300),
                clip: CGRect(x: 0, y: 0, width: size.width, height: 300)
            )
        } else {
            let theme = spec.template == .photo ? ShareTheme.midnight : spec.theme
            drawGradient(
                context, colors: [theme.start, theme.end],
                from: .zero, to: CGPoint(x: size.width, y: size.height), clip: full
            )
        }
    }

    private static func drawGradient(
        _ context: CGContext, colors: [UIColor], from: CGPoint, to: CGPoint, clip: CGRect
    ) {
        guard let gradient = CGGradient(
            colorsSpace: CGColorSpaceCreateDeviceRGB(),
            colors: colors.map(\.cgColor) as CFArray,
            locations: nil
        ) else { return }
        context.saveGState()
        context.clip(to: clip)
        context.drawLinearGradient(
            gradient, start: from, end: to,
            options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
        )
        context.restoreGState()
    }

    private static func heroFont(_ hero: ShareStatValue, width: CGFloat) -> UIFont {
        fittedFont(hero.value, size: 210, weight: .bold, maxWidth: width - 2 * margin)
    }

    private static func heroHeight(_ hero: ShareStatValue, width: CGFloat) -> CGFloat {
        40 + heroFont(hero, width: width).pointSize * 0.82 + 80
    }

    /// Minimal template: one oversized number. Returns the y below it.
    private static func drawHero(_ hero: ShareStatValue, width: CGFloat, top: CGFloat, palette: Palette) -> CGFloat {
        let font = heroFont(hero, width: width)
        let baseline = top + 40 + font.pointSize * 0.82
        drawText(hero.value, x: width / 2, baseline: baseline, font: font, color: palette.text, centered: true)
        drawText(
            hero.label.uppercased(), x: width / 2, baseline: baseline + 62,
            font: .systemFont(ofSize: 34, weight: .bold), color: palette.secondary,
            kern: 34 * 0.14, centered: true
        )
        return baseline + 80
    }

    /// Title, date, optional PR badge and the stats grid, anchored to the bottom edge.
    /// Returns the y of the block's top so the route can use the space above it.
    private static func drawTextBlock(
        content: ShareContent, stats: [ShareStatValue], prText: String?, size: CGSize, palette: Palette
    ) -> CGFloat {
        let columns: Int
        switch stats.count {
        case 0: columns = 0
        case 1, 2, 3: columns = stats.count
        case 4: columns = 2
        default: columns = 3
        }
        let rows = columns == 0 ? 0 : Int((Double(stats.count) / Double(columns)).rounded(.up))
        let gridHeight = rows == 0 ? 0 : CGFloat(rows) * rowHeight - 40
        let gridTop = size.height - margin - gridHeight

        if columns > 0 {
            let cellWidth = (size.width - 2 * margin) / CGFloat(columns)
            for (index, stat) in stats.enumerated() {
                let x = margin + CGFloat(index % columns) * cellWidth
                let y = gridTop + CGFloat(index / columns) * rowHeight
                drawStatValue(stat.value, x: x, baseline: y + 78, maxWidth: cellWidth - 36, palette: palette)
                drawText(
                    stat.label.uppercased(), x: x, baseline: y + 122,
                    font: .systemFont(ofSize: 30, weight: .bold), color: palette.secondary,
                    kern: 30 * 0.1, shadow: palette.shadow
                )
            }
        }

        var cursor = gridTop
        if columns > 0 {
            let dividerY = gridTop - 34
            palette.text.withAlphaComponent(0.25).setFill()
            UIRectFill(CGRect(x: margin, y: dividerY - 1, width: size.width - 2 * margin, height: 2))
            cursor = dividerY - 38
        }

        drawText(
            content.subtitle, x: margin, baseline: cursor,
            font: .systemFont(ofSize: 34, weight: .regular), color: palette.secondary, shadow: palette.shadow
        )

        let titleFont = UIFont.systemFont(ofSize: 62, weight: .bold)
        let titleBaseline = cursor - 54
        drawText(
            truncated(content.title, font: titleFont, maxWidth: size.width - 2 * margin),
            x: margin, baseline: titleBaseline, font: titleFont, color: palette.text, shadow: palette.shadow
        )
        var top = titleBaseline - 62

        if let prText {
            let badgeFont = UIFont.systemFont(ofSize: 30, weight: .bold)
            let label = truncated(prText, font: badgeFont, maxWidth: size.width - 2 * margin - 56)
            let badgeHeight: CGFloat = 60
            let badgeBottom = top - 18
            let textWidth = (label as NSString).size(withAttributes: [.font: badgeFont]).width
            let rect = CGRect(x: margin, y: badgeBottom - badgeHeight, width: textWidth + 56, height: badgeHeight)
            palette.accent.setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: badgeHeight / 2).fill()
            drawText(label, x: rect.minX + 28, baseline: rect.maxY - 20, font: badgeFont, color: palette.onAccent)
            top = rect.minY
        }
        return top
    }

    /// Draws "10.42 km" as a large number with a smaller unit so columns don't crowd each other.
    private static func drawStatValue(
        _ text: String, x: CGFloat, baseline: CGFloat, maxWidth: CGFloat, palette: Palette
    ) {
        let parts = text.split(separator: " ", maxSplits: 1).map(String.init)
        let number = parts.first ?? text
        let unit = parts.count > 1 ? parts[1] : ""
        func width(_ string: String, _ size: CGFloat) -> CGFloat {
            (string as NSString).size(
                withAttributes: [.font: UIFont.systemFont(ofSize: size, weight: .bold)]
            ).width
        }
        let gap: CGFloat = unit.isEmpty ? 0 : 12
        let total = width(number, 78) + gap + width(unit, 40)
        let shrink = total > maxWidth && total > 0 ? maxWidth / total : 1
        let numberFont = UIFont.systemFont(ofSize: 78 * shrink, weight: .bold)
        drawText(number, x: x, baseline: baseline, font: numberFont, color: palette.text, shadow: palette.shadow)
        if !unit.isEmpty {
            drawText(
                unit, x: x + width(number, 78 * shrink) + gap * shrink, baseline: baseline,
                font: .systemFont(ofSize: 40 * shrink, weight: .bold),
                color: palette.text, shadow: palette.shadow
            )
        }
    }

    private static func drawRoute(
        _ context: CGContext, points: [TrackPoint], bounds: CGRect, palette: Palette, thin: Bool
    ) {
        let lats = points.map(\.latitude)
        let lons = points.map(\.longitude)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return }
        let lonScale = cos((minLat + maxLat) / 2 * .pi / 180)

        let spanX = max((maxLon - minLon) * lonScale, 1e-6)
        let spanY = max(maxLat - minLat, 1e-6)
        let scale = min(bounds.width / spanX, bounds.height / spanY)
        let offsetX = bounds.minX + (bounds.width - spanX * scale) / 2
        let offsetY = bounds.minY + (bounds.height - spanY * scale) / 2
        func position(_ point: TrackPoint) -> CGPoint {
            CGPoint(
                x: offsetX + (point.longitude - minLon) * lonScale * scale,
                y: offsetY + (maxLat - point.latitude) * scale
            )
        }

        let strokeWidth: CGFloat = thin ? 9 : 13
        let path = CGMutablePath()
        let groups = Dictionary(grouping: points, by: { $0.segment })
        for key in groups.keys.sorted() {
            let segment = groups[key] ?? []
            guard segment.count >= 2 else { continue }
            path.addLines(between: segment.map(position))
        }

        context.saveGState()
        context.setLineCap(.round)
        context.setLineJoin(.round)
        if !thin {
            context.addPath(path)
            context.setStrokeColor(palette.route.withAlphaComponent(0.22).cgColor)
            context.setLineWidth(strokeWidth * 2.6)
            context.strokePath()
        }
        if palette.shadow {
            context.setShadow(
                offset: CGSize(width: 0, height: 3), blur: 10,
                color: UIColor.black.withAlphaComponent(0.55).cgColor
            )
        }
        context.addPath(path)
        context.setStrokeColor(palette.route.cgColor)
        context.setLineWidth(strokeWidth)
        context.strokePath()
        context.restoreGState()

        // Start is a hollow dot, finish a solid one.
        guard let first = points.first, let last = points.last else { return }
        let radius = strokeWidth * 1.25
        func dot(_ centre: CGPoint, radius: CGFloat, color: UIColor) {
            color.setFill()
            context.fillEllipse(
                in: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2)
            )
        }
        let coreColor: UIColor = palette.route == UIColor.white ? navy : .white
        dot(position(first), radius: radius, color: palette.route)
        dot(position(first), radius: radius * 0.5, color: coreColor)
        dot(position(last), radius: radius, color: palette.route)
    }

    // MARK: - Text helpers

    /// Draws text positioned by its baseline, like Android's Canvas.drawText.
    private static func drawText(
        _ text: String, x: CGFloat, baseline: CGFloat, font: UIFont, color: UIColor,
        kern: CGFloat = 0, centered: Bool = false, shadow: Bool = false
    ) {
        var attributes: [NSAttributedString.Key: Any] = [
            .font: font, .foregroundColor: color, .kern: kern,
        ]
        if shadow {
            let textShadow = NSShadow()
            textShadow.shadowColor = UIColor.black.withAlphaComponent(0.6)
            textShadow.shadowBlurRadius = 8
            textShadow.shadowOffset = CGSize(width: 0, height: 2)
            attributes[.shadow] = textShadow
        }
        let string = text as NSString
        let originX = centered ? x - string.size(withAttributes: attributes).width / 2 : x
        string.draw(at: CGPoint(x: originX, y: baseline - font.ascender), withAttributes: attributes)
    }

    /// A font shrunk until `text` fits in `maxWidth`.
    private static func fittedFont(_ text: String, size: CGFloat, weight: UIFont.Weight, maxWidth: CGFloat) -> UIFont {
        let font = UIFont.systemFont(ofSize: size, weight: weight)
        let measured = (text as NSString).size(withAttributes: [.font: font]).width
        guard measured > maxWidth, measured > 0 else { return font }
        return UIFont.systemFont(ofSize: size * maxWidth / measured, weight: weight)
    }

    private static func truncated(_ text: String, font: UIFont, maxWidth: CGFloat) -> String {
        func width(_ candidate: String) -> CGFloat {
            (candidate as NSString).size(withAttributes: [.font: font]).width
        }
        guard width(text) > maxWidth else { return text }
        var characters = Array(text)
        while characters.count > 1 && width(String(characters) + "…") > maxWidth {
            characters.removeLast()
        }
        return String(characters).trimmingCharacters(in: .whitespaces) + "…"
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
