/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 19/06/2022, 02:06.
 */
package org.thunderdog.challegram.ui.camera

import androidx.annotation.RequiresApi
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import android.graphics.RectF
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.FinderPattern
import org.thunderdog.challegram.Log
import org.thunderdog.challegram.U
import org.thunderdog.challegram.ui.camera.legacy.CameraApiLegacy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CameraQrBridge(manager: CameraManager<*>) {
    val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val delegate: CameraDelegate
    private val mainExecutor: Executor
    private val zxingReader = QRCodeReader()

    init {
        this.delegate = manager.delegate
        this.mainExecutor = ContextCompat.getMainExecutor(manager.context)
    }

    fun destroy() {
        backgroundExecutor.shutdown()
    }

    @RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
    fun processImage(proxy: ImageProxy) {
        val mediaImage = proxy.getImage()

        if (mediaImage == null) {
            proxy.close()
            return
        }

        zxingImplementation(
            bufferAsBytes(proxy.getPlanes()[0].getBuffer()),
            proxy.getWidth(),
            proxy.getHeight(),
            proxy.getImageInfo().getRotationDegrees()) {
            proxy.close()
        }
    }

    fun processImage(
        data: ByteArray,
        previewWidth: Int,
        previewHeight: Int,
        legacyApi: CameraApiLegacy
    ) {
        val rotation = delegate.getCurrentCameraOrientation()

        zxingImplementation(
            data,
            previewWidth,
            previewHeight,
            rotation) {
            legacyApi.notifyCanReadNextFrame()
        }
    }

    private fun zxingImplementation(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        onFinish: Runnable?
    ) {
        backgroundExecutor.submit(Runnable {
            try {
                val sensorRotation = delegate.getCurrentCameraSensorOrientation()
                val match = zxingImplementationImpl(data, width, height, rotation)
                if (match != null && match.getText() != null && !match.getText().isEmpty()) {
                    val zxingBox: RectF?
                    if (sensorRotation != rotation && U.isRotated(sensorRotation)) {
                        zxingBox = zxingBoundingBox(match, rotation, true, width, height)
                        mainExecutor.execute(Runnable {
                            delegate.onQrCodeFound(
                                match.getText(),
                                zxingBox,
                                height,
                                width,
                                rotation,
                                true
                            )
                        })
                    } else {
                        zxingBox = zxingBoundingBox(match, rotation, false, width, height)
                        mainExecutor.execute(Runnable {
                            delegate.onQrCodeFound(
                                match.getText(),
                                zxingBox,
                                width,
                                height,
                                rotation,
                                true
                            )
                        })
                    }
                } else {
                    mainExecutor.execute(Runnable { delegate.onQrCodeNotFound() })
                }
            } catch (ex: Exception) {
                if (ex is NotFoundException) {
                    mainExecutor.execute(Runnable { delegate.onQrCodeNotFound() })
                } else {
                    Log.e(Log.TAG_CAMERA, ex)
                }
            } finally {
                if (onFinish != null) onFinish.run()
            }
        })
    }

    private fun zxingBoundingBox(
        result: Result,
        rotation: Int,
        sensorRotationInverted: Boolean,
        width: Int,
        height: Int
    ): RectF? {
        // ordered in: bottom-left, top-left, top-right
        if (result.getResultPoints().size < 3) return null

        val points = result.getResultPoints()
        val bottomLeft: ResultPoint
        val topLeft: ResultPoint
        val topRight: ResultPoint
        val moduleSize: Int

        if (U.isRotated(rotation)) {
            bottomLeft = points[2]
            topLeft = points[1]
            topRight = points[0]
        } else {
            bottomLeft = points[0]
            topLeft = points[1]
            topRight = points[2]
        }

        if (bottomLeft is FinderPattern) {
            moduleSize = bottomLeft.getEstimatedModuleSize().toInt() * 2
        } else {
            moduleSize = 0
        }

        var x1 = min(min(topLeft.getX(), topRight.getX()), bottomLeft.getX()).toInt()
        var x2 = max(max(topLeft.getX(), topRight.getX()), bottomLeft.getX()).toInt()
        val y1 = min(min(topLeft.getY(), topRight.getY()), bottomLeft.getY()).toInt()
        val y2 = max(max(topLeft.getY(), topRight.getY()), bottomLeft.getY()).toInt()

        if (sensorRotationInverted) {
            // these rotations need inverting zones
            val px1 = width - x1
            val px2 = width - x2
            x1 = px2
            x2 = px1
        }

        return RectF(
            (x1 - moduleSize).toFloat(),
            (y1 - moduleSize).toFloat(),
            (x2 + moduleSize).toFloat(),
            (y2 + moduleSize).toFloat()
        )
    }

    @Throws(FormatException::class, ChecksumException::class, NotFoundException::class)
    private fun zxingImplementationImpl(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int
    ): Result? {
        var data = data
        var width = width
        var height = height
        if (rotation != 0) {
            data = rotateYuvImage(data, width, height, rotation)
        }

        if (U.isRotated(rotation)) {
            val prevWidth = width
            width = height
            height = prevWidth
        }

        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            width,
            height,
            rotation == 180
        )

        return zxingReader.decode(BinaryBitmap(HybridBinarizer(source)))
    }

    private fun bufferAsBytes(buffer: ByteBuffer): ByteArray {
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun rotateYuvImage(data: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        if (degrees == 0 || degrees % 90 != 0) return data

        val rotatedData = ByteArray(data.size)

        for (y in 0..<height) {
            for (x in 0..<width) {
                when (degrees) {
                    90 -> {
                        rotatedData[x * height + height - y - 1] = data[x + y * width]
                    }

                    180 -> {
                        rotatedData[width * (height - y - 1) + width - x - 1] = data[x + y * width]
                    }

                    270 -> {
                        rotatedData[y + x * height] = data[y * width + width - x - 1]
                    }
                }
            }
        }

        return rotatedData
    }
}
