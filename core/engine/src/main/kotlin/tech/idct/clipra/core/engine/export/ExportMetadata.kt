package tech.idct.clipra.core.engine.export

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.InAppMp4Muxer

/**
 * The MP4 metadata of an export (#219): its own creation time, and nothing from its clips.
 *
 * Transformer hands the metadata of the first clip's tracks to the muxer, which wrote it as it
 * was: the phone's recording date (MediaStore takes it as the date taken, so the gallery filed
 * the export among the clips of that day), its maker and model tags (`com.android.model`), the
 * maker's own camera data and its location; XMP or a slow-motion clip's capture frame rate would
 * go the same way. None of it describes the export, and the user never chose to publish where a
 * clip was recorded or on which phone. The rotation is not in this set: the muxer writes the
 * encoder's own.
 */
@OptIn(UnstableApi::class)
internal class ExportMetadata(private val nowMillis: () -> Long = System::currentTimeMillis) :
    InAppMp4Muxer.MetadataProvider {
    override fun updateMetadataEntries(metadataEntries: MutableSet<Metadata.Entry>) {
        metadataEntries.clear()
        val now = Mp4TimestampData.unixTimeToMp4TimeSeconds(nowMillis())
        metadataEntries.add(Mp4TimestampData(now, now))
    }
}
