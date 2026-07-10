import Foundation

let metersPerMile = 1609.344
let feetPerMeter = 3.28084

func formatDistance(_ meters: Double, imperial: Bool) -> String {
    imperial ? String(format: "%.2f mi", meters / metersPerMile)
        : String(format: "%.2f km", meters / 1000.0)
}

func formatDistanceShort(_ meters: Double, imperial: Bool) -> String {
    imperial ? String(format: "%.1f mi", meters / metersPerMile)
        : String(format: "%.1f km", meters / 1000.0)
}

func formatDuration(_ interval: TimeInterval) -> String {
    let total = Int(interval)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let seconds = total % 60
    return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
        : String(format: "%d:%02d", minutes, seconds)
}

/// Pace from speed, e.g. "5:30 /km".
func formatPace(speedMps: Double, imperial: Bool) -> String {
    guard speedMps >= 0.2 else { return "--:--" }
    let secondsPerUnit = (imperial ? metersPerMile : 1000.0) / speedMps
    return formatPaceSeconds(secondsPerUnit, imperial: imperial)
}

func formatPaceSeconds(_ secondsPerUnit: Double, imperial: Bool) -> String {
    guard secondsPerUnit > 0, secondsPerUnit <= 5400 else { return "--:--" }
    let minutes = Int(secondsPerUnit) / 60
    let seconds = Int(secondsPerUnit) % 60
    return String(format: "%d:%02d /%@", minutes, seconds, imperial ? "mi" : "km")
}

func formatSpeed(_ speedMps: Double, imperial: Bool) -> String {
    imperial ? String(format: "%.1f mph", speedMps * 3600 / metersPerMile)
        : String(format: "%.1f km/h", speedMps * 3.6)
}

func formatElevation(_ meters: Double, imperial: Bool) -> String {
    imperial ? String(format: "%.0f ft", meters * feetPerMeter)
        : String(format: "%.0f m", meters)
}

func formatDateTime(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.dateFormat = "EEE, d MMM yyyy 'at' HH:mm"
    return formatter.string(from: date)
}

func formatDate(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.dateFormat = "d MMM yyyy"
    return formatter.string(from: date)
}

/// Duration phrased for text-to-speech, e.g. "1 hour 5 minutes 20 seconds".
func spokenDuration(_ interval: TimeInterval) -> String {
    let total = Int(interval)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let seconds = total % 60
    var parts: [String] = []
    if hours > 0 { parts.append("\(hours) hour\(hours == 1 ? "" : "s")") }
    if minutes > 0 { parts.append("\(minutes) minute\(minutes == 1 ? "" : "s")") }
    if seconds > 0 || parts.isEmpty { parts.append("\(seconds) second\(seconds == 1 ? "" : "s")") }
    return parts.joined(separator: " ")
}

/// Default title like "Morning Run" from the start hour.
func defaultActivityTitle(type: ActivityType, start: Date) -> String {
    let hour = Calendar.current.component(.hour, from: start)
    let period: String
    switch hour {
    case 5...11: period = "Morning"
    case 12...16: period = "Afternoon"
    case 17...20: period = "Evening"
    default: period = "Night"
    }
    return "\(period) \(type.label)"
}
