/*
* Copyright 2016 The TensorFlow Authors. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.raphta.drvma

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.media.ImageReader.OnImageAvailableListener
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Display
import android.view.View

import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.raphta.drvma.camera.CameraActivity
import com.raphta.drvma.tracking.MultiBoxTracker
import com.raphta.drvma.utils.BorderedText
import com.raphta.drvma.utils.FileUtils
import com.raphta.drvma.utils.ImageUtils
import com.raphta.drvma.utils.Logger
import com.raphta.drvma.utils.OverlayView

import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.Vector


class MainActivity : CameraActivity(), OnImageAvailableListener {

    private var sensorOrientation: Int? = null

    private var classifier: Classifier? = null

    private var lastProcessingTimeMs: Long = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var computingDetection = false

    private var timestamp: Long = 0

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    private var tracker: MultiBoxTracker? = null

    private var luminanceCopy: ByteArray? = null

    private var borderedText: BorderedText? = null


    private val button: FloatingActionButton? = null

    private var initialized = false
    private var training = false

    internal var trackingOverlay: OverlayView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val width = size.x
        val height = size.y
        Log.e("Width", "" + width)
        Log.e("height", "" + height)


    }

    public override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        if (!initialized)
            Thread(Runnable { this.init() }).start()

        val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)

        tracker = MultiBoxTracker(this)

        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - screenOrientation
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation)

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight)
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(CROP_SIZE, CROP_SIZE, Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                CROP_SIZE, CROP_SIZE,
                sensorOrientation!!, false)

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        trackingOverlay = findViewById(R.id.tracking_overlay)
        trackingOverlay?.addCallback { canvas ->
            tracker!!.draw(canvas)
            if (isDebug) {
                tracker!!.drawDebug(canvas)
            }
        }

        addCallback { canvas ->
            if (!isDebug) {
                return@addCallback
            }
            val copy = cropCopyBitmap ?: return@addCallback

            val backgroundColor = Color.argb(100, 0, 0, 0)
            canvas.drawColor(backgroundColor)

            val matrix = Matrix()
            val scaleFactor = 2f
            matrix.postScale(scaleFactor, scaleFactor)
            matrix.postTranslate(
                    canvas.width - copy.width * scaleFactor,
                    canvas.height - copy.height * scaleFactor)
            canvas.drawBitmap(copy, matrix, Paint())

            val lines = Vector<String>()
            if (classifier != null) {
                val statString = classifier!!.statString
                val statLines = statString.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                Collections.addAll(lines, *statLines)
            }
            lines.add("")
            lines.add("Frame: " + previewWidth + "x" + previewHeight)
            lines.add("Crop: " + copy.width + "x" + copy.height)
            lines.add("View: " + canvas.width + "x" + canvas.height)
            lines.add("Rotation: " + sensorOrientation!!)
            lines.add("Inference time: " + lastProcessingTimeMs + "ms")

            borderedText!!.drawLines(canvas, 10f, (canvas.height - 10).toFloat(), lines)
        }
    }

    internal fun init() {
        val dir = File(FileUtils.ROOT)

        if (!dir.isDirectory) {
            if (dir.exists()) dir.delete()
            dir.mkdirs()

            val mgr = assets
            FileUtils.copyAsset(mgr, FileUtils.DATA_FILE)
            FileUtils.copyAsset(mgr, FileUtils.MODEL_FILE)
            FileUtils.copyAsset(mgr, FileUtils.LABEL_FILE)
        }

        try {
            classifier = Classifier.getInstance(assets, FACE_SIZE, FACE_SIZE)
        } catch (e: Exception) {
            LOGGER.e("Exception initializing classifier!", e)
            finish()
        }
        initialized = true
    }

    override fun processImage() {
        ++timestamp
        val currTimestamp = timestamp
        val originalLuminance = luminance
        tracker!!.onFrame(
                previewWidth,
                previewHeight,
                luminanceStride,
                sensorOrientation!!,
                originalLuminance,
                timestamp)
        trackingOverlay?.postInvalidate()

        // No mutex needed as this method is not reentrant.
        if (computingDetection || !initialized || training) {
            readyForNextImage()
            return
        }
        computingDetection = true
        LOGGER.i("Preparing image $currTimestamp for detection in bg thread.")

        rgbFrameBitmap!!.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        if (luminanceCopy == null) {
            luminanceCopy = ByteArray(originalLuminance.size)
        }
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.size)
        readyForNextImage()

        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap)
        }

        runInBackground {
            LOGGER.i("Running detection on image $currTimestamp")
            val startTime = SystemClock.uptimeMillis()

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap!!)
            val mappedRecognitions = classifier!!.recognizeImage(croppedBitmap!!, cropToFrameTransform!!)

            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
            tracker!!.trackResults(mappedRecognitions, luminanceCopy!!, currTimestamp)
            trackingOverlay?.postInvalidate()

            requestRender()
            computingDetection = false
        }
    }

    override fun getLayoutId(): Int {
        return R.layout.camera_connection_fragment_tracking
    }

    override fun getDesiredPreviewFrameSize(): Size {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val width = size.x
        val height = size.y
        Log.e("Width", "" + width)
        Log.e("height", "" + height)
        return DESIRED_PREVIEW_SIZE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!initialized) {
            Snackbar.make(
                    window.decorView.findViewById<View>(R.id.container),
                    "Try it again later", Snackbar.LENGTH_SHORT)
                    .show()
            return
        }

        if (resultCode == Activity.RESULT_OK) {
            training = true

            val clipData = data!!.clipData
            val uris = ArrayList<Uri>()

            if (clipData == null) {
                uris.add(data.data)
            } else {
                for (i in 0 until clipData.itemCount)
                    uris.add(clipData.getItemAt(i).uri)
            }

            Thread {
                try {
                    classifier!!.updateData(requestCode, contentResolver, uris)
                } catch (e: Exception) {
                    LOGGER.e(e, "Exception!")
                } finally {
                    training = false
                }
            }.start()

        }
    }

    fun performFileSearch(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.type = "image/*"

        startActivityForResult(intent, requestCode)
    }



    companion object {
        private val LOGGER = Logger()

        private val FACE_SIZE = 160
        private val CROP_SIZE = 300


        private val DESIRED_PREVIEW_SIZE = Size(640, 480)


        private val SAVE_PREVIEW_BITMAP = false
        private val TEXT_SIZE_DIP = 10f
    }
}
