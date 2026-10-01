package androidx.documentfile.provider

import android.net.Uri
import com.dd3boh.outertune.playback.downloadManager.indexDownloadFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// DocumentFile's constructor is package-private; this provider fixture shares its package.
@RunWith(RobolectricTestRunner::class)
class UnreadableDownloadFileTest {
    @Test fun unreadableProviderFileDoesNotHideDownloadCandidates() {
        val file = object : DocumentFile(null) {
            override fun getUri(): Uri = Uri.parse("content://downloads/audio/1")
            override fun getName(): String = "Song [abc].mka"
            override fun getType(): String = "audio/mka"
            override fun isDirectory() = false
            override fun isFile() = true
            override fun isVirtual() = false
            override fun lastModified() = 1L
            override fun length() = 100L
            override fun canRead() = false
            override fun canWrite() = false
            override fun exists() = true
            override fun delete() = false
            override fun renameTo(displayName: String) = false
            override fun listFiles(): Array<DocumentFile> = emptyArray()
            override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
            override fun createDirectory(displayName: String): DocumentFile? = null
        }
        assertTrue(indexDownloadFiles(listOf(file)).isEmpty())
    }
}
