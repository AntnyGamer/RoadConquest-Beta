package com.roadconquest.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.util.Prefs
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

class MapRenderer(
    private val context: Context,
    private val map: MapLibreMap,
    private val repository: TrackingRepository,
    private val mapView: MapView,
    initiallyCentered: Boolean = false
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val fogExecutor = Executors.newSingleThreadExecutor()
    private val overlayExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var centeredOnce = initiallyCentered
    val hasCentered: Boolean get() = centeredOnce
    private var mapMode = Prefs.mapMode(context)
    private var styleGeneration = 0
    private var queryRunning = false
    private var queryAgain = false
    private var queryRoadsAgain = false
    private var viewportRevision = 0
    private var cameraMoving = false
    private var resumeFrameListener: MapView.OnDidFinishRenderingFrameListener? = null
    private var resumeGeneration = 0
    private var fogRunning = false
    private var fogAgain = false
    private var reusableFogBitmap: Bitmap? = null
    private var carIconBitmap: Bitmap? = null
    private var appliedCarStyle = ""
    private var appliedCarColor = ""
    private var appliedRoadColor = ""
    private var fogEnabled = Prefs.isFogEnabled(context)
    private var overlayMode = Prefs.placeOverlayMode(context)
    private val overlayClient = PlaceOverlayClient()
    @Volatile private var overlayGeneration = 0
    @Volatile private var destroyed = false
    private val liveLocation = LiveLocation()
    private val fogTextureTransform = FogTextureTransform()
    private var displayedRoads = OverlayRoads.EMPTY
    private var displayedRoadFeatures = EMPTY_FEATURES
    private var displayedPlaces = doubleArrayOf()
    private var loadedRoadBounds: RoadQueryBounds? = null
    private var loadedPlaceBounds: RoadQueryBounds? = null
    private var roadDataRevision = 0
    private var placeDataRevision = 0
    private var roadProjectionDataRevision = -1
    private var roadProjectionViewportRevision = -1
    private var placeProjectionDataRevision = -1
    private var placeProjectionViewportRevision = -1
    private var roadProjectionWidth = -1
    private var roadProjectionHeight = -1
    private var placeProjectionWidth = -1
    private var placeProjectionHeight = -1
    private var roadScreenCache = doubleArrayOf()
    private var placeScreenCache = doubleArrayOf()
    private val liveCoordinates = DoubleArray(2)
    private val liveScreenCache = DoubleArray(2)
    private val fogMatrix = Matrix()
    private val fogMatrixValues = FloatArray(9)
    private var detailedFogCoordinates: DoubleArray? = null
    private val fogCoverageScreen = DoubleArray(8)
    private var showingDetailedFog = false
    private var fogHandoffGeneration = 0
    private var fogHandoffFrameListener: MapView.OnDidFinishRenderingFrameListener? = null
    private var lastFogRenderAt = 0L
    private var minimumZoom = Double.NaN
    private val renderFog = Runnable { scheduleFogRender() }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val cameraPosition = map.cameraPosition
        updateCameraLimits(cameraPosition)
        updateFogCoverage(cameraPosition = cameraPosition)
        scheduleFogRender(cameraPosition)
    }

    private val expireLocation = Runnable {
        if (!destroyed && liveLocation.current(SystemClock.elapsedRealtime()) == null) clearCurrentLocation()
    }
    private val cameraMoveStartedListener = MapLibreMap.OnCameraMoveStartedListener {
        // Native animations can advance before the next Java camera-move callback.
        // Cover the whole world before motion starts, not after a bitmap edge escapes.
        cameraMoving = true
        updateFogCoverage()
    }
    private val cameraMoveListener = MapLibreMap.OnCameraMoveListener {
        cameraMoving = true
        viewportRevision++
        // CameraPosition crosses the MapLibre/native boundary. Snapshot it once per move callback
        // and share that exact frame between the limit and coverage calculations.
        val cameraPosition = map.cameraPosition
        updateCameraLimits(cameraPosition)
        updateFogCoverage(cameraPosition = cameraPosition)
        if (cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM) {
            scheduleFogRender(cameraPosition)
        }
    }
    private val cameraIdleListener = MapLibreMap.OnCameraIdleListener {
        cameraMoving = false
        val cameraPosition = map.cameraPosition
        updateFogCoverage(cameraPosition = cameraPosition)
        refreshViewport()
        scheduleFogRender(cameraPosition)
    }

    fun initialize(onReady: () -> Unit) {
        map.setMaxZoomPreference(FogBitmapRenderer.MAX_ZOOM)
        map.setMaxPitchPreference(0.0)
        map.uiSettings.setTiltGesturesEnabled(false)
        mapView.addOnLayoutChangeListener(layoutListener)
        updateCameraLimits()
        map.addOnCameraMoveStartedListener(cameraMoveStartedListener)
        map.addOnCameraMoveListener(cameraMoveListener)
        map.addOnCameraIdleListener(cameraIdleListener)
        loadStyle(onReady)
    }

    fun setMapMode(mode: MapMode) {
        if (destroyed || mode == mapMode) return
        mapMode = mode
        loadStyle { }
    }

    fun setFogEnabled(enabled: Boolean) {
        if (destroyed) return
        fogEnabled = enabled
        val cameraPosition = map.cameraPosition
        updateFogCoverage(force = true, cameraPosition = cameraPosition)
        if (enabled) scheduleFogRender(cameraPosition)
    }

    fun placeOverlayMode(): PlaceOverlayMode = overlayMode

    fun setPlaceOverlayMode(mode: PlaceOverlayMode) {
        if (destroyed || mode == overlayMode) return
        overlayMode = mode
        Prefs.setPlaceOverlayMode(context, mode)
        refreshPlaceOverlays()
    }

    fun refreshPlaceOverlays() {
        if (destroyed) return
        val generation = ++overlayGeneration
        clearPlaceOverlaySources()
        val kind = overlayMode.kind ?: return
        val cacheGeneration = PlaceOverlayCache.generation()
        overlayExecutor.execute {
            val places = runCatching { ProgressionRepository(context).visitedPlaces(kind) }
                .getOrElse {
                    Log.e("RoadConquest", "Could not load discovered places for overlay", it)
                    return@execute
                }
            if (destroyed || generation != overlayGeneration ||
                cacheGeneration != PlaceOverlayCache.generation()) return@execute
            val loaded = ArrayList<PlaceOverlayData>(places.size)
            val missing = ArrayList<com.roadconquest.app.data.PlaceDiscovery>()
            for (place in places) {
                val cached = PlaceOverlayCache.read(context, place)
                if (!cached.cached) missing += place
                cached.data?.let(loaded::add)
            }
            postPlaceOverlay(generation, kind, loaded)
            var fetchedSincePost = 0
            for ((index, place) in missing.withIndex()) {
                if (destroyed || generation != overlayGeneration ||
                    cacheGeneration != PlaceOverlayCache.generation()) return@execute
                val result = runCatching { overlayClient.fetch(place) }
                if (result.isFailure) {
                    Log.w("RoadConquest", "Could not load place boundary", result.exceptionOrNull())
                    continue
                }
                if (cacheGeneration != PlaceOverlayCache.generation()) return@execute
                val data = result.getOrNull()
                if (!PlaceOverlayCache.write(context, place, data, cacheGeneration)) return@execute
                if (data != null) {
                    loaded += data
                    fetchedSincePost++
                    if (fetchedSincePost >= OVERLAY_UPDATE_BATCH) {
                        postPlaceOverlay(generation, kind, loaded)
                        fetchedSincePost = 0
                    }
                }
            }
            // Flush a partial final batch even when the last request failed or returned no
            // boundary. Successful earlier downloads should appear without waiting for refresh.
            if (fetchedSincePost > 0) postPlaceOverlay(generation, kind, loaded)
        }
    }

    fun cancelPlaceOverlayLoads() {
        overlayGeneration++
    }

    fun overlayInfoAt(screenPoint: PointF): PlaceOverlayInfo? {
        val mode = overlayMode
        val kind = mode.kind ?: return null
        val layerId = overlayLayerId(mode)
        val feature = map.queryRenderedFeatures(screenPoint, layerId).firstOrNull() ?: return null
        val name = feature.getStringProperty("overlay_name") ?: return null
        val featureKind = runCatching {
            PlaceKind.valueOf(feature.getStringProperty("overlay_kind") ?: kind.name)
        }.getOrDefault(kind)
        val population = feature.getNumberProperty("population")?.toLong()
        val area = feature.getNumberProperty("area_sq_km")?.toDouble() ?: return null
        return PlaceOverlayInfo(name, featureKind, population, area)
    }

    fun refreshCosmetics() {
        if (destroyed) return
        val style = map.style ?: return
        val road = Prefs.roadColor(context)
        if (road != appliedRoadColor) {
            val color = Cosmetics.roadColor(context).argb
            (style.getLayer(ROAD_LAYER_ID) as? LineLayer)?.setProperties(lineColor(color))
            (style.getLayer(PENDING_ROUTE_LAYER_ID) as? LineLayer)?.setProperties(lineColor(color))
            appliedRoadColor = road
        }
        val carStyle = Prefs.carStyle(context)
        val carColor = Prefs.carColor(context)
        if (carStyle != appliedCarStyle || carColor != appliedCarColor) {
            updateCarImage(style)
            appliedCarStyle = carStyle
            appliedCarColor = carColor
        }
    }

    private fun loadStyle(onReady: () -> Unit) {
        val generation = ++styleGeneration
        val styleJson = context.assets.open(mapMode.styleAsset).bufferedReader().use { it.readText() }
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            if (destroyed || generation != styleGeneration) return@setStyle
            installPlaceOverlayLayers(style)
            installFogLayer(style)
            installRecordedRouteLayer(style)
            installRoadLayer(style)
            installCarLayer(style)
            updateCarLayer()
            refreshViewport()
            scheduleFogRender()
            onReady()
        }
    }

    fun updateCar(latitude: Double, longitude: Double, bearing: Double, ageMillis: Long = 0L) {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val previousVisual = liveLocation.current(now)
        if (!liveLocation.update(latitude, longitude, bearing, now, ageMillis)) return
        mainHandler.removeCallbacks(expireLocation)
        mainHandler.postDelayed(expireLocation, LiveLocation.MAX_AGE_MS - ageMillis)

        // Equal live fixes can still be newer and must extend expiry, but they do not need
        // another MapLibre source upload or fog bitmap render. Only skip work when every
        // user-visible value is exactly unchanged.
        val currentVisual = liveLocation.current(now)
        val positionChanged = previousVisual == null || currentVisual == null ||
            previousVisual.latitude != currentVisual.latitude ||
            previousVisual.longitude != currentVisual.longitude
        val bearingChanged = previousVisual == null || currentVisual == null ||
            previousVisual.bearing != currentVisual.bearing
        if (positionChanged) {
            updateCarLayer()
            scheduleFogRender()
        } else if (bearingChanged) {
            // Heading-only updates still rotate the marker, but do not re-upload identical
            // GeoJSON or rebuild fog because neither depends on bearing.
            currentVisual?.let { fix ->
                (map.style?.getLayer(CAR_LAYER_ID) as? SymbolLayer)
                    ?.setProperties(iconRotate(fix.bearing.toFloat()))
            }
        }
        if (!centeredOnce) {
            centeredOnce = true
            centerOnCar()
        }
    }

    fun centerOnCar(): Boolean {
        if (destroyed) return false
        val fix = liveLocation.current(SystemClock.elapsedRealtime()) ?: return false
        centeredOnce = true
        val currentCamera = map.cameraPosition
        val camera = CameraPosition.Builder(currentCamera)
            .target(LatLng(fix.latitude, fix.longitude))
            .zoom(currentCamera.zoom.coerceIn(FogBitmapRenderer.CENTER_ZOOM, FogBitmapRenderer.MAX_ZOOM))
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera))
        return true
    }

    fun centerOnStartingLocation(latitude: Double, longitude: Double, force: Boolean = false): Boolean {
        if (destroyed || (!force && centeredOnce) || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.05112878..85.05112878 || longitude !in -180.0..180.0
        ) return false
        centeredOnce = true
        val camera = CameraPosition.Builder(map.cameraPosition)
            .target(LatLng(latitude, longitude))
            .zoom(STARTING_LOCATION_ZOOM)
            .tilt(0.0)
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        return true
    }

    fun clearCurrentLocation() {
        if (destroyed) return
        mainHandler.removeCallbacks(expireLocation)
        // refreshControls can ask for a clear repeatedly while Location stays unavailable.
        // Once the fix is already gone, another MapLibre upload and fog render cannot change
        // anything visible, so skip that work entirely.
        if (!liveLocation.clear()) return
        updateCarLayer()
        scheduleFogRender()
    }

    fun refreshViewport() = refreshViewport(loadRoads = true)

    fun refreshRoads() {
        loadedRoadBounds = null
        refreshViewport(loadRoads = true)
    }

    fun resumeViewport(reloadRoads: Boolean) {
        if (destroyed) return
        if (reloadRoads) loadedRoadBounds = null
        loadedPlaceBounds = null
        // onResume runs just before the window becomes visible. Arming the frame callback there
        // can lose the forced repaint, leaving MapLibre's transient overview camera in use.
        cameraMoving = false
        viewportRevision++
        val generation = ++resumeGeneration
        resumeFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        resumeFrameListener = null
        lateinit var awaitVisible: Runnable
        awaitVisible = Runnable {
            if (destroyed || generation != resumeGeneration) return@Runnable
            if (!mapView.isShown || mapView.width <= 0 || mapView.height <= 0) {
                mainHandler.postDelayed(awaitVisible, RESUME_VISIBILITY_RETRY_MS)
                return@Runnable
            }
            lateinit var listener: MapView.OnDidFinishRenderingFrameListener
            listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                mapView.removeOnDidFinishRenderingFrameListener(listener)
                if (resumeFrameListener === listener) resumeFrameListener = null
                if (!destroyed && generation == resumeGeneration) {
                    cameraMoving = false
                    viewportRevision++
                    refreshViewport(loadRoads = true)
                }
            }
            resumeFrameListener = listener
            mapView.addOnDidFinishRenderingFrameListener(listener)
            map.triggerRepaint()
        }
        mainHandler.post(awaitVisible)
    }

    fun refreshExploration() {
        loadedPlaceBounds = null
        refreshViewport(loadRoads = false)
    }

    fun refreshTracking() = refreshViewport(loadRoads = false)

    private fun refreshViewport(loadRoads: Boolean) {
        if (destroyed) return
        if (cameraMoving || queryRunning) {
            queryAgain = true
            queryRoadsAgain = queryRoadsAgain || loadRoads
            return
        }
        if (map.cameraPosition.zoom < FogBitmapRenderer.MIN_ROAD_ZOOM) {
            (map.style?.getSource(PENDING_ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(EMPTY_FEATURES)
            loadedPlaceBounds = null
            setDisplayedPlaces(doubleArrayOf())
            if (loadRoads) {
                loadedRoadBounds = null
                setDisplayedRoads(OverlayRoads.EMPTY, emptyRoadFeatures())
            } else {
                scheduleFogRender()
            }
            return
        }
        val bounds = map.projection.visibleRegion.latLngBounds
        val north = bounds.latitudeNorth
        val south = bounds.latitudeSouth
        val width = mapView.width.toFloat()
        val height = mapView.height.toFloat()
        val topLeft = unwrappedCorner(0f, 0f).longitude
        val topRight = unwrappedCorner(width, 0f).longitude
        val bottomRight = unwrappedCorner(width, height).longitude
        val bottomLeft = unwrappedCorner(0f, height).longitude
        val minLongitude = minOf(topLeft, topRight, bottomRight, bottomLeft)
        val maxLongitude = maxOf(topLeft, topRight, bottomRight, bottomLeft)
        fun wrappedLongitude(longitude: Double) = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val east = wrappedLongitude(maxLongitude)
        val west = wrappedLongitude(minLongitude)
        val revision = viewportRevision
        queryAgain = false
        queryRoadsAgain = false
        queryRunning = true
        val loadedRoadBoundsAtStart = loadedRoadBounds
        val loadedPlaceBoundsAtStart = loadedPlaceBounds
        executor.execute {
            val result = runCatching {
                // Match the larger native fog footprint so a normal pan continues to show
                // already-unlocked roads while the gesture is in progress.
                val fadePad = FogBitmapRenderer.ROAD_FULL_M / 110_000.0
                val latPad = (north - south) * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER + fadePad
                val longitudeSpan = if (east >= west) east - west else east - west + 360.0
                val lonPad = longitudeSpan * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER +
                    fadePad / cos(Math.toRadians((north + south) / 2)).coerceAtLeast(0.01)
                fun wrap(longitude: Double) = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                val querySpan = (longitudeSpan + 2 * lonPad).coerceAtMost(360.0)
                val queryWest = if (querySpan >= 360.0) -180.0 else wrap(west - lonPad)
                val queryEast = if (querySpan >= 360.0) 180.0 else wrap(east + lonPad)
                val queryNorth = (north + latPad).coerceAtMost(90.0)
                val querySouth = (south - latPad).coerceAtLeast(-90.0)
                val footprint = RoadQueryBounds(
                    queryNorth,
                    querySouth,
                    wrap((minLongitude + maxLongitude) / 2.0),
                    querySpan
                )
                val queryRoads = loadRoads && loadedRoadBoundsAtStart?.contains(footprint) != true
                val queryPlaces = loadedPlaceBoundsAtStart?.contains(footprint) != true
                val roadQuery = if (queryRoads) {
                    repository.getRoadsInBoundsResult(
                        queryNorth, queryEast, querySouth, queryWest, limit = Int.MAX_VALUE
                    )
                } else {
                    null
                }
                val roads = roadQuery?.roads?.let(OverlayRoads::prepare)
                val places = if (queryPlaces) {
                    repository.getExploredPlacesInBounds(queryNorth, queryEast, querySouth, queryWest)
                } else {
                    null
                }
                RoadDisplay(
                    roads,
                    roads?.let(::roadFeatures),
                    places,
                    footprint.takeIf { roadQuery?.complete == true },
                    footprint.takeIf { queryPlaces },
                    roadFeatures(OverlayRoads.prepare(repository.getPendingRouteInBounds(
                        queryNorth, queryEast, querySouth, queryWest
                    )))
                )
            }
            result.exceptionOrNull()?.let { Log.e("RoadConquest", "Could not refresh saved map data", it) }
            mainHandler.post {
                if (destroyed) return@post
                queryRunning = false
                if (revision == viewportRevision) {
                    result.getOrNull()?.let {
                        (map.style?.getSource(PENDING_ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(it.pendingRoute)
                        if (it.places != null) {
                            loadedPlaceBounds = it.placeBounds
                            setDisplayedPlaces(it.places)
                        }
                        if (it.overlay != null && it.features != null && !queryRoadsAgain) {
                            loadedRoadBounds = it.roadBounds
                            setDisplayedRoads(it.overlay, it.features)
                        } else {
                            scheduleFogRender()
                        }
                    }
                }
                if (queryAgain || revision != viewportRevision) {
                    val reloadRoads = queryRoadsAgain || revision != viewportRevision
                    queryAgain = false
                    queryRoadsAgain = false
                    if (!cameraMoving) refreshViewport(reloadRoads)
                }
            }
        }
    }

    private fun installPlaceOverlayLayers(style: Style) {
        for (mode in listOf(PlaceOverlayMode.COUNTRY, PlaceOverlayMode.STATE, PlaceOverlayMode.TOWN)) {
            style.addSource(GeoJsonSource(overlaySourceId(mode), EMPTY_FEATURES))
            style.addSource(GeoJsonSource(overlayBoundarySourceId(mode), EMPTY_FEATURES))
            val color = when (mode) {
                PlaceOverlayMode.COUNTRY -> Color.parseColor("#2F80ED")
                PlaceOverlayMode.STATE -> Color.parseColor("#8E44AD")
                PlaceOverlayMode.TOWN -> Color.parseColor("#27AE60")
                PlaceOverlayMode.NONE -> Color.TRANSPARENT
            }
            val outline = when (mode) {
                PlaceOverlayMode.COUNTRY -> Color.parseColor("#174A8B")
                PlaceOverlayMode.STATE -> Color.parseColor("#5D2C72")
                PlaceOverlayMode.TOWN -> Color.parseColor("#176B3D")
                PlaceOverlayMode.NONE -> Color.TRANSPARENT
            }
            style.addLayer(
                FillLayer(overlayLayerId(mode), overlaySourceId(mode)).withProperties(
                    fillColor(color),
                    fillOpacity(0.30f),
                    // Keep the built-in antialiased edge subtle; the dedicated 1.25 px line
                    // below supplies the visible separator without making borders look heavy.
                    fillOutlineColor(color),
                    fillAntialias(true)
                )
            )
            // A dedicated boundary layer gives neighboring places a stable, thin separator.
            // Fill outlines alone can disappear where adjacent/overlapping polygons meet.
            style.addLayer(
                LineLayer(overlayBoundaryLayerId(mode), overlayBoundarySourceId(mode)).withProperties(
                    lineColor(outline),
                    lineOpacity(0.90f),
                    lineWidth(1.25f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        refreshPlaceOverlays()
    }

    private fun clearPlaceOverlaySources() {
        for (mode in listOf(PlaceOverlayMode.COUNTRY, PlaceOverlayMode.STATE, PlaceOverlayMode.TOWN)) {
            (map.style?.getSource(overlaySourceId(mode)) as? GeoJsonSource)?.setGeoJson(EMPTY_FEATURES)
            (map.style?.getSource(overlayBoundarySourceId(mode)) as? GeoJsonSource)?.setGeoJson(EMPTY_FEATURES)
        }
    }

    private fun postPlaceOverlay(
        generation: Int,
        kind: com.roadconquest.app.data.PlaceKind,
        data: List<PlaceOverlayData>
    ) {
        val snapshot = data.toList()
        mainHandler.post {
            if (destroyed || generation != overlayGeneration || overlayMode.kind != kind) return@post
            val mode = when (kind) {
                com.roadconquest.app.data.PlaceKind.COUNTRY -> PlaceOverlayMode.COUNTRY
                com.roadconquest.app.data.PlaceKind.STATE -> PlaceOverlayMode.STATE
                com.roadconquest.app.data.PlaceKind.TOWN -> PlaceOverlayMode.TOWN
            }
            (map.style?.getSource(overlaySourceId(mode)) as? GeoJsonSource)
                ?.setGeoJson(overlayFeatureCollection(snapshot))
            (map.style?.getSource(overlayBoundarySourceId(mode)) as? GeoJsonSource)
                ?.setGeoJson(overlayBoundaryFeatureCollection(snapshot))
        }
    }

    private fun overlayFeatureCollection(data: List<PlaceOverlayData>): String {
        val features = JSONArray()
        data.forEach { features.put(it.featureJson()) }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun overlayBoundaryFeatureCollection(data: List<PlaceOverlayData>): String {
        val features = JSONArray()
        fun addRings(rings: JSONArray) {
            for (ringIndex in 0 until rings.length()) {
                val ring = rings.optJSONArray(ringIndex) ?: continue
                if (ring.length() < 2) continue
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", JSONObject())
                        .put("geometry", JSONObject()
                            .put("type", "LineString")
                            .put("coordinates", ring))
                )
            }
        }
        for (overlay in data) {
            val geometry = runCatching { JSONObject(overlay.geometryJson) }.getOrNull() ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            when (geometry.optString("type")) {
                "Polygon" -> addRings(coordinates)
                "MultiPolygon" -> {
                    for (polygonIndex in 0 until coordinates.length()) {
                        coordinates.optJSONArray(polygonIndex)?.let(::addRings)
                    }
                }
            }
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun overlaySourceId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_SOURCE_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_SOURCE_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_SOURCE_ID
        PlaceOverlayMode.NONE -> error("None has no overlay source")
    }

    private fun overlayBoundarySourceId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_BOUNDARY_SOURCE_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_BOUNDARY_SOURCE_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_BOUNDARY_SOURCE_ID
        PlaceOverlayMode.NONE -> error("None has no overlay boundary source")
    }

    private fun overlayLayerId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_LAYER_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_LAYER_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_LAYER_ID
        PlaceOverlayMode.NONE -> error("None has no overlay layer")
    }

    private fun overlayBoundaryLayerId(mode: PlaceOverlayMode): String = when (mode) {
        PlaceOverlayMode.COUNTRY -> COUNTRY_OVERLAY_BOUNDARY_LAYER_ID
        PlaceOverlayMode.STATE -> STATE_OVERLAY_BOUNDARY_LAYER_ID
        PlaceOverlayMode.TOWN -> TOWN_OVERLAY_BOUNDARY_LAYER_ID
        PlaceOverlayMode.NONE -> error("None has no overlay boundary layer")
    }

    private fun installRoadLayer(style: Style) {
        style.addSource(GeoJsonSource(ROAD_SOURCE_ID, displayedRoadFeatures))
        appliedRoadColor = Prefs.roadColor(context)
        val layer = LineLayer(ROAD_LAYER_ID, ROAD_SOURCE_ID).withProperties(
            lineColor(Cosmetics.roadColor(context).argb),
            lineOpacity(242f / 255f),
            lineWidth(4f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
        layer.setMinZoom(FogBitmapRenderer.MIN_ROAD_ZOOM.toFloat())
        style.addLayer(layer)
    }

    private fun installFogLayer(style: Style) {
        detailedFogCoordinates = null
        cancelFogHandoff()
        showingDetailedFog = false
        // Keep a ready native world layer behind the detailed viewport. A fast pinch can
        // outrun even a padded bitmap; it must never reveal an unrendered rectangle.
        style.addSource(ImageSource(WORLD_FOG_SOURCE_ID, worldFogQuad(), overviewFog))
        style.addLayer(RasterLayer(WORLD_FOG_LAYER_ID, WORLD_FOG_SOURCE_ID).withProperties(
            rasterOpacity(if (fogEnabled) 1f else 0f), rasterFadeDuration(0f)
        ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) })
        val transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        style.addSource(ImageSource(FOG_SOURCE_ID, currentFogQuad(), transparent))
        style.addLayer(
            RasterLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
                rasterOpacity(0f),
                rasterFadeDuration(0f)
            ).apply { setRasterOpacityTransition(TransitionOptions(0L, 0L)) }
        )
        transparent.recycle()
    }

    private fun installRecordedRouteLayer(style: Style) {
        style.addSource(GeoJsonSource(PENDING_ROUTE_SOURCE_ID, emptyRoadFeatures()))
        // Pending evidence is raw GPS, not final road geometry. Keep a faint, narrow
        // provisional trace so the map never appears to have a random hole while OSRM is
        // resolving the interval. Confirmed traveled roads remain thicker and nearly opaque,
        // and are drawn from road-snapped geometry only.
        val layer = LineLayer(PENDING_ROUTE_LAYER_ID, PENDING_ROUTE_SOURCE_ID).withProperties(
            lineColor(Cosmetics.roadColor(context).argb), lineWidth(3f), lineOpacity(0.60f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
        layer.setMinZoom(FogBitmapRenderer.MIN_ROAD_ZOOM.toFloat())
        style.addLayer(layer)
    }

    private fun updateCameraLimits(cameraPosition: CameraPosition = map.cameraPosition) {
        val target = cameraPosition.target ?: return
        val next = FogCoverage.minimumZoom(mapView.width, mapView.height, mapView.pixelRatio, target.latitude)
        if (!minimumZoom.isFinite() || abs(next - minimumZoom) > 0.001) {
            minimumZoom = next
            map.setMinZoomPreference(next)
        }
    }

    private fun updateFogCoverage(
        force: Boolean = false,
        cameraPosition: CameraPosition = map.cameraPosition
    ) {
        val coordinates = detailedFogCoordinates
        // Keep the detailed bitmap during ordinary movement, but reserve extra off-screen
        // coverage while the camera is moving. Android 12 can present a native frame before the
        // Java camera callback catches up; switching to the ready world layer early prevents a
        // bitmap edge from flashing as a white rectangle without closing fog on every gesture.
        val detailed = fogEnabled && cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM &&
            coordinates != null && run {
                map.projection.toScreenLocations(coordinates, fogCoverageScreen)
                FogCoverage.coversViewport(
                    fogCoverageScreen,
                    mapView.width,
                    mapView.height,
                    if (cameraMoving) FOG_MOVING_COVERAGE_MARGIN_FRACTION else 0.0
                )
            }
        val changed = detailed != showingDetailedFog
        if (!force && !changed) return
        val detailedLayer = map.style?.getLayer(FOG_LAYER_ID) as? RasterLayer
        val worldLayer = map.style?.getLayer(WORLD_FOG_LAYER_ID) as? RasterLayer
        if (!fogEnabled) {
            cancelFogHandoff()
            showingDetailedFog = false
            worldLayer?.setProperties(rasterOpacity(0f))
            detailedLayer?.setProperties(rasterOpacity(0f))
            return
        }
        if (!changed) {
            // A completed handoff already has the correct native layer state. During a pending
            // handoff, a fresh detailed bitmap must not prematurely hide the safety layer.
            if (fogHandoffFrameListener == null) {
                detailedLayer?.setProperties(rasterOpacity(if (detailed) 1f else 0f))
                worldLayer?.setProperties(rasterOpacity(if (detailed) 0f else 1f))
            }
            return
        }

        cancelFogHandoff()
        showingDetailedFog = detailed
        val generation = fogHandoffGeneration
        // Enable the incoming raster immediately, but keep the outgoing raster until MapLibre
        // has actually completed two native rendered frames. An Android Choreographer frame is
        // not sufficient: MapLibre's renderer can run on a different cadence and briefly show
        // neither source during a rapid animated zoom. Two native frames cover an in-flight
        // frame plus the first frame guaranteed to include the new opacity.
        if (detailed) {
            detailedLayer?.setProperties(rasterOpacity(1f))
        } else {
            worldLayer?.setProperties(rasterOpacity(1f))
        }
        awaitFogHandoffFrames(generation, detailed)
    }

    private fun cancelFogHandoff() {
        fogHandoffGeneration++
        fogHandoffFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        fogHandoffFrameListener = null
    }

    private fun awaitFogHandoffFrames(generation: Int, detailed: Boolean) {
        var renderedFrames = 0
        lateinit var listener: MapView.OnDidFinishRenderingFrameListener
        listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
            if (destroyed || generation != fogHandoffGeneration ||
                detailed != showingDetailedFog || !fogEnabled
            ) {
                mapView.removeOnDidFinishRenderingFrameListener(listener)
                if (fogHandoffFrameListener === listener) fogHandoffFrameListener = null
                return@OnDidFinishRenderingFrameListener
            }
            renderedFrames++
            if (renderedFrames < FOG_HANDOFF_RENDERED_FRAMES) {
                map.triggerRepaint()
                return@OnDidFinishRenderingFrameListener
            }
            mapView.removeOnDidFinishRenderingFrameListener(listener)
            if (fogHandoffFrameListener === listener) fogHandoffFrameListener = null
            if (detailed) {
                (map.style?.getLayer(WORLD_FOG_LAYER_ID) as? RasterLayer)
                    ?.setProperties(rasterOpacity(0f))
            } else {
                (map.style?.getLayer(FOG_LAYER_ID) as? RasterLayer)
                    ?.setProperties(rasterOpacity(0f))
            }
        }
        fogHandoffFrameListener = listener
        mapView.addOnDidFinishRenderingFrameListener(listener)
        map.triggerRepaint()
    }

    private fun installCarLayer(style: Style) {
        updateCarImage(style)
        appliedCarStyle = Prefs.carStyle(context)
        appliedCarColor = Prefs.carColor(context)
        style.addSource(GeoJsonSource(CAR_SOURCE_ID, emptyRoadFeatures()))
        style.addLayer(
            SymbolLayer(CAR_LAYER_ID, CAR_SOURCE_ID).withProperties(
                iconImage(CAR_IMAGE_ID),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconRotate(0f)
            )
        )
    }

    private fun updateCarImage(style: Style) {
        val next = Cosmetics.renderCarIcon(context)
        runCatching { style.addImage(CAR_IMAGE_ID, next) }
            .onSuccess {
                carIconBitmap?.recycle()
                carIconBitmap = next
            }
            .onFailure { next.recycle() }
    }

    private fun setDisplayedRoads(roads: OverlayRoads, features: FeatureCollection) {
        displayedRoads = roads
        displayedRoadFeatures = features
        roadDataRevision++
        (map.style?.getSource(ROAD_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(features)
        scheduleFogRender()
    }

    private fun setDisplayedPlaces(places: DoubleArray) {
        if (displayedPlaces.contentEquals(places)) return
        displayedPlaces = places
        placeDataRevision++
    }

    private fun projectRoads(roads: OverlayRoads): DoubleArray {
        if (roads.coordinates.isEmpty()) return roads.coordinates
        val width = mapView.width
        val height = mapView.height
        if (roadProjectionDataRevision != roadDataRevision ||
            roadProjectionViewportRevision != viewportRevision ||
            roadProjectionWidth != width || roadProjectionHeight != height ||
            roadScreenCache.size != roads.coordinates.size
        ) {
            if (roadScreenCache.size != roads.coordinates.size) {
                roadScreenCache = DoubleArray(roads.coordinates.size)
            }
            map.projection.toScreenLocations(roads.coordinates, roadScreenCache)
            roadProjectionDataRevision = roadDataRevision
            roadProjectionViewportRevision = viewportRevision
            roadProjectionWidth = width
            roadProjectionHeight = height
        }
        return roadScreenCache
    }

    private fun projectPlaces(places: DoubleArray): DoubleArray {
        if (places.isEmpty()) return places
        val width = mapView.width
        val height = mapView.height
        if (placeProjectionDataRevision != placeDataRevision ||
            placeProjectionViewportRevision != viewportRevision ||
            placeProjectionWidth != width || placeProjectionHeight != height ||
            placeScreenCache.size != places.size
        ) {
            if (placeScreenCache.size != places.size) {
                placeScreenCache = DoubleArray(places.size)
            }
            map.projection.toScreenLocations(places, placeScreenCache)
            placeProjectionDataRevision = placeDataRevision
            placeProjectionViewportRevision = viewportRevision
            placeProjectionWidth = width
            placeProjectionHeight = height
        }
        return placeScreenCache
    }

    private fun updateCarLayer() {
        val style = map.style ?: return
        val fix = liveLocation.current(SystemClock.elapsedRealtime())
        val source = style.getSource(CAR_SOURCE_ID) as? GeoJsonSource ?: return
        if (fix == null) {
            source.setGeoJson(emptyRoadFeatures())
            return
        }
        source.setGeoJson(Point.fromLngLat(fix.longitude, fix.latitude))
        (style.getLayer(CAR_LAYER_ID) as? SymbolLayer)?.setProperties(iconRotate(fix.bearing.toFloat()))
    }

    private fun scheduleFogRender(cameraPosition: CameraPosition? = null) {
        if (destroyed || !fogEnabled) return
        // Snapshot native camera state once when the caller did not already provide it.
        val position = cameraPosition ?: map.cameraPosition
        // At overview zooms the static world fog already provides complete coverage. Do not
        // burn CPU/GPU time rebuilding detailed reveal bitmaps that are intentionally hidden.
        if (position.zoom < FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM) {
            mainHandler.removeCallbacks(renderFog)
            fogAgain = false
            updateFogCoverage(cameraPosition = position)
            return
        }
        if (fogRunning) {
            fogAgain = true
            return
        }
        mainHandler.removeCallbacks(renderFog)
        val delay = FOG_RENDER_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastFogRenderAt)
        if (cameraMoving && delay > 0L) {
            mainHandler.postDelayed(renderFog, delay)
            return
        }
        val capture = captureFog() ?: return
        lastFogRenderAt = SystemClock.elapsedRealtime()
        fogAgain = false
        fogRunning = true
        val reusable = reusableFogBitmap
        reusableFogBitmap = null
        fogExecutor.execute {
            val bitmap = runCatching { FogBitmapRenderer.render(capture.request, reusable) }
            bitmap.exceptionOrNull()?.let { Log.e("RoadConquest", "Could not render map fog", it) }
            mainHandler.post {
                val rendered = bitmap.getOrNull()
                if (destroyed) {
                    rendered?.recycle()
                    return@post
                }
                fogRunning = false
                if (rendered != null) {
                    if (capture.styleGeneration == styleGeneration) {
                        val id = if (capture.world) WORLD_FOG_SOURCE_ID else FOG_SOURCE_ID
                        val source = map.style?.getSource(id) as? ImageSource
                        source?.setCoordinates(capture.quad)
                        // MapLibre 13.6.1 copies Android bitmap pixels synchronously in nativeSetImage.
                        source?.setImage(rendered)
                        if (!capture.world && source != null) {
                            detailedFogCoordinates = doubleArrayOf(
                                capture.quad.topLeft.latitude, capture.quad.topLeft.longitude,
                                capture.quad.topRight.latitude, capture.quad.topRight.longitude,
                                capture.quad.bottomRight.latitude, capture.quad.bottomRight.longitude,
                                capture.quad.bottomLeft.latitude, capture.quad.bottomLeft.longitude
                            )
                        }
                        updateFogCoverage(force = true)
                    }
                    reusableFogBitmap = rendered
                }
                if (fogAgain) {
                    fogAgain = false
                    scheduleFogRender()
                }
            }
        }
    }

    private fun captureFog(): FogCapture? {
        if (mapView.width <= 0 || mapView.height <= 0 || map.style == null) return null
        val padX = mapView.width * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER
        val padY = mapView.height * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER
        val left = -padX
        val top = -padY
        val right = mapView.width + padX
        val bottom = mapView.height + padY
        val expandedWidth = right - left
        val expandedHeight = bottom - top
        if (expandedWidth <= 0f || expandedHeight <= 0f) return null
        val maxBitmapDimension = FogBitmapRenderer.bitmapDimensionForZoom(map.cameraPosition.zoom)
        val scale = min(
            maxBitmapDimension / expandedWidth,
            maxBitmapDimension / expandedHeight
        ).coerceAtMost(1f)
        val bitmapWidth = (expandedWidth * scale).roundToInt().coerceAtLeast(2)
        val bitmapHeight = (expandedHeight * scale).roundToInt().coerceAtLeast(2)
        val roads = if (map.cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM) displayedRoads
            else OverlayRoads.EMPTY
        val roadScreen = projectRoads(roads)
        val places = if (map.cameraPosition.zoom >= FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM) displayedPlaces else doubleArrayOf()
        val placeScreen = projectPlaces(places)

        val fix = liveLocation.current(SystemClock.elapsedRealtime())
        val liveScreen = fix?.let {
            liveCoordinates[0] = it.latitude
            liveCoordinates[1] = it.longitude
            map.projection.toScreenLocations(liveCoordinates, liveScreenCache)
            liveScreenCache
        }
        val center = map.cameraPosition.target ?: return null
        val metersPerPixel = map.projection.getMetersPerPixelAtLatitude(center.latitude) / mapView.pixelRatio
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0) return null
        if (!fogTextureTransform.update(center.latitude, center.longitude, map.cameraPosition.zoom,
                { coordinates, output -> map.projection.toScreenLocations(coordinates, output) }, fogMatrix)) return null
        val mercatorMetersPerPixel = metersPerPixel / cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)
        // Screen corners can span several wrapped worlds at overview zooms. An ImageSource
        // cannot represent those as one narrow wrapped quad; use one complete Mercator world.
        if (map.cameraPosition.zoom < FogBitmapRenderer.MIN_FOG_REVEAL_ZOOM ||
            (expandedWidth + expandedHeight) * mercatorMetersPerPixel >= 2 * PI * 6378137.0) {
            val world = 2 * PI * 6378137.0
            val size = maxBitmapDimension
            val inverse = Matrix()
            if (!fogMatrix.invert(inverse)) return null
            val centerScreen = map.projection.toScreenLocation(center)
            val phase = floatArrayOf(centerScreen.x, centerScreen.y)
            inverse.mapPoints(phase)
            val texelScale = (FogTextureTransform.tileMetersForZoom(map.cameraPosition.zoom) / world * size / FogTexture.SIZE).toFloat()
            val longitude = ((center.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            val mercatorY = 6378137.0 * ln(tan(PI / 4 + Math.toRadians(center.latitude.coerceIn(-85.05112878, 85.05112878)) / 2))
            fogMatrix.setScale(texelScale, texelScale)
            fogMatrix.postTranslate(((longitude + 180.0) / 360.0 * size).toFloat() - texelScale * phase[0],
                ((0.5 - mercatorY / world) * size).toFloat() - texelScale * phase[1])
            return FogCapture(styleGeneration, worldFogQuad(), FogBitmapRenderer.Request(
                size, size, 0f, 0f, 1f, OverlayRoads.EMPTY, doubleArrayOf(), 0.0,
                world / size, null, null, fogMatrixValues.also { fogMatrix.getValues(it) }.copyOf()
            ), world = true)
        }
        val quad = fogQuad(left, top, right, bottom)
        fogMatrix.postTranslate(-left, -top)
        fogMatrix.postScale(scale, scale)
        fogMatrix.getValues(fogMatrixValues)

        return FogCapture(
            styleGeneration,
            quad,
            FogBitmapRenderer.Request(
                bitmapWidth,
                bitmapHeight,
                left,
                top,
                scale,
                roads,
                roadScreen.copyOf(),
                center.latitude,
                metersPerPixel,
                fix?.latitude,
                liveScreen?.copyOf(),
                fogMatrixValues.copyOf(),
                places,
                placeScreen.copyOf()
            )
        )
    }

    private fun currentFogQuad(): LatLngQuad {
        if (map.cameraPosition.zoom < 6.0) return worldFogQuad()
        if (mapView.width <= 0 || mapView.height <= 0) {
            val bounds = map.projection.visibleRegion.latLngBounds
            return LatLngQuad(
                LatLng(bounds.latitudeNorth, bounds.longitudeWest),
                LatLng(bounds.latitudeNorth, bounds.longitudeEast),
                LatLng(bounds.latitudeSouth, bounds.longitudeEast),
                LatLng(bounds.latitudeSouth, bounds.longitudeWest)
            )
        }
        val padX = mapView.width * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER
        val padY = mapView.height * FogBitmapRenderer.VIEWPORT_PADDING_MULTIPLIER
        return fogQuad(-padX, -padY, mapView.width + padX, mapView.height + padY)
    }

    private fun worldFogQuad() = LatLngQuad(
        LatLng(85.05112878, -180.0), LatLng(85.05112878, 180.0),
        LatLng(-85.05112878, 180.0), LatLng(-85.05112878, -180.0)
    )

    private fun unwrappedCorner(x: Float, y: Float): LatLng {
        val point = map.projection.fromScreenLocation(PointF(x, y))
        val center = map.cameraPosition.target?.longitude ?: 0.0
        val longitude = center + ((point.longitude - center + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return LatLng(point.latitude, longitude)
    }

    private fun fogQuad(left: Float, top: Float, right: Float, bottom: Float): LatLngQuad =
        LatLngQuad(
            unwrappedCorner(left, top), unwrappedCorner(right, top),
            unwrappedCorner(right, bottom), unwrappedCorner(left, bottom)
        )

    private fun roadFeatures(roads: OverlayRoads): FeatureCollection {
        val features = ArrayList<Feature>((roads.starts.size - 1).coerceAtLeast(0))
        for (road in 0 until roads.starts.size - 1) {
            val points = ArrayList<Point>((roads.starts[road + 1] - roads.starts[road]) / 2)
            for (i in roads.starts[road] until roads.starts[road + 1] step 2) {
                points += Point.fromLngLat(roads.coordinates[i + 1], roads.coordinates[i])
            }
            if (points.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(points))
        }
        return FeatureCollection.fromFeatures(features)
    }

    private data class RoadDisplay(
        val overlay: OverlayRoads?,
        val features: FeatureCollection?,
        val places: DoubleArray?,
        val roadBounds: RoadQueryBounds?,
        val placeBounds: RoadQueryBounds?,
        val pendingRoute: FeatureCollection
    )

    private data class RoadQueryBounds(
        val north: Double,
        val south: Double,
        val longitudeCenter: Double,
        val longitudeSpan: Double
    ) {
        fun contains(other: RoadQueryBounds): Boolean {
            if (north + BOUNDS_EPSILON < other.north || south - BOUNDS_EPSILON > other.south ||
                longitudeSpan + BOUNDS_EPSILON < other.longitudeSpan
            ) return false
            if (longitudeSpan >= 360.0 - BOUNDS_EPSILON) return true
            val centerDistance = abs(
                ((other.longitudeCenter - longitudeCenter + 540.0) % 360.0) - 180.0
            )
            return other.longitudeSpan + 2 * centerDistance <= longitudeSpan + BOUNDS_EPSILON
        }
    }

    private data class FogCapture(
        val styleGeneration: Int,
        val quad: LatLngQuad,
        val request: FogBitmapRenderer.Request,
        val world: Boolean = false
    )

    fun destroy() {
        destroyed = true
        resumeGeneration++
        map.removeOnCameraMoveStartedListener(cameraMoveStartedListener)
        map.removeOnCameraMoveListener(cameraMoveListener)
        map.removeOnCameraIdleListener(cameraIdleListener)
        mapView.removeOnLayoutChangeListener(layoutListener)
        mainHandler.removeCallbacksAndMessages(null)
        resumeFrameListener?.let(mapView::removeOnDidFinishRenderingFrameListener)
        resumeFrameListener = null
        cancelFogHandoff()
        reusableFogBitmap?.recycle()
        reusableFogBitmap = null
        carIconBitmap?.recycle()
        carIconBitmap = null
        overlayGeneration++
        executor.shutdownNow()
        fogExecutor.shutdownNow()
        overlayExecutor.shutdownNow()
    }

    companion object {
        private const val COUNTRY_OVERLAY_SOURCE_ID = "roadconquest-country-overlays"
        private const val STATE_OVERLAY_SOURCE_ID = "roadconquest-state-overlays"
        private const val TOWN_OVERLAY_SOURCE_ID = "roadconquest-town-overlays"
        private const val COUNTRY_OVERLAY_BOUNDARY_SOURCE_ID = "roadconquest-country-overlay-boundaries"
        private const val STATE_OVERLAY_BOUNDARY_SOURCE_ID = "roadconquest-state-overlay-boundaries"
        private const val TOWN_OVERLAY_BOUNDARY_SOURCE_ID = "roadconquest-town-overlay-boundaries"
        private const val COUNTRY_OVERLAY_LAYER_ID = "roadconquest-country-overlays-fill"
        private const val STATE_OVERLAY_LAYER_ID = "roadconquest-state-overlays-fill"
        private const val TOWN_OVERLAY_LAYER_ID = "roadconquest-town-overlays-fill"
        private const val COUNTRY_OVERLAY_BOUNDARY_LAYER_ID = "roadconquest-country-overlays-outline"
        private const val STATE_OVERLAY_BOUNDARY_LAYER_ID = "roadconquest-state-overlays-outline"
        private const val TOWN_OVERLAY_BOUNDARY_LAYER_ID = "roadconquest-town-overlays-outline"
        private const val ROAD_SOURCE_ID = "roadconquest-traveled-roads"
        private const val PENDING_ROUTE_SOURCE_ID = "roadconquest-recorded-route"
        private const val PENDING_ROUTE_LAYER_ID = "roadconquest-recorded-route-line"
        private const val ROAD_LAYER_ID = "roadconquest-traveled-roads-line"
        private const val FOG_SOURCE_ID = "roadconquest-fog"
        private const val FOG_LAYER_ID = "roadconquest-fog-raster"
        private const val WORLD_FOG_SOURCE_ID = "roadconquest-world-fog"
        private const val WORLD_FOG_LAYER_ID = "roadconquest-world-fog-raster"
        private val overviewFog by lazy {
            FogBitmapRenderer.render(FogBitmapRenderer.Request(
                FogTexture.SIZE, FogTexture.SIZE, 0f, 0f, 1f, OverlayRoads.EMPTY,
                doubleArrayOf(), 0.0, 1.0, null, null
            ))
        }
        private const val CAR_SOURCE_ID = "roadconquest-car"
        private const val CAR_LAYER_ID = "roadconquest-car-symbol"
        private const val CAR_IMAGE_ID = "roadconquest-car-image"
        private val EMPTY_FEATURES = FeatureCollection.fromFeatures(emptyList<Feature>())
        private const val BOUNDS_EPSILON = 1e-9
        private const val RESUME_VISIBILITY_RETRY_MS = 16L
        // Native MapLibre transforms the georeferenced bitmap between refreshes, so ~8 fps
        // while actively gesturing is visually continuous without wasting battery on 12.5 fps
        // off-screen bitmap redraws. Idle renders still happen immediately.
        private const val FOG_RENDER_INTERVAL_MS = 120L
        private const val FOG_MOVING_COVERAGE_MARGIN_FRACTION = 0.50
        private const val FOG_HANDOFF_RENDERED_FRAMES = 2
        private const val OVERLAY_UPDATE_BATCH = 4
        private const val STARTING_LOCATION_ZOOM = 15.0
    }
}
