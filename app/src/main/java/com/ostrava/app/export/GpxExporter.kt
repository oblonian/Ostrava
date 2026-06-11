package com.ostrava.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.TrackPoint
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object GpxExporter {

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun buildGpx(activity: ActivityEntity, points: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="Ostrava" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(activity.title)).append("</name>\n")
        sb.append("    <time>").append(timestampFormatter.format(Instant.ofEpochMilli(activity.startTime)))
            .append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(activity.title)).append("</name>\n")
        sb.append("    <type>").append(activity.type.lowercase()).append("</type>\n")
        points.groupBy { it.segment }.toSortedMap().values.forEach { segment ->
            sb.append("    <trkseg>\n")
            segment.forEach { point ->
                sb.append("      <trkpt lat=\"").append(point.latitude).append("\" lon=\"")
                    .append(point.longitude).append("\">\n")
                sb.append("        <ele>").append(point.altitude).append("</ele>\n")
                sb.append("        <time>")
                    .append(timestampFormatter.format(Instant.ofEpochMilli(point.timeMillis)))
                    .append("</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Writes the GPX to the cache dir and fires an ACTION_SEND share sheet. */
    fun shareGpx(context: Context, activity: ActivityEntity, points: List<TrackPoint>) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = activity.title.replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_')
        val file = File(exportsDir, "${safeTitle}_${activity.id}.gpx")
        file.writeText(buildGpx(activity, points))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export GPX"))
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
