package com.aflyingporo.fourchandownloader

import com.aflyingporo.fourchandownloader.model.ImageLink
import com.aflyingporo.fourchandownloader.FileUtil.cleanFileName
import com.aflyingporo.fourchandownloader.FileUtil.div
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.datetime.Clock
import kotlinx.datetime.toKotlinInstant
import org.slf4j.LoggerFactory
import java.io.File
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.seconds

private val httpClient = HttpClient {
    install(UserAgent) {
        agent = "4chan-Downloader"
    }
}
private val logger = LoggerFactory.getLogger("Main")

private const val defaultConcurrentCount = 5
private const val downloadFolderName = "4Chan-Downloader"
private val defaultDownloadFolder = File(System.getProperty("user.home"), "Downloads") / downloadFolderName

private val regexThreadSubject = Regex("<span class=\"subject\"(?: .+?=.+?)*>([^<]*?)</span>")
private val regexThreadTitle = Regex("<title(?: .+?=.+?)*>/\\w+?/ - (.+?) - .+? - 4chan</title>")
private val regexThreadCleanUrl = Regex("<link rel=\"canonical\" href=\"https?://boards\\.4chan\\.org/(.+?)/thread/(\\d+)/(.+?)/?\">")
private val regexImages = Regex("<a(?: title=\"(.+?\\.(?:jpg|png|gif|webm))\")? href=\"//(is?\\.(?:4chan|4cdn)\\.org/\\w+?/(\\d+?)\\.(?:jpg|png|gif|webm))\".+?>(.+?\\.(?:jpg|png|gif|webm))</a>")

