package cn.personal.phonebridge

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingQrTest {
    @Test fun desktopQrDecodesExactlyWithProductionScannerCharset() {
        val fixture=JSONObject(javaClass.getResourceAsStream("/pairing_qr_synthetic.json")!!.bufferedReader().use {it.readText()})
        val matrix=fixture.getJSONArray("matrix");val size=matrix.length()*4
        val pixels=IntArray(size*size){index -> if(matrix.getJSONArray((index/size)/4).getBoolean((index%size)/4))0xff000000.toInt() else 0xffffffff.toInt()}
        val result=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels))),mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
        assertEquals(fixture.getString("payload"),result.text)
        val data=JSONObject(result.text);assertEquals("测试电脑",data.getString("name"));assertEquals(1,data.getInt("protocol"));assertFalse(data.has("private_key"))
    }
}
