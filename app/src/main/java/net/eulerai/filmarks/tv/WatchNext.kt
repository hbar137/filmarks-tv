package net.eulerai.filmarks.tv

import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/**
 * The Google TV home's "Continue watching" row (Watch Next): a title being
 * watched is added or updated there with its position; selecting it opens
 * the title in Filmarks (filmarkstv://open?path=…). Finished titles leave
 * the row. Programs are remembered per title path on the device.
 */
object WatchNext {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watchnext", Context.MODE_PRIVATE)

    fun update(ctx: Context, path: String, title: String, poster: String, positionMs: Long, durationMs: Long, episode: Boolean) {
        if (path == "" || durationMs <= 0) return
        runCatching {
            val p = prefs(ctx)
            val id = p.getLong(path, -1)
            val done = positionMs > durationMs * 0.95 || durationMs - positionMs < 180_000
            if (done) {
                if (id >= 0) ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
                p.edit().remove(path).apply()
                return
            }
            val program = WatchNextProgram.Builder()
                .setType(if (episode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(title)
                .setPosterArtUri(Uri.parse(poster))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setLastPlaybackPositionMillis(positionMs.toInt())
                .setDurationMillis(durationMs.toInt())
                .setIntentUri(Uri.parse("filmarkstv://open?path=" + Uri.encode(path)))
                .setInternalProviderId(path)
                .build()
            if (id >= 0 && ctx.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(id), program.toContentValues(), null, null) > 0) return
            val uri = ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
            if (uri != null) p.edit().putLong(path, android.content.ContentUris.parseId(uri)).apply()
        }.onFailure { Report.send(ctx, "watchnext", it.toString()) }
    }
}