@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>): Unit = runBlocking {
    val argParser = ArgParser("4chan-downloader")
    val argUrl by argParser.argument(ArgType.String, fullName = "URL", description = "URL of the thread")
    val argCounter by argParser.option(ArgType.Boolean, shortName = "c", fullName = "counter", description = "Show counter next to the image that has been downloaded")
    val argDate by argParser.option(ArgType.Boolean, shortName = "d", fullName = "date", description = "Show date next to the image that has been downloaded")
//    val argLess by argParser.option(ArgType.Boolean, shortName = "l", fullName = "less", description = "Show less information (suppress checking messages)")
    val argUseThreadNames by argParser.option(ArgType.Boolean, shortName = "n", fullName = "use-names", description = "Use thread name instead thread ID")
//    val argReload by argParser.option(ArgType.Boolean, shortName = "r", fullName = "reload", description = "Reloads the queue file every 5 minutes")
    val argOriginalFilename by argParser.option(ArgType.Boolean, shortName = "f", fullName = "original-filename", description = "Save original filenames")
    val argThreadCount by argParser.option(ArgType.Int, shortName = "t", fullName = "threads", description = "Number of concurrent threads used for downloading (default: $defaultConcurrentCount)")
    val argDownloadsFolder by argParser.option(ArgType.String, shortName = "o", fullName = "output", description = "Directory to save images to (default: Downloads folder [${(defaultDownloadFolder / "Board" / "Thread").absolutePath}]")
    val argSubject by argParser.option(ArgType.String, shortName = "s", fullName = "subject", description = "Sets the thread subject manually instead of inferring from the thread")
    argParser.parse(args)

    val threadUrl = try {
        Url(argUrl)
    } catch (e: URLParserException) {
        logger.error("Provided URL is invalid.")
        e.printStackTrace()
        return@runBlocking
    }

    val threadHtml = httpClient.get(threadUrl).bodyAsText()

    // Scraping information
    val resultSubject = regexThreadSubject.find(threadHtml)
    if (resultSubject == null) {
        logger.error("Could not find the subject for the given URL. Please report this to the author.")
        return@runBlocking
    }
    val resultCleanUrl = regexThreadCleanUrl.find(threadHtml)
    if (resultCleanUrl == null) {
        logger.error("Could not find the canonical URL for the given URL. Please report this to the author.")
        return@runBlocking
    }
    val resultTitle = regexThreadTitle.find(threadHtml)
    if (resultTitle == null) {
        logger.error("Could not find the title for the given URL. Please report this to the author.")
        return@runBlocking
    }
    val (threadBoard, threadId) = resultCleanUrl.destructured
    val threadSubject = if (argSubject != null) {
        argSubject!!
    } else {
        resultSubject.groupValues[1].ifBlank {
            resultTitle.groupValues[1].ifBlank {
                resultCleanUrl.groupValues[3].ifBlank {
                    logger.info("Could not infer thread subject, resulting to thread ID")
                    threadId
                }
            }
        }
    }

    val downloadFolder = if (argDownloadsFolder != null) {
        try {
            File(argDownloadsFolder!!)
        } catch (e: Throwable) {
            logger.error("Could not parse provided downloads folder: $argDownloadsFolder", e)
            return@runBlocking
        }
    } else {
        defaultDownloadFolder
    } / threadBoard / if (argUseThreadNames == true) threadSubject.cleanFileName().trim() else threadId
    downloadFolder.mkdirs()
    logger.info("Download folder: ${downloadFolder.absolutePath}")





    val concurrentThreads = (argThreadCount ?: defaultConcurrentCount).coerceAtLeast(1)/*.coerceAtMost(Runtime.getRuntime().availableProcessors())*/
    val imageLinks = regexImages.findAll(threadHtml)
        .map { matchedImage ->
            val (fullTitle, imageUrl, imageId, truncatedTitle) = matchedImage.destructured
            ImageLink(imageId, "https://$imageUrl", fullTitle.ifBlank { truncatedTitle })
        }
        .toList()
    val totalImages = imageLinks.size
    val totalImagesString = totalImages.toString()

    val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
    val dateFormat = DateFormat.getDateInstance()
    val timeFormat = DateFormat.getTimeInstance()

    val imagesChannel = produce {
        var index = 0
        imageLinks.forEach {
            send(index++ to it)
        }
    }

    val downloadJobs = buildList {
        repeat(concurrentThreads) {
            add(
                launch {
                    for ((index, imageLink) in imagesChannel) {
                        val imageExtension = '.' + imageLink.url.substringAfterLast('.')
                        val outputFilename = if (argOriginalFilename == true) {
                            imageLink.originalFilename.cleanFileName()
                        } else {
                            imageLink.id + imageExtension
                        }
                        logger.info(
                            buildString {
                                val current = Date()
                                append('[')
                                if (argDate == true) {
                                    append(dateFormat.format(current))
                                    append(' ')
                                }
                                append(timeFormat.format(current))
                                append("] ")
                                if (argCounter == true) {
                                    append('[')
                                    append((index + 1).toString().padStart(totalImagesString.length, '0'))
                                    append(" / ")
                                    append(totalImagesString)
                                    append("] ")
                                }
                                append(threadBoard)
                                append('/')
                                append(threadId)
                                append('/')
                                append(imageLink.id)
                                append(imageExtension)
                                if (argOriginalFilename == true) {
                                    append(" -> ")
                                    append(outputFilename)
                                }
                            }
                        )

                        val outputFile = downloadFolder / outputFilename

                        if (outputFile.exists()) {
                            logger.info("File exists, skipping")
                            return@launch
                        }

                        try {
                            var response: HttpResponse

                            // Retry fetch if 429 Too Many Requests reached
                            do {
                                response = httpClient.get(imageLink.url)
                                when {
                                    // 429
                                    response.status == HttpStatusCode.TooManyRequests -> {
                                        // Try to check if server provides a time to retry after
                                        val retryHeader = response.headers["Retry-After"]?.takeIf { it.isNotBlank() }

                                        fun parseHttpDate(dateString: String) = try {
                                            httpDateFormat.parse(dateString).toInstant().toKotlinInstant()
                                        } catch (e: ParseException) {
                                            logger.warn("Could not parse HTTP Too Many Requests retry after. Using default retry timeout.", e)
                                            null
                                        }

                                        val retryDuration = retryHeader
                                            ?.let { retryHeader.toIntOrNull()?.seconds ?: parseHttpDate(retryHeader)?.minus(Clock.System.now()) }?.coerceIn(5.seconds, 30.seconds)
                                            ?: 30.seconds

                                        logger.info("HTTP ${response.status} received for $threadBoard/$threadId/${imageLink.id}$imageExtension, waiting for $retryDuration before retrying...")

                                        delay(retryDuration)
                                    }
                                    // 200 - 299
                                    response.status.isSuccess() -> {
                                        break
                                    }
                                    // Error
                                    else -> {
                                        logger.warn("Could not download $threadBoard/$threadId/${imageLink.id}$imageExtension, server returned HTTP ${response.status}")
                                        return@launch
                                    }
                                }
                            } while (true)

                            val imageDownloadChannel = response.bodyAsChannel()
                            val outputFileChannel = outputFile.writeChannel()

                            imageDownloadChannel.copyAndClose(outputFileChannel)
                        } catch (e: Exception) {
                            logger.error("An error occurred while fetching image $threadBoard/$threadId/${imageLink.id}$imageExtension", e)
                        }
                    }
                }
            )
        }
    }
    downloadJobs.joinAll()

    logger.info("Download complete")
}
