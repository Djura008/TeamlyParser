import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.*
import org.example.dataDirectory
import java.io.File
import java.util.concurrent.TimeUnit

data class DeferredPayload(
    val file_url: String?,
    val file_name: String,
    val type: String,
    val total: Int,
    val successed_total: Int,
    val status_files: Map<String, StatusFile>
)

data class StatusFile(
    val id: String,
    val title: String?,
    val status: String,
    val error_message: String? = null
)

data class DeferredStatus(
    val status: String,
    val payload: DeferredPayload?
)

val errorLog = File("$dataDirectory/error.log")

val client = OkHttpClient()
val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()
val adapter: JsonAdapter<DeferredStatus> = moshi.adapter(DeferredStatus::class.java)

fun pollUntilDone(
    token: String,
    authTokens: AuthTokens,
    maxAttempts: Int = 60
): String {
    var attempts = 0
    var articleTag = ""
    while (attempts < maxAttempts) {
        val request = Request.Builder()
            .url("https://app2.teamly.ru/api/v1/deferred_result/status?token=$token")
            .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Content-Type", "application/json")
            .header("X-Account-Slug", "hr-link")
            .header("Origin", "https://hr-link.teamly.ru")
            .header("Connection", "keep-alive")
            .header("Referer", "https://hr-link.teamly.ru/")
            .header(
                "Cookie",
                "s2_access_token=${authTokens.s2_access_token}; s2_refresh_token=${authTokens.s2_refresh_token}; s2_access_token_expires_at=${authTokens.s2_access_token_expires_at}; s2_refresh_token_expires_at=${authTokens.s2_refresh_token_expires_at}; s4_access_token=${authTokens.s4_access_token}; s4_refresh_token=${authTokens.s4_refresh_token}; s4_access_token_expires_at=${authTokens.s4_access_token_expires_at}; s4_refresh_token_expires_at=${authTokens.s4_refresh_token_expires_at}; s5_access_token=${authTokens.s5_access_token}; s5_refresh_token=${authTokens.s5_refresh_token}; s5_access_token_expires_at=${authTokens.s5_access_token_expires_at}; s5_refresh_token_expires_at=${authTokens.s5_refresh_token_expires_at}; s1_access_token=${authTokens.s1_access_token}; s1_refresh_token=${authTokens.s1_refresh_token}; s1_access_token_expires_at=${authTokens.s1_access_token_expires_at}; s1_refresh_token_expires_at=${authTokens.s1_refresh_token_expires_at}"
            )
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-site")
            .header("Priority", "u=0")
            .header("TE", "trailers")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("Ошибка: ${response.code}")
                if (!fileLog.exists()) {fileLog.createNewFile() }
                fileLog.appendText("Ошибка: ${response.code}\n")
                return ""
            }

            val body = response.body?.string()
            println("Ответ: $body")
            if (!fileLog.exists()) {fileLog.createNewFile() }
            fileLog.appendText("Ответ: $body\n")

            val statusResponse = adapter.fromJson(body!!)

            if (statusResponse?.status == "error") {
                val errors = statusResponse.payload?.status_files?.values?.mapNotNull {
                    if (it.status == "error") {
                        "${it.title}: ${it.error_message ?: "Неизвестная ошибка"}"
                    } else null
                } ?: listOf("Неизвестная ошибка экспорта")
                if (!errorLog.exists()) errorLog.createNewFile()
                errorLog.appendText("Article: $articleTag - Ошибка при экспорте:\n" + errors.joinToString("\n") + "\n")
                println("Ошибка при экспорте:" + errors.joinToString("\n") + "\n")
                return ""
            }
            if (statusResponse?.status == "done") {
                val payload = statusResponse.payload
                if (payload?.file_url != null && payload.file_url != "") {
                    println("Файл: ${payload.file_name}. Ссылка: ${payload.file_url}")
                    if (!fileLog.exists()) {fileLog.createNewFile() }
                    fileLog.appendText("Файл: ${payload.file_name}. Ссылка: ${payload.file_url}\n")
                    return payload.file_url
                } else {
                    println("Статус 'done', но file_url отсутствует. Повтор через 1 сек.")
                    if (!fileLog.exists()) {fileLog.createNewFile() }
                    fileLog.appendText("Статус 'done', но file_url отсутствует. Повтор через 1 сек.\n")
                    articleTag = payload?.file_name ?: "unknown"
                }
            }
        }
        attempts++
        TimeUnit.SECONDS.sleep(1)
    }

    if (!errorLog.exists()) errorLog.createNewFile()
    println("Превышено количество попыток ожидания file_url.")
    errorLog.appendText("Article: $articleTag - Превышено количество попыток на скачивание. Попробуйте скачать вручную позже\n")
    if (!fileLog.exists()) fileLog.createNewFile()
    fileLog.appendText("Article: $articleTag - Превышено количество попыток на скачивание. Попробуйте скачать вручную позже\n")
    return ""
}