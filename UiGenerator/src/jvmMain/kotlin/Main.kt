// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

const val API_ENDPOINT = "http://localhost:8080/generate"
const val USER_HOME = "user.home"
const val GENERATED_PROJECT = "Downloads/generated_project"
const val ZIP_PREFIX = "generated_project"
const val ZIP_SUFFIX = ".zip"

@Composable
@Preview
fun App() {

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            MainScreen()
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        App()
    }
}

@OptIn(InternalAPI::class)
@Composable
fun MainScreen() {
    var filePath by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun uploadFile() {
        if (filePath.isEmpty()) {
            errorMessage = "Пожалуйста, введите путь к файлу."
            return
        }

        isLoading = true
        errorMessage = null

        CoroutineScope(IO).launch {
            try {
                val zipFilePath = uploadFileToServer(filePath)
                val extractedPath = extractZipFile(zipFilePath)
                withContext(Main) {
                    isLoading = false
                    savedPath = extractedPath
                }
            } catch (e: Exception) {
                withContext(Main) {
                    isLoading = false
                    errorMessage = "Ошибка загрузки: ${e.message}"
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextField(
            value = filePath,
            onValueChange = { filePath = it },
            label = { Text("Введите путь к файлу (MP4)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { uploadFile() }) {
            Text("Отправить")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator()
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it)
        }

        savedPath?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Проект сохранен по пути: $it")
        }
    }
}

@InternalAPI
suspend fun uploadFileToServer(filePath: String): String {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies)
    }

    val file = File(filePath)

    if (!file.exists() || !file.isFile) {
        throw IllegalArgumentException("Файл не найден.")
    }

    val responseBytes: ByteArray = client.post(API_ENDPOINT) {
        body = MultiPartFormDataContent(
            formData {
                append("file", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.MultiPart.FormData.toString())
                })
            }
        )
    }.readBytes()

    client.close()

    val zipFile = createTempFile(ZIP_PREFIX, ZIP_SUFFIX)
    zipFile.writeBytes(responseBytes)

    return zipFile.absolutePath
}

fun extractZipFile(zipFilePath: String): String {
    val zipFile = File(zipFilePath)
    val extractFolder = File(System.getProperty(USER_HOME), GENERATED_PROJECT)
    if (!extractFolder.exists()) {
        extractFolder.mkdirs()
    }

    ZipInputStream(zipFile.inputStream()).use { zipInputStream ->
        var entry: ZipEntry? = zipInputStream.nextEntry
        while (entry != null) {
            val filePath = Paths.get(extractFolder.absolutePath, entry.name).toFile()
            if (entry.isDirectory) {
                filePath.mkdirs()
            } else {
                filePath.outputStream().use { output ->
                    zipInputStream.copyTo(output)
                }
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
    }

    return extractFolder.absolutePath
}