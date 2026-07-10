import CoreLocation
import Foundation

private func distanceMeters(_ a: TrackPoint, _ b: TrackPoint) -> Double {
    CLLocation(latitude: a.latitude, longitude: a.longitude)
        .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
}

private func distanceToPoint(lat: Double, lon: Double, point: TrackPoint) -> Double {
    CLLocation(latitude: lat, longitude: lon)
        .distance(from: CLLocation(latitude: point.latitude, longitude: point.longitude))
}

struct Split: Identifiable {
    var index: Int
    var distance: Double
    var duration: TimeInterval
    var elevationDelta: Double
    var id: Int { index }

    var paceSecondsPerKm: Double {
        distance > 0 ? duration / (distance / 1000.0) : 0
    }
}

/// Consecutive splits of `splitLength` meters, interpolating boundary crossing time.
func computeSplits(points: [TrackPoint], splitLength: Double) -> [Split] {
    guard points.count >= 2 else { return [] }
    var splits: [Split] = []
    var splitDistance = 0.0
    var splitStartTime = points[0].time
    var splitStartAltitude = points[0].altitude
    var index = 1

    var prev = points[0]
    for point in points.dropFirst() {
        if point.segment != prev.segment {
            splitStartTime += point.time - prev.time
            prev = point
            continue
        }
        var legDistance = distanceMeters(prev, point)
        var legStartTime = prev.time
        var legDuration = point.time - prev.time

        while splitDistance + legDistance >= splitLength && legDistance > 0 {
            let needed = splitLength - splitDistance
            let fraction = needed / legDistance
            let crossingTime = legStartTime + legDuration * fraction
            splits.append(
                Split(
                    index: index,
                    distance: splitLength,
                    duration: crossingTime - splitStartTime,
                    elevationDelta: point.altitude - splitStartAltitude
                )
            )
            index += 1
            splitStartTime = crossingTime
            splitStartAltitude = point.altitude
            legDuration -= legDuration * fraction
            legStartTime = crossingTime
            legDistance -= needed
            splitDistance = 0
        }
        splitDistance += legDistance
        prev = point
    }
    if splitDistance > splitLength * 0.05 {
        splits.append(
            Split(
                index: index,
                distance: splitDistance,
                duration: points[points.count - 1].time - splitStartTime,
                elevationDelta: points[points.count - 1].altitude - splitStartAltitude
            )
        )
    }
    return splits
}

struct BestEffortResult: Identifiable {
    var label: String
    var distance: Double
    var duration: TimeInterval
    var id: String { label }
}

/// Fastest contiguous stretch covering `target` meters (two-pointer sweep).
func bestEffort(points: [TrackPoint], target: Double, label: String) -> BestEffortResult? {
    guard points.count >= 2 else { return nil }
    let n = points.count
    var cumDistance = [Double](repeating: 0, count: n)
    var cumTime = [Double](repeating: 0, count: n)
    for i in 1..<n {
        let same = points[i].segment == points[i - 1].segment
        cumDistance[i] = cumDistance[i - 1] + (same ? distanceMeters(points[i - 1], points[i]) : 0)
        cumTime[i] = cumTime[i - 1] + (same ? points[i].time - points[i - 1].time : 0)
    }
    guard cumDistance[n - 1] >= target else { return nil }

    var best = Double.greatestFiniteMagnitude
    var start = 0
    for end in 1..<n {
        while start + 1 < end && cumDistance[end] - cumDistance[start + 1] >= target {
            start += 1
        }
        if cumDistance[end] - cumDistance[start] >= target {
            let duration = cumTime[end] - cumTime[start]
            if duration > 0 && duration < best { best = duration }
        }
    }
    return best == .greatestFiniteMagnitude ? nil
        : BestEffortResult(label: label, distance: target, duration: best)
}

func bestEffortsFor(type: ActivityType, points: [TrackPoint]) -> [BestEffortResult] {
    let targets: [(String, Double)]
    switch type {
    case .run:
        targets = [("1 km", 1000), ("5 km", 5000), ("10 km", 10000), ("Half marathon", 21097.5)]
    case .ride:
        targets = [("5 km", 5000), ("20 km", 20000), ("40 km", 40000)]
    default:
        targets = [("1 km", 1000), ("5 km", 5000)]
    }
    return targets.compactMap { bestEffort(points: points, target: $0.1, label: $0.0) }
}

/// MET-based calorie estimate.
func estimateCalories(type: ActivityType, avgSpeedMps: Double, movingTime: TimeInterval, weightKg: Double) -> Int {
    let kmh = avgSpeedMps * 3.6
    let met: Double
    switch type {
    case .run: met = max(6.0, kmh)
    case .ride:
        if kmh < 16 { met = 6 } else if kmh < 20 { met = 8 } else if kmh < 25 { met = 10 } else { met = 12 }
    case .walk: met = 3.5
    case .hike: met = 6.0
    }
    return Int(met * weightKg * movingTime / 3600.0)
}

