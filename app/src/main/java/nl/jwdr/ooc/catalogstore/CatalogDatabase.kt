package nl.jwdr.ooc.catalogstore

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CatalogEntity::class, EcuEntity::class, CatalogFileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CatalogDatabase : RoomDatabase() {

    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile
        private var instance: CatalogDatabase? = null

        fun get(context: Context): CatalogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CatalogDatabase::class.java,
                    "catalog.db",
                ).build().also { instance = it }
            }
    }
}
