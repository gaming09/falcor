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
     * Best-effort PTZ move. Older/community docs expose GET /api/{camera}/ptz/{command}
     * with commands like MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN, ZOOM_IN, ZOOM_OUT, STOP.
     * Frigate primarily documents MQTT PTZ; this HTTP route is attempted and failures are ignored gracefully.
     */
    @GET("{camera}/ptz/{command}")
    suspend fun ptzCommand(
        @Path("camera") camera: String,
        @Path("command") command: String
    ): Response<GenericSuccess>

    @GET("go2rtc/streams")
    suspend fun go2rtcStreams(): Response<okhttp3.ResponseBody>
}
