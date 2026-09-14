// File: app/src/main/java/com/navirotation/worker/CctvAnalysisWorker.kt

package com.navirotation.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.navirotation.CctvFeed
import com.navirotation.R
import com.navirotation.TFLiteHelper
import com.navirotation.repository.CctvRepository
// ▼▼▼ [중요] 서비스 클래스 import 확인 (패키지명이 다르면 수정 필요) ▼▼▼
import com.navirotation.CctvService
import com.navirotation.service.DirectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.xmlpull.v1.XmlPullParserFactory
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlin.math.*

// ▼▼▼ Worker 내부에서 사용할 API 클라이언트 (의존성 주입 오류 방지용) ▼▼▼
object ApiClient {
    private val naverRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://naveropenapi.apigw.ntruss.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val cctvRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://openapi.its.go.kr/")
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }

    val directionService: DirectionService by lazy {
        naverRetrofit.create(DirectionService::class.java)
    }

    val cctvService: CctvService by lazy {
        cctvRetrofit.create(CctvService::class.java)
    }
}

class CctvAnalysisWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private var tflite: Interpreter? = null

    companion object {
        private const val WORKER_CHANNEL_ID = "flood_worker_channel"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d("CctvWorker", "백그라운드 CCTV 분석 작업을 시작합니다.")

        val startLon = inputData.getDouble("START_LON", 0.0)
        val startLat = inputData.getDouble("START_LAT", 0.0)
        val endLon = inputData.getDouble("END_LON", 0.0)
        val endLat = inputData.getDouble("END_LAT", 0.0)

        if (startLat == 0.0 || endLat == 0.0) {
            return@withContext Result.failure()
        }

        try {
            // TFLite 모델 로드
            val modelBuffer = TFLiteHelper.loadModelFile(appContext.assets, "updatemodel.tflite") // 또는 best_float16.tflite
            tflite = Interpreter(modelBuffer)

            // 1. 경로 재탐색
            val directionService = ApiClient.directionService
            val routeResponse = directionService.getRoute(
                start = "$startLon,$startLat",
                goal = "$endLon,$endLat"
            ).execute()

            val wgsPoints = routeResponse.body()?.route?.trafast?.firstOrNull()?.path
                ?.map { com.naver.maps.geometry.LatLng(it[1], it[0]) } ?: emptyList()

            if (wgsPoints.isEmpty()) return@withContext Result.success()

            // 2. 경로 주변 CCTV 가져오기
            val cctvRepository = CctvRepository(ApiClient.cctvService)
            val minLat = minOf(startLat, endLat).toString()
            val maxLat = maxOf(startLat, endLat).toString()
            val minLon = minOf(startLon, endLon).toString()
            val maxLon = maxOf(startLon, endLon).toString()

            // 고속도로(ex)와 국도(its) 모두 호출하여 합침. cctvType("1" 동영상) 명시.
            // MapActivity에서 사용된 category: "ex" (고속도로), "its" (국도)
            // MapActivity에서 사용된 pageNo: "2"
            // cctvType은 동영상("1")으로 통일하여 요청합니다.

            val xmlEx = cctvRepository.fetchCctvXml(
                category = "ex",
                pageNo = "2",
                cctvType = "1", // ⭐ 추가 및 통일
                minX = minLon,
                maxX = maxLon,
                minY = minLat,
                maxY = maxLat // ⭐ maxY 인자 올바르게 전달
            ).execute().body().orEmpty()

            val xmlNa = cctvRepository.fetchCctvXml(
                category = "its",
                pageNo = "2",
                cctvType = "1", // ⭐ 추가 및 통일
                minX = minLon,
                maxX = maxLon,
                minY = minLat,
                maxY = maxLat // ⭐ maxY 인자 올바르게 전달
            ).execute().body().orEmpty()

            val allCctvs = parseCctvXml(xmlEx) + parseCctvXml(xmlNa)

            // 경로 주변 80m 이내 필터링
            val cctvList = allCctvs.filter { feed ->
                wgsPoints.any { haversine(feed.latitude, feed.longitude, it.latitude, it.longitude) <= 80.0 }
            }

            // 3. CCTV 분석 (병렬 처리)
            val analysisJobs = cctvList.map { feed ->
                async { analyzeCctvStream(feed) }
            }
            val floodedFeeds = analysisJobs.awaitAll().filterNotNull()

            // 4. 침수 발견 시 알림
            if (floodedFeeds.isNotEmpty()) {
                showNotification(
                    "⚠️ 경로상 침수 위험 감지!",
                    "${floodedFeeds.size}곳의 CCTV에서 침수가 의심됩니다. 앱을 열어 우회 경로를 확인하세요."
                )
            } else {
                Log.d("CctvWorker", "백그라운드 분석 완료. 특이사항 없음.")
            }

            Result.success()

        } catch (e: Exception) {
            Log.e("CctvWorker", "백그라운드 작업 중 오류 발생", e)
            Result.failure()
        } finally {
            // TFLite Interpreter 정리
            tflite?.close()
            tflite = null
        }
    }

    // --- 내부 Helper 함수들 ---

    private suspend fun analyzeCctvStream(feed: CctvFeed): CctvFeed? {
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var reader: ImageReader? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    extractor.setDataSource(appContext, Uri.parse(feed.streamUrl), null)
                } else {
                    extractor.setDataSource(feed.streamUrl)
                }
                var videoTrack = -1; var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        videoTrack = i; format = fmt; break
                    }
                }
                if (videoTrack < 0) return@withContext null

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

                // 최대 5프레임만 분석
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

                            var sumF2 = 0f; var sumF1 = 0f; var sumNF = 0f; var valid = 0
                            for (p in output[0]) {
                                if (p.size >= 8 && p[4] > 0.05f) {
                                    sumNF += p[5]; sumF1 += p[6]; sumF2 += p[7]; valid++
                                }
                            }

                            val fResult = if (valid > 0) {
                                val avg2 = sumF2/valid; val avg1 = sumF1/valid; val avg0 = sumNF/valid
                                when {
                                    avg2 >= 0.82f -> "level2"
                                    avg1 >= 0.6f && avg1 > avg0 && avg1 > avg2 -> "level1"
                                    else -> "level0"
                                }
                            } else "level0"

                            resultList.add(fResult)
                            decodedFrames++
                        }
                        decoder.releaseOutputBuffer(outIdx, true)
                    }
                }

                val finalLevel = when {
                    "level2" in resultList -> "level2"
                    "level1" in resultList -> "level1"
                    else -> "level0"
                }

                if (finalLevel != "level0") feed else null

            } catch (e: Exception) {
                Log.e("CctvWorker", "스트림 분석 에러 [${feed.name}]", e)
                null
            } finally {
                decoder?.stop(); decoder?.release(); reader?.close(); extractor.release()
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val notifyBuilder = NotificationCompat.Builder(appContext, WORKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flood_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(WORKER_CHANNEL_ID, "Background Flood Check", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "주기적인 침수 확인 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(2, notifyBuilder.build())
    }

    private fun parseCctvXml(xml: String): List<CctvFeed> {
        val list = mutableListOf<CctvFeed>()
        if (xml.isBlank()) return list
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
            var event = parser.eventType
            var id = ""; var name = ""; var lat = 0.0; var lon = 0.0; var url = ""
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "data" -> { id = ""; name = ""; lat = 0.0; lon = 0.0; url = "" }
                        "roadsectionid" -> id = parser.nextText()
                        "cctvname" -> name = parser.nextText()
                        "coordy" -> lat = parser.nextText().toDoubleOrNull() ?: 0.0
                        "coordx" -> lon = parser.nextText().toDoubleOrNull() ?: 0.0
                        "cctvurl" -> url = parser.nextText()
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name.lowercase() == "data") {
                        if (lat != 0.0 && lon != 0.0) list += CctvFeed(id, name, lat, lon, url)
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) { Log.e("CctvWorker", "XML 파싱 오류", e) }
        return list
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val w = image.width; val h = image.height
        val yBuffer = image.planes[0].buffer; val uBuffer = image.planes[1].buffer; val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize); uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, w, h), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3
        val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1); val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}