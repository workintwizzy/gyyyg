package ru.dmitriy.workouts.core

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.dmitriy.workouts.data.local.WorkoutDatabase
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class) object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context:Context):WorkoutDatabase = Room.databaseBuilder(context,WorkoutDatabase::class.java,"workouts.db").build()
}
