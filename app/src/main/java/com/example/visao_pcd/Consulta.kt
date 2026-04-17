package com.example.visao_pcd

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Database
import androidx.room.RoomDatabase
import android.content.Context
import androidx.room.Room

@Entity(tableName = "historico_consultas")
data class Consulta(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val data: Long = System.currentTimeMillis(),
    val modo: String,
    val resultado: String
)

@Dao
interface ConsultaDao {
    @Insert
    @JvmSuppressWildcards
    suspend fun salvar(consulta: Consulta): Long

    @Query("SELECT * FROM historico_consultas ORDER BY data DESC LIMIT 50")
    @JvmSuppressWildcards
    suspend fun listarUltimas(): List<Consulta>

    @Query("DELETE FROM historico_consultas")
    fun limparTudo(): Int
}

@Database(entities = [Consulta::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun consultaDao(): ConsultaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "visao_assertiva_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
