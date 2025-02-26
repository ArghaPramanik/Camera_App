package com.example.cameraapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.media.ImageReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var capturePhotoButton: Button
    private lateinit var recordVideoButton: Button
    private lateinit var surfaceView: SurfaceView
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var isRecordingVideo = false
    private var videoFile: File? = null
    private lateinit var photoDirectory: File
    private lateinit var videoDirectory: File

    // State tracking
    private var isSurfaceCreated = false
    private var isPendingCameraOpen = false
    private var isCapturingPhoto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        photoDirectory = filesDir

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceCreated = true
                openCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceCreated = false
            }
        })
    }

    private fun setupButtons() {
        capturePhotoButton.setOnClickListener {
            if (checkPermissions()) capturePhoto()
            else requestPermissions()
        }

        recordVideoButton.setOnClickListener {
            if (checkPermissions()) {
                if (isRecordingVideo) stopRecordingVideo()
                else startRecordingVideo()
            } else {
                requestPermissions()
            }
        }
    }

    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceCreated = true
                if (checkPermissions()) {
                    openCamera()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (cameraDevice != null) {
                    startCameraPreview()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceCreated = false
                closeCamera()
            }
        })
    }

    private fun startRecordingVideo() {
        try {
            if (cameraDevice == null || !isSurfaceCreated) {
                Toast.makeText(this, "Camera not initialized", Toast.LENGTH_SHORT).show()
                return
            }

            val surface = surfaceView.holder.surface
            val videoFileName = "video_${System.currentTimeMillis()}.mp4"
            videoFile = File(videoDirectory, videoFileName)

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile!!.absolutePath)
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }

            val recordingSurface = mediaRecorder.surface
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.addTarget(recordingSurface)

            cameraDevice!!.createCaptureSession(
                listOf(surface, recordingSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            captureSession?.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                null
                            )
                            mediaRecorder.start()
                            isRecordingVideo = true
                            runOnUiThread { recordVideoButton.text = "Stop Recording" }
                        } catch (e: CameraAccessException) {
                            Log.e("CameraAppp", "Failed to start video recording", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraAppp", "Failed to configure video recording session")
                        Toast.makeText(this@MainActivity, "Failed to configure video recording", Toast.LENGTH_SHORT).show()
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e("CameraAppp", "Error in startRecordingVideo", e)
        }
    }

    private fun stopRecordingVideo() {
        try {
            if (!isRecordingVideo) return

            mediaRecorder.stop()
            mediaRecorder.reset()
            isRecordingVideo = false
            runOnUiThread { recordVideoButton.text = "Start Recording" }

            Toast.makeText(this, "Video saved to: ${videoFile?.absolutePath}", Toast.LENGTH_LONG).show()

            // Restart preview after stopping recording
            startCameraPreview()
        } catch (e: Exception) {
            Log.e("CameraAppp", "Error in stopRecordingVideo", e)
        }
    }

    private fun capturePhoto() {
        if (isCapturingPhoto || cameraDevice == null || !isSurfaceCreated) return

        try {
            isCapturingPhoto = true
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
            val photoFile = File(photoDirectory, "photo_${System.currentTimeMillis()}.jpg")

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    val buffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    saveImageToFile(bytes, photoFile)
                    it.close()
                    isCapturingPhoto = false

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Photo saved to: ${photoFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            }, null)

            val surface = imageReader.surface
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            cameraDevice!!.createCaptureSession(
                listOf(surface, surfaceView.holder.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        captureSession = session
                        try {
                            captureSession?.capture(
                                captureRequestBuilder.build(),
                                null,
                                null
                            )
                        } catch (e: CameraAccessException) {
                            Log.e("CameraAppp", "Failed to capture photo", e)
                            isCapturingPhoto = false
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CameraAppp", "Failed to configure photo capture session")
                        Toast.makeText(this@MainActivity, "Failed to configure photo capture", Toast.LENGTH_SHORT).show()
                        isCapturingPhoto = false
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e("CameraAppp", "Error in capturePhoto", e)
            isCapturingPhoto = false
        }
    }

    private fun saveImageToFile(bytes: ByteArray, file: File) {
        try {
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            Log.d("CameraAppp", "Photo saved successfully at: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e("CameraAppp", "Error saving photo", e)
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO
            ),
            100
        )
    }



    private fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
                return
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCameraPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: Exception) {
            Log.e("CameraApp", "Error opening camera", e)
        }
    }

    private fun startCameraPreview() {
        try {
            if (cameraDevice == null || !isSurfaceCreated) return

            val surface = surfaceView.holder.surface
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)

            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)

            // Set Auto Exposure Mode
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.let { aeModes ->
                if (aeModes.contains(CameraMetadata.CONTROL_AE_MODE_ON)) {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                }
            }

            // Set Auto White Balance Mode
            characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.let { awbModes ->
                if (awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            }

            // Set Focus Mode
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            // Set Exposure Compensation
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, range.upper / 2)
            }

            // Set ISO Sensitivity
            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange ->
                previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange.upper / 2)
            }

            cameraDevice!!.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
                    } catch (e: CameraAccessException) {
                        Log.e("CameraApp", "Failed to start camera preview", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Failed to configure camera", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e("CameraApp", "Error starting camera preview", e)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e("CameraAppp", "Error closing camera", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            Toast.makeText(this, "Permissions required to use camera", Toast.LENGTH_SHORT).show()
        }
    }

