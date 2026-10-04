package com.prism.music.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val artistId: String?,
    val albumTitle: String?,
    val albumId: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val isVideo: Boolean = false,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val lastPlayed: Long = 0,
    val smartDownload: Boolean = false,
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artists = artistName.split(", ").filter { it.isNotBlank() }.mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) },
        album = albumTitle?.let { AlbumRef(it, albumId) },
        durationSec = durationSec,
        thumbnail = thumbnail,
        isVideo = isVideo,
    )
}

fun Song.toEntity(base: SongEntity? = null) = SongEntity(
    id = id,
    title = title,
    artistName = artistText,
    artistId = artists.firstOrNull()?.id,
    albumTitle = album?.title,
    albumId = album?.id,
    durationSec = durationSec.takeIf { it > 0 } ?: (base?.durationSec ?: 0),
    thumbnail = thumbnail ?: base?.thumbnail,
    isVideo = isVideo,
    liked = base?.liked ?: false,
    likedAt = base?.likedAt ?: 0,
    lastPlayed = base?.lastPlayed ?: 0,
    smartDownload = base?.smartDownload ?: false,
)

@Entity(tableName = "play_events", indices = [Index("songId"), Index("timestamp")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val timestamp: Long,
    val playedMs: Long,
)

@Entity(tableName = "lyrics", primaryKeys = ["songId", "source"])
data class LyricsEntity(
    val songId: String,
    val source: String,
    val synced: Boolean,
    val content: String,
    val fetchedAt: Long,
)

@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val key: String, // "song:<id>", "album:<title|artist>", "artist:<name>"
    val genre: String?,
    val fetchedAt: Long,
)

@Entity(tableName = "motion_art")
data class MotionArtEntity(
    @PrimaryKey val key: String,
    val tallUrl: String?,
    val squareUrl: String?,
    val fetchedAt: Long,
)

data class SongPlays(
    val songId: String,
    val plays: Int,
    val ms: Long,
    val title: String,
    val artistName: String,
    val thumbnail: String?,
    val albumTitle: String?,
    val durationSec: Int,
    val artistId: String?,
)

data class ArtistPlays(val artistName: String, val plays: Int, val ms: Long, val thumbnail: String?, val artistId: String?)
data class MonthTotal(val month: String, val ms: Long)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<SongEntity>

    @Upsert
    suspend fun upsert(song: SongEntity)

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    fun likedFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    suspend fun liked(): List<SongEntity>

    @Query("SELECT id FROM songs WHERE liked = 1")
    fun likedIdsFlow(): Flow<List<String>>

    @Query("UPDATE songs SET liked = :liked, likedAt = :at WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, at: Long)

    @Query("UPDATE songs SET lastPlayed = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("SELECT * FROM songs WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT :limit")
    fun recentFlow(limit: Int = 30): Flow<List<SongEntity>>

    @Query("UPDATE songs SET smartDownload = :v WHERE id = :id")
    suspend fun setSmart(id: String, v: Boolean)

    @Query("SELECT * FROM songs WHERE smartDownload = 1")
    suspend fun smartDownloads(): List<SongEntity>
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEventEntity)

    @Query("SELECT * FROM play_events WHERE timestamp > :since")
    suspend fun eventsSince(since: Long): List<PlayEventEntity>

    @Query("SELECT COALESCE(SUM(playedMs),0) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun totalMs(from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun count(from: Long, to: Long): Int

    @Query(
        """SELECT e.songId AS songId, COUNT(*) AS plays, SUM(e.playedMs) AS ms, s.title AS title,
           s.artistName AS artistName, s.thumbnail AS thumbnail, s.albumTitle AS albumTitle,
           s.durationSec AS durationSec, s.artistId AS artistId
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to
           GROUP BY e.songId ORDER BY ms DESC, plays DESC LIMIT :limit"""
    )
    suspend fun topSongs(from: Long, to: Long, limit: Int): List<SongPlays>

    @Query(
        """SELECT s.artistName AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to
           GROUP BY s.artistName ORDER BY ms DESC LIMIT :limit"""
    )
    suspend fun topArtists(from: Long, to: Long, limit: Int): List<ArtistPlays>

    @Query(
        """SELECT strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime') AS month, SUM(playedMs) AS ms
           FROM play_events WHERE timestamp BETWEEN :from AND :to GROUP BY month ORDER BY month"""
    )
    suspend fun monthly(from: Long, to: Long): List<MonthTotal>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime')) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun activeDays(from: Long, to: Long): Int

    @Query("SELECT MIN(timestamp) FROM play_events")
    suspend fun firstEvent(): Long?

    @Query("SELECT * FROM play_events WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun events(from: Long, to: Long): List<PlayEventEntity>

    @Query("SELECT COUNT(DISTINCT songId) FROM play_events WHERE timestamp BETWEEN :from AND :to")
    suspend fun songCount(from: Long, to: Long): Int

    @Query("SELECT COUNT(DISTINCT s.artistName) FROM play_events e JOIN songs s ON s.id = e.songId WHERE e.timestamp BETWEEN :from AND :to")
    suspend fun artistCount(from: Long, to: Long): Int

    /** Artists first heard between [from] and [to], with their listening in that time, most listened first. */
    @Query(
        """SELECT s.artistName AS artistName, COUNT(*) AS plays, SUM(e.playedMs) AS ms,
           MAX(s.thumbnail) AS thumbnail, MAX(s.artistId) AS artistId
           FROM play_events e JOIN songs s ON s.id = e.songId
           WHERE e.timestamp BETWEEN :from AND :to AND s.artistName IN (
               SELECT s2.artistName FROM play_events e2 JOIN songs s2 ON s2.id = e2.songId
               GROUP BY s2.artistName HAVING MIN(e2.timestamp) BETWEEN :from AND :to)
           GROUP BY s.artistName ORDER BY ms DESC"""
    )
    suspend fun newArtists(from: Long, to: Long): List<ArtistPlays>

    @Query("SELECT songId FROM play_events WHERE timestamp > :since GROUP BY songId ORDER BY COUNT(*) DESC LIMIT :limit")
    suspend fun mostPlayedIds(since: Long, limit: Int): List<String>
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId AND source = :source")
    suspend fun get(songId: String, source: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE songId = :songId AND content != ''")
    suspend fun allFor(songId: String): List<LyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: LyricsEntity)

    /** Forgets cached "not found" answers so they're looked up again. */
    @Query("DELETE FROM lyrics WHERE content = ''")
    suspend fun clearMisses()
}

@Dao
interface MetaDao {
    @Query("SELECT * FROM genres WHERE `key` = :key")
    suspend fun genre(key: String): GenreEntity?

    @Query("SELECT * FROM genres WHERE `key` IN (:keys)")
    suspend fun genres(keys: List<String>): List<GenreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGenre(e: GenreEntity)

    @Query("SELECT * FROM motion_art WHERE `key` = :key")
    suspend fun motion(key: String): MotionArtEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMotion(e: MotionArtEntity)
}

@Database(
    entities = [SongEntity::class, PlayEventEntity::class, LyricsEntity::class, GenreEntity::class, MotionArtEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PrismDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun plays(): PlayEventDao
    abstract fun lyrics(): LyricsDao
    abstract fun meta(): MetaDao

    companion object {
        fun build(context: Context): PrismDatabase =
            Room.databaseBuilder(context, PrismDatabase::class.java, "prism.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
