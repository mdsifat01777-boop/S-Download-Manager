package com.flowfetch.app

import com.flowfetch.app.data.extractor.SnapSaveExtractor
import com.flowfetch.app.data.model.Platform
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapSaveExtractorTest {

    @Test
    fun testFacebookSnapSaveExtraction() {
        val client = OkHttpClient()
        val extractor = SnapSaveExtractor(client)
        val fbUrl = "https://www.facebook.com/share/r/1FAA4o3w5j/"

        val result = extractor.extract(fbUrl, Platform.FACEBOOK)
        if (result.isSuccess) {
            val meta = result.getOrNull()
            assertNotNull(meta)
            assertTrue(meta!!.formats.isNotEmpty())
            println("Facebook formats from SnapSave:")
            for (f in meta.formats) {
                println("${f.qualityLabel} -> ${f.downloadUrl.take(60)}...")
            }
        }
    }
}

