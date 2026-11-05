package com.aid.usageguard.agent.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    /**
     * 流式 POST（SSE）：逐行回调原始行，由客户端解析 `data:` / `event:`。
     *
     * 与 [postJson] 的 enqueue 不同，这里用阻塞 execute + 协程取消回调：
     * 协程被取消时 `call.cancel()` 会中断读取，不会留下悬挂线程。
     *
     * 错误分级（供上层决定是否允许降级为非流式）：
     * - 还没读到任何一行就失败（建连失败 / HTTP 非 2xx / 空流）→ [LlmStreamUnavailableException]；
     * - 已经收到部分内容后断线 → [LlmException]（禁止自动重放整项任务）；
     * - 协程取消 → [CancellationException] 原样上抛。
     */
    suspend fun postJsonStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .build()
        } catch (e: Exception) {
            throw LlmStreamUnavailableException("接口地址无效：$url", e)
        }

        val call: Call = client.newCall(request)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
        var receivedAnyLine = false
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = runCatching { response.body?.string() ?: "" }.getOrDefault("")
                    throw LlmStreamUnavailableException(
                        "HTTP ${response.code}：${text.take(300).replace('\n', ' ')}",
                    )
                }
                val responseBody = response.body ?: throw LlmStreamUnavailableException("流式响应为空")
                val source = responseBody.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    receivedAnyLine = true
                    onLine(line)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // 若是协程被取消（含 withTimeout 超时），抛出原始的 CancellationException，
            // 让上层能正确区分「用户取消」与「超时/断流」。
            currentCoroutineContext().ensureActive()
            if (!receivedAnyLine) {
                throw LlmStreamUnavailableException(
                    "建立流式连接失败：${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
            throw LlmException("流式请求中断（已接收部分内容）：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            handle?.dispose()
        }
    }
}
