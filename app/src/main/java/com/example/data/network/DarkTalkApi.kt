package com.example.data.network

import com.example.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface DarkTalkApi {

    // Ping / Health Check
    @GET("ping/")
    suspend fun ping(): Response<PingResponse>

    // Account API
    @POST("account/api/auth/register/")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("account/api/auth/login/")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("account/api/auth/2fa/verify/")
    suspend fun verify2Fa(@Body request: TwoFactorVerifyRequest): Response<AuthResponse>

    @POST("account/api/auth/2fa/resend/")
    suspend fun resend2Fa(@Body request: TwoFactorResendRequest): Response<AuthResponse>

    @GET("account/api/2fa/")
    suspend fun get2FaState(): Response<TwoFactorStateResponse>

    @PATCH("account/api/2fa/")
    suspend fun toggle2Fa(@Body request: TwoFactorToggleRequest): Response<TwoFactorStateResponse>

    @POST("account/api/auth/logout/")
    suspend fun logout(@Body request: LogoutRequest): Response<Unit>

    @GET("account/api/profile/")
    suspend fun getProfile(): Response<ProfileResponse>

    @PATCH("account/api/profile/")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<ProfileResponse>

    @Multipart
    @PATCH("account/api/profile/")
    suspend fun updateProfileWithAvatar(
        @Part("username") username: RequestBody? = null,
        @Part("info") info: RequestBody? = null,
        @Part("language") language: RequestBody? = null,
        @Part("date_of_birth") dateOfBirth: RequestBody? = null,
        @Part("avatar_access") avatarAccess: RequestBody? = null,
        @Part avatar: MultipartBody.Part? = null
    ): Response<ProfileResponse>

    @GET("account/api/users/search/")
    suspend fun searchUsers(@Query("username") username: String): Response<UserSearchResponse>

    @GET("account/api/devices/")
    suspend fun getDevices(): Response<DevicesListResponse>

    @GET("account/api/login-history/")
    suspend fun getLoginHistory(): Response<LoginHistoryResponse>

    // Chat API
    @GET("chat/api/chats/")
    suspend fun getChats(): Response<ChatsListResponse>

    @POST("chat/api/chats/create/")
    suspend fun createDirectChat(@Body request: CreateDirectChatRequest): Response<CreateChatResponse>

    @POST("chat/api/chats/create/")
    suspend fun createGroupChat(@Body request: CreateGroupChatRequest): Response<CreateChatResponse>

    @GET("chat/api/chats/{chat_id}/")
    suspend fun getChatDetails(@Path("chat_id") chatId: Long): Response<Chat>

    @DELETE("chat/api/chats/{chat_id}/")
    suspend fun deleteChat(@Path("chat_id") chatId: Long): Response<Unit>

    @GET("chat/api/chats/{chat_id}/messages/")
    suspend fun getMessages(
        @Path("chat_id") chatId: Long,
        @Query("limit") limit: Int = 50,
        @Query("before_id") beforeId: Long? = null
    ): Response<MessagesPageResponse>

    @POST("chat/api/chats/{chat_id}/read/")
    suspend fun markChatAsRead(
        @Path("chat_id") chatId: Long,
        @Body request: ReadChatRequest = ReadChatRequest()
    ): Response<Map<String, Any?>>

    @POST("chat/api/messages/create/")
    suspend fun createTextMessage(@Body request: CreateMessageRequest): Response<CreateMessageResponse>

    @Multipart
    @POST("chat/api/messages/create/")
    suspend fun createAttachmentMessage(
        @Part("chat_id") chatId: RequestBody,
        @Part("text") text: RequestBody?,
        @Part("message_type") messageType: RequestBody,
        @Part("reply_to") replyTo: RequestBody?,
        @Part attachment: MultipartBody.Part
    ): Response<CreateMessageResponse>

    @PATCH("chat/api/messages/{message_id}/")
    suspend fun editMessage(
        @Path("message_id") messageId: Long,
        @Body request: EditMessageRequest
    ): Response<Message>

    @DELETE("chat/api/messages/{message_id}/")
    suspend fun deleteMessage(@Path("message_id") messageId: Long): Response<Map<String, Any?>>

    @POST("chat/api/messages/read/{message_id}/")
    suspend fun markMessageAsRead(@Path("message_id") messageId: Long): Response<Map<String, Any?>>

    @POST("chat/api/messages/reaction/{message_id}/")
    suspend fun addReaction(
        @Path("message_id") messageId: Long,
        @Body request: ReactionRequest
    ): Response<ReactionResponse>

    @DELETE("chat/api/messages/reaction/{message_id}/")
    suspend fun removeReaction(
        @Path("message_id") messageId: Long,
        @Query("emoji") emoji: String
    ): Response<Map<String, Any?>>

    @POST("chat/api/chats/{chat_id}/participants/")
    suspend fun addParticipants(
        @Path("chat_id") chatId: Long,
        @Body request: AddParticipantsRequest
    ): Response<Map<String, Any?>>

    @DELETE("chat/api/chats/{chat_id}/participants/{user_id}/")
    suspend fun leaveOrRemoveParticipant(
        @Path("chat_id") chatId: Long,
        @Path("user_id") userId: Long
    ): Response<GenericStatusResponse>
}
