package com.boji.app.assistant

import com.boji.app.model.AppState
import com.boji.app.model.DraftBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDate

data class AssistantReply(val text: String, val draft: DraftBatch? = null, val success: Boolean = true)

/** The assistant only prepares drafts. Saving always belongs to the confirmation UI. */
class AssistantClient internal constructor(
    private val transport: AssistantTransport,
    private val today: () -> LocalDate,
) {
    constructor() : this(UrlConnectionTransport(), { LocalDate.now() })

    suspend fun respond(
        text: String,
        state: AppState,
        image: ByteArray? = null,
        mimeType: String = "image/jpeg",
    ): AssistantReply {
        if (text.length > 8_000) return AssistantReply("内容过长，请分次发送。",success=false)
        if (image != null && (image.isEmpty() || image.size > MAX_IMAGE_BYTES)) {
            return AssistantReply("图片需小于 5 MB，请重新选择。",success=false)
        }
        if (image != null && mimeType !in IMAGE_TYPES) return AssistantReply("请选择 JPG、PNG 或 WebP 图片。",success=false)
        if (text.isBlank() && image == null) return AssistantReply("写下要记录或安排的事。")
        if (!state.settings.enabled) {
            if (image != null) return AssistantReply("图片识别需要先在设置中启用支持图片的模型；文字仍可本地整理。",success=false)
            return LocalDraftParser.reply(text, state, today())
        }
        val connection = state.settings.connections.getOrNull(state.settings.activeIndex)
            ?: return AssistantReply("请选择一个模型连接。",success=false)
        val endpoint = normalizeEndpoint(connection.endpoint)
            ?: return AssistantReply("模型地址需为 HTTPS，例如 https://example.com/v1。",success=false)
        if (connection.apiKey.isBlank() || connection.apiKey.length > 4096 ||
            connection.apiKey.any { it == '\r' || it == '\n' }) {
            return AssistantReply("请检查模型密钥。",success=false)
        }
        if (connection.model.isBlank() || connection.model.length > 200) return AssistantReply("请填写模型名称。",success=false)
        if (image != null && !connection.supportsImages) return AssistantReply("当前模型未开启图片支持，请更换模型或移除图片。",success=false)
        return try {
            currentCoroutineContext().ensureActive()
            val body = AssistantProtocol.request(text, state, today(), image, mimeType)
            val response = transport.post(endpoint, connection.apiKey.trim(), body)
            currentCoroutineContext().ensureActive()
            AssistantProtocol.response(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AssistantFailure) {
            AssistantReply(failure.safeMessage,success=false)
        } catch (_: Exception) {
            // Provider errors can contain echoed credentials, endpoint parameters, or request data.
            AssistantReply("连接失败，请检查网络和模型设置后重试。",success=false)
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        internal fun normalizeEndpoint(value: String): String? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            val base = value.trim().trimEnd('/')
            when {
                uri.path.trimEnd('/').endsWith("/chat/completions") -> base
                uri.path.isEmpty() || uri.path == "/" -> "$base/v1/chat/completions"
                else -> "$base/chat/completions"
            }
        }.getOrNull()
    }
}

internal fun interface AssistantTransport {
    suspend fun post(endpoint: String, apiKey: String, body: String): String
}

internal class AssistantFailure(val safeMessage: String) : IOException()

internal class UrlConnectionTransport : AssistantTransport {
    override suspend fun post(endpoint: String, apiKey: String, body: String): String = suspendCancellableCoroutine { continuation ->
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = request(connection,apiKey,body)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { job.cancel(); connection.disconnect() }
    }

    private suspend fun request(connection: HttpURLConnection, apiKey: String, body: String): String {
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            currentCoroutineContext().ensureActive()
            connection.outputStream.use { it.write(bytes) }
            currentCoroutineContext().ensureActive()
            when (connection.responseCode) {
                in 200..299 -> Unit
                401, 403 -> throw AssistantFailure("模型认证失败，请检查密钥和权限。")
                429 -> throw AssistantFailure("模型请求过多或额度不足，请稍后重试。")
                in 300..399 -> throw AssistantFailure("模型地址发生跳转，请填写最终 HTTPS 地址。")
                else -> throw AssistantFailure("模型暂时无法响应，请检查模型名称后重试。")
            }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 1024 * 1024) throw AssistantFailure("模型回复过长，请缩小问题后重试。")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }
}
