package com.ostrava.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ostrava.app.domain.TrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * OpenStreetMap view rendering the recorded route, one polyline per pause-segment.
 * [followLast] keeps the camera on the latest fix (live recording); otherwise the
 * camera fits the whole route once.
 */
@Composable
fun RouteMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    currentFix: TrackPoint? = null,
    followLast: Boolean = false,
    interactive: Boolean = true,
) {
    val context = LocalContext.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.clear()

            points.groupBy { it.segment }.toSortedMap().values.forEach { segment ->
                if (segment.size >= 2) {
                    val polyline = Polyline(map).apply {
                        outlinePaint.color = routeColor
                        outlinePaint.strokeWidth = 10f
                        setPoints(segment.map { GeoPoint(it.latitude, it.longitude) })
                    }
                    map.overlays.add(polyline)
                }
            }

            val markerPoint = currentFix ?: points.lastOrNull()
            if (markerPoint != null) {
                val marker = Marker(map).apply {
                    position = GeoPoint(markerPoint.latitude, markerPoint.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(marker)
            }

            if (followLast && markerPoint != null) {
                val target = GeoPoint(markerPoint.latitude, markerPoint.longitude)
                val previous = map.tag as? GeoPoint
                when {
                    // First fix: jump straight there instead of animating from (0, 0).
                    previous == null -> {
                        map.controller.setCenter(target)
                        map.tag = target
                    }
                    previous.latitude != target.latitude || previous.longitude != target.longitude -> {
                        map.controller.animateTo(target)
                        map.tag = target
                    }
                }
            } else if (!followLast && points.size >= 2) {
                val box = BoundingBox.fromGeoPointsSafe(points.map { GeoPoint(it.latitude, it.longitude) })
                // zoomToBoundingBox needs a measured view; before layout it is a no-op.
                if (map.isLayoutOccurred) {
                    map.zoomToBoundingBox(box.increaseByScale(1.4f), false)
                } else {
                    map.controller.setCenter(box.centerWithDateLine)
                    map.addOnFirstLayoutListener { _, _, _, _, _ ->
                        map.zoomToBoundingBox(box.increaseByScale(1.4f), false)
                    }
                }
            } else if (!followLast && markerPoint != null) {
                map.controller.setCenter(GeoPoint(markerPoint.latitude, markerPoint.longitude))
            }
            map.invalidate()
        },
    )
}
