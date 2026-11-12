package dev.netcheck.app.ui

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.netcheck.app.data.MeasurementEntity
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val POINT_SOURCE = "measurements"
private const val ROUTE_SOURCE = "routes"
private const val POINT_LAYER = "measurement-points"

fun pointGeoJson(measurements: List<MeasurementEntity>): String {
    val features = JSONArray()
    measurements.forEach { m ->
        val lat = m.latitude ?: return@forEach
        val lon = m.longitude ?: return@forEach
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
        val properties = JSONObject().put("id", m.id).put("color", if (validRoutePoint(m)) mapColor(m.status) else "#7D8992")
        features.put(JSONObject().put("type", "Feature").put("properties", properties).put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

fun routeGeoJson(measurements: List<MeasurementEntity>): String {
    val features = JSONArray()
    routeSegments(measurements).forEach { segment ->
        val coordinates = JSONArray()
        segment.forEach { coordinates.put(JSONArray().put(it.longitude).put(it.latitude)) }
        features.put(JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates)))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

@Composable fun MeasurementMap(measurements: List<MeasurementEntity>, cameraKey: String, speedRunning: Boolean, modifier: Modifier = Modifier, onSelect: (MeasurementEntity) -> Unit) {
    if (speedRunning) {
        Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            EmptyState("속도 측정 중에는 지도를 쉬어갑니다", "지도 다운로드가 측정에 영향을 주지 않도록 잠시 멈췄습니다.")
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var retry by remember { mutableIntStateOf(0) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var readyMap by remember(retry) { mutableStateOf<MapLibreMap?>(null) }
    var readyStyle by remember(retry) { mutableStateOf<Style?>(null) }
    var fittedKey by remember(retry) { mutableStateOf<String?>(null) }
    val currentMeasurements by rememberUpdatedState(measurements)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val mapView = remember(retry) {
        // OpenGL is supported on Android 10 devices and emulators without Vulkan GPUs.
        MapLibre.getInstance(context, null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL)
        MapLibre.setConnected(null)
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(50L * 1024L * 1024L, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = Unit
            override fun onError(message: String) { android.util.Log.w("NetCheckMap", "Map cache limit could not be applied") }
        })
        MapView(context).apply {
            onCreate(Bundle())
            addOnDidFailLoadingMapListener { error -> mapError = error }
            getMapAsync { map ->
                readyMap = map
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = false
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(36.3, 127.7), 5.3))
                map.addOnMapClickListener { coordinate ->
                    val hits = map.queryRenderedFeatures(map.projection.toScreenLocation(coordinate), POINT_LAYER)
                    val id = hits.firstOrNull()?.getNumberProperty("id")?.toLong()
                    currentMeasurements.firstOrNull { it.id == id }?.let(currentOnSelect)
                    id != null
                }
                map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                    style.addSource(GeoJsonSource(ROUTE_SOURCE, routeGeoJson(emptyList())))
                    style.addSource(GeoJsonSource(POINT_SOURCE, pointGeoJson(emptyList())))
                    style.addLayer(LineLayer("measurement-route", ROUTE_SOURCE).withProperties(lineColor("#087F72"), lineWidth(3f), lineOpacity(.65f)))
                    style.addLayer(CircleLayer(POINT_LAYER, POINT_SOURCE).withProperties(circleColor(Expression.get("color")), circleRadius(6f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                    readyStyle = style
                    mapError = null
                }
            }
        }
    }
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { if (!started) { MapLibre.setConnected(null); mapView.onStart(); started = true } }
                Lifecycle.Event.ON_RESUME -> { if (!resumed) { mapView.onResume(); resumed = true } }
                Lifecycle.Event.ON_PAUSE -> { if (resumed) { mapView.onPause(); resumed = false } }
                Lifecycle.Event.ON_STOP -> { if (started) { MapLibre.setConnected(false); mapView.onStop(); started = false } }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            MapLibre.setConnected(false)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(readyStyle, measurements, cameraKey) {
        val style = readyStyle ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(POINT_SOURCE)?.setGeoJson(pointGeoJson(measurements))
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(routeGeoJson(measurements))
        val points = measurements.filter { it.latitude != null && it.longitude != null && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        if (points.isNotEmpty() && fittedKey != cameraKey) {
            val map = readyMap ?: return@LaunchedEffect
            val unique = points.map { LatLng(it.latitude!!, it.longitude!!) }.distinctBy { "${it.latitude},${it.longitude}" }
            if (unique.size == 1) map.moveCamera(CameraUpdateFactory.newLatLngZoom(unique.first(), 15.0))
            else map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(unique).build(), 60))
            fittedKey = cameraKey
        }
    }
    Box(modifier.fillMaxWidth()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (readyStyle == null && mapError == null) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        if (mapError != null) Card(Modifier.align(Alignment.TopCenter).padding(12.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("배경 지도를 불러오지 못했어요", style = MaterialTheme.typography.titleSmall)
                Text("측정과 기록 저장은 계속됩니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { mapError = null; retry++ }) { Icon(Icons.Outlined.Refresh, null); Text("다시 불러오기") }
            }
        }
        if (measurements.none { it.latitude != null && it.longitude != null } && mapError == null) Surface(Modifier.align(Alignment.TopCenter).padding(16.dp), shape = RoundedCornerShape(14.dp), tonalElevation = 4.dp) {
            Text("위치가 포함된 기록이 생기면 지도에 표시합니다.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
