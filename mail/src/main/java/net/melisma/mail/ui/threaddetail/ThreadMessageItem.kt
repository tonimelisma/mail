package net.melisma.mail.ui.threaddetail

import net.melisma.core_data.model.Message

data class ThreadMessageItem(
    val message: Message,
    val bodyState: BodyLoadingState = BodyLoadingState.Initial
) 