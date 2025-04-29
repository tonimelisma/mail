package net.melisma.mail

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// Data class to hold relevant folder information
data class MailFolder(
    val id: String,
    val displayName: String,
    val totalItemCount: Int,
    val unreadItemCount: Int
)

object GraphApiHelper {

    private const val TAG = "GraphApiHelper"
    private const val MS_GRAPH_ROOT_ENDPOINT = "https://graph.microsoft.com/v1.0"

    // Function to fetch mail folders using an access token
    suspend fun getMailFolders(accessToken: String): Result<List<MailFolder>> {
        // Ensure network operations run on the IO dispatcher
        return withContext(Dispatchers.IO) {
            val url = URL("$MS_GRAPH_ROOT_ENDPOINT/me/mailFolders")
            var connection: HttpsURLConnection? = null

            try {
                connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                // Add the Authorization header
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Accept", "application/json")

                val responseCode = connection.responseCode
                Log.d(TAG, "GET /me/mailFolders Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    Log.d(TAG, "GET /me/mailFolders Response: $response")
                    // Parse the JSON response
                    val folders = parseFolders(response)
                    Result.success(folders)
                } else {
                    val errorStream = connection.errorStream ?: connection.inputStream
                    val reader = BufferedReader(InputStreamReader(errorStream))
                    val errorResponse = reader.readText()
                    reader.close()
                    Log.e(TAG, "Error fetching folders: $responseCode - $errorResponse")
                    Result.failure(Exception("Error fetching folders: $responseCode - $errorResponse"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching folders", e)
                Result.failure(e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    // Basic JSON parsing using org.json
    private fun parseFolders(jsonResponse: String): List<MailFolder> {
        val folders = mutableListOf<MailFolder>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            val valueArray = jsonObject.getJSONArray("value")

            for (i in 0 until valueArray.length()) {
                val folderObject = valueArray.getJSONObject(i)
                folders.add(
                    MailFolder(
                        id = folderObject.getString("id"),
                        displayName = folderObject.getString("displayName"),
                        totalItemCount = folderObject.getInt("totalItemCount"),
                        unreadItemCount = folderObject.getInt("unreadItemCount")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing folder JSON", e)
            // Return empty list or rethrow depending on desired error handling
        }
        return folders
    }
}