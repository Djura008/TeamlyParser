package org.example

import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.Keys
import java.time.Duration
import java.io.File
import parseArticles
import printTree
import ArticleNode
import kotlin.error
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pollUntilDone
import flattenToMap
import getFullPath
import AuthTokens
import fileLog
import io.github.cdimascio.dotenv.dotenv


// env
val dotenv = dotenv {
    ignoreIfMissing = true
}

val myUsername = dotenv["TEAMLY_USERNAME"]
val myPassword = dotenv["TEAMLY_PASSWORD"]
val dataDirectory = dotenv["DATA_DIRECTORY"]

fun main() {

    val loginPage = "https://hr-link.teamly.ru/old/auth" //Страница авторизации
    val projectId = "3e87be08-f5bf-5905-885b-f068a1d9a80b" //ID проекта из URL
    val projectPage = "https://hr-link.teamly.ru/old/space/$projectId" //Страница проекта

    // Вручную прописываем драйвер для Selenium
    System.setProperty("webdriver.gecko.driver", "/usr/bin/geckodriver")
    val options = FirefoxOptions()
    val driver = FirefoxDriver(options)

    //Аутентификация
    login(driver, loginPage)
  
    //Переходим на корневую страницу пространства
    navigateAndWaitFor(driver, projectPage, "div.article-content-tree.article-detail-sidebar__tree")

    //Раскрываем все элементы структуры статей
    expandAllTreeNodes(driver)

    //Парсинг дерева статей
    val nodes = printAllTreeNodes(driver.pageSource)
 
    //Скачка всех статей
    val articleUrls = nodes.keys.map {"https://.../article/$it"}
    articleUrls.forEach {
        downloadCurrentArticle(driver, projectId, it, nodes)
        Thread.sleep(500)
    }

    driver.close()
}

fun login(driver: FirefoxDriver, loginPage: String) {
    
    // заходим на страницу с логином
    driver.get(loginPage)

    //Логинимся
    val wait = WebDriverWait(driver, Duration.ofSeconds(20))
    val login = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("username")))
    login.clear()
    login.sendKeys(myUsername)
    val password = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("password")))
    password.clear()
    password.sendKeys(myPassword)
    password.sendKeys(Keys.ENTER)
    
    //Ожидаем авторизации
    wait.until(ExpectedConditions.urlToBe("https://hr-link.teamly.ru/home/my"))
    //Закрываем всплывающее окно
    val closeButton = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("button.popup__close")))
    closeButton.click()
    //Ожидаем прогрузки страницы
    wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("a.article-item__link")))
}

fun navigateAndWaitFor(driver: FirefoxDriver, url: String, waitingElement: String) {
    driver.get(url)
    val wait = WebDriverWait(driver, Duration.ofSeconds(20))
    wait.until(ExpectedConditions.presenceOfElementLocated((By.cssSelector(waitingElement))))
}

fun expandAllTreeNodes(driver: FirefoxDriver, timeForLoading: Long = 150) {
    var expanded = true
    
    while (expanded) {
        expanded = false

        val buttons = driver.findElements(
            By.cssSelector("button.tree-item__main-button.expand:not(.expanded)")
        )

        for (btn in buttons) {
            try {
                if (btn.isDisplayed && btn.isEnabled) {
                    btn.click()
                    expanded = true
                    Thread.sleep(timeForLoading) // время на подгрузку новых элементов
                }
            } catch (e: Exception) {}
        }
    }
}

fun printAllTreeNodes(pathToFile: String): Map<String, ArticleNode> {
    val roots = parseArticles(pathToFile)
    val nodesById = roots.flattenToMap()
    //До ввода nodesById возвращался roots (List<ArticleNode>)
    //Отладка - вывод всего дерева в терминал
    roots.forEach { printTree(it) }
    
    return nodesById
}

