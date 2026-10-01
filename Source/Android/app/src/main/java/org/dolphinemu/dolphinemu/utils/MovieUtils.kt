// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.features.DocumentProvider
import org.dolphinemu.dolphinemu.features.settings.model.StringSetting
import org.dolphinemu.dolphinemu.model.GameFile
import org.dolphinemu.dolphinemu.model.GameFileCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MovieUtils {
    fun getMoviesDirectory(): File {
        val userDir = NativeLibrary.GetUserDirectory()
        return File(userDir, "Movies").apply { mkdirs() }
    }

    fun generateMovieFileName(gameId: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return "${gameId}_${timestamp}.dtm"
    }

    /**
     * Returns the non-empty .dtm files in the Movies directory, newest first.
     */
    fun listMovies(): List<File> {
        val files = getMoviesDirectory().listFiles { file ->
            file.isFile && file.name.endsWith(".dtm", ignoreCase = true) && file.length() > 0
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    /**
     * Shows a list of the recordings in the Movies directory. If [onBrowse] is non-null, a
     * "Browse…" entry is appended so the user can pick a file from elsewhere.
     */
    fun showMoviePicker(context: Context, onPicked: (String) -> Unit, onBrowse: (() -> Unit)?) {
        val movies = listMovies()
        if (movies.isEmpty() && onBrowse == null) {
            Toast.makeText(context, R.string.movie_none_found, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = ArrayList<CharSequence>(movies.map { it.name })
        if (onBrowse != null) {
            labels.add(context.getString(R.string.movie_browse))
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.movie_choose)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < movies.size) {
                    onPicked(movies[which].absolutePath)
                } else {
                    onBrowse?.invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Checks that the file is a valid .dtm and that its companion savestate exists if needed.
     * This must be done before handing the movie to the core, because the core reports these
     * problems with a blocking PanicAlert, which deadlocks if raised from the UI thread.
     *
     * @return null if the movie is OK, otherwise a string resource describing the problem.
     */
    @StringRes
    fun validateMovie(path: String): Int? {
        val file = File(path)
        if (!file.isFile || file.length() == 0L || NativeLibrary.GetMovieGameID(path) == null) {
            return R.string.movie_invalid
        }
        if (NativeLibrary.IsMovieFromSavestate(path) && !File("$path.sav").isFile) {
            return R.string.movie_missing_savestate
        }
        return null
    }

    /**
     * Resolves a movie string (raw file path, relative file name in the Movies directory, or URI)
     * into a filesystem path that the core can open.
     */
    fun resolveMovieInput(context: Context, pathOrUri: String): String? {
        if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
            return resolveMovieUri(context, Uri.parse(pathOrUri))
        }
        val file = File(pathOrUri)
        return if (file.isAbsolute) file.path else File(getMoviesDirectory(), pathOrUri).absolutePath
    }

    /**
     * Resolves game paths passed via intent or finds the matching game in the library cache from
     * a Game ID, file name, or the movie header's Game ID when no game path was explicitly given.
     */
    fun resolveGamePaths(
        filePaths: Array<String>,
        resolvedMoviePath: String?
    ): Array<String> {
        if (filePaths.isNotEmpty() && filePaths.all { ContentHandler.isContentUri(it) || File(it).exists() }) {
            return filePaths
        }

        val cache = GameFileCache()
        cache.load()
        val allGames = cache.getAllGames()

        if (filePaths.isNotEmpty()) {
            return filePaths.map { query ->
                if (ContentHandler.isContentUri(query) || File(query).exists()) {
                    query
                } else {
                    val match = allGames.firstOrNull { game ->
                        game.getGameId().equals(query, ignoreCase = true) ||
                            game.getTitle().equals(query, ignoreCase = true) ||
                            File(Uri.decode(game.getPath())).name.equals(query, ignoreCase = true)
                    }
                    match?.getPath() ?: query
                }
            }.toTypedArray()
        }

        if (resolvedMoviePath != null) {
            val movieGameId = NativeLibrary.GetMovieGameID(resolvedMoviePath)
            if (!movieGameId.isNullOrEmpty()) {
                val match = allGames.firstOrNull {
                    it.getGameId().equals(movieGameId, ignoreCase = true)
                }
                if (match != null) {
                    val secondDisc = findSecondDisc(match, allGames)
                    return if (secondDisc == null) {
                        arrayOf(match.getPath())
                    } else {
                        arrayOf(match.getPath(), secondDisc.getPath())
                    }
                }
            }
            val defaultIso = StringSetting.MAIN_DEFAULT_ISO.string
            if (defaultIso.isNotEmpty()) {
                return arrayOf(defaultIso)
            }
        }

        return filePaths
    }

    private fun findSecondDisc(game: GameFile, allGames: Array<GameFile>): GameFile? {
        var matchWithoutRevision: GameFile? = null
        for (otherGame in allGames) {
            if (game.getGameId() == otherGame.getGameId() && game.getDiscNumber() != otherGame.getDiscNumber()) {
                if (game.getRevision() == otherGame.getRevision()) {
                    return otherGame
                } else {
                    matchWithoutRevision = otherGame
                }
            }
        }
        return matchWithoutRevision
    }

    /**
     * Converts a URI returned by the system file picker into a path the core can open.
     *
     * Files served by Dolphin's own DocumentProvider are mapped straight back to their real path,
     * so the companion .dtm.sav stays alongside. Anything else is copied into the cache directory.
     * We never copy over the source file: opening the destination for writing would truncate it
     * before it has been read, destroying the recording.
     */
    fun resolveMovieUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }

        resolveOwnDocumentUri(context, uri)?.let { return it.absolutePath }

        return try {
            var fileName = "movie.dtm"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrEmpty()) {
                        fileName = File(name).name
                    }
                }
            }

            val importDir = File(context.cacheDir, "movies").apply { mkdirs() }
            val destFile = File(importDir, fileName)
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use {
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.error("[MovieUtils] Failed to resolve or copy movie URI: ${e.message}")
            null
        }
    }

    private fun resolveOwnDocumentUri(context: Context, uri: Uri): File? {
        if (uri.authority != "${context.packageName}.user") {
            return null
        }
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val prefix = "${DocumentProvider.ROOT_ID}/"
            if (!documentId.startsWith(prefix)) {
                return null
            }
            val userDir = File(NativeLibrary.GetUserDirectory()).canonicalFile
            val file = File(userDir, documentId.removePrefix(prefix)).canonicalFile
            if (file.isFile && file.path.startsWith(userDir.path + File.separator)) file else null
        } catch (e: Exception) {
            Log.error("[MovieUtils] Failed to resolve document URI: ${e.message}")
            null
        }
    }
}
