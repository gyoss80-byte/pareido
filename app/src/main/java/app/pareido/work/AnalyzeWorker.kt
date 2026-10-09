package app.pareido.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pareido.app
import app.pareido.core.ClaudeException
import app.pareido.core.FindStatus
import app.pareido.image.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Offline queue: analyzes a saved photo once the phone is back online. */
class AnalyzeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.app
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val find = app.finds.load(id) ?: return@withContext Result.failure()
        val scan = find.scan ?: return@withContext Result.failure()
        if (find.status != FindStatus.QUEUED) return@withContext Result.success()

        try {
            val photo = Images.load(app.finds.photoFile(id))
            val result = app.claude.findFigures(photo, scan)
            app.finds.save(
                find.copy(
                    status = if (result.figures.isEmpty()) FindStatus.NOTHING_FOUND else FindStatus.DONE,
                    figures = result.figures,
                    comment = result.comment,
                    error = "",
                )
            )
            Result.success()
        } catch (e: ClaudeException) {
            when (e.kind) {
                ClaudeException.Kind.OFFLINE, ClaudeException.Kind.RATE_LIMITED, ClaudeException.Kind.OTHER ->
                    if (runAttemptCount < 5) Result.retry() else fail(id, e.message)
                else -> fail(id, e.message)
            }
        }
    }

    private fun fail(id: String, message: String?): Result {
        val app = applicationContext.app
        app.finds.load(id)?.let { app.finds.save(it.copy(status = FindStatus.FAILED, error = message.orEmpty())) }
        return Result.failure()
    }

    companion object {
        private const val KEY_ID = "find_id"

        fun enqueue(context: Context, findId: String) {
            val request = OneTimeWorkRequestBuilder<AnalyzeWorker>()
                .setInputData(workDataOf(KEY_ID to findId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("analyze-$findId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
