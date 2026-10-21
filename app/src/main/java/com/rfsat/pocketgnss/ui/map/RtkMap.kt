package com.rfsat.pocketgnss.ui.map

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rfsat.pocketgnss.R
import com.rfsat.pocketgnss.core.GnssFix
import com.rfsat.pocketgnss.data.Field
import com.rfsat.pocketgnss.data.SurveyPoint
import com.rfsat.pocketgnss.data.Vertex
import com.rfsat.pocketgnss.data.TrackPoint
import com.rfsat.pocketgnss.protocol.Geodetic
import com.rfsat.pocketgnss.ui.fixColorHex
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Base layers, the same three raster sources as DBM's navigation map and PocketSpectro (no API key needed). */
enum class MapLayer(@StringRes val label: Int) { STREET(R.string.map_street), SATELLITE(R.string.map_satellite), TERRAIN(R.string.map_terrain) }

object MapStyles {
    private fun raster(tiles: String, attribution: String, maxZoom: Int = 19) = """
{
  "version": 8,
  "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
  "sources": { "base": { "type": "raster", "tiles": ["$tiles"], "tileSize": 256,
    "attribution": "$attribution", "maxzoom": $maxZoom } },
  "layers": [ { "id": "base", "type": "raster", "source": "base" } ]
}
"""
    private val STREET = raster("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors")
    // Esri imagery is sparse above z18 in rural areas: deeper zooms enlarge z18 tiles instead of fetching blank ones.
    private val SATELLITE = raster("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "© Esri, Maxar, Earthstar Geographics", 18)
    private val TERRAIN = raster("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "© OpenTopoMap (CC-BY-SA)", 17)

    fun styleFor(l: MapLayer) = when (l) { MapLayer.STREET -> STREET; MapLayer.SATELLITE -> SATELLITE; MapLayer.TERRAIN -> TERRAIN }

    /** The base map with the user's own MBTiles map ([ownPath], absolute) drawn over it. */
    fun styleFor(l: MapLayer, ownPath: String?): String {
        val base = styleFor(l)
        if (ownPath.isNullOrBlank()) return base
        val src = ""","own": { "type": "raster", "url": "mbtiles://$ownPath", "tileSize": 256 } },"""
        return base.replaceFirst(" } },", " }$src").replace("""{ "id": "base", "type": "raster", "source": "base" } ]""",
            """{ "id": "base", "type": "raster", "source": "base" }, { "id": "own", "type": "raster", "source": "own" } ]""")
    }
}

/**
 * Zoom used when the app moves the camera (first view, fit, recenter): every base layer has tiles there
 * (OpenTopoMap stops at 17). The user can still zoom in further; MapLibre then enlarges the deepest tiles.
 */
private const val VIEW_ZOOM = 17.0

private const val TRACK_SRC = "r-track"; private const val TRACK_LYR = "r-track-l"
private const val PTS_SRC = "r-pts"; private const val PTS_LYR = "r-pts-l"; private const val PTS_LBL = "r-pts-t"
private const val POS_SRC = "r-pos"; private const val POS_LYR = "r-pos-l"
private const val BASE_SRC = "r-base"; private const val BASE_LYR = "r-base-l"
private const val TGT_SRC = "r-tgt"; private const val TGT_LYR = "r-tgt-l"
private const val FLD_SRC = "r-fld"; private const val FLD_FILL = "r-fld-f"; private const val FLD_LINE = "r-fld-l"; private const val FLD_LBL = "r-fld-t"
private const val CAP_SRC = "r-cap"; private const val CAP_LYR = "r-cap-l"
private const val GDE_SRC = "r-gde"; private const val GDE_LYR = "r-gde-l"

/**
 * MapLibre map of the live position (coloured by fix type), the recent track (segments coloured by fix
 * type), saved survey points with names, the NTRIP base station and a stake-out target. As in DBM, the
 * user's pan and zoom are never overridden: the camera moves on first load, on [fitKey] and [recenterKey]
 * changes, and while [follow] is on.
 */
@Composable
fun RtkMap(
    layer: MapLayer,
    track: List<TrackPoint>,
    points: List<SurveyPoint>,
    position: GnssFix?,
    base: Geodetic?,
    target: SurveyPoint?,
    follow: Boolean,
    fitKey: Int,
    recenterKey: Int,
    onPointTap: (SurveyPoint) -> Unit,
    modifier: Modifier = Modifier,
    fields: List<Field> = emptyList(),
    capture: List<Vertex> = emptyList(),
    guide: List<List<Pair<Double, Double>>> = emptyList(),
    ownMap: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    val mapHolder = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleHolder = remember { mutableStateOf<Style?>(null) }
    val loadedLayer = remember { mutableStateOf<MapLayer?>(null) }
    val loadedOwn = remember { mutableStateOf(ownMap) }
    val didInitial = remember { mutableStateOf(false) }
    // True once the camera has shown real data; until then the first position that arrives is centred once.
    val placed = remember { mutableStateOf(false) }
    val lastFit = remember { mutableStateOf(fitKey) }
    val lastRecenter = remember { mutableStateOf(recenterKey) }
    val currentPoints = remember { mutableStateOf(points) }
    val currentTap = remember { mutableStateOf(onPointTap) }
    currentPoints.value = points; currentTap.value = onPointTap

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); mapView.onStop(); mapView.onDestroy() }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        val map = mapHolder.value
        if (map == null) {
            mv.getMapAsync { m ->
                mapHolder.value = m
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isCompassEnabled = true
                m.uiSettings.isTiltGesturesEnabled = false
                m.addOnMapClickListener { pt ->
                    val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(pt), PTS_LYR).firstOrNull()
                    hit?.getNumberProperty("i")?.toInt()?.let { i -> currentPoints.value.getOrNull(i)?.let { currentTap.value(it) } }
                    hit != null
                }
                m.setStyle(Style.Builder().fromJson(MapStyles.styleFor(layer, ownMap))) { style ->
                    styleHolder.value = style; loadedLayer.value = layer
                    ensureLayers(style); updateData(style, track, points, position, base, target); updateFields(style, fields, capture, guide)
                    if (!didInitial.value) {
                        fitCamera(m, track, points, position, false, fields); didInitial.value = true
                        placed.value = track.isNotEmpty() || points.isNotEmpty() || fields.isNotEmpty() || position != null
                    }
                }
            }
        } else {
            if (loadedLayer.value != layer || loadedOwn.value != ownMap) {
                loadedOwn.value = ownMap
                map.setStyle(Style.Builder().fromJson(MapStyles.styleFor(layer, ownMap))) { style ->
                    styleHolder.value = style; loadedLayer.value = layer
                    ensureLayers(style); updateData(style, track, points, position, base, target); updateFields(style, fields, capture, guide)
                }
            } else styleHolder.value?.let { updateData(it, track, points, position, base, target); updateFields(it, fields, capture, guide) }
            if (!placed.value && didInitial.value && position != null) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.latitude, position.longitude), VIEW_ZOOM))
                placed.value = true
            }
            if (fitKey != lastFit.value) { fitCamera(map, track, points, position, true, fields); lastFit.value = fitKey }
            if (recenterKey != lastRecenter.value) {
                position?.let { map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                    .target(LatLng(it.latitude, it.longitude)).zoom(VIEW_ZOOM).build())) }
                lastRecenter.value = recenterKey
            } else if (follow && position != null) {
                map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(position.latitude, position.longitude)), 300)
            }
        }
    }
}

