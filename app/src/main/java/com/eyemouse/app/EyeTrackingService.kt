package com.eyemouse.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt

/** 전면 카메라로 얼굴/홍채를 추적해 GazeEngine 에 특징값을 넘기는 포그라운드 서비스 */
class EyeTrackingService : LifecycleService() {

    companion object {
        @Volatile
        var running = false
        private const val TAG = "EyeMouse"
        private const val CHANNEL_ID = "eye_tracking"
        private const val NOTIFICATION_ID = 1
        private const val MODEL_ASSET = "face_landmarker.task"
    }

    private var landmarker: FaceLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private lateinit var executor: ExecutorService
    private var lastTs = 0L

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        try {
            startAsForeground()
        } catch (e: Throwable) {
            Log.e(TAG, "포그라운드 시작 실패", e)
            Toast.makeText(this, "카메라 권한을 확인해 주세요.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        try {
            setupLandmarker()
        } catch (e: Throwable) {
            Log.e(TAG, "모델 초기화 실패", e)
            Toast.makeText(
                this,
                "얼굴 인식 모델(face_landmarker.task)을 불러오지 못했어요.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }
        startCamera()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind 실패", e)
        }
        try {
            landmarker?.close()
        } catch (e: Exception) {
            Log.w(TAG, "landmarker close 실패", e)
        }
        landmarker = null
        if (::executor.isInitialized) executor.shutdown()
        GazeEngine.onStopped()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "안구마우스 작동 상태", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("안구마우스 작동 중")
            .setContentText("전면 카메라로 시선을 추적하고 있어요 (영상은 저장되지 않아요)")
            .setSmallIcon(R.drawable.ic_eye)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setResultListener { result, input -> onResult(result, input.width, input.height) }
            .setErrorListener { e -> Log.e(TAG, "MediaPipe 오류", e) }
            .build()
        landmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 시작 실패", e)
                Toast.makeText(this, "전면 카메라를 시작하지 못했어요.", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val src = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val bmp: Bitmap = if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            } else {
                src
            }
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            lm.detectAsync(BitmapImageBuilder(bmp).build(), ts)
        } catch (e: Exception) {
            Log.w(TAG, "프레임 처리 실패", e)
        } finally {
            proxy.close()
        }
    }

    private fun onResult(result: FaceLandmarkerResult, w: Int, h: Int) {
        try {
            val faces = result.faceLandmarks()
            if (faces.isEmpty() || faces[0].size < 478) {
                GazeEngine.onFrame(null, 0f)
                return
            }
            val lm = faces[0]

            val bs = HashMap<String, Float>()
            val opt = result.faceBlendshapes()
            if (opt.isPresent) {
                val list = opt.get()
                if (list.isNotEmpty()) {
                    for (c in list[0]) bs[c.categoryName()] = c.score()
                }
            }
            fun g(name: String): Float = bs[name] ?: 0f

            val fw = w.toFloat()
            val fh = h.toFloat()

            // 눈 한쪽씩: [가로비, 세로비]
            val r = FloatArray(2)
            val l = FloatArray(2)
            eyeRatios(lm, 33, 133, 468, fw, fh, r)
            eyeRatios(lm, 362, 263, 473, fw, fh, l)

            // 머리 방향/위치
            val nose = lm[1]
            val cheekL = lm[234]
            val cheekR = lm[454]
            val top = lm[10]
            val chin = lm[152]
            val faceW = hypot((cheekR.x() - cheekL.x()) * fw, (cheekR.y() - cheekL.y()) * fh)
                .coerceAtLeast(1f)
            val faceH = hypot((chin.x() - top.x()) * fw, (chin.y() - top.y()) * fh)
                .coerceAtLeast(1f)
            val yaw = (nose.x() - (cheekL.x() + cheekR.x()) / 2f) * fw / faceW
            val pitch = (nose.y() - (top.y() + chin.y()) / 2f) * fh / faceH

            val bsH = (g("eyeLookOutRight") + g("eyeLookInLeft")) -
                (g("eyeLookOutLeft") + g("eyeLookInRight"))
            val bsV = (g("eyeLookDownLeft") + g("eyeLookDownRight")) -
                (g("eyeLookUpLeft") + g("eyeLookUpRight"))

            val f = FloatArray(GazeEngine.NF)
            f[0] = (r[0] + l[0]) / 2f
            f[1] = (r[1] + l[1]) / 2f
            f[2] = bsH
            f[3] = bsV
            f[4] = yaw
            f[5] = pitch
            f[6] = nose.x()
            f[7] = nose.y()

            val blink = minOf(g("eyeBlinkLeft"), g("eyeBlinkRight"))
            GazeEngine.onFrame(f, blink)
        } catch (e: Exception) {
            Log.w(TAG, "결과 처리 실패", e)
        }
    }

    /**
     * 홍채가 눈 양끝 사이 어디에 있는지 계산한다.
     * out[0]: 눈 안쪽-바깥쪽 방향 비율(0~1, 이미지 왼쪽 끝 기준)
     * out[1]: 눈꼬리 중점 기준 세로 편차(눈 너비로 정규화, 아래쪽이 +)
     */
    private fun eyeRatios(
        lm: List<NormalizedLandmark>,
        p1: Int,
        p2: Int,
        iris: Int,
        w: Float,
        h: Float,
        out: FloatArray
    ) {
        var ax = lm[p1].x() * w
        var ay = lm[p1].y() * h
        var bx = lm[p2].x() * w
        var by = lm[p2].y() * h
        if (ax > bx) {
            val tx = ax
            ax = bx
            bx = tx
            val ty = ay
            ay = by
            by = ty
        }
        val ex = bx - ax
        val ey = by - ay
        val len = sqrt(ex * ex + ey * ey).coerceAtLeast(1e-3f)
        val px = lm[iris].x() * w
        val py = lm[iris].y() * h
        out[0] = ((px - ax) * ex + (py - ay) * ey) / (len * len)
        val nx = -ey / len
        val ny = ex / len
        val mx = (ax + bx) / 2f
        val my = (ay + by) / 2f
        out[1] = ((px - mx) * nx + (py - my) * ny) / len
    }
}
