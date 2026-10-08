package cn.personal.phonebridge

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean

object LocalOcr {
    private val processing=AtomicBoolean(false)
    suspend fun recognize(screenshot:JSONObject,focus:OcrLine?=null):List<OcrLine> = suspendCancellableCoroutine {continuation ->
        if(!processing.compareAndSet(false,true)) {continuation.resumeWithException(BridgeError("OCR_BUSY","上一次本地识别仍在清理，不启动重复识别"));return@suspendCancellableCoroutine}
        val bitmap=try {
            val bytes=Base64.decode(screenshot.getString("base64"),Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        } catch(_:Exception) {
            processing.set(false);continuation.resumeWithException(BridgeError("OCR_FAILED","无法读取本次截图数据"));return@suspendCancellableCoroutine
        }
        if(bitmap==null) {processing.set(false);continuation.resumeWithException(BridgeError("OCR_FAILED","无法解码本次截图"));return@suspendCancellableCoroutine}
        val plan=if(focus==null)null else SleepOcr.crop(focus,bitmap.width,bitmap.height)
        if(focus!=null && plan==null) {bitmap.recycle();processing.set(false);continuation.resumeWithException(BridgeError("OCR_FAILED","局部文字区域无效或过大"));return@suspendCancellableCoroutine}
        val image=try {
            if(plan==null)bitmap else {
                val crop=Bitmap.createBitmap(bitmap,plan.left,plan.top,plan.right-plan.left,plan.bottom-plan.top)
                val enlarged=Bitmap.createScaledBitmap(crop,crop.width*plan.scale,crop.height*plan.scale,true)
                if(crop!==bitmap && crop!==enlarged)crop.recycle()
                if(enlarged!==bitmap)bitmap.recycle()
                enlarged
            }
        }catch(_:Exception) {bitmap.recycle();processing.set(false);continuation.resumeWithException(BridgeError("OCR_FAILED","局部放大失败"));return@suspendCancellableCoroutine}
        val recognizer=try {TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())}catch(_:Exception) {
            image.recycle();processing.set(false);continuation.resumeWithException(BridgeError("OCR_FAILED","本地中文识别器无法初始化"));return@suspendCancellableCoroutine
        }
        try {
            recognizer.process(InputImage.fromBitmap(image,0)).addOnCompleteListener {task ->
                val outcome=runCatching {
                    if(!task.isSuccessful)throw BridgeError("OCR_FAILED","本地文字识别失败，请核对截图或设备兼容性")
                    task.result.textBlocks.flatMap {it.lines}.mapNotNull {line -> line.boundingBox?.let {b->plan?.line(line.text,b.left,b.top,b.right,b.bottom) ?: OcrLine(line.text,b.left,b.top,b.right,b.bottom)}}
                }
                try {recognizer.close();image.recycle()}finally {processing.set(false)}
                if(continuation.isActive)outcome.fold({continuation.resume(it)},{continuation.resumeWithException(it)})
            }
        } catch(_:Exception) {
            try {recognizer.close();image.recycle()}finally {processing.set(false)}
            if(continuation.isActive)continuation.resumeWithException(BridgeError("OCR_FAILED","本地文字识别无法启动"))
        }
        // ML Kit processing itself is not cancellable. Cleanup happens on its
        // completion; cancelled continuations cannot submit a late result.
    }
}
