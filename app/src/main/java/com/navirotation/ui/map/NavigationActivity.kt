// File: app/src/main/java/com/navirotation/ui/map/NavigationActivity.kt
package com.navirotation.ui.map

import com.navirotation.repository.CctvRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.kakaomobility.knsdk.*
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.ui.component.MapViewCameraMode
import com.kakaomobility.knsdk.map.uicustomsupport.renewal.KNMapMarker
import com.kakaomobility.knsdk.map.knmaprenderer.objects.KNMapCameraUpdate
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.kakaomobility.knsdk.common.util.IntPoint
import com.kakaomobility.knsdk.map.knmapview.KNMapView
import com.kakaomobility.knsdk.map.knmapview.idl.KNMarkerEventListener
import com.navirotation.R
import com.navirotation.TFLiteHelper
import com.navirotation.base.BaseActivity
import com.navirotation.databinding.ActivityNavigationBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlPullParser
import kotlin.math.*
import javax.inject.Inject

import com.navirotation.ui.map.NavigationActivity.Companion.CHANNEL_ID
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.MappedByteBuffer
import java.util.concurrent.Semaphore
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.annotation.DrawableRes
import android.graphics.Color
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@AndroidEntryPoint
class NavigationActivity :
    BaseActivity(), KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate, KNGuidance_SafetyGuideDelegate,
    KNGuidance_RouteGuideDelegate, KNGuidance_VoiceGuideDelegate, KNGuidance_CitsGuideDelegate {

    private lateinit var binding: ActivityNavigationBinding
    private lateinit var viewModel: NavigationViewModel

    @Inject lateinit var cctvRepository: CctvRepository

    private var startLatitude = 0.0
    private var startLongitude = 0.0
    private var endLatitude = 0.0
    private var endLongitude = 0.0

    // ★ 추가: 경유지(우회 포인트) WGS84 좌표 (있을 때만 사용)
    private var viaLatitude: Double? = null
    private var viaLongitude: Double? = null

    private var rgCode = ""
    private var floodedCctvList: List<CctvFeed> = emptyList()

    // 우회 재탐색 제어용
    private var lastDetourAt = 0L
    private val DETOUR_COOLDOWN_MS = 60_000L

    // 최신 경로(지도 폴리라인, WGS84) 캐시
    private var latestRouteWgs: List<com.naver.maps.geometry.LatLng> = emptyList()

    // 좌표 변환(이미 observeFlow에서 계산하던 값 캐시)
    private var lastKatechStartX = 0
    private var lastKatechStartY = 0
    private var lastKatechEndX = 0
    private var lastKatechEndY = 0

    // URL -> CCTV 매핑(분석 결과가 어느 CCTV인지 찾기)
    private val urlToFeed = java.util.concurrent.ConcurrentHashMap<String, CctvFeed>()

    // 이전 경로 스냅샷(비교용)
    private var prevRouteHash: String? = null
    private var prevRouteLenM: Double = 0.0
    private var awaitingReroute = false
    private var currentTrip: KNTrip? = null
    private var isGuidanceBound: Boolean = false

    private var dummyLatitude: Double? = null
    private var dummyLongitude: Double? = null

    companion object {
        private const val CHANNEL_ID = "navi"
    }

    private fun getTintedBitmap(
        @DrawableRes resId: Int,
        tintColor: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val original = BitmapFactory.decodeResource(resources, resId)
        val bmp = Bitmap.createScaledBitmap(original, width, height, true)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP)
        }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        return bmp
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_navigation)
        viewModel = ViewModelProvider(this)[NavigationViewModel::class.java]

        // CCTV 마커 클릭 리스너
        binding.naviView.mapComponent.mapView.knMarkerEventListener = object : KNMarkerEventListener {
            override fun onSingleTapped(mapView: KNMapView?, marker: KNMapMarker) {
                val idx = marker.info as? Int
                idx?.let { i ->
                    floodedCctvList.getOrNull(i)?.let { cctv ->
                        Log.d("CCTV_CLICK", "✅ 마커 클릭됨: ${cctv.name}, URL=${cctv.streamUrl}")
                        val intent = Intent(this@NavigationActivity, CctvPlayerActivity::class.java)
                        intent.putExtra("streamUrl", cctv.streamUrl)
                        intent.putExtra("name", cctv.name)
                        startActivity(intent)
                    }
                }
            }
            override fun onMarkerAnimateEnded(mapView: KNMapView?, marker: KNMapMarker) {}
            override fun onCalloutBubbleSelected(mapView: KNMapView?, marker: KNMapMarker) {}
            override fun onDoubleTapped(mapView: KNMapView?, marker: KNMapMarker) {}
            override fun onLongPressed(mapView: KNMapView?, marker: KNMapMarker) {}
        }
        observeFlow()

        // 출발/도착 + (선택) 경유 좌표 수신
        startLatitude  = intent.getDoubleExtra("startLatitude", 0.0)
        startLongitude = intent.getDoubleExtra("startLongitude", 0.0)
        endLatitude    = intent.getDoubleExtra("endLatitude", 0.0)
        endLongitude   = intent.getDoubleExtra("endLongitude", 0.0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            floodedCctvList = intent.getParcelableArrayListExtra("floodedCctvList", CctvFeed::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            floodedCctvList = intent.getParcelableArrayListExtra("floodedCctvList") ?: emptyList()
        }
        Log.d("NAV_CCTV", "전달받은 침수 CCTV 개수: ${floodedCctvList.size}")

        intent.getDoubleExtra("viaLatitude", Double.NaN).let { if (!it.isNaN()) viaLatitude = it }
        intent.getDoubleExtra("viaLongitude", Double.NaN).let { if (!it.isNaN()) viaLongitude = it }

        if (intent.hasExtra("dummyLatitude")) {
            dummyLatitude = intent.getDoubleExtra("dummyLatitude", 0.0)
            dummyLongitude = intent.getDoubleExtra("dummyLongitude", 0.0)
        }

        viewModel.getCoordConvertData(startLatitude, startLongitude, endLatitude, endLongitude)
    }

    private fun observeFlow() {
        lifecycleScope.launch {
            viewModel.coordZipResult.collectLatest { it ->
                if (it.success == null) return@collectLatest

                // 1) 출발/도착 KATECH 좌표(Int) 구성
                val katechStartX = it.success.startLongitude!!.split(".")[0].toInt()
                val katechStartY = it.success.startLatitude!!.split(".")[0].toInt()
                val katechEndX = it.success.endLongitude!!.split(".")[0].toInt()
                val katechEndY = it.success.endLatitude!!.split(".")[0].toInt()

                // ★ (A) 최신 좌표 캐시
                lastKatechStartX = katechStartX
                lastKatechStartY = katechStartY
                lastKatechEndX = katechEndX
                lastKatechEndY = katechEndY

                val start = KNPOI("출발", katechStartX, katechStartY, null)
                val end = KNPOI("도착", katechEndX, katechEndY, null)

                // 2) (선택) 경유지 KATECH 변환 후 KNPOI 생성
                val viaPois: MutableList<KNPOI>? =
                    if (viaLatitude != null && viaLongitude != null) {
                        val fp: FloatPoint? =
                            viewModel.convertWgsToKatechSync(viaLatitude!!, viaLongitude!!)
                        if (fp != null) {
                            val vx = fp.x.toInt()
                            val vy = fp.y.toInt()
                            mutableListOf(KNPOI("우회1", vx, vy, null)).also {
                                Log.i(
                                    "NAV_VIA",
                                    "경유지 적용(KATECH): x=$vx, y=$vy (WGS: ${viaLatitude}, ${viaLongitude})"
                                )
                            }
                        } else {
                            Log.w(
                                "NAV_VIA",
                                "경유지 KATECH 변환 실패(WGS: ${viaLatitude}, ${viaLongitude}) — 경유지 없이 진행"
                            )
                            null
                        }
                    } else null

                // 3) Trip 생성: 경유지가 있으면 함께 전달
                KNSDK.makeTripWithStart(
                    start, end, viaPois, null
                ) { knError: KNError?, knTrip: KNTrip? ->
                    if (knError != null || knTrip == null) {
                        Log.d("NAVI_ROTATION", "경로 생성 에러(KNError): $knError")
                        Toast.makeText(
                            this@NavigationActivity,
                            "경로 생성 실패: $knError",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@makeTripWithStart
                    }

                    currentTrip = knTrip

                    val curPriority = KNRoutePriority.KNRoutePriority_Recommand
                    val curAvoidOption = KNRouteAvoidOption.KNRouteAvoidOption_None.value

                    knTrip.routeWithPriority(curPriority, curAvoidOption) { error, routes ->
                        if (error != null || routes.isNullOrEmpty()) {
                            Log.d("NAVI_ROTATION", "경로 요청 실패 : $error")
                            Toast.makeText(
                                this@NavigationActivity,
                                "경로 요청 실패: $error",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@routeWithPriority
                        }

                        // 최신 경로 캐시(선택)
                        try {
                            latestRouteWgs = routeToWgsLatLng(routes.first())
                        } catch (_: Throwable) {
                        }

                        val g = KNSDK.sharedGuidance()
                        if (g == null) {
                            Log.w("NAVI_ROTATION", "sharedGuidance==null — 바인딩을 진행합니다.")
                        } else {
                            // 델리게이트 세팅
                            g.apply {
                                guideStateDelegate = this@NavigationActivity
                                locationGuideDelegate = this@NavigationActivity
                                routeGuideDelegate = this@NavigationActivity
                                safetyGuideDelegate = this@NavigationActivity
                                voiceGuideDelegate = this@NavigationActivity
                                citsGuideDelegate = this@NavigationActivity
                            }
                        }

                        // ★ 초기 바인딩: 세션 종료 없이 바로 initWithGuidance
                        settingMap()
                        KNSDK.sharedGuidance()?.let { gg ->
                            binding.naviView.initWithGuidance(
                                gg,
                                knTrip,
                                curPriority,
                                curAvoidOption
                            )
                            isGuidanceBound = true    // ★ 최초 바인딩 완료
                        } ?: run {
                            // 드물게 null이면 다음 프레임으로 미루어 바인딩 시도
                            binding.naviView.post {
                                KNSDK.sharedGuidance()?.let { gg ->
                                    binding.naviView.initWithGuidance(
                                        gg,
                                        knTrip,
                                        curPriority,
                                        curAvoidOption
                                    )
                                    isGuidanceBound = true
                                }
                            }
                        }

                        Log.d("NAVI_ROTATION", "경로 요청 성공 (경유지=${viaPois?.size ?: 0})")
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.distanceData.collectLatest {
                Log.d("NAVI_ROTATION", "SK 직선 거리 : ${it.success?.distance}")
                if (it.success == null) return@collectLatest
                binding.tvInform.text = "다음 경로: $rgCode ${it.success.distance}m"
            }
        }
    }

    override fun guidanceDidUpdateRoutes(
        aGuidance: KNGuidance,
        aRoutes: List<KNRoute>,
        aMultiRouteInfo: KNMultiRouteInfo?
    ) {
        binding.naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo)
        lifecycleScope.launch {
            // 1. 경로 정보가 비어있는지 확인
            val firstRoute = aRoutes.firstOrNull()
            if (firstRoute == null) {
                Log.e("NAV_DEBUG", "경로 정보가 없습니다.")
                return@launch
            }

            // 2. 경로의 전체 좌표(WGS84)를 가져옵니다.
            val wgsPoints = routeToWgsLatLng(firstRoute)
            if (wgsPoints.isEmpty()) {
                Log.e("NAV_DEBUG", "경로 좌표가 비어있습니다.")
                return@launch
            }

            // 3. 경로의 정중앙 지점(mid)을 계산합니다.
            val mid = wgsPoints[wgsPoints.size / 2]

            // 4. 중앙 지점 좌표로 사용자께서 원하시는 더미 데이터를 생성합니다.
            val dummyFeed = CctvFeed(
                id        = "dummy",
                name      = "테스트 침수 영상",
                latitude  = dummyLatitude!!,
                longitude = dummyLongitude!!,
                streamUrl   = "https://ysj4384.github.io/flood-test-video/test_flood_video.mp4"
            )

            // 5. 생성한 더미 데이터를 리스트에 담아 showCctvMarkers 함수에 전달합니다.
            showCctvMarkers(listOf(dummyFeed))
        }
    }
    // ================= CCTV 마커 추가/클릭 =================
    private suspend fun getCctvListFromApiWgsBbox(
        minX: String, maxX: String, minY: String, maxY: String
    ): List<CctvFeed> = withContext(Dispatchers.IO) {
        try {
            val xmlString = cctvRepository.fetchCctvXml(
                category = "all",
                pageNo = "1",
                cctvType = "2", // 👈 ⭐ 이 부분을 추가해야 합니다. (예시: "2" - 이미지)
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY
            ).execute().body()
            Log.d("CCTV", "WGS84 bbox CCTV API 응답(200자): ${xmlString?.take(200)}")
            val list = parseCctvXmlToFeedList(xmlString ?: "")
            Log.d("CCTV", "API에서 파싱된 CCTV 개수: ${list.size}")
            list
        } catch (e: Exception) {
            Log.e("CCTV", "CCTV API Error: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseCctvXmlToFeedList(xml: String): List<CctvFeed> {
        val list = mutableListOf<CctvFeed>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(xml.reader()) }
        var event = parser.eventType
        var id = ""; var name = ""; var lat = 0.0; var lon = 0.0; var url = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "data" -> { id = ""; name = ""; lat = 0.0; lon = 0.0; url = "" }
                    "roadsectionid" -> id = parser.nextText().ifBlank { "" }
                    "cctvname"      -> name = parser.nextText()
                    "coordy"        -> lat  = parser.nextText().toDoubleOrNull() ?: 0.0
                    "coordx"        -> lon  = parser.nextText().toDoubleOrNull() ?: 0.0
                    "cctvurl"       -> url  = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "data") {
                    if (lat != 0.0 && lon != 0.0) {
                        list += CctvFeed(id, name, lat, lon, url)
                    }
                }
            }
            event = parser.next()
        }
        return list.toList()
    }

    // URL 추출
    private fun List<CctvFeed>.extractUrls(): List<String> =
        this.map { it.streamUrl }.filter { it.isNotBlank() }

    // 거리(m) 계산 함수 (NavigationActivity 클래스 내부에 추가)
    private fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val s = kotlin.math.sin(dLat/2).pow(2) +
                kotlin.math.cos(Math.toRadians(aLat)) * kotlin.math.cos(Math.toRadians(bLat)) *
                kotlin.math.sin(dLon/2).pow(2)
        return 2 * R * kotlin.math.atan2(kotlin.math.sqrt(s), kotlin.math.sqrt(1 - s))
    }

    // KNRoute -> WGS84 폴리라인(네이버 LatLng 리스트) 변환
    private fun routeToWgsLatLng(route: KNRoute): List<com.naver.maps.geometry.LatLng> {
        val raw = route.routePolylineWGS84() ?: return emptyList()
        return raw.mapNotNull { m ->
            val lat = (m["y"] as? Number)?.toDouble()
            val lon = (m["x"] as? Number)?.toDouble()
            if (lat != null && lon != null) com.naver.maps.geometry.LatLng(lat, lon) else null
        }
    }

    /** 폴리라인의 길이(m) */
    private fun routeLengthM(points: List<com.naver.maps.geometry.LatLng>): Double =
        points.zipWithNext().sumOf { (a, b) -> haversine(a.latitude, a.longitude, b.latitude, b.longitude) }

    /** 경로 비교용 해시(앞쪽 50개 좌표를 5자리로 라운딩해서 문자열화) */
    private fun routeHash(points: List<com.naver.maps.geometry.LatLng>): String =
        points.take(50).joinToString("|") { p ->
            "${"%.5f".format(p.latitude)},${"%.5f".format(p.longitude)}"
        }

    private fun nearestIndexOnPolyline(p: com.naver.maps.geometry.LatLng, poly: List<com.naver.maps.geometry.LatLng>): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in poly.indices) {
            val d = haversine(p.latitude, p.longitude, poly[i].latitude, poly[i].longitude)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0) best else 0
    }

    private fun pointAheadOnPolyline(poly: List<com.naver.maps.geometry.LatLng>, startIdx: Int, aheadM: Int): com.naver.maps.geometry.LatLng {
        var acc = 0.0
        var i = startIdx
        while (i + 1 < poly.size) {
            val a = poly[i]
            val b = poly[i + 1]
            val seg = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
            if (acc + seg >= aheadM) {
                val t = (aheadM - acc) / seg
                val lat = a.latitude + (b.latitude - a.latitude) * t
                val lng = a.longitude + (b.longitude - a.longitude) * t
                return com.naver.maps.geometry.LatLng(lat, lng)
            }
            acc += seg
            i++
        }
        return poly.last()
    }

    private fun offsetPerpendicular(
        base: com.naver.maps.geometry.LatLng,
        next: com.naver.maps.geometry.LatLng,
        offsetM: Int,
        awayFrom: com.naver.maps.geometry.LatLng
    ): com.naver.maps.geometry.LatLng {
        val dx = next.longitude - base.longitude
        val dy = next.latitude  - base.latitude
        val len = kotlin.math.hypot(dx, dy)
        if (len == 0.0) return base
        val nx = -dy / len
        val ny =  dx / len

        fun meterToLat(m: Double) = m / 111_320.0
        fun meterToLng(lat: Double, m: Double) = m / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))

        val left  = com.naver.maps.geometry.LatLng(
            base.latitude  + ny * meterToLat(offsetM.toDouble()),
            base.longitude + nx * meterToLng(base.latitude, offsetM.toDouble())
        )
        val right = com.naver.maps.geometry.LatLng(
            base.latitude  - ny * meterToLat(offsetM.toDouble()),
            base.longitude - nx * meterToLng(base.latitude, offsetM.toDouble())
        )
        val dl = haversine(awayFrom.latitude, awayFrom.longitude, left.latitude, left.longitude)
        val dr = haversine(awayFrom.latitude, awayFrom.longitude, right.latitude, right.longitude)
        return if (dl >= dr) left else right
    }

    /** 최신 경로 기준 앞쪽으로 aheadM 이동 후 좌/우 offsetM 떨어진 점을 우회 경유지로 선택 */
    private fun computeForwardBypass(flood: com.naver.maps.geometry.LatLng, aheadM: Int = 300, offsetM: Int = 160): com.naver.maps.geometry.LatLng {
        val poly = latestRouteWgs
        if (poly.isEmpty()) return flood
        val meIdx    = nearestIndexOnPolyline(com.naver.maps.geometry.LatLng(startLatitude, startLongitude), poly)
        val floodIdx = nearestIndexOnPolyline(flood, poly)
        val startIdx = kotlin.math.max(meIdx, floodIdx)
        val base = pointAheadOnPolyline(poly, startIdx, aheadM)
        val next = pointAheadOnPolyline(poly, startIdx, aheadM + 30)
        return offsetPerpendicular(base, next, offsetM, flood)
    }

    private suspend fun requestRerouteOnce(
        aheadM: Int,
        offsetM: Int,
        floodWgs: com.naver.maps.geometry.LatLng
    ): Boolean = suspendCancellableCoroutine { cont ->
        // 1) 우회 경유 후보 계산(앞쪽/옆쪽)
        val bypassWgs = computeForwardBypass(floodWgs, aheadM = aheadM, offsetM = offsetM)

        lifecycleScope.launch {
            // 2) WGS84 -> KATECH
            val fp = try { viewModel.convertWgsToKatechSync(bypassWgs.latitude, bypassWgs.longitude) }
            catch (_: Exception) { null }
            if (fp == null) {
                Log.w("NAV_REROUTE", "Katech 변환 실패 (ahead=$aheadM, offset=$offsetM)")
                cont.resume(false); return@launch
            }

            val viaPoi   = KNPOI("우회1", fp.x.toInt(), fp.y.toInt(), null)
            val startPoi = KNPOI("출발", lastKatechStartX, lastKatechStartY, null)
            val endPoi   = KNPOI("도착", lastKatechEndX,   lastKatechEndY,   null)

            // 3) 이전 경로 스냅샷 저장(Δ 비교용)
            prevRouteHash = routeHash(latestRouteWgs)
            prevRouteLenM = routeLengthM(latestRouteWgs)
            awaitingReroute = true
            Log.i("NAV_DIFF", "스냅샷 저장 hash=$prevRouteHash len=${"%.0f".format(prevRouteLenM)}m")

            // 4) Trip 생성
            KNSDK.makeTripWithStart(startPoi, endPoi, mutableListOf(viaPoi), null) { e, trip ->
                if (e != null || trip == null) {
                    Log.w("NAV_REROUTE", "우회 trip 생성 실패(ahead=$aheadM, offset=$offsetM): $e")
                    cont.resume(false); return@makeTripWithStart
                }

                val priority = KNRoutePriority.KNRoutePriority_Recommand
                val avoidOpt = KNRouteAvoidOption.KNRouteAvoidOption_RoadEvent.value

                // 5) 경로 요청
                trip.routeWithPriority(priority, avoidOpt) { err, routes ->
                    if (err != null || routes.isNullOrEmpty()) {
                        Log.w("NAV_REROUTE", "우회 경로 요청 실패(ahead=$aheadM, offset=$offsetM): $err")
                        cont.resume(false); return@routeWithPriority
                    }

                    // 6) 새 경로 WGS84로 즉시 비교(Δ km)
                    val newWgs = routeToWgsLatLng(routes.first())
                    latestRouteWgs = newWgs
                    val newHash = routeHash(newWgs)
                    val newLen  = routeLengthM(newWgs)
                    val changed = (prevRouteHash == null || newHash != prevRouteHash)

                    if (changed) {
                        val deltaM  = newLen - prevRouteLenM
                        val deltaKm = kotlin.math.abs(deltaM) / 1000.0
                        val msg = if (deltaM >= 0) "우회 재탐색: 이전 대비 +%.2f km".format(deltaKm)
                        else              "우회 재탐색: 이전 대비 -%.2f km".format(deltaKm)
                        Log.i("NAV_DIFF", "경로 변경 감지(즉시): $msg")
                        Toast.makeText(this@NavigationActivity, msg, Toast.LENGTH_SHORT).show()
                        binding.tvInform.text = "우회 재탐색 완료 · Δ %.2f km".format(deltaKm)
                        prevRouteHash = newHash
                        prevRouteLenM = newLen
                    } else {
                        Log.i("NAV_DIFF", "경로 변경 없음(동일 루트)")
                    }
                    awaitingReroute = false

                    // 7) ★★★ 엔진/뷰에 새 Trip 확실히 주입(패널/남은거리까지 싱크) ★★★
                    val g = KNSDK.sharedGuidance()
                    if (g == null) {
                        Log.w("NAV_REROUTE", "sharedGuidance==null — 지연 바인딩 시도")
                        binding.naviView.post {
                            KNSDK.sharedGuidance()?.let { gg ->
                                settingMap()
                                binding.naviView.initWithGuidance(gg, trip, priority, avoidOpt)
                            }
                        }
                        cont.resume(changed); return@routeWithPriority
                    }

// 델리게이트 보강
                    g.apply {
                        guideStateDelegate    = this@NavigationActivity
                        locationGuideDelegate = this@NavigationActivity
                        routeGuideDelegate    = this@NavigationActivity
                        safetyGuideDelegate   = this@NavigationActivity
                        voiceGuideDelegate    = this@NavigationActivity
                        citsGuideDelegate     = this@NavigationActivity
                    }

// 엔진에 직접 setTrip 지원 시 호출(없으면 무시)
                    try { g.javaClass.methods.firstOrNull { it.name == "setTrip" }?.invoke(g, trip) } catch (_: Throwable) {}

// ★ 재탐색 때만 세션 정리
                    if (isGuidanceBound) {
                        try { binding.naviView.guidanceGuideEnded(g, false) } catch (_: Throwable) {}
                    }

                    settingMap()
                    binding.naviView.initWithGuidance(g, trip, priority, avoidOpt)
                    isGuidanceBound = true
                    Toast.makeText(this@NavigationActivity, "침수 구간 회피 경로로 재탐색했습니다.", Toast.LENGTH_SHORT).show()
                    cont.resume(changed)
                }
            }
        }
    }

    data class CctvFeed(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val streamUrl: String
    )

    private fun getResizedBitmap(resourceId: Int, width: Int, height: Int): Bitmap {
        val originalBitmap = BitmapFactory.decodeResource(resources, resourceId)
        return Bitmap.createScaledBitmap(originalBitmap, width, height, true)
    }

    private suspend fun showCctvMarkers(cctvList: List<CctvFeed>) {
        val mapView = binding.naviView.mapComponent.mapView
        mapView.removeMarkersAll()

        val floodIcon = getTintedBitmap(R.drawable.ic_cctv_marker, Color.RED, 80, 80)

        if (cctvList.isEmpty()) {
            Log.d("CCTV", "표시할 침수 CCTV가 없습니다.")
            return
        }

        this.floodedCctvList = cctvList

        val markers = withContext(Dispatchers.IO) {
            cctvList.mapIndexedNotNull { idx, cctv ->
                val fp = viewModel.convertWgsToKatechSync(cctv.latitude, cctv.longitude)
                    ?: return@mapIndexedNotNull null

                KNMapMarker(fp).apply {
                    icon = floodIcon
                    info = idx
                    icon?.let {
                        pixelOffset = IntPoint(0, it.height / 2)
                    }
                }
            }
        }
        mapView.addMarkers(markers)
    }

    private fun settingMap() {
        binding.naviView.mapViewMode = MapViewCameraMode.Top
        binding.naviView.carType = KNCarType.KNCarType_Bike
    }

    override fun onBackPressed() {
        guidanceGuideEnded(binding.naviView.guidance)
        super.onBackPressed()
    }

    // ======= KNSDK 안내 콜백 =======
    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) {
        binding.naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide)
        if (aRouteGuide.curDirection?.location?.pos != null && aRouteGuide.nextDirection?.location?.pos != null) {
            rgCode = aRouteGuide.nextDirection?.rgCode.toString()
            when (rgCode) {
                "KNRGCode_Straight" -> rgCode = "직진"
                "KNRGCode_LeftTurn" -> rgCode = "좌회전"
                "KNRGCode_RightTurn" -> rgCode = "우회전"
                "KNRGCode_UTurn"    -> rgCode = "유턴"
            }
            viewModel.getDistanceData(
                aRouteGuide.curDirection?.location?.pos!!.toFloatPoint(),
                aRouteGuide.nextDirection?.location?.pos!!.toFloatPoint()
            )
        }
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        binding.naviView.guidanceGuideEnded(aGuidance, false); finish()
    }

    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) {
        binding.naviView.guidanceCheckingRouteChange(aGuidance)
    }

    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) {
        binding.naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties)
    }

    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) {
        binding.naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide)
    }

    override fun guidanceDidUpdateLocation(g: KNGuidance, loc: KNGuide_Location) {
        binding.naviView.guidanceDidUpdateLocation(g, loc)
    }

    override fun guidanceGuideStarted(aGuidance: KNGuidance) {
        binding.naviView.guidanceGuideStarted(aGuidance)
    }

    override fun guidanceOutOfRoute(aGuidance: KNGuidance) {
        binding.naviView.guidanceOutOfRoute(aGuidance)
    }

    override fun guidanceRouteChanged(
        aGuidance: KNGuidance,
        aFromRoute: KNRoute,
        aFromLocation: KNLocation,
        aToRoute: KNRoute,
        aToLocation: KNLocation,
        aChangeReason: KNGuideRouteChangeReason
    ) {
        binding.naviView.guidanceRouteChanged(aGuidance)

        latestRouteWgs = routeToWgsLatLng(aToRoute)
        Log.i("NAV_DIFF", "guidanceRouteChanged 수신: pts=${latestRouteWgs.size}, awaiting=$awaitingReroute, reason=$aChangeReason")

        // ★ 스냅샷이 있고 재탐색에 대한 응답이라면 비교
        if (awaitingReroute) {
            val newHash = routeHash(latestRouteWgs)
            val newLen  = routeLengthM(latestRouteWgs)

            if (prevRouteHash == null) {
                prevRouteHash = newHash
                prevRouteLenM = newLen
                Log.i("NAV_DIFF", "기준 경로 세팅 hash=$newHash len=${"%.0f".format(newLen)}m")
            } else if (newHash != prevRouteHash) {
                val deltaM = newLen - prevRouteLenM
                val deltaKm = kotlin.math.abs(deltaM) / 1000.0
                val msg = if (deltaM >= 0) "우회 재탐색: 이전 대비 +%.2f km".format(deltaKm)
                else              "우회 재탐색: 이전 대비 -%.2f km".format(deltaKm)
                Log.i("NAV_DIFF", "경로 변경 감지: $msg (old=${"%.0f".format(prevRouteLenM)}m → new=${"%.0f".format(newLen)}m)")
                Toast.makeText(this@NavigationActivity, msg, Toast.LENGTH_SHORT).show()
                binding.tvInform.text = "우회 재탐색 완료 · Δ %.2f km".format(deltaKm)

                prevRouteHash = newHash
                prevRouteLenM = newLen
            } else {
                Log.i("NAV_DIFF", "경로 변경 없음(동일 루트)")
            }
            awaitingReroute = false
        }

    }

    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) {
        binding.naviView.guidanceRouteUnchanged(aGuidance)
    }

    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) {
        binding.naviView.guidanceRouteUnchangedWithError(aGuidnace, aError)
    }

    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        binding.naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun shouldPlayVoiceGuide(
        aGuidance: KNGuidance,
        aVoiceGuide: KNGuide_Voice,
        aNewData: MutableList<ByteArray>
    ): Boolean {
        return binding.naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData)
    }

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        binding.naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) {
        binding.naviView.didUpdateCitsGuide(aGuidance, aCitsGuide)
    }
}
