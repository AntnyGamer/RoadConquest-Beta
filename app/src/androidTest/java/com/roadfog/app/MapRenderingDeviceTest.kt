package com.roadfog.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.view.TextureView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import android.os.Build
import android.os.SystemClock
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.roadfog.app.data.MatchedRoad
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.map.MapMode
import com.roadfog.app.map.MapRenderer
import com.roadfog.app.util.Prefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android/MapLibre integration, including the projection and the actual recenter button. */
@RunWith(AndroidJUnit4::class)
class MapRenderingDeviceTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test fun fogCoversTheWholeWorldAndBlueRoadsStayVisibleAtOverviewZooms() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        val repository = TrackingRepository(app)
        repository.readableDatabase().execSQL("DELETE FROM roads")
        repository.readableDatabase().execSQL("DELETE FROM explored_places")
        repository.upsertRoads(listOf(MatchedRoad("Overview test", "[[-0.08,0],[0.08,0]]", 1, 2, 100.0)))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var map: MapLibreMap
                lateinit var view: MapView
                lateinit var renderer: MapRenderer
                val initialReady = CountDownLatch(1)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopPreviewLocation").apply { isAccessible = true }.invoke(activity)
                    view = activity.findViewById(R.id.mapView)
                    view.getMapAsync { readyMap ->
                        map = readyMap
                        readyMap.getStyle { initialReady.countDown() }
                    }
                }
                assertTrue(initialReady.await(30, TimeUnit.SECONDS))
                val whiteReady = CountDownLatch(1)
                scenario.onActivity { activity ->
                    renderer = MainActivity::class.java.getDeclaredField("renderer").apply { isAccessible = true }.get(activity) as MapRenderer
                    renderer.clearCurrentLocation()
                    // No remote tiles or labels: inspect the pixels produced by our native layers.
                    map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"white","type":"background","paint":{"background-color":"#ffffff"}}]}""")) { style ->
                        for (method in listOf("installFogLayer", "installRoadLayer")) {
                            MapRenderer::class.java.getDeclaredMethod(method, Style::class.java).apply { isAccessible = true }.invoke(renderer, style)
                        }
                        renderer.refreshViewport()
                        renderer.setFogEnabled(true)
                        whiteReady.countDown()
                    }
                }
                assertTrue(whiteReady.await(30, TimeUnit.SECONDS))
                fun move(target: LatLng, zoom: Double, bearing: Double = 0.0) {
                    val idle = CountDownLatch(1)
                    val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                    scenario.onActivity {
                        map.addOnCameraIdleListener(listener)
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            org.maplibre.android.camera.CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).tilt(0.0).build()))
                    }
                    assertTrue(idle.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { map.removeOnCameraIdleListener(listener) }
                }
                fun awaitPixels(message: String, predicate: (android.graphics.Bitmap) -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000L
                    var passed = false
                    while (!passed && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity {
                            (view.renderView as TextureView).bitmap?.let { bitmap ->
                                passed = predicate(bitmap)
                                bitmap.recycle()
                            }
                        }
                        if (!passed) SystemClock.sleep(100)
                    }
                    assertTrue(message, passed)
                }
                for (zoom in listOf(0.0, 1.0, 2.0, 3.0)) for (longitude in listOf(0.0, 179.0, -179.0, 539.0, -539.0)) {
                    move(LatLng(0.0, longitude), zoom)
                    awaitPixels("Fog covers visible world copies at zoom $zoom, longitude $longitude") { bitmap ->
                        val rows = if (zoom == 0.0) listOf(0.5) else listOf(0.2, 0.5, 0.8)
                        rows.all { row -> listOf(0.01, 0.25, 0.5, 0.75, 0.99).all { column ->
                            Color.red(bitmap.getPixel((bitmap.width * column).toInt(), (bitmap.height * row).toInt())) < 220
                        } }
                    }
                }
                for (zoom in listOf(1.0, 2.0)) {
                    move(LatLng(0.0, 0.0), zoom)
                    for (direction in listOf(1f, -1f)) repeat(6) { loop ->
                        // scrollBy moves the native camera directly without dispatching
                        // OnCameraIdle. Confirm the pan and wait for its rendered frame.
                        val frame = CountDownLatch(1)
                        val listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frame.countDown() }
                        scenario.onActivity {
                            val center = android.graphics.PointF(view.width / 2f, view.height / 2f)
                            val before = map.projection.fromScreenLocation(center).longitude
                            view.addOnDidFinishRenderingFrameListener(listener)
                            map.scrollBy(direction * view.width * 1.25f, 0f)
                            val after = map.projection.fromScreenLocation(center).longitude
                            val difference = ((after - before + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                            assertTrue("The world pan changes the visible longitude", kotlin.math.abs(difference) > 0.1)
                        }
                        assertTrue("The world pan renders", frame.await(10, TimeUnit.SECONDS))
                        scenario.onActivity { view.removeOnDidFinishRenderingFrameListener(listener) }
                        awaitPixels("Fog survives loop $loop in direction $direction at zoom $zoom") { bitmap ->
                            listOf(0.01, 0.25, 0.5, 0.75, 0.99).all { column ->
                                Color.red(bitmap.getPixel((bitmap.width * column).toInt(), bitmap.height / 2)) < 220
                            }
                        }
                    }
                }
                for (longitude in listOf(179.99, -179.99)) {
                    move(LatLng(0.0, longitude), 10.0, 55.0)
                    awaitPixels("Local fog spans the date line at longitude $longitude") { bitmap ->
                        listOf(0.1, 0.3, 0.5, 0.7, 0.9).all { fraction ->
                            Color.red(bitmap.getPixel((bitmap.width * fraction).toInt(), bitmap.height / 2)) < 220
                        }
                    }
                }
                for ((zoom, bearing) in listOf(9.0 to 0.0, 11.0 to 60.0)) {
                    move(LatLng(0.0, 0.0), zoom, bearing)
                    awaitPixels("Blue road pixels remain visible above fog at zoom $zoom") { bitmap ->
                        val point = map.projection.toScreenLocation(LatLng(0.0, 0.0))
                        (-4..4).any { dx -> (-4..4).any { dy ->
                            val color = bitmap.getPixel((point.x.toInt() + dx).coerceIn(0, bitmap.width - 1),
                                (point.y.toInt() + dy).coerceIn(0, bitmap.height - 1))
                            Color.blue(color) - Color.red(color) > 100 && Color.blue(color) - Color.green(color) > 70
                        } }
                    }
                }
                scenario.onActivity {
                    val ids = map.style!!.layers.map { it.id }
                    assertTrue(ids.indexOf("roadconquest-fog-raster") < ids.indexOf("roadconquest-traveled-roads-line"))
                }
            }
        } finally { repository.readableDatabase().execSQL("DELETE FROM roads") }
    }

    @Test fun nativeFogAndCarSurvivePanningZoom22RecenteringAndSatelliteStyleReload() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        Prefs.setMapMode(app, MapMode.STREETS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var map: MapLibreMap
            lateinit var view: MapView
            lateinit var renderer: MapRenderer
            val styleReady = CountDownLatch(1)
            scenario.onActivity { activity ->
                view = activity.findViewById(R.id.mapView)
                view.getMapAsync { readyMap ->
                    map = readyMap
                    renderer = MainActivity::class.java.getDeclaredField("renderer")
                        .apply { isAccessible = true }.get(activity) as MapRenderer
                    readyMap.getStyle { styleReady.countDown() }
                }
            }
            assertTrue("Bundled style must load", styleReady.await(30, TimeUnit.SECONDS))

            val car = LatLng(37.7749, -122.4194)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                    .apply { isAccessible = true }.invoke(activity)
                renderer.updateCar(car.latitude, car.longitude, 15.0)
                assertEquals(22.0, map.maxZoomLevel, 0.0)
                assertTrue("Map stays on its native render surface", view.renderView is TextureView)
                assertTrue(map.style!!.getSource("roadconquest-fog") is ImageSource)
                assertTrue(map.style!!.getLayer("roadconquest-fog-raster") is RasterLayer)
                assertTrue(map.style!!.getSource("roadconquest-car") is GeoJsonSource)
                assertTrue(map.style!!.getLayer("roadconquest-car-symbol") is SymbolLayer)
            }

            fun moveAndAssert(target: LatLng, zoom: Double, bearing: Double) {
                val idle = CountDownLatch(1)
                val frame = CountDownLatch(1)
                val frameListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frame.countDown() }
                val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                scenario.onActivity {
                    view.addOnDidFinishRenderingFrameListener(frameListener)
                    map.addOnCameraIdleListener(listener)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .target(target).zoom(zoom).bearing(bearing).tilt(0.0).build()
                    ))
                }
                assertTrue("Camera settles", idle.await(10, TimeUnit.SECONDS))
                assertTrue("Native renderer finishes a frame before feature queries", frame.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    view.removeOnDidFinishRenderingFrameListener(frameListener)
                    map.removeOnCameraIdleListener(listener)
                    assertTrue("Fog remains a native georeferenced source", map.style!!.getSource("roadconquest-fog") is ImageSource)
                    assertTrue("Fog remains a native raster layer", map.style!!.getLayer("roadconquest-fog-raster") is RasterLayer)
                    assertTrue("Car remains a native symbol layer", map.style!!.getLayer("roadconquest-car-symbol") is SymbolLayer)
                }
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                var rendered = false
                while (!rendered && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        val screen = map.projection.toScreenLocation(car)
                        rendered = map.queryRenderedFeatures(screen, "roadconquest-car-symbol").isNotEmpty()
                    }
                    if (!rendered) SystemClock.sleep(50)
                }
                assertTrue("Car stays rendered at its geographic point", rendered)
            }

            moveAndAssert(car, 18.0, 0.0)
            moveAndAssert(LatLng(car.latitude + 0.00008, car.longitude - 0.00008), 20.0, 55.0)
            moveAndAssert(LatLng(car.latitude - 0.00004, car.longitude + 0.00006), 22.0, 125.0)

            scenario.onActivity { activity ->
                activity.findViewById<ImageButton>(R.id.centerCarButton).performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                var centered = false
                scenario.onActivity {
                    centered = map.cameraPosition.target!!.distanceTo(car) < 1.0 &&
                        kotlin.math.abs(map.cameraPosition.zoom - 22.0) < 0.01
                }
                if (centered) break
                SystemClock.sleep(50)
            }
            scenario.onActivity {
                assertTrue("Icon recenters on the live fix", map.cameraPosition.target!!.distanceTo(car) < 1.0)
                assertEquals("Recenter preserves close zoom", 22.0, map.cameraPosition.zoom, 0.01)
                renderer.setFogEnabled(false)
                renderer.setFogEnabled(true)
            }

            val terrainReady = CountDownLatch(1)
            scenario.onActivity {
                renderer.setMapMode(MapMode.TERRAIN)
                map.getStyle { terrainReady.countDown() }
            }
            assertTrue("Terrain style reloads", terrainReady.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                assertTrue("Terrain actually uses imagery", map.style!!.getSource("satellite") is RasterSource)
                assertTrue("Fog source is recreated after style reload", map.style!!.getSource("roadconquest-fog") is ImageSource)
                assertTrue("Fog layer is recreated after style reload", map.style!!.getLayer("roadconquest-fog-raster") is RasterLayer)
                assertTrue("Car source is recreated after style reload", map.style!!.getSource("roadconquest-car") is GeoJsonSource)
                assertTrue("Car layer is recreated after style reload", map.style!!.getLayer("roadconquest-car-symbol") is SymbolLayer)
            }
        }
    }

    @Test fun traveledRoadLayerStaysAtItsGeographicPointAcrossCameraChanges() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        Prefs.setMapMode(app, MapMode.STREETS)
        val roadPoint = LatLng(37.7749, -122.4191)
        TrackingRepository(app).apply {
            readableDatabase().execSQL("DELETE FROM roads")
            upsertRoads(listOf(MatchedRoad(
                "Projection test",
                "[[-122.4198,37.7749],[-122.4187,37.7749]]",
                1L, 2L, 100.0
            )))
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var map: MapLibreMap
            lateinit var view: MapView
            val ready = CountDownLatch(1)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("stopPreviewLocation").apply { isAccessible = true }.invoke(activity)
                view = activity.findViewById(R.id.mapView)
                view.getMapAsync { readyMap ->
                    map = readyMap
                    readyMap.getStyle { ready.countDown() }
                }
            }
            assertTrue("Bundled style must load", ready.await(30, TimeUnit.SECONDS))

            fun moveAndAssert(target: LatLng, zoom: Double, bearing: Double): android.graphics.PointF {
                val idle = CountDownLatch(1)
                val frame = CountDownLatch(1)
                val frameListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frame.countDown() }
                val idleListener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                scenario.onActivity {
                    view.addOnDidFinishRenderingFrameListener(frameListener)
                    map.addOnCameraIdleListener(idleListener)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .target(target).zoom(zoom).bearing(bearing).tilt(0.0).build()))
                }
                assertTrue("Camera settles", idle.await(10, TimeUnit.SECONDS))
                assertTrue("Native renderer finishes a frame before feature queries", frame.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    map.removeOnCameraIdleListener(idleListener)
                    view.removeOnDidFinishRenderingFrameListener(frameListener)
                }

                val deadline = SystemClock.elapsedRealtime() + 10_000L
                var screen = android.graphics.PointF()
                var found = false
                while (!found && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        screen = map.projection.toScreenLocation(roadPoint)
                        found = map.queryRenderedFeatures(
                            screen,
                            "roadconquest-traveled-roads-line"
                        ).isNotEmpty()
                    }
                    if (!found) SystemClock.sleep(50)
                }
                assertTrue("Traveled road stays rendered at its projected geographic point", found)
                return screen
            }

            val first = moveAndAssert(LatLng(37.7749, -122.4194), 18.0, 0.0)
            val second = moveAndAssert(LatLng(37.77505, -122.41955), 20.0, 55.0)
            val third = moveAndAssert(LatLng(37.77475, -122.4190), 19.0, 125.0)
            assertTrue("Camera changes actually move the tested road on screen",
                kotlin.math.hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()) > 20.0)
            assertTrue("A second camera change also moves the tested road on screen",
                kotlin.math.hypot((second.x - third.x).toDouble(), (second.y - third.y).toDouble()) > 20.0)
            }
        } finally {
            TrackingRepository(app).readableDatabase().execSQL("DELETE FROM roads")
        }
    }

    @Test fun notificationStopRemainsStoppedUntilTheAppIsReopened() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setTrackingPaused(app, false)
        val manager = app.getSystemService(NotificationManager::class.java)
        fun awaitState(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline && !predicate()) SystemClock.sleep(50)
            assertTrue("$message (paused=${Prefs.isTrackingPaused(app)}, running=${TrackingService.isRunning}, " +
                "notifications=${manager.activeNotifications.map { it.id }})", predicate())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, TrackingService::class.java)) }
                awaitState("Tracking notification appears") {
                    TrackingService.isRunning && manager.activeNotifications.any { it.id == 4101 }
                }
                manager.activeNotifications.single { it.id == 4101 }.notification.actions.single().actionIntent.send()
                awaitState("Stop action removes the service and notification") {
                    Prefs.isTrackingPaused(app) && !TrackingService.isRunning &&
                        manager.activeNotifications.none { it.id == 4101 }
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue("Shade-style pause/resume does not restart tracking", Prefs.isTrackingPaused(app))
                assertFalse(TrackingService.isRunning)
                scenario.recreate()
                assertTrue("Activity recreation does not undo a stop", Prefs.isTrackingPaused(app))
                assertFalse(TrackingService.isRunning)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitState("Reopening resumes the previous tracking session, including manual mode") {
                    !Prefs.isTrackingPaused(app) && TrackingService.isRunning
                }
            }
        } finally {
            app.stopService(Intent(app, TrackingService::class.java))
            Prefs.setTrackingPaused(app, false)
        }
    }
}
