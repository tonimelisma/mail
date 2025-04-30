package net.melisma.mail

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

// Data class to hold relevant folder information
data class MailFolder(
    val id: String,
    val displayName: String,
    val totalItemCount: Int,
    val unreadItemCount: Int
)

// Data class for basic Message info needed in list view
data class Message(
    val id: String,
    val receivedDateTime: String,
    val subject: String?,
    val senderName: String?, // Nullable String
    val senderAddress: String?, // Nullable String
    val bodyPreview: String?,
    val isRead: Boolean
)

object GraphApiHelper {

    private const val TAG = "GraphApiHelper"
    private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"

    suspend fun getMailFolders(accessToken: String): Result<List<MailFolder>> {
        return withContext(Dispatchers.IO) {
            makeGraphApiCall(
                accessToken = accessToken,
                endpoint = "/me/mailFolders?\$top=100",
                parser = ::parseFolders
            )
        }
    }

    suspend fun getMessagesForFolder(
        accessToken: String,
        folderId: String,
        selectFields: List<String>,
        top: Int
    ): Result<List<Message>> {
        return withContext(Dispatchers.IO) {
            val selectParam = selectFields.joinToString(",")
            val queryParams = "?\$select=$selectParam&\$top=$top&\$orderby=receivedDateTime desc"
            val endpointPath = URLEncoder.encode(
                folderId,
                StandardCharsets.UTF_8.toString()
            ) // Always encode folder ID
            val endpoint = "/me/mailFolders/$endpointPath/messages$queryParams"

            makeGraphApiCall(
                accessToken = accessToken,
                endpoint = endpoint,
                parser = ::parseMessages
            )
        }
    }

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
                Log.d(TAG, "GET $endpoint Response: $response")
                val parsedData = parser(response)
                Result.success(parsedData)
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val reader = BufferedReader(InputStreamReader(errorStream))
                val errorResponse = reader.readText()
                reader.close()
                Log.e(TAG, "Error fetching $endpoint: $responseCode - $errorResponse")
                Result.failure(Exception("Error fetching $endpoint: $responseCode - $errorResponse"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during API call to $endpoint", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing folder JSON", e)
        }
        return folders.sortedBy { it.displayName }
    }

    private fun parseMessages(jsonResponse: String): List<Message> {
        val messages = mutableListOf<Message>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            val valueArray = jsonObject.getJSONArray("value")

            for (i in 0 until valueArray.length()) {
                val msgObject = valueArray.getJSONObject(i)
                val senderObject = msgObject.optJSONObject("sender")
                val emailAddressObject = senderObject?.optJSONObject("emailAddress")

                messages.add(
                    Message(
                        id = msgObject.getString("id"),
                        receivedDateTime = msgObject.optString("receivedDateTime", ""),
                        subject = msgObject.optString("subject", "(No Subject)"),
                        // Use elvis operator for safe fallback to empty string for non-nullable fields
                        senderName = emailAddressObject?.optString("name") ?: "", // Fix warning
                        senderAddress = emailAddressObject?.optString("address")
                            ?: "", // Fix warning
                        bodyPreview = msgObject.optString("bodyPreview", ""),
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