fun downloadCurrentArticle(driver: FirefoxDriver, projectId: String, articleUrl: String, nodesById: Map<String, ArticleNode>) {

    val articleId = articleUrl.substringAfterLast("article/")

    val node = nodesById[articleId] ?: error("ArticleNode с id=$articleId не найден")

    //Сбор куки токенов
    val cookies = driver.manage().cookies
    val authTokens = AuthTokens(
        s2_access_token = cookies.find{it.name=="s2_access_token"}?.value,
        s2_refresh_token = cookies.find{it.name=="s2_refresh_token"}?.value,
        s2_access_token_expires_at = cookies.find{it.name=="s2_access_token_expires_at"}?.value,
        s2_refresh_token_expires_at = cookies.find{it.name=="s2_refresh_token_expires_at"}?.value,
        s4_access_token = cookies.find{it.name=="s4_access_token"}?.value,
        s4_refresh_token = cookies.find{it.name=="s4_refresh_token"}?.value,
        s4_access_token_expires_at = cookies.find{it.name=="s4_access_token_expires_at"}?.value,
        s4_refresh_token_expires_at = cookies.find{it.name=="s4_refresh_token_expires_at"}?.value,
        s5_access_token = cookies.find{it.name=="s5_access_token"}?.value,
        s5_refresh_token = cookies.find{it.name=="s5_refresh_token"}?.value,
        s5_access_token_expires_at = cookies.find{it.name=="s5_access_token_expires_at"}?.value,
        s5_refresh_token_expires_at = cookies.find{it.name=="s5_refresh_token_expires_at"}?.value,
        s1_access_token = cookies.find{it.name=="s1_access_token"}?.value,
        s1_refresh_token = cookies.find{it.name=="s1_refresh_token"}?.value,
        s1_access_token_expires_at = cookies.find{it.name=="s1_access_token_expires_at"}?.value,
        s1_refresh_token_expires_at = cookies.find{it.name=="s1_refresh_token_expires_at"}?.value
    )
    //Получаем DownloadToken
    val token = sendRequestToGetDownloadToken(projectId = projectId, articleId = articleId, authTokens)
    //Ждем, когда сервер подготовит файл
    val url = pollUntilDone(token = token, authTokens)
    //Сохраняем файл
    val relativePath = node.getFullPath(nodesById)
    val saveTo = "$dataDirectory/$relativePath.docx"
    File(saveTo).parentFile.mkdirs()
    downloadFile(url = url, saveTo = saveTo, authTokens)
}

fun sendRequestToGetDownloadToken(
    projectId: String,
    articleId: String,
    authTokens: AuthTokens
    ): String {
    val client = OkHttpClient()
    var targetToken = ""
    //Формирование запроса на получение токена скачки
    val MEDIA_TYPE = "application/json".toMediaType()
    val requestBody = "{\"export_format\":\"docx\",\"union_type\":\"archive\",\"articles\":[{\"id\":\"$articleId\",\"children\":false}]}"
    val request = Request.Builder()
        .url("https://app2.teamly.ru/api/v1/space/$projectId/articles/export")
        .post(requestBody.toRequestBody(MEDIA_TYPE))
        .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0")
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "en-US,en;q=0.5")
        .header("Content-Type", "application/json")
        .header("X-Account-Slug", "hr-link")
        .header("Origin", "https://hr-link.teamly.ru")
        .header("Connection", "keep-alive")
        .header("Referer", "https://hr-link.teamly.ru/")
        .header("Cookie", "s2_access_token=${authTokens.s2_access_token}; s2_refresh_token=${authTokens.s2_refresh_token}; s2_access_token_expires_at=${authTokens.s2_access_token_expires_at}; s2_refresh_token_expires_at=${authTokens.s2_refresh_token_expires_at}; s4_access_token=${authTokens.s4_access_token}; s4_refresh_token=${authTokens.s4_refresh_token}; s4_access_token_expires_at=${authTokens.s4_access_token_expires_at}; s4_refresh_token_expires_at=${authTokens.s4_refresh_token_expires_at}; s5_access_token=${authTokens.s5_access_token}; s5_refresh_token=${authTokens.s5_refresh_token}; s5_access_token_expires_at=${authTokens.s5_access_token_expires_at}; s5_refresh_token_expires_at=${authTokens.s5_refresh_token_expires_at}; s1_access_token=${authTokens.s1_access_token}; s1_refresh_token=${authTokens.s1_refresh_token}; s1_access_token_expires_at=${authTokens.s1_access_token_expires_at}; s1_refresh_token_expires_at=${authTokens.s1_refresh_token_expires_at}")
        .header("Sec-Fetch-Dest", "empty")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "same-site")
        .header("Priority", "u=0")
        .header("TE", "trailers")
        .build()
    //Отправка запроса на получение токена скачки
    client.newCall(request).execute().use { response ->
        val bodyString = response.body?.string()
        bodyString?.let {
            val jsonObject = JSONObject(it)
            val token = jsonObject.getString("token")
            targetToken = token
        }
    }
    return targetToken
}

