package com.memorypalace.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CardEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    // TODO(persistence/P2-中危): 现在是 version=1 + exportSchema=false + 零迁移。
    //  给 CardEntity 加一列时，老用户升级会 `A migration from 1 to 2 was not provided` 启动即崩。
    //  正确修法（需要改 build.gradle，故本次不改，保持现状不引入风险）：
    //    1. 把 exportSchema 改 true + room { schemaDirectory("$projectDir/schemas") }，先提交 v1 schema JSON；
    //    2. 每次升版本必须随附 Migration(old,new)，并用 MigrationTestHelper 写迁移测试；
    //    3. 禁止 fallbackToDestructiveMigration —— 它会静默清空用户全部卡片，本 app 不可接受。
    //  仅注释、不动 version/构建配置，避免本批次改动触发升级崩溃或数据丢失。
}
