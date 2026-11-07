package tech.idct.clipra.feature.editor

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * The photo picker's provider (authority `media`) for the links it hands over,
 * `content://media/picker/0/com.android.providers.media.photopicker/media/<id>`, named as the
 * API 37 emulator names them (#215): `<id>.<extension>`, not the file's name. [answersDateTaken]
 * says whether it knows `datetaken`; MediaStore refuses a column it doesn't know.
 */
class PhotoPickerProvider : ContentProvider() {
    /** A picked photo or video: its bytes, its type and when it was taken. */
    class Item(val file: File, val mime: String, val takenAtMillis: Long)

    /** What the picker hands over, by the id at the end of the link. */
    val items = mutableMapOf<String, Item>()
    var answersDateTaken = true

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = items[uri.lastPathSegment]?.mime

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val id = uri.lastPathSegment ?: return null
        val item = items[id] ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when {
                column == OpenableColumns.DISPLAY_NAME -> "$id.${item.file.extension}"

                column == OpenableColumns.SIZE -> item.file.length()

                column == MediaStore.MediaColumns.DATE_TAKEN && answersDateTaken ->
                    item.takenAtMillis

                else -> throw IllegalArgumentException("Invalid column $column")
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = items[uri.lastPathSegment]?.file?.takeIf { it.exists() }
            ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        /** The link the picker hands over for [id]. */
        fun uri(id: String): Uri =
            Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/$id")
    }
}
