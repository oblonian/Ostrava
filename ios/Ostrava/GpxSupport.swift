import Foundation

enum GpxExporter {

    static func buildGpx(activity: Activity) -> String {
        let formatter = ISO8601DateFormatter()
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Ostrava" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata>
            <name>\(escape(activity.title))</name>
            <time>\(formatter.string(from: activity.startTime))</time>
          </metadata>
          <trk>
            <name>\(escape(activity.title))</name>
            <type>\(activity.typeRaw.lowercased())</type>

        """
        let segments = Dictionary(grouping: activity.points, by: { $0.segment })
        for key in segments.keys.sorted() {
            xml += "    <trkseg>\n"
            for point in segments[key] ?? [] {
                let time = formatter.string(from: Date(timeIntervalSince1970: point.time))
                xml += "      <trkpt lat=\"\(point.latitude)\" lon=\"\(point.longitude)\">\n"
                xml += "        <ele>\(point.altitude)</ele>\n"
                xml += "        <time>\(time)</time>\n"
                xml += "      </trkpt>\n"
            }
            xml += "    </trkseg>\n"
        }
        xml += "  </trk>\n</gpx>\n"
        return xml
    }

    /// Writes the GPX to a temp file and returns its URL for ShareLink.
    static func exportURL(activity: Activity) -> URL? {
        let safeTitle = activity.title.replacingOccurrences(
            of: "[^A-Za-z0-9 _-]", with: "", options: .regularExpression
        ).replacingOccurrences(of: " ", with: "_")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(safeTitle)_\(activity.uid.uuidString.prefix(8)).gpx")
        do {
            try buildGpx(activity: activity).write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    private static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }
}

/// Streaming GPX parser producing a track plus name/type hints.
final class GpxParser: NSObject, XMLParserDelegate {

    private(set) var title = ""
    private(set) var typeHint = ""
    private(set) var points: [TrackPoint] = []

    private var segment = -1
    private var inTrk = false
    private var inTrkpt = false
    private var currentElement = ""
    private var currentText = ""
    private var lat = 0.0
    private var lon = 0.0
    private var ele = 0.0
    private var time: TimeInterval = 0
    private let isoFormatter = ISO8601DateFormatter()
    private let isoFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    static func parse(url: URL) -> GpxParser? {
        guard let parser = XMLParser(contentsOf: url) else { return nil }
        let delegate = GpxParser()
        parser.delegate = delegate
        return parser.parse() ? delegate : nil
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: [String: String] = [:]
    ) {
        currentElement = elementName
        currentText = ""
        switch elementName {
        case "trk": inTrk = true
        case "trkseg": segment += 1
        case "trkpt":
            inTrkpt = true
            lat = Double(attributes["lat"] ?? "") ?? 0
            lon = Double(attributes["lon"] ?? "") ?? 0
            ele = 0
            time = 0
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?
    ) {
        let text = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        switch elementName {
        case "ele" where inTrkpt:
            ele = Double(text) ?? 0
        case "time" where inTrkpt:
            let date = isoFractional.date(from: text) ?? isoFormatter.date(from: text)
            time = date?.timeIntervalSince1970 ?? 0
        case "name" where inTrk && !inTrkpt && title.isEmpty:
            title = text
        case "type" where inTrk && !inTrkpt && typeHint.isEmpty:
            typeHint = text
        case "trkpt":
            inTrkpt = false
            if time > 0 {
                points.append(
                    TrackPoint(
                        latitude: lat, longitude: lon, altitude: ele,
                        time: time, speed: 0, segment: max(segment, 0)
                    )
                )
            }
        case "trk":
            inTrk = false
        default: break
        }
        currentText = ""
    }

    var activityType: ActivityType {
        let hint = typeHint.lowercased()
        if hint.contains("bik") || hint.contains("cycl") || hint.contains("ride") { return .ride }
        if hint.contains("walk") { return .walk }
        if hint.contains("hik") { return .hike }
        return .run
    }
}
