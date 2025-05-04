package net.melisma.backend_microsoft

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.Message
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/**
 * Helper class for making calls to the Microsoft Graph API (v1.0).
 * Encapsulates network request logic and JSON parsing for mail-related data.
 * Provided as a Singleton by Hilt.
 */
@Singleton
class GraphApiHelper @Inject constructor() {

    companion object {
        private const val TAG = "GraphApiHelper"
        private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"
    }

    /**
     * Fetches the list of mail folders for the authenticated user.
     */
    suspend fun getMailFolders(accessToken: String): Result<List<MailFolder>> {
        return withContext(Dispatchers.IO) {
            makeGraphApiCall(
                accessToken = accessToken,
                endpoint = "/me/mailFolders?\$top=100",
                parser = ::parseFolders
            )
        }
    }

    /**
     * Fetches a list of messages for a specific mail folder.
     */
    suspend fun getMessagesForFolder(
        accessToken: String,
        folderId: String,
        selectFields: List<String>,
        top: Int
    ): Result<List<Message>> {
        return withContext(Dispatchers.IO) {
            val selectParam = selectFields.joinToString(",")
            val queryParams = "?\$select=$selectParam&\$top=$top&\$orderby=receivedDateTime desc"
            val encodedFolderId = URLEncoder.encode(folderId, StandardCharsets.UTF_8.toString())
            val endpoint = "/me/mailFolders/$encodedFolderId/messages$queryParams"

            makeGraphApiCall(
                accessToken = accessToken,
                endpoint = endpoint,
                parser = ::parseMessages
            )
        }
    }

    /**
     * Generic internal function to make a GET request to the Microsoft Graph API.
     */
    private suspend fun <T> makeGraphApiCall(
        accessToken: String,
        endpoint: String,
        parser: (String) -> T
    ): Result<T> {
        val url = URL("$MS_GRAPH_ROOT_ENDPOINT$endpoint")
        var connection: HttpsURLConnection? = null
        Log.d(TAG, "Requesting URL: $url")

        return try {
            connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            Log.d(TAG, "GET $endpoint Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                Log.d(TAG, "GET $endpoint Response: ${response.take(500)}...")
                val parsedData = parser(response)
                Result.success(parsedData)
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val reader = BufferedReader(InputStreamReader(errorStream))
                val errorResponse = reader.readText()
                reader.close()
                Log.e(TAG, "Error fetching $endpoint: $responseCode - $errorResponse")
                // Ensure IOException can be referenced here
                Result.failure(IOException("Error fetching $endpoint: $responseCode - $errorResponse"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during API call to $endpoint", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses the JSON response for a list of mail folders.
     */
    private fun parseFolders(jsonResponse: String): List<MailFolder> {
        val folders = mutableListOf<MailFolder>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            val valueArray = jsonObject.getJSONArray("value")

            for (i in 0 until valueArray.length()) {
                val folderObject = valueArray.getJSONObject(i)
                if (folderObject.has("id") && folderObject.has("displayName")) {
                    folders.add(
                        MailFolder(
                            id = folderObject.getString("id"),
                            displayName = folderObject.getString("displayName"),
                            totalItemCount = folderObject.optInt("totalItemCount", 0),
                            unreadItemCount = folderObject.optInt("unreadItemCount", 0)
                        )
                    )
                } else {
                    Log.w(TAG, "Skipping folder due to missing id or displayName: $folderObject")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing folder JSON", e)
        }
        return folders.sortedBy { it.displayName }
    }

    /**
     * Parses the JSON response for a list of messages.
     */
    private fun parseMessages(jsonResponse: String): List<Message> {
        val messages = mutableListOf<Message>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            val valueArray = jsonObject.getJSONArray("value")

            for (i in 0 until valueArray.length()) {
                val msgObject = valueArray.getJSONObject(i)
                val senderObject = msgObject.optJSONObject("sender")
                val emailAddressObject = senderObject?.optJSONObject("emailAddress")

                if (!msgObject.has("id")) {
                    Log.w(TAG, "Skipping message due to missing id: $msgObject")
                    continue
                }

                messages.add(
                    Message(
                        id = msgObject.getString("id"),
                        receivedDateTime = msgObject.optString("receivedDateTime", ""),
                        subject = msgObject.optString("subject").ifEmpty { null },
                        senderName = emailAddressObject?.optString("name")?.ifEmpty { null },
                        senderAddress = emailAddressObject?.optString("address")?.ifEmpty { null },
                        bodyPreview = msgObject.optString("bodyPreview").ifEmpty { null },
                        isRead = msgObject.optBoolean("isRead", true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing messages JSON", e)
        }
        return messages
    }
}