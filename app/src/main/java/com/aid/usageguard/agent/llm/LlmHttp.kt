package com.aid.usageguard.agent.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LLM 请求的公共 HTTP 层：OkHttp 4.12 + 协程可取消。
 *
 * 用 enqueue + suspendCancellableCoroutine 而不是阻塞 execute，
 * 这样 Agent 任务被取消（用户离开助手页 / ViewModel 清理）时会真正 cancel 掉 OkHttp Call，
 * 不会留下悬挂的读线程。
 */
internal object LlmHttp {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String = suspendCancellableCoroutine { continuation ->
        val request = try {
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .build()
        } catch (e: Exception) {
            continuation.resumeWithException(LlmException("接口地址无效：$url", e))
            return@suspendCancellableCoroutine
        }

        val call: Call = client.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            LlmException("网络请求失败：${e.message ?: e.javaClass.simpleName}", e),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                        if (!resp.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    LlmException("HTTP ${resp.code}：${text.take(300).replace('\n', ' ')}"),
                                )
                            }
                        } else if (continuation.isActive) {
                            continuation.resume(text)
                        }
                    }
                }
            },
        )
    }
}
