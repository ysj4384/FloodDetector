// File: app/src/main/java/com/navirotation/ui/map/MapActivity.kt

package com.navirotation.ui.map

import com.navirotation.repository.CctvRepository
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError_Code_C103
import com.kakaomobility.knsdk.common.objects.KNError_Code_C302
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PolylineOverlay
import com.naver.maps.map.util.MarkerIcons
import com.navirotation.BuildConfig
import com.navirotation.CctvFeed
import com.navirotation.R
import com.navirotation.TFLiteHelper
import com.navirotation.base.BaseActivity
import com.navirotation.databinding.ActivityMapBinding
import com.navirotation.navi.DetourKakaoNavi

import com.navirotation.service.DirectionResponse
import com.navirotation.service.DirectionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.MappedByteBuffer
import javax.inject.Inject
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.navirotation.worker.CctvAnalysisWorker
import java.util.concurrent.TimeUnit
import kotlin.math.*

@AndroidEntryPoint
class MapActivity : BaseActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMapBinding
    private lateinit var viewModel: MapViewModel
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var naverMap: NaverMap

    @Inject
    lateinit var cctvRepository: CctvRepository
    @Inject
    lateinit var directionService: DirectionService

    private var myLatitude = 0.0
    private var myLongitude = 0.0
    private var endLatitude = 0.0
    private var endLongitude = 0.0
    private var tflite: Interpreter? = null
    private var simFloodMarker: Marker? = null
    private var detourVia: LatLng? = null
    private val detourPolylines = mutableListOf<PolylineOverlay>()
    private val detourColors = listOf(0xFFFF9800.toInt(), 0xFF4CAF50.toInt(), 0xFF7E57C2.toInt())
    private data class DetourCandidate(val via: LatLng, val coords: List<LatLng>, val lengthM: Double)
    private var detourCandidates: MutableList<DetourCandidate> = mutableListOf()
    private var dummyFeedForNavi: CctvFeed? = null

    companion object {
        private const val CHANNEL_ID = "flood"
    }

    private val permissionArray = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val myLocationMarker = Marker()
    private val endMarker = Marker()
    private val markers = mutableListOf<Marker>()
    private var routeLine: PolylineOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_map)
        viewModel = ViewModelProvider(this)[MapViewModel::class.java]
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        NaverMapSdk.getInstance(this).client =
            NaverMapSdk.NaverCloudPlatformClient(BuildConfig.NAVER_CLIENT_ID)

        setupListeners()
        observeLiveData()

        if (permissionArray.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                viewModel.getInitMyLocationData(fusedLocationClient)
            } else {
                myLatitude = 37.55453
                myLongitude = 126.97071
                binding.mapView.getMapAsync(this)
            }
        } else {
            requestPermissions(permissionArray, 1)
        }

        try {
            val modelBuffer: MappedByteBuffer = TFLiteHelper.loadModelFile(assets, "updatemodel.tflite")
            tflite = Interpreter(modelBuffer)
        } catch (e: IOException) {
            Toast.makeText(this, "모델 로딩 실패", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeLiveData() {
        viewModel.initMyLocationData.observe(this) { loc ->
            loc?.let {
                myLatitude = it.latitude
                myLongitude = it.longitude
                binding.mapView.getMapAsync(this)
            }
        }
        viewModel.updateMyLocationData.observe(this) { loc ->
            loc?.let {
                myLatitude = it.latitude
                myLongitude = it.longitude
                setMyLocationMarker()
                moveCamera(myLatitude, myLongitude)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                viewModel.getInitMyLocationData(fusedLocationClient)
            } else {
                myLatitude = 37.55453
                myLongitude = 126.97071
                binding.mapView.getMapAsync(this)
            }
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Toast.makeText(this, "권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMapReady(mMap: NaverMap) {
        naverMap = mMap
        setMyLocationMarker()
        moveCamera(myLatitude, myLongitude)

        naverMap.setOnMapLongClickListener { _, latLng ->
            if (endLatitude == 0.0 || endLongitude == 0.0) {
                Toast.makeText(this, "목적지를 먼저 선택해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnMapLongClickListener
            }

            val bypass = pickBypassVia(latLng, 150)
            detourVia = bypass
            simFloodMarker?.map = null
            simFloodMarker = Marker().apply {
                position = latLng
                icon = MarkerIcons.BLACK
                iconTintColor = Color.RED
                captionText = "MOCK FLOOD"
                map = naverMap
            }

            if (com.navirotation.BuildConfig.USE_KAKAO_NAV) {
                DetourKakaoNavi.launchWithDetour(this, LatLng(myLatitude, myLongitude), LatLng(endLatitude, endLongitude), latLng, 150, 1)
            } else {
                requestNaverDetour(bypass)
                Toast.makeText(this, "디버그: 우회 경로(주황색) 적용", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val searchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                endLatitude = result.data!!.getDoubleExtra("latitude", 0.0)
                endLongitude = result.data!!.getDoubleExtra("longitude", 0.0)

                binding.etSearch.setText(result.data!!.getStringExtra("placeName"))
                binding.tvPlaceName.text = result.data!!.getStringExtra("placeName")
                binding.tvAddressName.text = result.data!!.getStringExtra("addressName")
                binding.tvDistance.text = result.data!!.getStringExtra("distance")
                binding.ctDetail.visibility = View.VISIBLE

                endMarker.apply {
                    map = null; width = 70; height = 100
                    icon = MarkerIcons.BLACK; iconTintColor = Color.RED
                    position = LatLng(endLatitude, endLongitude); map = naverMap
                }
                moveCamera(endLatitude, endLongitude)
                drawRealRoute()
                loadCctvForRoute()
            }
        }

    private fun setupListeners() {
        binding.ibMyLocation.setOnClickListener { viewModel.getUpdateMyLocationData(fusedLocationClient) }
        binding.etSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("latitude", myLatitude.toString())
                putExtra("longitude", myLongitude.toString())
            }
            searchLauncher.launch(intent)
        }
        binding.btnIntentNavi.setOnClickListener {
            KNSDK.initializeWithAppKey(BuildConfig.KAKAO_NATIVE_APP_KEY, BuildConfig.VERSION_NAME, null, KNLanguageType.KNLanguageType_KOREAN) { error ->
                if (error != null) {
                    when (error.code) {
                        KNError_Code_C103 -> Log.d("MapActivity", "내비 인증 실패")
                        KNError_Code_C302 -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                        else -> Log.d("MapActivity", "내비 초기화 실패: $error")
                    }
                } else {
                    val intent = Intent(this, NavigationActivity::class.java).apply {
                        putExtra("startLatitude", myLatitude)
                        putExtra("startLongitude", myLongitude)
                        putExtra("endLatitude", endLatitude)
                        putExtra("endLongitude", endLongitude)
                        detourVia?.let { via ->
                            putExtra("viaLatitude", via.latitude)
                            putExtra("viaLongitude", via.longitude)
                        }
                        dummyFeedForNavi?.let { feed ->
                            putExtra("dummyLatitude", feed.latitude)
                            putExtra("dummyLongitude", feed.longitude)
                        }
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun drawRealRoute() {
        routeLine?.map = null
        val startStr = "$myLongitude,$myLatitude"
        val goalStr  = "$endLongitude,$endLatitude"
        directionService.getRoute(start = startStr, goal = goalStr).enqueue(object : Callback<DirectionResponse> {
            override fun onResponse(call: Call<DirectionResponse>, res: Response<DirectionResponse>) {
                res.body()?.route?.trafast?.firstOrNull()?.let {
                    val coords = it.path.map { pair -> LatLng(pair[1], pair[0]) }
                    routeLine = PolylineOverlay().apply {
                        this.coords = coords
                        this.width = 10
                        this.color = Color.BLUE
                        this.map = naverMap
                    }
                    startBackgroundAnalysis()
                }
            }
            override fun onFailure(call: Call<DirectionResponse>, t: Throwable) { Log.e("MapActivity", "길찾기 API 실패", t) }
        })
    }

    // 15분 주기 백그라운드 작업 시작
    private fun startBackgroundAnalysis() {
        // Worker에게 보낼 좌표 데이터 포장
        val inputData = Data.Builder()
            .putDouble("START_LAT", myLatitude)
            .putDouble("START_LON", myLongitude)
            .putDouble("END_LAT", endLatitude)
            .putDouble("END_LON", endLongitude)
            .build()

        // 15분 주기 설정 (배터리 최적화를 위해 최소 15분임)
        val analysisRequest = PeriodicWorkRequestBuilder<CctvAnalysisWorker>(1, TimeUnit.MINUTES)
            .setInputData(inputData)
            .build()

        // "cctvAnalysis"라는 이름표를 붙여서 작업 등록 (중복 실행 방지)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cctvAnalysis",
            ExistingPeriodicWorkPolicy.UPDATE, // 이미 돌고 있으면 새 정보로 업데이트
            analysisRequest
        )

        Log.d("MapActivity", "15분 주기 백그라운드 CCTV 분석 작업을 시작합니다.")
    }

    private fun loadCctvForRoute() {
        if (myLatitude == 0.0 || endLatitude == 0.0) return
        markers.forEach { it.map = null }
        markers.clear()

        val minLat = minOf(myLatitude, endLatitude).toString()
        val maxLat = maxOf(myLatitude, endLatitude).toString()
        val minLon = minOf(myLongitude, endLongitude).toString()
        val maxLon = maxOf(myLongitude, endLongitude).toString()

        var xmlExpressway = "" // 1. 고속도로 XML 결과 담을 변수
        var xmlNational = ""   // 2. 국도 XML 결과 담을 변수
        var callCount = 0      // 3. API 호출 횟수 카운터

        // 두 번의 API 호출 결과를 모두 처리하기 위한 헬퍼 함수
        fun processCombinedResults() {
            callCount++
            // API 호출 2번이 모두 끝났을 때만 아래 로직 실행
            if (callCount < 2) return

            Log.d("CCTV_PARSE", "고속도로, 국도 API 호출 완료. 파싱을 시작합니다.")

            // 🌟 디버깅: 국도 XML만 파싱하여 개수 확인
            val onlyNationalData = parseCctvXmlDataTag(xmlNational).joinToString("")
            val onlyNationalXml = "<response><data>$onlyNationalData</data></response>"
            val feedsOnlyNational = parseCctvXml(onlyNationalXml)
            Log.d("CCTV_DEBUG", "국도 XML만 단독 파싱된 CCTV 개수: ${feedsOnlyNational.size}개")

            // 🌟 디버깅: 고속도로 XML만 파싱하여 개수 확인
            val onlyExpresswayData = parseCctvXmlDataTag(xmlExpressway).joinToString("")
            val onlyExpresswayXml = "<response><data>$onlyExpresswayData</data></response>"
            val feedsOnlyExpressway = parseCctvXml(onlyExpresswayXml)
            Log.d("CCTV_DEBUG", "고속도로 XML만 단독 파싱된 CCTV 개수: ${feedsOnlyExpressway.size}개")
            // 🌟 디버깅 끝

            // 4. 고속도로와 국도 XML에서 <data> 태그 내용만 합치기
            val combinedXmlData = (parseCctvXmlDataTag(xmlExpressway) + parseCctvXmlDataTag(xmlNational)).joinToString("")
            // 5. 하나의 문자열로 다시 감싸기 (파싱을 위해)
            val finalXml = "<response><data>$combinedXmlData</data></response>"

            val feeds = parseCctvXml(finalXml) // 합친 XML로 파싱

            // ▼▼▼ 최종 파싱 결과 로그 출력 ▼▼▼
            Log.d("CCTV_PARSE", "--- 최종 파싱된 CCTV 목록 (총 ${feeds.size}개) ---")
            feeds.forEachIndexed { index, feed ->
                Log.d("CCTV_PARSE", "[${index + 1}] 이름: ${feed.name}")
            }
            Log.d("CCTV_PARSE", "--- 목록 출력 완료 ---")
            // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

            val routeCoords = routeLine?.coords ?: emptyList()
            // 🚩 필터링 거리 80m -> 150m로 완화
            val closeFeeds = feeds.filter { feed -> routeCoords.any { haversine(feed.latitude, feed.longitude, it.latitude, it.longitude) <= 150.0 } }

            Log.d("CCTV_PARSE", "--- 경로 주변 필터링 결과 (총 ${closeFeeds.size}개) ---")

            val finalFeeds: List<CctvFeed> = if (routeCoords.isNotEmpty()) {
                val mid = routeCoords[routeCoords.size / 2]
                val dummyFeed = CctvFeed("dummy", "테스트 침수 영상", mid.latitude, mid.longitude, "https://ysj4384.github.io/flood-test-video/test_flood_video.mp4")
                dummyFeedForNavi = dummyFeed
                closeFeeds + dummyFeed
            } else { closeFeeds }

            finalFeeds.forEach { feed ->
                markers += Marker().apply {
                    position = LatLng(feed.latitude, feed.longitude)
                    icon = MarkerIcons.BLACK
                    iconTintColor = if (feed.id == "dummy") Color.RED else Color.BLUE
                    captionText = if (feed.id == "dummy") "침수영상" else feed.name
                    map = naverMap
                    tag = feed
                    setOnClickListener { onMarkerClick(this); true }
                }
            }

            lifecycleScope.launch {
                val analysisJobs = mutableListOf<Deferred<CctvFeed?>>()
                finalFeeds.forEach { feed ->
                    if (feed.streamUrl.isNotBlank()) {
                        val job = async(Dispatchers.IO) { analyzeCctvStream(feed) }
                        analysisJobs.add(job)
                    }
                }
                val floodedFeeds = analysisJobs.awaitAll().filterNotNull()
                processFloodedCctvResults(floodedFeeds)
            }
        }

        // 1. 고속도로(type 'ex', cctvType '1' - 동영상) 호출 (통일)
        cctvRepository.fetchCctvXml("ex", "2", "1", minX = minLon, maxX = maxLon, minY = minLat, maxY = maxLat)
            .enqueue(object : Callback<String> {
                override fun onResponse(call: Call<String>, response: Response<String>) {
                    if (response.isSuccessful) {
                        xmlExpressway = response.body().orEmpty()
                        Log.d("CCTV_DEBUG", "고속도로(ex) XML 응답 수신 성공. (type=1)")
                    }
                    processCombinedResults() // 호출 끝났다고 알리기
                }
                override fun onFailure(call: Call<String>, t: Throwable) {
                    Log.e("CCTV_API_ERROR", "고속도로 CCTV fetch error", t)
                    processCombinedResults() // 실패해도 카운트는 올려야 함
                }
            })

        // 2. 국도(type 'its', cctvType '1' - 동영상) 호출 (통일)
        cctvRepository.fetchCctvXml("its", "2", "1", minX = minLon, maxX = maxLon, minY = minLat, maxY = maxLat)
            .enqueue(object : Callback<String> {
                override fun onResponse(call: Call<String>, response: Response<String>) {
                    if (response.isSuccessful) {
                        xmlNational = response.body().orEmpty()
                        Log.d("CCTV_DEBUG", "국도(its) XML 응답 수신 성공. (type=1)")
                        // 국도 XML 응답 전문 로깅은 잠시 제거하여 로그 과부하 방지
                    }
                    processCombinedResults() // 호출 끝났다고 알리기
                }
                override fun onFailure(call: Call<String>, t: Throwable) {
                    Log.e("CCTV_API_ERROR", "국도 CCTV fetch error", t)
                    processCombinedResults() // 실패해도 카운트는 올려야 함
                }
            })


        // 2. 국도(type 'its', cctvType '1' - 동영상) 호출로 변경 테스트
        // 국도 데이터가 적게 오는 것이 cctvType="2" 때문일 수 있으므로 "1"로 테스트해 봅니다.
        cctvRepository.fetchCctvXml("its", "2", "1", minX = minLon, maxX = maxLon, minY = minLat, maxY = maxLat)
            .enqueue(object : Callback<String> {
                override fun onResponse(call: Call<String>, response: Response<String>) {
                    if (response.isSuccessful) {
                        xmlNational = response.body().orEmpty()
                        // 🌟 국도 XML 응답 전문 로깅 (디버깅용)
                        Log.d("CCTV_DEBUG", "국도(its) XML 응답 전문: ${xmlNational.take(2000)}")
                    }
                    processCombinedResults() // 호출 끝났다고 알리기
                }
                override fun onFailure(call: Call<String>, t: Throwable) {
                    Log.e("CCTV_API_ERROR", "국도 CCTV fetch error", t)
                    processCombinedResults() // 실패해도 카운트는 올려야 함
                }
            })
    }

    private suspend fun analyzeCctvStream(feed: CctvFeed): CctvFeed? {
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var reader: ImageReader? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    extractor.setDataSource(this@MapActivity, Uri.parse(feed.streamUrl), null)
                } else {
                    extractor.setDataSource(feed.streamUrl)
                }
                var videoTrack = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        videoTrack = i; format = fmt; break
                    }
                }
                if (videoTrack < 0) throw RuntimeException("비디오 트랙을 찾을 수 없습니다.")
                extractor.selectTrack(videoTrack)
                val mime = format!!.getString(MediaFormat.KEY_MIME)!!
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
                decoder = MediaCodec.createDecoderByType(mime).apply {
                    configure(format, reader.surface, null, 0); start()
                }
                val resultList = mutableListOf<String>()
                val info = MediaCodec.BufferInfo()
                var decodedFrames = 0
                while (decodedFrames < 5) {
                    val inIdx = decoder.dequeueInputBuffer(10000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = decoder.dequeueOutputBuffer(info, 10000L)
                    if (outIdx >= 0) {
                        reader.acquireLatestImage()?.use { img ->
                            val bmp = imageToBitmap(img)
                            val input = TFLiteHelper.preprocessBitmap(bmp)
                            val output = Array(1) { Array(25200) { FloatArray(8) } }
                            synchronized(tflite!!) { tflite!!.run(input, output) }
                            var sumFlood2 = 0f; var sumFlood1 = 0f; var sumNonFlood = 0f; var valid = 0
                            for (p in output[0]) {
                                if (p.size >= 8 && p[4] > 0.05f) {
                                    sumNonFlood += p[5]; sumFlood1 += p[6]; sumFlood2 += p[7]; valid++
                                }
                            }
                            val frameResult = if (valid > 0) {
                                val avgLv2 = sumFlood2/valid; val avgLv1 = sumFlood1/valid; val avgLv0 = sumNonFlood/valid
                                when {
                                    avgLv2 >= 0.82f -> "level2"
                                    avgLv1 >= 0.6f && avgLv1 > avgLv0 && avgLv1 > avgLv2 -> "level1"
                                    else -> "level0"
                                }
                            } else "level0"
                            resultList.add(frameResult)
                            decodedFrames++
                        }
                        decoder.releaseOutputBuffer(outIdx, true)
                    }
                }
                val finalLevel = when { "level2" in resultList -> "level2"; "level1" in resultList -> "level1"; else -> "level0" }
                if (finalLevel != "level0") feed else null
            } catch (e: Exception) {
                Log.e("FloodDebug", "스트림 분석 중 에러! [${feed.name}]", e); null
            } finally {
                decoder?.stop(); decoder?.release(); reader?.close(); extractor.release()
            }
        }
    }

    private fun processFloodedCctvResults(floodedFeeds: List<CctvFeed>) {
        if (floodedFeeds.isEmpty()) {
            Log.i("FloodDebug", "모든 CCTV 분석 완료. 침수 지역 없음.")
            return
        }
        val notificationTitle = "경로상 침수 CCTV 발견!"
        val notificationMessage = "${floodedFeeds.size}개의 CCTV에서 침수가 감지되었습니다. 우회 경로를 탐색합니다."
        showNotification(notificationTitle, notificationMessage)

        val closestFlood = floodedFeeds.minByOrNull { haversine(myLatitude, myLongitude, it.latitude, it.longitude) }
        closestFlood?.let { feed ->
            val floodLatLng = LatLng(feed.latitude, feed.longitude)
            simFloodMarker?.map = null
            simFloodMarker = Marker().apply {
                position = floodLatLng; icon = MarkerIcons.BLACK; iconTintColor = Color.RED
                captionText = "CCTV FLOOD"; map = naverMap
            }
            if (com.navirotation.BuildConfig.USE_KAKAO_NAV) {
                DetourKakaoNavi.launchWithDetour(this, LatLng(myLatitude, myLongitude), LatLng(endLatitude, endLongitude), floodLatLng, 150, 1)
            } else {
                requestMultipleDetours(flood = floodLatLng, maxAlt = 3, applyBest = false)
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val notifyBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flood_warning) // 아이콘은 경고용으로 통일
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Flood", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "침수 알림 채널"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(1, notifyBuilder.build())
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3 // metres
        val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun meterToLat(m: Double) = m / 111_320.0
    private fun meterToLng(lat: Double, m: Double) = m / (111_320.0 * cos(Math.toRadians(lat)))

    private fun intersectsFlood(center: LatLng, radiusM: Double, poly: List<LatLng>): Boolean =
        poly.any { haversine(center.latitude, center.longitude, it.latitude, it.longitude) <= radiusM }

    private fun genBypassCandidates(center: LatLng, radii: IntArray = intArrayOf(150, 220)): List<LatLng> {
        val out = mutableListOf<LatLng>()
        for (r in radii) {
            val dLat = meterToLat(r.toDouble()); val dLng = meterToLng(center.latitude, r.toDouble())
            out += listOf(
                LatLng(center.latitude + dLat, center.longitude), LatLng(center.latitude + dLat, center.longitude + dLng),
                LatLng(center.latitude, center.longitude + dLng), LatLng(center.latitude - dLat, center.longitude + dLng),
                LatLng(center.latitude - dLat, center.longitude), LatLng(center.latitude - dLat, center.longitude - dLng),
                LatLng(center.latitude, center.longitude - dLng), LatLng(center.latitude + dLat, center.longitude - dLng)
            )
        }
        return out
    }

    private fun bearingDeg(a: LatLng, b: LatLng): Double {
        val dLon = Math.toRadians(b.longitude - a.longitude); val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var brng = Math.toDegrees(atan2(y, x)); if (brng < 0) brng += 360.0
        return brng
    }

    private fun angDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun pickBypassVia(center: LatLng, radiusM: Int = 150): LatLng {
        val dLat = meterToLat(radiusM.toDouble()); val dLng = meterToLng(center.latitude, radiusM.toDouble())
        val cands = listOf(
            LatLng(center.latitude + dLat, center.longitude), LatLng(center.latitude, center.longitude + dLng),
            LatLng(center.latitude - dLat, center.longitude), LatLng(center.latitude, center.longitude - dLng)
        )
        val me = LatLng(myLatitude, myLongitude); val goal = LatLng(endLatitude, endLongitude)
        val hdgToGoal = bearingDeg(me, goal)
        data class Scored(val p: LatLng, val score: Double)
        val scored = cands
            .filter { (goal.longitude - me.longitude) * (it.longitude - me.longitude) + (goal.latitude - me.latitude) * (it.latitude - me.latitude) > 0.0 }
            .map { p ->
                val turn = angDiff(hdgToGoal, bearingDeg(me, p))
                val toGoal = haversine(p.latitude, p.longitude, goal.latitude, goal.longitude)
                val awayFromFlood = haversine(p.latitude, p.longitude, center.latitude, center.longitude)
                Scored(p, (if (turn > 120) 1e9 else 0.0) + toGoal - awayFromFlood * 2.0)
            }
            .ifEmpty { cands.map { Scored(it, haversine(it.latitude, it.longitude, goal.latitude, goal.longitude)) } }
        return scored.minBy { it.score }.p
    }

    private fun requestMultipleDetours(flood: LatLng, maxAlt: Int = 3, floodRadiusM: Double = 120.0, applyBest: Boolean = false) {
        routeLine?.map = null; routeLine = null
        detourPolylines.forEach { it.map = null }; detourPolylines.clear(); detourCandidates.clear()
        val startStr = "$myLongitude,$myLatitude"; val goalStr = "$endLongitude,$endLatitude"
        val cands = genBypassCandidates(flood)
        var idx = 0
        fun callNext() {
            if (idx >= cands.size || detourCandidates.size >= maxAlt) {
                showDetourOverlays(applyBest); return
            }
            val via = cands[idx++]; val viaStr = "${via.longitude},${via.latitude}"
            directionService.getRoute(start = startStr, goal = goalStr, waypoints = viaStr)
                .enqueue(object : Callback<DirectionResponse> {
                    override fun onResponse(call: Call<DirectionResponse>, res: Response<DirectionResponse>) {
                        res.body()?.route?.trafast?.firstOrNull()?.let { r ->
                            val coords = r.path.map { p -> LatLng(p[1], p[0]) }
                            if (!intersectsFlood(flood, floodRadiusM, coords)) {
                                val lengthM = r.summary?.distance?.toDouble() ?: coords.zipWithNext().sumOf { (a, b) -> haversine(a.latitude, a.longitude, b.latitude, b.longitude) }
                                detourCandidates += DetourCandidate(via, coords, lengthM)
                            }
                        }
                        callNext()
                    }
                    override fun onFailure(call: Call<DirectionResponse>, t: Throwable) { callNext() }
                })
        }
        callNext()
    }

    private fun showDetourOverlays(applyBest: Boolean = false) {
        detourPolylines.forEach { it.map = null }; detourPolylines.clear()
        if (detourCandidates.isEmpty()) {
            Toast.makeText(this, "우회 대안을 찾지 못했어요.", Toast.LENGTH_SHORT).show()
            return
        }
        detourCandidates.sortBy { it.lengthM }
        if (applyBest) {
            val best = detourCandidates.first()
            detourPolylines += PolylineOverlay().apply {
                coords = best.coords; width = 12; color = 0xFFFF9800.toInt(); map = naverMap
            }
            detourVia = best.via
            Toast.makeText(this, "우회경로로 변경됨 (약 ${"%.1f".format(best.lengthM / 1000)} km)", Toast.LENGTH_SHORT).show()
            return
        }
        detourCandidates.take(detourColors.size).forEachIndexed { i, cand ->
            detourPolylines += PolylineOverlay().apply {
                coords = cand.coords; width = if (i == 0) 12 else 10; color = detourColors[i]; map = naverMap
                setOnClickListener {
                    detourPolylines.forEach { it.map = null }; detourPolylines.clear()
                    requestNaverDetour(cand.via)
                    Toast.makeText(this@MapActivity, "우회경로로 변경됨 (약 ${"%.1f".format(cand.lengthM / 1000)} km)", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }

    private fun requestNaverDetour(via: LatLng) {
        val startStr = "$myLongitude,$myLatitude"; val goalStr = "$endLongitude,$endLatitude"; val viaStr = "${via.longitude},${via.latitude}"
        routeLine?.map = null; routeLine = null
        detourPolylines.forEach { it.map = null }; detourPolylines.clear()
        directionService.getRoute(start = startStr, goal = goalStr, waypoints = viaStr)
            .enqueue(object : Callback<DirectionResponse> {
                override fun onResponse(call: Call<DirectionResponse>, res: Response<DirectionResponse>) {
                    res.body()?.route?.trafast?.firstOrNull()?.let { route ->
                        val coords = route.path.map { p -> LatLng(p[1], p[0]) }
                        routeLine = PolylineOverlay().apply {
                            this.coords = coords; this.width = 12; this.color = 0xFFFF9800.toInt(); this.map = naverMap
                        }
                        detourVia = via
                        Toast.makeText(this@MapActivity, "우회 경로 적용", Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(this@MapActivity, "우회 경로를 찾지 못했어요.", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(call: Call<DirectionResponse>, t: Throwable) { Toast.makeText(this@MapActivity, "우회 요청 실패: ${t.localizedMessage}", Toast.LENGTH_SHORT).show() }
            })
    }

    // 1. XML에서 <data> ... </data> 태그 안의 내용물만 꺼내는 헬퍼 함수
    private fun parseCctvXmlDataTag(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val dataList = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser  = factory.newPullParser().apply { setInput(StringReader(xml)) }
            var event = parser.eventType
            var isInsideDataTag = false
            var currentTagDepth = 0
            val currentTagBuilder = StringBuilder()

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name ?: ""
                        if (tagName.lowercase() == "data") {
                            isInsideDataTag = true
                            currentTagBuilder.clear()
                            currentTagBuilder.append("<${parser.name}>")
                            currentTagDepth = 1 // data 태그 시작
                        } else if (isInsideDataTag) {
                            currentTagDepth++
                            currentTagBuilder.append("<${parser.name}")
                            for (i in 0 until parser.attributeCount) {
                                currentTagBuilder.append(" ${parser.getAttributeName(i)}=\"${parser.getAttributeValue(i)}\"")
                            }
                            currentTagBuilder.append(">")
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (isInsideDataTag) {
                            currentTagBuilder.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name ?: ""
                        if (isInsideDataTag) {
                            currentTagBuilder.append("</${parser.name}>")
                            currentTagDepth--
                            if (tagName.lowercase() == "data" && currentTagDepth == 0) {
                                dataList.add(currentTagBuilder.toString())
                                isInsideDataTag = false
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e("CCTV_PARSE_ERROR", "XML <data> 태그 추출 중 오류", e)
        }
        return dataList
    }
    // 2. parseCctvXml 함수도 <data> 태그만 파싱하도록 살짝 수정
    private fun parseCctvXml(xml: String): List<CctvFeed> {
        val list = mutableListOf<CctvFeed>()
        if (xml.isBlank()) return list // XML이 비어있으면 바로 반환
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser  = factory.newPullParser().apply { setInput(StringReader(xml)) }
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
                        if (lat != 0.0 && lon != 0.0) { // 좌표가 유효한 것만 추가
                            list += CctvFeed(id, name, lat, lon, url)
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e("CCTV_PARSE_ERROR", "CCTV XML 파싱 중 심각한 오류 발생!", e)
        }
        return list
    }

    fun onMarkerClick(marker: Marker): Boolean {
        val feed = marker.tag as? CctvFeed ?: return true
        if (feed.streamUrl.isBlank()) {
            Toast.makeText(this, "CCTV URL 없음", Toast.LENGTH_SHORT).show()
            return true
        }
        startActivity(Intent(this, CctvPlayerActivity::class.java).putExtra("streamUrl", feed.streamUrl))
        return true
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val w = image.width; val h = image.height
        val yBuffer = image.planes[0].buffer; val uBuffer = image.planes[1].buffer; val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize); uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun setMyLocationMarker() {
        myLocationMarker.apply {
            map = null; width = 70; height = 100
            position = LatLng(myLatitude, myLongitude)
            map = naverMap
        }
    }

    private fun moveCamera(lat: Double, lng: Double) {
        naverMap.cameraPosition = CameraPosition(LatLng(lat, lng), 13.0)
    }

    override fun onDestroy() {super.onDestroy();binding.mapView.onDestroy()}
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onSaveInstanceState(s: Bundle) { super.onSaveInstanceState(s); binding.mapView.onSaveInstanceState(s) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
}