//    private fun logCameraParameters() {
//        try {
//            cameraDevice?.let { device ->
//                val characteristics = cameraManager.getCameraCharacteristics(device.id)
//                Log.d("CameraApp", "COLOR_CORRECTION_ABERRATION_MODE: ${characteristics.get(CameraCharacteristics
//                    .COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.joinToString()}")
//                Log.d("CameraApp", "CONTROL_AE_AVAILABLE_MODES: ${characteristics.get(CameraCharacteristics
//                    .CONTROL_AE_AVAILABLE_MODES)?.joinToString()}")
//                Log.d("CameraApp", "CONTROL_AE_COMPENSATION_RANGE: ${characteristics.get(CameraCharacteristics
//                    .CONTROL_AE_COMPENSATION_RANGE)}")
//                Log.d("CameraApp", "CONTROL_AE_COMPENSATION_STEP: ${characteristics.get(CameraCharacteristics
//                    .CONTROL_AE_COMPENSATION_STEP)}")
//                Log.d("CameraApp", "CONTROL_AE_LOCK_AVAILABLE: ${characteristics.get(CameraCharacteristics
//                    .CONTROL_AE_LOCK_AVAILABLE)}")
//                Log.d("CameraApp", "CONTROL_AWB_AVAILABLE_MODES: ${characteristics.get(CameraCharacteristics
//                    .CONTROL_AWB_AVAILABLE_MODES)?.joinToString()}")
//                Log.d("CameraApp", "LENS_INFO_AVAILABLE_FOCAL_LENGTHS: ${characteristics.get(CameraCharacteristics
//                    .LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
//                Log.d("CameraApp", "LENS_INFO_HYPERFOCAL_DISTANCE: ${characteristics.get(CameraCharacteristics
//                    .LENS_INFO_HYPERFOCAL_DISTANCE)}")
//                Log.d("CameraApp", "SENSOR_INFO_SENSITIVITY_RANGE: ${characteristics.get(CameraCharacteristics
//                    .SENSOR_INFO_SENSITIVITY_RANGE)}")
//                Log.d("CameraApp", "SENSOR_INFO_EXPOSURE_TIME_RANGE: ${characteristics.get(CameraCharacteristics
//                    .SENSOR_INFO_EXPOSURE_TIME_RANGE)}")
//                Log.d("CameraApp", "SENSOR_ORIENTATION: ${characteristics.get(CameraCharacteristics
//                    .SENSOR_ORIENTATION)}")
//                Log.d("CameraApp", "SENSOR_INFO_PIXEL_ARRAY_SIZE: ${characteristics.get(CameraCharacteristics
//                    .SENSOR_INFO_PIXEL_ARRAY_SIZE)}")
//                Log.d("CameraApp", "SENSOR_INFO_PHYSICAL_SIZE: ${characteristics.get(CameraCharacteristics
//                    .SENSOR_INFO_PHYSICAL_SIZE)}")
//                Log.d("CameraApp", "SCALER_AVAILABLE_STREAM_CONFIGURATIONS: ${characteristics.get(CameraCharacteristics
//                    .SCALER_STREAM_CONFIGURATION_MAP)}")
//            }
//        } catch (e: CameraAccessException) {
//            Log.e("CameraApp", "Error retrieving camera parameters", e)
//        }
//    }


    override fun onPause() {
        super.onPause()
        if (isRecordingVideo) {
            stopRecordingVideo()
        }
        closeCamera()
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissions() && isSurfaceCreated) {
            openCamera()
        }
    }
}