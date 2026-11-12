package com.chessvision.ai.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val white: String,
    val black: String,
    val result: String,
    val pgn: String,
    /** practice | import | screenshot | analysis */
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val accuracyWhite: Double? = null,
    val accuracyBlack: Double? = null,
    /** One line per ply: "<class>|<evalSortKey>|<bestUci>|<bestSan>" */
    val reviewData: String? = null,
)

@Entity(tableName = "mistakes", indices = [Index("gameId")])
data class MistakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val fen: String,
    val bestUci: String,
    val bestSan: String,
    val playedSan: String,
    val classification: String,
    val solvedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "puzzle_stats")
data class PuzzleStatEntity(
    @PrimaryKey val puzzleId: String,
    val solved: Boolean,
    val attempts: Int,
    val lastTs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "learned_samples")
data class LearnedSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,
    val features: ByteArray,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun floats(): FloatArray {
        val bb = ByteBuffer.wrap(features).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(features.size / 4) { bb.getFloat() }
    }

    companion object {
        fun bytes(f: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            f.forEach { bb.putFloat(it) }
            return bb.array()
        }
    }
}

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY createdAt DESC")
    fun all(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: Long): GameEntity?

    @Insert
    suspend fun insert(g: GameEntity): Long

    @Update
    suspend fun update(g: GameEntity)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MistakeDao {
    @Query("SELECT * FROM mistakes ORDER BY solvedCount ASC, createdAt DESC")
    suspend fun all(): List<MistakeEntity>

    @Query("SELECT COUNT(*) FROM mistakes")
    fun count(): Flow<Int>

    @Insert
    suspend fun insertAll(m: List<MistakeEntity>)

    @Query("DELETE FROM mistakes WHERE gameId = :gameId")
    suspend fun deleteForGame(gameId: Long)

    @Query("UPDATE mistakes SET solvedCount = solvedCount + 1 WHERE id = :id")
    suspend fun markSolved(id: Long)
}

@Dao
interface PuzzleStatDao {
    @Query("SELECT * FROM puzzle_stats")
    suspend fun all(): List<PuzzleStatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: PuzzleStatEntity)

    @Query("SELECT COUNT(*) FROM puzzle_stats WHERE solved = 1")
    fun solvedCount(): Flow<Int>
}

@Dao
interface SampleDao {
    @Query("SELECT * FROM learned_samples ORDER BY createdAt DESC LIMIT 3000")
    suspend fun all(): List<LearnedSampleEntity>

    @Insert
    suspend fun insertAll(s: List<LearnedSampleEntity>)

    @Query("SELECT COUNT(*) FROM learned_samples")
    fun count(): Flow<Int>

    @Query("DELETE FROM learned_samples")
    suspend fun clear()
}

@Database(
    entities = [GameEntity::class, MistakeEntity::class, PuzzleStatEntity::class, LearnedSampleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun mistakes(): MistakeDao
    abstract fun puzzleStats(): PuzzleStatDao
    abstract fun samples(): SampleDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "chessvision.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
