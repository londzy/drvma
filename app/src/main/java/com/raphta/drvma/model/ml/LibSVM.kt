package com.raphta.drvma.model.ml

import android.text.TextUtils
import android.util.Log

import java.io.File
import java.nio.FloatBuffer
import java.util.ArrayList
import java.util.Arrays

import androidx.core.util.Pair
import com.raphta.drvma.Classifier
import com.raphta.drvma.utils.FileUtils


class LibSVM private constructor() {
    private val LOG_TAG = "LibSVM"
    private val DATA_PATH = FileUtils.ROOT + File.separator + FileUtils.DATA_FILE
    private val MODEL_PATH = FileUtils.ROOT + File.separator + FileUtils.MODEL_FILE

    private val index: Int = 0
    private val prob: Double = 0.toDouble()

    // connect the native functions
    private external fun testLog(log: String)

    private external fun jniSvmTrain(cmd: String)
    private external fun jniSvmPredict(cmd: String, buf: FloatBuffer, len: Int)
    private external fun jniSvmScale(cmd: String, fileOutPath: String)

    // public interfaces
    private fun train(cmd: String) {
        jniSvmTrain(cmd)
    }

    private fun predict(cmd: String, buf: FloatBuffer, len: Int) {
        jniSvmPredict(cmd, buf, len)
    }

    private fun scale(cmd: String, fileOutPath: String) {
        jniSvmScale(cmd, fileOutPath)
    }

    fun train(label: Int, list: ArrayList<FloatArray>) {
        val builder = StringBuilder()

        for (i in list.indices) {
            val array = list[i]
            builder.append(label)
            for (j in array.indices) {
                builder.append(" ").append(j).append(":").append(array[j])
            }
            if (i < list.size - 1) builder.append(System.lineSeparator())
        }
        FileUtils.appendText(builder.toString(), FileUtils.DATA_FILE)

        train()
    }

    fun train() {
        val options = "-t 0 -b 1"
        val cmd = TextUtils.join(" ", Arrays.asList(options, DATA_PATH, MODEL_PATH))
        train(cmd)
    }

    fun predict(buffer: FloatBuffer): Pair<Int, Float> {
        val options = "-b 1"
        val cmd = TextUtils.join(" ", Arrays.asList(options, MODEL_PATH))

        predict(cmd, buffer, Classifier.EMBEDDING_SIZE)
        return Pair(index, prob.toFloat())
    }

    init {
        Log.d(LOG_TAG, "LibSVM init")
    }

    companion object {

        init {
            System.loadLibrary("")
        }

        // singleton for the easy access
        private var svm: LibSVM? = null
        val instance: LibSVM
            get() {
                if (svm == null) {
                    svm = LibSVM()
                }
                return svm!!
            }
    }
}