private fun ensureLayers(style: Style) {
    for (id in listOf(FLD_SRC, CAP_SRC, GDE_SRC, TRACK_SRC, PTS_SRC, POS_SRC, BASE_SRC, TGT_SRC)) if (style.getSource(id) == null) style.addSource(GeoJsonSource(id))
    // Fields under everything else: translucent fill, yellow outline, name and area at the centroid.
    if (style.getLayer(FLD_FILL) == null) style.addLayer(FillLayer(FLD_FILL, FLD_SRC).withProperties(
        PropertyFactory.fillColor("#FFD600"), PropertyFactory.fillOpacity(0.18f)))
    if (style.getLayer(FLD_LINE) == null) style.addLayer(LineLayer(FLD_LINE, FLD_SRC).withProperties(
        PropertyFactory.lineColor("#FFD600"), PropertyFactory.lineWidth(2.5f)))
    if (style.getLayer(GDE_LYR) == null) style.addLayer(LineLayer(GDE_LYR, GDE_SRC).withProperties(
        PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))), PropertyFactory.lineWidth(Expression.get("w")), PropertyFactory.lineOpacity(0.85f)))
    if (style.getLayer(CAP_LYR) == null) style.addLayer(LineLayer(CAP_LYR, CAP_SRC).withProperties(
        PropertyFactory.lineColor("#FF6D00"), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))))
    if (style.getLayer(TRACK_LYR) == null) style.addLayer(LineLayer(TRACK_LYR, TRACK_SRC).withProperties(
        PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))), PropertyFactory.lineWidth(3.5f), PropertyFactory.lineOpacity(0.9f),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
    if (style.getLayer(TGT_LYR) == null) style.addLayer(CircleLayer(TGT_LYR, TGT_SRC).withProperties(
        PropertyFactory.circleRadius(16f), PropertyFactory.circleColor("#00000000"),
        PropertyFactory.circleStrokeColor("#FFC107"), PropertyFactory.circleStrokeWidth(4f)))
    if (style.getLayer(PTS_LYR) == null) style.addLayer(CircleLayer(PTS_LYR, PTS_SRC).withProperties(
        PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#FF7043"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
    if (style.getLayer(PTS_LBL) == null) style.addLayer(SymbolLayer(PTS_LBL, PTS_SRC).withProperties(
        PropertyFactory.textField(Expression.get("n")), PropertyFactory.textSize(12f), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
        PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("#000000"), PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textAllowOverlap(false)))
    if (style.getLayer(BASE_LYR) == null) style.addLayer(CircleLayer(BASE_LYR, BASE_SRC).withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#7B1FA2"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
    if (style.getLayer(POS_LYR) == null) style.addLayer(CircleLayer(POS_LYR, POS_SRC).withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(Expression.toColor(Expression.get("c"))),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
}

private fun updateData(style: Style, track: List<TrackPoint>, points: List<SurveyPoint>, pos: GnssFix?, base: Geodetic?, target: SurveyPoint?) {
    // One line feature per run of the same fix type, so the track shows where RTK was fixed, float or lost.
    val segs = ArrayList<Feature>()
    var i = 0
    while (i < track.size - 1) {
        val q = track[i].quality
        var j = i
        while (j < track.size - 1 && track[j + 1].quality == q) j++
        val end = minOf(j + 1, track.size - 1)
        if (end > i) segs.add(Feature.fromGeometry(LineString.fromLngLats(track.subList(i, end + 1).map { Point.fromLngLat(it.longitude, it.latitude) }))
            .also { it.addStringProperty("c", fixColorHex(q)) })
        i = end
    }
    style.getSourceAs<GeoJsonSource>(TRACK_SRC)?.setGeoJson(FeatureCollection.fromFeatures(segs))
    style.getSourceAs<GeoJsonSource>(PTS_SRC)?.setGeoJson(FeatureCollection.fromFeatures(points.mapIndexed { k, p ->
        Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also { it.addNumberProperty("i", k); it.addStringProperty("n", p.name) }
    }))
    style.getSourceAs<GeoJsonSource>(POS_SRC)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(pos?.let {
        Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).also { f -> f.addStringProperty("c", fixColorHex(it.quality)) }
    })))
    style.getSourceAs<GeoJsonSource>(BASE_SRC)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(base?.let {
        Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
    style.getSourceAs<GeoJsonSource>(TGT_SRC)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(target?.let {
        Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
}

private fun updateFields(style: Style, fields: List<Field>, capture: List<Vertex>, guide: List<List<Pair<Double, Double>>>) {
    style.getSourceAs<GeoJsonSource>(FLD_SRC)?.setGeoJson(FeatureCollection.fromFeatures(fields.filter { it.vertices.size >= 3 }.map { f ->
        val ring = (f.vertices + f.vertices.first()).map { Point.fromLngLat(it.longitude, it.latitude) }
        Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }))
    if (style.getSource(FLD_SRC + "-c") == null) style.addSource(GeoJsonSource(FLD_SRC + "-c"))
    if (style.getLayer(FLD_LBL) == null) style.addLayer(SymbolLayer(FLD_LBL, FLD_SRC + "-c").withProperties(
        PropertyFactory.textField(Expression.get("n")), PropertyFactory.textSize(12f), PropertyFactory.textColor("#FFF59D"),
        PropertyFactory.textHaloColor("#000000"), PropertyFactory.textHaloWidth(1.5f)))
    style.getSourceAs<GeoJsonSource>(FLD_SRC + "-c")?.setGeoJson(FeatureCollection.fromFeatures(fields.filter { it.vertices.size >= 3 }.map { f ->
        Feature.fromGeometry(Point.fromLngLat(f.geometry.centroidLon, f.geometry.centroidLat))
            .also { it.addStringProperty("n", f.name + "\n" + com.rfsat.pocketgnss.ui.formatArea(f.geometry.area)) }
    }))
    style.getSourceAs<GeoJsonSource>(CAP_SRC)?.setGeoJson(FeatureCollection.fromFeatures(if (capture.size >= 2)
        listOf(Feature.fromGeometry(LineString.fromLngLats((capture + capture.first()).map { Point.fromLngLat(it.longitude, it.latitude) }))) else emptyList()))
    // Guidance: the active line first (thick, green), then its neighbours.
    style.getSourceAs<GeoJsonSource>(GDE_SRC)?.setGeoJson(FeatureCollection.fromFeatures(guide.mapIndexed { i, l ->
        Feature.fromGeometry(LineString.fromLngLats(l.map { Point.fromLngLat(it.second, it.first) })).also {
            it.addStringProperty("c", if (i == 0) "#00E676" else "#B0BEC5"); it.addNumberProperty("w", if (i == 0) 4f else 1.5f)
        }
    }))
}

private fun fitCamera(map: MapLibreMap, track: List<TrackPoint>, points: List<SurveyPoint>, pos: GnssFix?, animate: Boolean, fields: List<Field> = emptyList()) {
    val pts = track.map { LatLng(it.latitude, it.longitude) } + points.map { LatLng(it.latitude, it.longitude) } +
        fields.flatMap { f -> f.vertices.map { LatLng(it.latitude, it.longitude) } } +
        listOfNotNull(pos?.let { LatLng(it.latitude, it.longitude) })
    val bounds = if (pts.distinct().size >= 2) LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build() else null
    // A static RTK track spans centimetres: fitting it would zoom far past any tile level, so small extents use VIEW_ZOOM.
    val small = bounds == null || com.rfsat.pocketgnss.protocol.Geo.distance(bounds.latitudeSouth, bounds.longitudeWest,
        bounds.latitudeNorth, bounds.longitudeEast) < 400.0
    val update = when {
        bounds != null && !small -> CameraUpdateFactory.newLatLngBounds(bounds, 120)
        bounds != null -> CameraUpdateFactory.newLatLngZoom(bounds.center, VIEW_ZOOM)
        pts.isNotEmpty() -> CameraUpdateFactory.newLatLngZoom(pts.first(), VIEW_ZOOM)
        else -> CameraUpdateFactory.newLatLngZoom(LatLng(48.0, 10.0), 3.0)
    }
    if (animate) map.animateCamera(update) else map.moveCamera(update)
}
