package com.example.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Long,
    val username: String,
    val email: String?,
    val avatar: String?,
    val info: String?,
    val dateOfBirth: String?,
    val language: String?,
    val isOnline: Boolean?,
    val twoFactorEnabled: Boolean?,
    val lastOnline: String?,
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): User {
        return User(
            id = id,
            username = username,
            email = email,
            avatar = avatar,
            info = info,
            dateOfBirth = dateOfBirth,
            language = language,
            isOnline = isOnline,
            twoFactorEnabled = twoFactorEnabled,
            lastOnline = lastOnline
        )
    }

    companion object {
        fun fromDomain(user: User): UserEntity {
            return UserEntity(
                id = user.id,
                username = user.username,
                email = user.email,
                avatar = user.avatar,
                info = user.info,
                dateOfBirth = user.dateOfBirth,
                language = user.language,
                isOnline = user.isOnline,
                twoFactorEnabled = user.twoFactorEnabled,
                lastOnline = user.lastOnline
            )
        }
    }
}
