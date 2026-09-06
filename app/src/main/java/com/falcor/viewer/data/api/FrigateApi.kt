package com.falcor.viewer.data.api

import com.falcor.viewer.data.model.CameraSetBody
import com.falcor.viewer.data.model.FrigateConfig
import com.falcor.viewer.data.model.FrigateEvent
import com.falcor.viewer.data.model.GenericSuccess
import com.falcor.viewer.data.model.PtzInfo
import com.falcor.viewer.data.model.RecordingSegment
import com.falcor.viewer.data.model.RecordingSummaryDay
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Frigate HTTP API (paths relative to /api/).
 * See https://docs.frigate.video/integrations/api/frigate-http-api
 */
interface FrigateApi {

    @GET("config")
    suspend fun getConfig(): FrigateConfig

    /**
     * Runtime camera feature toggle (Frigate 0.14+).
     * PUT /api/camera/{camera}/set/enabled  body: {"value":"ON"|"OFF"}
     */
    @PUT("camera/{camera}/set/{feature}")
    suspend fun setCameraFeature(
        @Path("camera") camera: String,
        @Path("feature") feature: String,
        @Body body: CameraSetBody
    ): Response<GenericSuccess>

    @GET("events")
    suspend fun getEvents(
        @Query("cameras") cameras: String? = null,
        @Query("labels") labels: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("has_clip") hasClip: Int? = null,
        @Query("has_snapshot") hasSnapshot: Int? = null,
        @Query("after") after: Double? = null,
        @Query("before") before: Double? = null
    ): List<FrigateEvent>

    @GET("events/{eventId}")
    suspend fun getEvent(@Path("eventId") eventId: String): FrigateEvent

    @GET("{camera}/recordings")
    suspend fun getRecordings(
        @Path("camera") camera: String,
        @Query("after") after: Double,
        @Query("before") before: Double
    ): List<RecordingSegment>

    @GET("{camera}/recordings/summary")
    suspend fun getRecordingsSummary(
        @Path("camera") camera: String,
        @Query("timezone") timezone: String? = null
    ): List<RecordingSummaryDay>

    @GET("{camera}/ptz/info")
    suspend fun getPtzInfo(@Path("camera") camera: String): PtzInfo

    /**
     * Legacy HTTP PTZ move (last-resort fallback only).
     * Primary PTZ path is Frigate WebSocket: topic "{camera}/ptz", payload MOVE_* / STOP / ZOOM_* .
     * Prefer [com.falcor.viewer.data.ws.FrigateWsClient].
     */
    @GET("{camera}/ptz/{command}")
    suspend fun ptzCommand(
        @Path("camera") camera: String,
        @Path("command") command: String
    ): Response<GenericSuccess>

    @GET("go2rtc/streams")
    suspend fun go2rtcStreams(): Response<okhttp3.ResponseBody>
}
