package tech.idct.clipra.core.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.settings.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where a model stands on this phone. */
sealed interface ModelStatus {
    data object BuiltIn : ModelStatus

    /** Not downloaded; [failure] says why the last download failed, if it did. */
    data class Missing(val failure: DownloadFailure? = null) : ModelStatus

    /** Waiting for a network ([wifiOnly]: for Wi-Fi) or for a retry. */
    data class Waiting(val wifiOnly: Boolean, val bytes: Long) : ModelStatus

    data class Downloading(val bytes: Long, val total: Long) : ModelStatus

    /** Installed, taking [bytes] on disk. */
    data class Installed(val bytes: Long) : ModelStatus
}

/** Why a download failed for good: the file failed its check, or its source is gone. */
enum class DownloadFailure { Corrupt, Unavailable }

data class ModelState(val model: AiModel, val status: ModelStatus)

/**
 * The AI models on this phone (#43): their states, downloads, removal and storage. Downloads
 * run in WorkManager, on Wi-Fi only unless the user allows mobile data, and come from the
 * official sources in [ModelCatalog].
 */
@Singleton
class ModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ModelStore,
    private val settings: SettingsRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)
    private val removed = MutableStateFlow(0)

    /** Every model of the catalogue with its status, updated as downloads run. */
    val models: Flow<List<ModelState>> =
        combine(workManager.getWorkInfosByTagFlow(TAG), removed) { infos, _ ->
            val latest = infos.groupBy { it.tags.firstOrNull { tag -> tag.startsWith(PREFIX) } }
            ModelCatalog.all.map { model ->
                val info = latest["$PREFIX${model.id}"]?.firstOrNull { !it.state.isFinished }
                    ?: latest["$PREFIX${model.id}"]?.firstOrNull()
                ModelState(model, status(model, info))
            }
        }.flowOn(Dispatchers.IO)

    /** An installed model's file, for its runtime; null until it is downloaded. */
    fun file(model: AiModel, name: String): File? = store.file(model, name)

    /** Starts downloading [model], or keeps the download that is already under way. */
    suspend fun download(model: AiModel) {
        if (model.builtIn) return
        enqueue(model, settings.wifiOnlyDownloads.first(), ExistingWorkPolicy.KEEP)
    }

    /** Stops a download and throws away what it fetched. */
    suspend fun cancel(model: AiModel) {
        workManager.cancelUniqueWork(workName(model))
        withContext(Dispatchers.IO) {
            store.partial(model).delete()
            store.staging(model).deleteRecursively()
        }
        removed.update { it + 1 }
    }

    /** Removes an installed model's files. */
    suspend fun remove(model: AiModel) {
        workManager.cancelUniqueWork(workName(model))
        withContext(Dispatchers.IO) { store.remove(model) }
        removed.update { it + 1 }
    }

    /** Switches Wi-Fi-only downloads; waiting downloads pick up the new rule. */
    suspend fun setWifiOnly(enabled: Boolean) {
        settings.setWifiOnlyDownloads(enabled)
        val waiting = withContext(Dispatchers.IO) {
            ModelCatalog.all.filter { model ->
                workManager.getWorkInfosForUniqueWork(workName(model)).get()
                    .any { it.state == WorkInfo.State.ENQUEUED }
            }
        }
        waiting.forEach { enqueue(it, enabled, ExistingWorkPolicy.REPLACE) }
    }

    private fun enqueue(model: AiModel, wifiOnly: Boolean, policy: ExistingWorkPolicy) {
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag("$PREFIX${model.id}")
            .build()
        workManager.enqueueUniqueWork(workName(model), policy, request)
    }

    private fun status(model: AiModel, info: WorkInfo?): ModelStatus {
        if (model.builtIn) return ModelStatus.BuiltIn
        if (store.isInstalled(model)) return ModelStatus.Installed(store.bytesOnDisk(model))
        val total = model.download?.bytes ?: 0
        return when (info?.state) {
            WorkInfo.State.RUNNING -> ModelStatus.Downloading(
                info.progress.getLong(ModelDownloadWorker.KEY_BYTES, store.partial(model).length()),
                total,
            )

            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelStatus.Waiting(
                wifiOnly = info.constraints.requiredNetworkType == NetworkType.UNMETERED,
                bytes = store.partial(model).length(),
            )

            WorkInfo.State.FAILED -> ModelStatus.Missing(
                when (info.outputData.getString(ModelDownloadWorker.KEY_ERROR)) {
                    ModelDownloadWorker.ERROR_UNAVAILABLE -> DownloadFailure.Unavailable
                    else -> DownloadFailure.Corrupt
                },
            )

            else -> ModelStatus.Missing()
        }
    }

    private fun workName(model: AiModel) = "$PREFIX${model.id}"

    private companion object {
        const val TAG = "model-download"
        const val PREFIX = "model-download-"
        const val BACKOFF_SECONDS = 30L
    }
}
