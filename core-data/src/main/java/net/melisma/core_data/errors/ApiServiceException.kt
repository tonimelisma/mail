package net.melisma.core_data.errors

import net.melisma.core_data.model.ErrorDetails

/**
 * Custom exception to carry structured ErrorDetails from MailApiServices.
 */
class ApiServiceException(
    val errorDetails: ErrorDetails
) : Exception(errorDetails.message, errorDetails.cause) 