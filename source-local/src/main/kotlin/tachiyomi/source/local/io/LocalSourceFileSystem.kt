package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager

@Inject
@SingleIn(AppScope::class)
class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    /**
     * The Local source reads one folder per series, so a loose book dropped straight into the local folder
     * would be invisible. Give each loose .epub its own folder of the same name (copy, then delete the original).
     * Returns how many books were organised.
     */
    fun organizeLooseEpubs(): Int {
        val base = getBaseDirectory() ?: return 0
        var moved = 0
        for (file in base.listFiles().orEmpty()) {
            val name = file.name.orEmpty()
            if (file.isDirectory || name.startsWith('.') || !name.endsWith(".epub", ignoreCase = true)) continue
            try {
                val title = name.substringBeforeLast('.').trim().ifEmpty { continue }
                val dir = base.findFile(title)?.takeIf { it.isDirectory } ?: base.createDirectory(title) ?: continue
                if (dir.findFile(name) != null) continue
                val target = dir.createFile(name) ?: continue
                val ok = file.openInputStream().use { input ->
                    target.openOutputStream().use { output -> input.copyTo(output) }
                    true
                }
                if (ok && target.length() == file.length()) {
                    file.delete()
                    moved++
                } else {
                    target.delete()
                }
            } catch (_: Exception) {
                // leave the loose file where it is; it is retried on the next refresh
            }
        }
        return moved
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }
}