struct ComputedStats {
    var distance: Double
    var movingTime: TimeInterval
    var elevationGain: Double
    var avgSpeed: Double
    var maxSpeed: Double
}

/// Summary stats from a raw track (GPX import, backup restore).
func computeActivityStats(points: [TrackPoint]) -> ComputedStats {
    guard points.count >= 2 else {
        return ComputedStats(distance: 0, movingTime: 0, elevationGain: 0, avgSpeed: 0, maxSpeed: 0)
    }
    var distance = 0.0
    var movingTime = 0.0
    var gain = 0.0
    var maxSpeed = 0.0
    var baseline = points[0].altitude

    for i in 1..<points.count {
        let prev = points[i - 1]
        let point = points[i]
        if point.segment != prev.segment {
            baseline = point.altitude
            continue
        }
        let d = distanceMeters(prev, point)
        let dt = point.time - prev.time
        distance += d
        if dt > 0 && dt <= 15 {
            movingTime += dt
            let legSpeed = d / dt
            if legSpeed > maxSpeed && legSpeed < 40 { maxSpeed = legSpeed }
        }
        if point.altitude - baseline >= 2 {
            gain += point.altitude - baseline
            baseline = point.altitude
        } else if point.altitude < baseline {
            baseline = point.altitude
        }
    }
    let avgSpeed = movingTime > 0 ? distance / movingTime : 0
    return ComputedStats(
        distance: distance, movingTime: movingTime, elevationGain: gain,
        avgSpeed: avgSpeed, maxSpeed: maxSpeed
    )
}

struct SegmentMatch {
    var duration: TimeInterval
    var startTime: TimeInterval
}

private let segmentMatchRadius = 40.0

/// Fastest traversal of a start->end gate pair within a track, if any.
func matchSegment(
    startLat: Double, startLon: Double,
    endLat: Double, endLon: Double,
    segmentDistance: Double,
    points: [TrackPoint]
) -> SegmentMatch? {
    guard points.count >= 2, segmentDistance > 0 else { return nil }
    let n = points.count
    var cumDistance = [Double](repeating: 0, count: n)
    var cumTime = [Double](repeating: 0, count: n)
    for i in 1..<n {
        let same = points[i].segment == points[i - 1].segment
        cumDistance[i] = cumDistance[i - 1] + (same ? distanceMeters(points[i - 1], points[i]) : 0)
        cumTime[i] = cumTime[i - 1] + (same ? points[i].time - points[i - 1].time : 0)
    }

    let minPath = segmentDistance * 0.75
    let maxPath = segmentDistance * 1.35
    var best: SegmentMatch?

    var i = 0
    while i < n {
        if distanceToPoint(lat: startLat, lon: startLon, point: points[i]) <= segmentMatchRadius {
            var j = i + 1
            while j < n && cumDistance[j] - cumDistance[i] <= maxPath {
                if cumDistance[j] - cumDistance[i] >= minPath
                    && distanceToPoint(lat: endLat, lon: endLon, point: points[j]) <= segmentMatchRadius {
                    let duration = cumTime[j] - cumTime[i]
                    if duration > 0 && (best == nil || duration < best!.duration) {
                        best = SegmentMatch(duration: duration, startTime: points[i].time)
                    }
                    break
                }
                j += 1
            }
            while i + 1 < n
                && distanceToPoint(lat: startLat, lon: startLon, point: points[i + 1]) <= segmentMatchRadius {
                i += 1
            }
        }
        i += 1
    }
    return best
}

/// (current, longest) weekly streaks; the present week may still be empty.
func weeklyStreaks(activeWeekIndices: Set<Int>, currentWeekIndex: Int) -> (current: Int, longest: Int) {
    guard !activeWeekIndices.isEmpty else { return (0, 0) }

    var current = 0
    var week = activeWeekIndices.contains(currentWeekIndex) ? currentWeekIndex : currentWeekIndex - 1
    while activeWeekIndices.contains(week) {
        current += 1
        week -= 1
    }

    var longest = 0
    var run = 0
    var previous: Int?
    for w in activeWeekIndices.sorted() {
        run = (previous != nil && w == previous! + 1) ? run + 1 : 1
        longest = max(longest, run)
        previous = w
    }
    return (current, longest)
}

/// Monday-based week index for streak/load bucketing.
func weekIndex(of date: Date) -> Int {
    var calendar = Calendar(identifier: .iso8601)
    calendar.firstWeekday = 2
    let monday = calendar.dateInterval(of: .weekOfYear, for: date)?.start ?? date
    return Int(monday.timeIntervalSince1970 / 604_800)
}
