/* Copyright 2015 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.raphta.drvma

import android.content.ContentResolver
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.raphta.drvma.model.ml.FaceNet


import com.raphta.drvma.model.ml.LibSVM
import com.raphta.drvma.model.ml.MTCNN

import com.raphta.drvma.utils.FileUtils

import java.util.ArrayList
import java.util.LinkedList

/**
 * Generic interface for interacting with different recognition engines.
 */
class Classifier private constructor() {

    private var mtcnn: MTCNN? = null
    private var faceNet: FaceNet? = null
    private var svm: LibSVM? = null

    private var classNames: MutableList<String>? = null

    internal val statString: String
        get() = faceNet!!.statString

    /**
     * An immutable result returned by a Classifier describing what was recognized.
     */
    inner class Recognition internal constructor(
            /**
             * A unique identifier for what has been recognized. Specific to the class, not the instance of
             * the object.
             */
            val id: String?,
            /**
             * Display name for the recognition.
             */
            val title: String?,
            /**
             * A sortable score for how good the recognition is relative to others. Higher should be better.
             */
            val confidence: Float?,
            /** Optional location within the source image for the location of the recognized object.  */
            private var location: RectF?) {

        fun getLocation(): RectF {
            return RectF(location)
        }

        internal fun setLocation(location: RectF) {
            this.location = location
        }

        override fun toString(): String {
            var resultString = ""
            if (id != null) {
                resultString += "[$id] "
            }

            if (title != null) {
                resultString += "$title "
            }

            if (confidence != null) {
                resultString += String.format("(%.1f%%) ", confidence * 100.0f)
            }

            if (location != null) {
                resultString += location!!.toString() + " "
            }

            return resultString.trim { it <= ' ' }
        }
    }

//    internal fun getClassNames(): Array<CharSequence> {
//        val cs = arrayOfNulls<CharSequence>(classNames!!.size + 1)
//        var idx = 1
//
//        cs[0] = "+ Add new person"
//        for (name in classNames!!) {
//            cs[idx++] = name
//        }
//
//        return cs
//    }

    internal fun recognizeImage(bitmap: Bitmap, matrix: Matrix): List<Recognition> {
        synchronized(this) {
            val faces = mtcnn!!.detect(bitmap)

            val mappedRecognitions = LinkedList<Recognition>()

            for (face in faces) {
                val rectF = face.first as RectF?

                val rect = Rect()
                rectF!!.round(rect)

                val buffer = faceNet!!.getEmbeddings(bitmap, rect)
                val pair = svm!!.predict(buffer)

                matrix.mapRect(rectF)
                val prob = pair.second

                val name: String

                name = "Unknown"

                val result = Recognition("" + pair.first!!, name, prob, rectF)
                mappedRecognitions.add(result)
            }
            return mappedRecognitions
        }

    }

    @Throws(Exception::class)
    internal fun updateData(label: Int, contentResolver: ContentResolver, uris: ArrayList<Uri>) {
        synchronized(this) {
            val list = ArrayList<FloatArray>()

            for (uri in uris) {
                val bitmap = getBitmapFromUri(contentResolver, uri)
                val faces = mtcnn!!.detect(bitmap)

                var max = 0f
                val rect = Rect()

                for (face in faces) {
                    val prob = face.second as Float?

                    if(prob != null){
                        if (prob > max) {
                            max = prob!!

                            val rectF = face.first as RectF?
                            rectF!!.round(rect)
                        }

                    }

                }

                val emb_array = FloatArray(EMBEDDING_SIZE)
                faceNet!!.getEmbeddings(bitmap, rect).get(emb_array)
                list.add(emb_array)
            }

            svm!!.train(label, list)
        }
    }

    internal fun addPerson(name: String): Int {
        FileUtils.appendText(name, FileUtils.LABEL_FILE)
        classNames!!.add(name)

        return classNames!!.size
    }

    @Throws(Exception::class)
    private fun getBitmapFromUri(contentResolver: ContentResolver, uri: Uri): Bitmap {
        val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
        val fileDescriptor = parcelFileDescriptor!!.fileDescriptor
        val bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor)
        parcelFileDescriptor.close()

        return bitmap
    }

    internal fun enableStatLogging(debug: Boolean) {}

    internal fun close() {
        mtcnn!!.close()
        faceNet!!.close()
    }

    companion object {

        val EMBEDDING_SIZE = 512
        private var classifier: Classifier? = null

        @Throws(Exception::class)
        internal fun getInstance(assetManager: AssetManager,
                                 inputHeight: Int,
                                 inputWidth: Int): Classifier {
            if (classifier != null) return classifier!!

            classifier = Classifier()

            classifier!!.mtcnn = MTCNN.create(assetManager)
            classifier!!.faceNet = FaceNet.create(assetManager, inputHeight, inputWidth)
            classifier!!.svm = LibSVM.instance

            classifier!!.classNames = FileUtils.readLabel(FileUtils.LABEL_FILE)

            return classifier!!
        }
    }
}
