package com.rongyou.xinling

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 心聆 —— 语音情感实时分析助手。
 *
 * 流程：录音（PCM 16 kHz / 单声道 / 16 bit）
 *   -> 百度短语音识别（语音转文字）
 *   -> 百度情感倾向分析（文字转情绪）
 *   -> 进度条可视化情绪积极程度。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_RECORDING_MS = 60_000L
        const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        const val ASR_URL = "https://vop.baidu.com/server_api"
        const val NLP_URL = "https://aip.baidubce.com/rpc/2.0/nlp/v1/sentiment_classify"
    }

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var btnRecord: Button
    private lateinit var tvResult: TextView
    private lateinit var emotionProgressBar: ProgressBar
    private lateinit var mainLayout: LinearLayout

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var maxDurationJob: Job? = null
    private var recordedPcmBytes: ByteArray? = null

    @Volatile
    private var isRecording = false

    private var backgroundAnimator: ValueAnimator? = null
    private var buttonPulseAnimator: ValueAnimator? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                tvResult.text = getString(R.string.permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvResult = findViewById(R.id.tvResult)
        emotionProgressBar = findViewById(R.id.emotionProgressBar)
        mainLayout = findViewById(R.id.mainLayout)

        btnRecord.setOnClickListener { onRecordButtonClick() }
    }

    private fun onRecordButtonClick() {
        if (isRecording) {
            stopRecordingAndAnalyze()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startRecording()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ==================== 录音 ====================

    // 调用方（按钮点击 / 权限回调）已确保录音权限已授予
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return

        if (!hasApiCredentials()) {
            tvResult.setText(R.string.error_missing_keys)
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            tvResult.setText(R.string.error_audio_params)
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                minBufferSize
            )
        } catch (e: Exception) {
            tvResult.text = getString(R.string.error_record_init, e.message.orEmpty())
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            tvResult.setText(R.string.error_record_init_unknown)
            return
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            tvResult.text = getString(R.string.error_record_init, e.message.orEmpty())
            return
        }

        audioRecord = recorder
        recordedPcmBytes = null
        isRecording = true

        btnRecord.setText(R.string.stop_recording)
        btnRecord.isEnabled = true
        tvResult.setText(R.string.recording_hint)
        emotionProgressBar.visibility = View.INVISIBLE
        startBackgroundBreathAnimation()
        startButtonPulseAnimation()

        recordingJob = scope.launch(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(minBufferSize)
            try {
                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                    } else if (read < 0) {
                        break
                    }
                }
            } finally {
                recordedPcmBytes = output.toByteArray()
            }
        }

        // 百度短语音识别单次建议不超过 60 秒，超时自动停止
        maxDurationJob = scope.launch {
            delay(MAX_RECORDING_MS)
            if (isRecording) stopRecordingAndAnalyze()
        }
    }

    private fun stopRecordingAndAnalyze() {
        if (!isRecording) return
        isRecording = false
        maxDurationJob?.cancel()
        maxDurationJob = null

        btnRecord.isEnabled = false
        btnRecord.setText(R.string.start_recording)
        tvResult.setText(R.string.recognizing)
        emotionProgressBar.visibility = View.INVISIBLE
        stopBackgroundBreathAnimation()
        stopButtonPulseAnimation()

        scope.launch {
            val pcm = withContext(Dispatchers.IO) {
                // 等待录音协程退出，确保 PCM 数据完整写入
                recordingJob?.join()
                releaseRecorder()
                recordedPcmBytes
            }

            try {
                analyze(pcm)
            } finally {
                btnRecord.isEnabled = true
            }
        }
    }

    private fun releaseRecorder() {
        audioRecord?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        audioRecord = null
        recordingJob = null
    }

    // ==================== 识别与情感分析 ====================

    private suspend fun analyze(pcm: ByteArray?) {
        if (pcm == null || pcm.isEmpty()) {
            tvResult.setText(R.string.error_no_audio)
            return
        }

        val result = try {
            withContext(Dispatchers.IO) { requestAnalysis(pcm) }
        } catch (e: Exception) {
            tvResult.text = getString(
                R.string.error_generic,
                e.message ?: e.javaClass.simpleName
            )
            return
        }

        renderResult(result)
    }

    private fun requestAnalysis(pcm: ByteArray): AnalyzeResult {
        val speechToken = getAccessToken(
            BuildConfig.BAIDU_SPEECH_API_KEY,
            BuildConfig.BAIDU_SPEECH_SECRET_KEY
        )
        val text = speechToText(speechToken, pcm)
        if (text.isBlank()) {
            return AnalyzeResult(text = "", sentiment = null)
        }

        val nlpToken = getAccessToken(
            BuildConfig.BAIDU_NLP_API_KEY,
            BuildConfig.BAIDU_NLP_SECRET_KEY
        )
        return AnalyzeResult(text = text, sentiment = sentimentClassify(nlpToken, text))
    }

    private fun renderResult(result: AnalyzeResult) {
        val sentiment = result.sentiment

        if (result.text.isBlank()) {
            tvResult.setText(R.string.empty_recognition)
            emotionProgressBar.visibility = View.INVISIBLE
            return
        }

        if (sentiment == null) {
            tvResult.text = getString(R.string.result_text_only, result.text)
            emotionProgressBar.visibility = View.INVISIBLE
            return
        }

        tvResult.text = getString(
            R.string.result_template,
            result.text,
            sentiment.label,
            sentiment.confidence,
            sentiment.positiveProb,
            sentiment.negativeProb
        )

        emotionProgressBar.progress = (sentiment.positiveProb * 100).toInt().coerceIn(0, 100)
        emotionProgressBar.progressTintList =
            ColorStateList.valueOf(colorForProbability(sentiment.positiveProb))
        emotionProgressBar.visibility = View.VISIBLE
    }

    private fun hasApiCredentials(): Boolean =
        BuildConfig.BAIDU_SPEECH_API_KEY.isNotBlank() &&
            BuildConfig.BAIDU_SPEECH_SECRET_KEY.isNotBlank() &&
            BuildConfig.BAIDU_NLP_API_KEY.isNotBlank() &&
            BuildConfig.BAIDU_NLP_SECRET_KEY.isNotBlank()

    private fun getAccessToken(apiKey: String, secretKey: String): String {
        val url = "$TOKEN_URL?grant_type=client_credentials" +
            "&client_id=$apiKey&client_secret=$secretKey"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $body")
            }
            val json = JSONObject(body)
            if (!json.has("access_token")) {
                error(json.optString("error_description", body))
            }
            return json.getString("access_token")
        }
    }

    private fun speechToText(token: String, pcm: ByteArray): String {
        val payload = JSONObject().apply {
            put("format", "pcm")
            put("rate", SAMPLE_RATE)
            put("channel", 1)
            put("cuid", Build.MODEL.replace(" ", "_"))
            put("token", token)
            put("speech", Base64.encodeToString(pcm, Base64.NO_WRAP))
            put("len", pcm.size)
        }

        val request = Request.Builder()
            .url(ASR_URL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val json = JSONObject(responseText)
            if (json.has("result")) {
                return json.getJSONArray("result").optString(0)
            }
            error("err_no=${json.optInt("err_no", -1)}, err_msg=${json.optString("err_msg", responseText)}")
        }
    }

    private fun sentimentClassify(token: String, text: String): Sentiment {
        val payload = JSONObject().apply { put("text", text) }
        val request = Request.Builder()
            .url("$NLP_URL?access_token=$token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val json = JSONObject(responseText)
            val items = json.optJSONArray("items")
            if (items == null || items.length() == 0) {
                error(json.optString("error_msg", responseText))
            }

            val item = items.getJSONObject(0)
            val labelRes = when (item.optInt("sentiment", -1)) {
                0 -> R.string.sentiment_negative
                1 -> R.string.sentiment_neutral
                2 -> R.string.sentiment_positive
                else -> R.string.sentiment_unknown
            }

            return Sentiment(
                label = getString(labelRes),
                confidence = item.optDouble("confidence", 0.0),
                positiveProb = item.optDouble("positive_prob", 0.5),
                negativeProb = item.optDouble("negative_prob", 0.0)
            )
        }
    }

    /** 根据积极概率（0.0~1.0）返回由红到绿的渐变色。 */
    private fun colorForProbability(probability: Double): Int {
        val safeProbability = probability.coerceIn(0.0, 1.0).toFloat()
        val red: Float
        val green: Float
        if (safeProbability < 0.5f) {
            red = 1f
            green = safeProbability * 2f
        } else {
            red = (1f - safeProbability) * 2f
            green = 1f
        }
        return Color.rgb((red * 255).toInt(), (green * 255).toInt(), 0)
    }

    // ==================== 动画 ====================

    private fun startBackgroundBreathAnimation() {
        backgroundAnimator?.cancel()
        backgroundAnimator = ValueAnimator.ofArgb(
            Color.WHITE,
            "#F3E5F5".toColorInt(),
            Color.WHITE
        ).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { mainLayout.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun stopBackgroundBreathAnimation() {
        backgroundAnimator?.cancel()
        backgroundAnimator = null
        mainLayout.animate()
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { mainLayout.setBackgroundColor(Color.WHITE) }
            .start()
    }

    private fun startButtonPulseAnimation() {
        buttonPulseAnimator?.cancel()
        buttonPulseAnimator = ValueAnimator.ofFloat(0.98f, 1.02f, 0.98f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                btnRecord.scaleX = scale
                btnRecord.scaleY = scale
            }
            start()
        }
    }

    private fun stopButtonPulseAnimation() {
        buttonPulseAnimator?.cancel()
        buttonPulseAnimator = null
        btnRecord.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // ==================== 生命周期 ====================

    override fun onStop() {
        super.onStop()
        if (isRecording) {
            // 退到后台时停止录音并丢弃本次数据，避免占用麦克风
            isRecording = false
            maxDurationJob?.cancel()
            maxDurationJob = null
            btnRecord.setText(R.string.start_recording)
            btnRecord.isEnabled = true
            stopBackgroundBreathAnimation()
            stopButtonPulseAnimation()
            scope.launch {
                withContext(Dispatchers.IO) {
                    recordingJob?.join()
                    releaseRecorder()
                }
                recordedPcmBytes = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundAnimator?.cancel()
        buttonPulseAnimator?.cancel()
        scope.cancel()
        audioRecord?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        audioRecord = null
    }

    private data class Sentiment(
        val label: String,
        val confidence: Double,
        val positiveProb: Double,
        val negativeProb: Double
    )

    private data class AnalyzeResult(
        val text: String,
        val sentiment: Sentiment?
    )
}
