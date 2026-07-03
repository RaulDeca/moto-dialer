package com.motocallrecorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class RecordService : Service() {
    @Volatile private var isRecording = false
    @Volatile private var isStopping = false
    private var outputFile: File? = null

    private var audioRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var mediaRecorder: MediaRecorder? = null

    // Video recording
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioInputThread: Thread? = null
    private var muxerThread: Thread? = null

    companion object {
        private const val TAG = "RecordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_recording_channel"
        const val ACTION_START_RECORDING = "com.motocallrecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.motocallrecorder.STOP_RECORDING"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_IS_VOIP = "is_voip"
        private const val SAMPLE_RATE = 16000
        private const val VIDEO_BITRATE = 2_000_000
        private const val VIDEO_FRAME_RATE = 24
        private const val BUFFER_SIZE = 4096
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, true)
                val isVoip = intent.getBooleanExtra(EXTRA_IS_VOIP, false)
                Handler(Looper.getMainLooper()).postDelayed({
                    startRecording(contactName, phoneNumber, isIncoming, isVoip)
                }, 800)
            }
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(contactName: String, phoneNumber: String, isIncoming: Boolean, isVoip: Boolean) {
        if (isRecording) return
        try {
            val displayName = if (contactName.isNotBlank()) contactName
                else if (phoneNumber.isNotBlank()) phoneNumber
                else "Unknown"
            val recDir = EnvironmentUtils.getContactDir(this, displayName, phoneNumber)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val direction = if (isIncoming) "IN" else "OUT"
            val baseName = "REC_${direction}_$timestamp"

            showNotification("Recording $displayName")

            if (isVoip) {
                if (tryVideoRecording(recDir, baseName)) return
                Log.w(TAG, "Video recording failed, trying audio-only")
            }
            if (tryMediaProjection(recDir, baseName)) return
            tryMediaRecorder(recDir, baseName)
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed: ${e.message}", e)
            cleanup()
            stopSelf()
        }
    }

    private fun createMicRecord(bufSize: Int): AudioRecord? {
        return try {
            val mic = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .build()
            if (mic.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "Mic record not initialized")
                mic.release(); null
            } else {
                mic
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create mic record: ${e.message}")
            null
        }
    }

    private fun mixPcm16(buf1: ByteArray, buf2: ByteArray, out: ByteArray, len: Int): Int {
        val n = minOf(len, buf1.size, buf2.size, out.size) / 2 * 2
        var i = 0
        while (i < n) {
            val s1 = (buf1[i + 1].toInt() shl 8) or (buf1[i].toInt() and 0xFF)
            val s2 = (buf2[i + 1].toInt() shl 8) or (buf2[i].toInt() and 0xFF)
            val mixed = (s1 + s2).coerceIn(-32768, 32767)
            out[i] = (mixed and 0xFF).toByte()
            out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            i += 2
        }
        return n
    }

    private fun tryVideoRecording(recDir: File, baseName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val mpData = ProjectionGlobals.data ?: return false
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(ProjectionGlobals.resultCode, mpData) ?: return false
            mediaProjection = projection

            outputFile = File(recDir, "$baseName.mp4")

            val metrics = resources.displayMetrics
            val width = minOf(metrics.widthPixels, 1280)
            val height = minOf(metrics.heightPixels, 720)

            val videoFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            val vCodec: MediaCodec = MediaCodec.createEncoderByType("video/avc")
            vCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface: Surface = vCodec.createInputSurface()
            vCodec.start()
            videoCodec = vCodec

            val aFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            val aCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()
            audioCodec = aCodec

            val vd = projection.createVirtualDisplay(
                "RecorderVideo", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
            virtualDisplay = vd

            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val bufSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT),
                BUFFER_SIZE * 4
            )
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                .addMatchingUsage(5) // USAGE_NOTIFICATION_TELEPHONY_RINGTONE
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                configBuilder.addMatchingUsage(16) // USAGE_CALL_ASSISTANT
            }
            val config = configBuilder.build()
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) return false
            record.startRecording()
            audioRecord = record

            val micRec = createMicRecord(bufSize)
            micRec?.startRecording()
            micRecord = micRec

            outputFile!!.parentFile?.mkdirs()
            val mux = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrack = mux.addTrack(videoFormat)
            val audioTrack = mux.addTrack(aFormat)
            mux.start()
            muxer = mux

            isRecording = true
            Log.d(TAG, "OK: Video recording started")

            startMuxerLoop(vCodec, aCodec, mux, videoTrack, audioTrack, record, bufSize, micRec)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Video recording failed: ${e.message}")
            cleanupVideoRecording()
        }
        return false
    }

    private fun startMuxerLoop(
        vCodec: MediaCodec, aCodec: MediaCodec, mux: MediaMuxer,
        videoTrack: Int, audioTrack: Int, record: AudioRecord, bufSize: Int,
        micRecord: AudioRecord? = null
    ) {
        muxerThread = Thread {
            val audioInfo = MediaCodec.BufferInfo()
            val videoInfo = MediaCodec.BufferInfo()
            var audioDone = false
            var videoDone = false
            var audioEosSent = false
            var videoEosSent = false
            var pts: Long = 0
            val pcmBuf = ByteArray(bufSize)
            val micBuf = ByteArray(bufSize)
            val mixBuf = ByteArray(bufSize)

            while (isRecording && !(audioDone && videoDone)) {
                if (!audioEosSent) {
                    val audioInIdx = aCodec.dequeueInputBuffer(10000)
                    if (audioInIdx >= 0) {
                        val n = record.read(pcmBuf, 0, bufSize)
                        if (n > 0) {
                            val feedBuf: ByteArray
                            val feedLen: Int
                            if (micRecord != null && micRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                val m = micRecord.read(micBuf, 0, minOf(bufSize, n))
                                if (m > 0) {
                                    feedLen = mixPcm16(pcmBuf, micBuf, mixBuf, minOf(n, m))
                                    feedBuf = mixBuf
                                } else {
                                    feedBuf = pcmBuf; feedLen = n
                                }
                            } else {
                                feedBuf = pcmBuf; feedLen = n
                            }
                            val buf = aCodec.getInputBuffer(audioInIdx) ?: continue
                            buf.clear()
                            buf.put(feedBuf, 0, feedLen)
                            aCodec.queueInputBuffer(audioInIdx, 0, feedLen, pts, 0)
                            pts += (feedLen * 1_000_000L) / (SAMPLE_RATE * 2L)
                        }
                    }
                }

                val audioOutIdx = aCodec.dequeueOutputBuffer(audioInfo, 10000)
                if (audioOutIdx >= 0) {
                    val buf = aCodec.getOutputBuffer(audioOutIdx) ?: continue
                    if (audioInfo.size > 0) {
                        try { mux.writeSampleData(audioTrack, buf, audioInfo) } catch (_: Exception) {}
                    }
                    aCodec.releaseOutputBuffer(audioOutIdx, false)
                    if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioDone = true
                }

                val videoOutIdx = vCodec.dequeueOutputBuffer(videoInfo, 10000)
                if (videoOutIdx >= 0) {
                    val buf = vCodec.getOutputBuffer(videoOutIdx) ?: continue
                    if (videoInfo.size > 0) {
                        try { mux.writeSampleData(videoTrack, buf, videoInfo) } catch (_: Exception) {}
                    }
                    vCodec.releaseOutputBuffer(videoOutIdx, false)
                    if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoDone = true
                }

                if (!isRecording && !audioEosSent) {
                    try {
                        val idx = aCodec.dequeueInputBuffer(5000)
                        if (idx >= 0) { aCodec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); audioEosSent = true }
                    } catch (_: Exception) { audioEosSent = true }
                }
                if (!isRecording && !videoEosSent) {
                    try { vCodec.signalEndOfInputStream(); videoEosSent = true } catch (_: Exception) { videoEosSent = true }
                }
            }
            Log.d(TAG, "Muxer loop done, cleaning up video")
            cleanupVideoRecording()
        }.also { it.start() }
    }

    private fun tryMediaProjection(recDir: File, baseName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val mpData = ProjectionGlobals.data ?: return false
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(ProjectionGlobals.resultCode, mpData) ?: return false
            outputFile = File(recDir, "$baseName.wav")
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val bufSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT),
                BUFFER_SIZE
            )
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                .addMatchingUsage(5) // USAGE_NOTIFICATION_TELEPHONY_RINGTONE
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                configBuilder.addMatchingUsage(16) // USAGE_CALL_ASSISTANT
            }
            val config = configBuilder.build()
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) return false
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
            audioRecord = record

            val micRec = createMicRecord(bufSize)
            micRec?.startRecording()
            micRecord = micRec

            isRecording = true
            Log.d(TAG, "OK: MediaProjection audio-only")

            recordingThread = Thread {
                writeMixedWav(outputFile!!, record, micRec, bufSize, channelMask)
            }.also { it.start() }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection audio: ${e.message}")
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
        return false
    }

    private fun tryMediaRecorder(recDir: File, baseName: String): Boolean {
        try {
            outputFile = File(recDir, "$baseName.m4a")
            val mRecorder = MediaRecorder()
            mRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mRecorder.setOutputFile(outputFile!!.absolutePath)
            mRecorder.prepare()
            mRecorder.start()
            mediaRecorder = mRecorder
            isRecording = true
            Log.d(TAG, "OK: MediaRecorder fallback")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Fallback failed: ${e.message}")
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
        }
        return false
    }

    private fun writeMixedWav(file: File, record: AudioRecord, micRecord: AudioRecord?, bufSize: Int, channelMask: Int) {
        try {
            file.parentFile?.mkdirs()
            val rawData = ByteArray(bufSize)
            val micData = ByteArray(bufSize)
            val mixBuf = ByteArray(bufSize)
            val buffer = ByteArrayOutputStream()
            var totalBytes = 0L
            while (isRecording && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = record.read(rawData, 0, bufSize)
                if (n > 0) {
                    if (micRecord != null && micRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val m = micRecord.read(micData, 0, minOf(bufSize, n))
                        if (m > 0) {
                            val mixedLen = mixPcm16(rawData, micData, mixBuf, minOf(n, m))
                            buffer.write(mixBuf, 0, mixedLen)
                            totalBytes += mixedLen
                        } else {
                            buffer.write(rawData, 0, n)
                            totalBytes += n
                        }
                    } else {
                        buffer.write(rawData, 0, n)
                        totalBytes += n
                    }
                }
            }
            if (totalBytes < 1000) { file.delete(); return }
            val pcm = buffer.toByteArray()
            val ch = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val brate = SAMPLE_RATE * ch * 2
            FileOutputStream(file).use { fos ->
                val dos = DataOutputStream(fos)
                dos.writeBytes("RIFF"); dos.writeInt(Integer.reverseBytes(36 + pcm.size))
                dos.writeBytes("WAVE"); dos.writeBytes("fmt ")
                dos.writeInt(Integer.reverseBytes(16))
                dos.writeShort(Integer.reverseBytes(1)); dos.writeShort(Integer.reverseBytes(ch))
                dos.writeInt(Integer.reverseBytes(SAMPLE_RATE)); dos.writeInt(Integer.reverseBytes(brate))
                dos.writeShort(Integer.reverseBytes(ch * 2)); dos.writeShort(Integer.reverseBytes(16))
                dos.writeBytes("data"); dos.writeInt(Integer.reverseBytes(pcm.size))
                dos.write(pcm); dos.flush()
            }
            Log.d(TAG, "WAV: ${file.length()} bytes")
            if (file.length() < 2000L) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "WAV error: ${e.message}", e)
            try { file.delete() } catch (_: Exception) {}
        }
    }

    private fun stopRecording() {
        if (!isRecording && !isStopping) return
        isStopping = true
        isRecording = false

        // Wait for video muxer thread to finish (handles cleanup internally)
        if (muxerThread != null && muxerThread?.isAlive == true) {
            try { muxerThread?.join(5000) } catch (_: Exception) {}
        } else {
            // Audio-only or fallback cleanup
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            try { audioRecord?.stop() } catch (_: Exception) {}
            audioRecord?.release()
            audioRecord = null
            try { micRecord?.stop() } catch (_: Exception) {}
            micRecord?.release()
            micRecord = null
            try { recordingThread?.join(3000) } catch (_: Exception) {}
            recordingThread = null
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        val size = outputFile?.length() ?: 0
        if (size < 500L) outputFile?.delete()
        isStopping = false
        stopSelf()
    }

    private fun cleanupVideoRecording() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        try { micRecord?.stop() } catch (_: Exception) {}
        micRecord?.release()
        virtualDisplay = null; videoCodec = null; audioCodec = null; muxer = null; audioRecord = null; micRecord = null; mediaProjection = null
    }

    private fun cleanup() {
        isRecording = false
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        try { micRecord?.stop() } catch (_: Exception) {}
        micRecord?.release()
        micRecord = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        recordingThread = null
        muxerThread = null
        outputFile?.let { if (it.exists() && it.length() < 500L) it.delete() }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    private fun showNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Recording")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_mic)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) { Log.e(TAG, "Notif: ${e.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Call Recording", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { if (isRecording) stopRecording(); super.onDestroy() }
}