fun downloadFile(
    url: String,
    saveTo: String,
    authTokens: AuthTokens
    ) {
    var errorLog = File("/home/dev/Projects/teamly_parser/Data/error.log")
    if (url.isNotBlank()) {
            val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Content-Type", "application/json")
            .header("X-Account-Slug", "hr-link")
            .header("Origin", "https://hr-link.teamly.ru")
            .header("Connection", "keep-alive")
            .header("Referer", "https://hr-link.teamly.ru/")
            .header("Cookie", "s2_access_token=${authTokens.s2_access_token}; s2_refresh_token=${authTokens.s2_refresh_token}; s2_access_token_expires_at=${authTokens.s2_access_token_expires_at}; s2_refresh_token_expires_at=${authTokens.s2_refresh_token_expires_at}; s4_access_token=${authTokens.s4_access_token}; s4_refresh_token=${authTokens.s4_refresh_token}; s4_access_token_expires_at=${authTokens.s4_access_token_expires_at}; s4_refresh_token_expires_at=${authTokens.s4_refresh_token_expires_at}; s5_access_token=${authTokens.s5_access_token}; s5_refresh_token=${authTokens.s5_refresh_token}; s5_access_token_expires_at=${authTokens.s5_access_token_expires_at}; s5_refresh_token_expires_at=${authTokens.s5_refresh_token_expires_at}; s1_access_token=${authTokens.s1_access_token}; s1_refresh_token=${authTokens.s1_refresh_token}; s1_access_token_expires_at=${authTokens.s1_access_token_expires_at}; s1_refresh_token_expires_at=${authTokens.s1_refresh_token_expires_at}")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-site")
            .header("Priority", "u=0")
            .header("TE", "trailers")
            .build()
        
        val client = OkHttpClient()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Ошибка при скачивании файла: ${response.code}")
            }

            val inputStream: InputStream = response.body?.byteStream()
                ?: throw Exception("Пустое тело ответа")

            val outputFile = File(saveTo)
            outputFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            println("Файл успешно сохранён в: $saveTo")
            if (!fileLog.exists()) {fileLog.createNewFile() }
            fileLog.appendText("Файл успешно сохранён в: $saveTo\n")
        }
    } else {
        if (!errorLog.exists()) errorLog.createNewFile()
        errorLog.appendText("Скачивание отменено\n")
    }
    
}

fun downloadAllArticles(driver: FirefoxDriver, nodes: List<ArticleNode>, projectId: String, nodesById: Map<String, ArticleNode>) {
    for (node in nodes) {
        if (node.url != null) {
            println("Скачиваю статью: ${node.title}")
            if (!fileLog.exists()) {fileLog.createNewFile() }
            fileLog.appendText("Скачиваю статью: ${node.title}\n")
            try {
                downloadCurrentArticle(driver, projectId, node.url, nodesById)
            } catch (e: Exception) {
                println("Ошибка при скачивании статьи ${node.title}: ${e.message}")
                if (!fileLog.exists()) {fileLog.createNewFile() }
                fileLog.appendText("Ошибка при скачивании статьи ${node.title}: ${e.message}\n")
            }
        }
        if (node.children.isNotEmpty()) {
            downloadAllArticles(driver, node.children, projectId, nodesById)
        }
    }
}