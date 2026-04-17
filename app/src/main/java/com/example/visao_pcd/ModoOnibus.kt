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
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.util.ArrayList

class ModoOnibus(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val client: OkHttpClient,
    private val groqService: GroqService,
    private val scope: CoroutineScope,
    private val googleMapsKey: String
) {
    data class GtfsStop(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val source: String = "IMMU"
    )

    val customStops = mutableListOf<GtfsStop>()
    private val allNearbyStops = mutableListOf<GtfsStop>()
    private var lastAnnouncedLocation = ""
    private var lastAnnouncedStopId = ""

    fun buscarParadasGoogle(mapView: MapView, onFalar: (String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onFalar("Erro: Sem permissão de localização.")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                onFalar("Erro: GPS desativado ou sem sinal.")
                return@addOnSuccessListener
            }

            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=${location.latitude},${location.longitude}&radius=1500&type=bus_station&key=$googleMapsKey"
            
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onFalar("Erro de conexão ao buscar paradas.")
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    val googleStops = mutableListOf<GtfsStop>()
                    try {
                        val json = JSONObject(body)
                        if (json.has("error_message")) {
                            Log.e("ModoOnibus", "Google API Error: ${json.getString("error_message")}")
                        }
                        
                        val results = json.optJSONArray("results")
                        if (results == null || results.length() == 0) {
                            onFalar("Nenhuma parada de ônibus encontrada por perto.")
                            return
                        }

                        for (i in 0 until results.length()) {
                            val obj = results.getJSONObject(i)
                            val loc = obj.getJSONObject("geometry").getJSONObject("location")
                            googleStops.add(GtfsStop(obj.getString("place_id"), obj.getString("name"), loc.getDouble("lat"), loc.getDouble("lng"), "Google"))
                        }
                        
                        allNearbyStops.clear()
                        allNearbyStops.addAll(googleStops)
                        allNearbyStops.addAll(customStops)
                        
                        scope.launch(Dispatchers.Main) {
                            adicionarMarcadores(mapView, allNearbyStops)
                            orientarComGroq(location, googleStops, mapView, onFalar)
                            mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                        }
                    } catch (e: Exception) {
                        Log.e("ModoOnibus", "Erro parse: ${e.message}")
                        onFalar("Erro ao processar dados das paradas.")
                    }
                }
            })
        }
    }

    private fun orientarComGroq(userLoc: Location, stops: List<GtfsStop>, mapView: MapView, onFalar: (String) -> Unit) {
        if (stops.isEmpty()) { 
            onFalar("Nenhuma parada encontrada próxima.")
            return 
        }
        
        val listStops = stops.take(3).joinToString { "${it.name} em ${it.lat},${it.lon}" }
        val prompt = "Você é um guia para deficientes visuais em Manaus. Minha localização: ${userLoc.latitude}, ${userLoc.longitude}. Paradas próximas encontradas: [$listStops]. Forneça uma instrução curta e direta (máximo 20 palavras) de como chegar na parada mais próxima usando pontos cardeais ou referências simples."

        groqService.analisarImagemSemBitmap(prompt) { responseText: String ->
            scope.launch(Dispatchers.Main) {
                onFalar(responseText)
                buscarRota(LatLng(userLoc.latitude, userLoc.longitude), LatLng(stops[0].lat, stops[0].lon), mapView)
            }
        }
    }

    private fun adicionarMarcadores(mapView: MapView, stops: List<GtfsStop>) {
        val markersToRemove = mapView.overlays.filterIsInstance<Marker>()
        mapView.overlays.removeAll(markersToRemove)

        val busIcon = ContextCompat.getDrawable(context, R.drawable.ic_bus_marker)

        stops.forEachIndexed { index, stop ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(stop.lat, stop.lon)
            marker.title = stop.name
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            if (busIcon != null) {
                val size = (32 * context.resources.displayMetrics.density).toInt()
                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                busIcon.setBounds(0, 0, canvas.width, canvas.height)
                busIcon.draw(canvas)
                marker.icon = BitmapDrawable(context.resources, bitmap)
            }

            mapView.overlays.add(marker)
            if (index == 0) marker.showInfoWindow()
        }
        mapView.invalidate()
    }

    private fun buscarRota(origem: LatLng, destino: LatLng, mapView: MapView) {
        val url = "https://router.project-osrm.org/route/v1/foot/${origem.longitude},${origem.latitude};${destino.longitude},${destino.latitude}?overview=full"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    val geometry = json.getJSONArray("routes").getJSONObject(0).getString("geometry")
                    scope.launch(Dispatchers.Main) { exibirRotaNoMapa(geometry, mapView) }
                } catch (e: Exception) {}
            }
        })
    }

    private fun exibirRotaNoMapa(geometry: String, mapView: MapView) {
        val polylinesToRemove = mapView.overlays.filterIsInstance<Polyline>()
        mapView.overlays.removeAll(polylinesToRemove)

        val points = decodePolylineOsm(geometry)
        val polyline = Polyline()
        polyline.setPoints(points)
        polyline.outlinePaint.color = Color.parseColor("#7B1FA2")
        polyline.outlinePaint.strokeWidth = 15f
        
        mapView.overlays.add(polyline)
        
        if (points.isNotEmpty()) {
            mapView.controller.animateTo(points[0])
        }
        mapView.invalidate()
    }

    private fun decodePolylineOsm(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            poly.add(GeoPoint(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }

    fun salvarParadaAtual(onFalar: (String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                val stop = GtfsStop("custom_${System.currentTimeMillis()}", "Ponto Salvo", loc.latitude, loc.longitude, "CUSTOM")
                customStops.add(stop)
                val prefs = context.getSharedPreferences("visao_assertiva", Context.MODE_PRIVATE)
                val all = (prefs.getString("stops", "") ?: "") + "${stop.id};${stop.name};${stop.lat};${stop.lon}|"
                prefs.edit().putString("stops", all).apply()
                onFalar("Ponto salvo com sucesso.")
            }
        }
    }

    fun monitorarTrajeto(onFalar: (String) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { loc ->
                // 1. Verificar se está passando por uma parada conhecida (raio de 60m)
                val nearbyStop = allNearbyStops.find { stop ->
                    val results = FloatArray(1)
                    Location.distanceBetween(loc.latitude, loc.longitude, stop.lat, stop.lon, results)
                    results[0] < 60 
                }
                
                if (nearbyStop != null && nearbyStop.id != lastAnnouncedStopId) {
                    lastAnnouncedStopId = nearbyStop.id
                    onFalar("Passando pela parada: ${nearbyStop.name}")
                    return@addOnSuccessListener
                }

                // 2. Anunciar a rua atual (Geocoder)
                val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                try {
                    val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    if (addresses?.isNotEmpty() == true) {
                        val rua = addresses[0].thoroughfare ?: addresses[0].subLocality ?: ""
                        if (rua.isNotEmpty() && rua != lastAnnouncedLocation) {
                            lastAnnouncedLocation = rua
                            onFalar("Você está na $rua")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ModoOnibus", "Erro no monitoramento: ${e.message}")
                }
            }
        }
    }

    fun carregarCustomStops() {
        val prefs = context.getSharedPreferences("visao_assertiva", Context.MODE_PRIVATE)
        val data = prefs.getString("stops", "") ?: ""
        if (data.isNotEmpty()) {
            data.split("|").forEach { row ->
                if (row.contains(";")) {
                    val parts = row.split(";")
                    if (parts.size >= 4) {
                        customStops.add(GtfsStop(parts[0], parts[1], parts[2].toDouble(), parts[3].toDouble(), "CUSTOM"))
                    }
                }
            }
        }
    }
}
