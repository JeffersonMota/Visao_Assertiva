package com.example.visao_pcd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.util.ArrayList

class ModoOnibus(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val client: OkHttpClient,
    private val groqService: GroqService,
    private val scope: CoroutineScope
) {
    data class GtfsStop(val id: String, val name: String, val lat: Double, val lon: Double)
    data class RouteStep(val instruction: String, val distance: Double, val location: GeoPoint)

    private val routeSteps = mutableListOf<RouteStep>()
    private var lastAnnouncedStepIndex = -1
    private var currentDestination: LatLng? = null

    // Busca paradas usando Overpass API
    fun buscarParadasProximas(mapView: MapView, onFalar: (String) -> Unit) {
        configurarMapa(mapView)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                centralizarMapa(it, mapView)
                val url = "https://overpass-api.de/api/interpreter?data=[out:json];node(around:1000,${it.latitude},${it.longitude})[highway=bus_stop];out;"
                
                client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { onFalar("Erro ao buscar paradas.") }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        try {
                            val json = JSONObject(body)
                            val elements = json.getJSONArray("elements")
                            if (elements.length() == 0) {
                                onFalar("Nenhuma parada encontrada por perto.")
                                return
                            }
                            val stops = mutableListOf<GtfsStop>()
                            for (i in 0 until elements.length()) {
                                val obj = elements.getJSONObject(i)
                                val name = if (obj.has("tags") && obj.getJSONObject("tags").has("name")) 
                                    obj.getJSONObject("tags").getString("name") else "Parada sem nome"
                                stops.add(GtfsStop(obj.getLong("id").toString(), name, obj.getDouble("lat"), obj.getDouble("lon")))
                            }
                            scope.launch(Dispatchers.Main) {
                                adicionarMarcadores(mapView, stops)
                                onFalar("Encontrei ${stops.size} paradas. A mais próxima é ${stops[0].name}.")
                                currentDestination = LatLng(stops[0].lat, stops[0].lon)
                                atualizarRota(mapView, onFalar)
                            }
                        } catch (e: Exception) { onFalar("Erro ao processar paradas.") }
                    }
                })
            }
        }
    }

    // Busca destino usando Nominatim (Gratuito e sem chave)
    fun navegarPara(destino: String, mapView: MapView, onFalar: (String) -> Unit) {
        val url = "https://nominatim.openstreetmap.org/search?q=${destino.replace(" ", "+")}&format=json&limit=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VisaoPCD/1.0") // Nominatim exige User-Agent
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onFalar("Erro ao buscar destino.") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject("{\"results\":$body}").getJSONArray("results")
                    if (json.length() == 0) {
                        onFalar("Não encontrei esse lugar.")
                        return
                    }
                    val result = json.getJSONObject(0)
                    val lat = result.getDouble("lat")
                    val lon = result.getDouble("lon")
                    val name = result.getString("display_name").split(",")[0]
                    
                    currentDestination = LatLng(lat, lon)
                    
                    scope.launch(Dispatchers.Main) {
                        onFalar("Destino encontrado: $name. Iniciando navegação a pé.")
                        atualizarRota(mapView, onFalar)
                    }
                } catch (e: Exception) { onFalar("Erro ao processar destino.") }
            }
        })
    }

    fun atualizarRota(mapView: MapView, onFalar: (String) -> Unit) {
        if (currentDestination == null || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { loc ->
                val url = "https://router.project-osrm.org/route/v1/foot/${loc.longitude},${loc.latitude};${currentDestination!!.longitude},${currentDestination!!.latitude}?overview=full&steps=true"
                client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: ""
                        try {
                            val json = JSONObject(body)
                            val route = json.getJSONArray("routes").getJSONObject(0)
                            val geometry = route.getString("geometry")
                            val legs = route.getJSONArray("legs").getJSONObject(0)
                            val stepsJson = legs.getJSONArray("steps")

                            routeSteps.clear()
                            for (i in 0 until stepsJson.length()) {
                                val s = stepsJson.getJSONObject(i)
                                val maneuver = s.getJSONObject("maneuver")
                                val instr = maneuver.getString("instruction")
                                val dist = s.getDouble("distance")
                                val pt = maneuver.getJSONArray("location")
                                routeSteps.add(RouteStep(instr, dist, GeoPoint(pt.getDouble(1), pt.getDouble(0))))
                            }

                            scope.launch(Dispatchers.Main) {
                                exibirRotaNoMapa(geometry, mapView)
                                if (routeSteps.isNotEmpty()) {
                                    val primeiraInstrucao = routeSteps[0].instruction
                                    onFalar("Siga em frente: $primeiraInstrucao")
                                }
                            }
                        } catch (e: Exception) {}
                    }
                })
            }
        }
    }

    fun monitorarPassoAPasso(onFalar: (String) -> Unit) {
        if (routeSteps.isEmpty() || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { loc ->
                // Verificar proximidade do próximo passo
                for (i in routeSteps.indices) {
                    val step = routeSteps[i]
                    val dist = FloatArray(1)
                    Location.distanceBetween(loc.latitude, loc.longitude, step.location.latitude, step.location.longitude, dist)

                    if (dist[0] < 15 && i > lastAnnouncedStepIndex) {
                        lastAnnouncedStepIndex = i
                        onFalar("Em 15 metros, ${step.instruction}")
                        break
                    }
                }
            }
        }
    }

    private fun realizarBusca(url: String, mapView: MapView, onFalar: (String) -> Unit, tipo: String) {
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onFalar("Erro de conexão.") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        onFalar("Nenhuma $tipo encontrada por perto.")
                        return
                    }
                    val stops = mutableListOf<GtfsStop>()
                    for (i in 0 until results.length()) {
                        val obj = results.getJSONObject(i)
                        val loc = obj.getJSONObject("geometry").getJSONObject("location")
                        stops.add(GtfsStop(obj.getString("place_id"), obj.getString("name"), loc.getDouble("lat"), loc.getDouble("lng")))
                    }
                    scope.launch(Dispatchers.Main) {
                        adicionarMarcadores(mapView, stops)
                        onFalar("Encontrei ${stops.size} ${tipo}s próximas. A mais perto é ${stops[0].name}.")
                        currentDestination = LatLng(stops[0].lat, stops[0].lon)
                        atualizarRota(mapView, onFalar)
                    }
                } catch (e: Exception) { onFalar("Erro ao processar locais.") }
            }
        })
    }

    private fun configurarMapa(mapView: MapView) {
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(19.0) // Mais perto para estilo Waze
        mapView.setMapOrientation(0f)
        
        // Inclinação estilo 3D (Waze)
        mapView.mapOrientation = 0f
        
        // Limpa cache para evitar sobreposição do mapa claro
        mapView.tileProvider.clearTileCache()
        
        val satelliteTiles = XYTileSource(
            "GoogleHybrid",
            0, 20, 256, ".png", arrayOf(
                "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"
            ),
            "© Google"
        )
        
        mapView.setTileSource(satelliteTiles)
        mapView.invalidate()
    }

    private fun centralizarMapa(location: Location, mapView: MapView) {
        val startPoint = GeoPoint(location.latitude, location.longitude)
        
        // Mantém a orientação do mapa de acordo com o movimento (Estilo Waze)
        if (location.hasBearing()) {
            mapView.mapOrientation = -location.bearing
        }
        
        mapView.controller.animateTo(startPoint)
        
        mapView.overlays.removeAll(mapView.overlays.filter { it is Polygon || (it is Marker && it.title == "Minha Posição") })

        val userMarker = Marker(mapView)
        userMarker.position = startPoint
        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        userMarker.title = "Minha Posição"
        
        // Se estiver se movendo, usa ícone de seta/direção
        userMarker.icon = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
        userMarker.rotation = location.bearing
        
        mapView.overlays.add(userMarker)
        mapView.invalidate()
    }

    private fun adicionarMarcadores(mapView: MapView, stops: List<GtfsStop>) {
        mapView.overlays.removeAll(mapView.overlays.filterIsInstance<Marker>())
        stops.forEach { stop ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(stop.lat, stop.lon)
            marker.title = stop.name
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    private fun exibirRotaNoMapa(geometry: String, mapView: MapView) {
        mapView.overlays.removeAll(mapView.overlays.filterIsInstance<Polyline>())
        val points = decodePolylineOsm(geometry)
        val polyline = Polyline().apply {
            setPoints(points)
            outlinePaint.color = Color.parseColor("#BC3DFA") // Roxo vibrante estilo Waze
            outlinePaint.strokeWidth = 18f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        }
        mapView.overlays.add(polyline)
        mapView.invalidate()
    }

    private fun decodePolylineOsm(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var b: Int; var shift = 0; var result = 0
            do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            poly.add(GeoPoint(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }
}
