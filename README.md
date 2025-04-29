# Melisma Mail (Android)

## Overview

Melisma Mail is an Android email client aiming to provide a clean, native user experience similar to
stock Pixel apps, using Material 3. It's being built as a potential replacement for default mail
apps, starting with support for Microsoft Outlook accounts, with plans to support Google and
potentially other backends in the future.

## Current Status (As of 2025-04-28)

* **Authentication:** Users can securely sign in and out using Microsoft accounts (MSAL).
* **Folder Listing:** Users can fetch and view a list of their mail folders after signing in.

## Key Planned Features (Prioritized Backlog)

* **Core Mail Viewing:** View message lists within folders, view full message content (HTML/Text).
* **Basic Mail Actions:** Mark read/unread, delete, archive messages.
* **Composing & Sending:** Compose new emails, reply, reply-all, forward.
* **Offline & Sync:** Local caching of mail data for faster access and basic offline viewing,
  background sync for new messages.
* **Advanced Organization:** Move messages between folders, search mail.
* **Settings:** Basic account management and app settings.
* **Google Account Support:** Full integration for Gmail accounts (Authentication, Folders/Labels,
  Viewing, Actions, Sending).

*(See the full Requirements & Architecture document for details)*

## Setup & Build

1. **Clone the repository:**
   ```bash
   git clone <your-repository-url>
   ```
2. **Open in Android Studio:** Open the cloned project directory in the latest stable version of
   Android Studio.
3. **Sync Gradle:** Allow Gradle to sync dependencies. Ensure you have the necessary Android SDK
   versions installed.
4. **Permissions:** The app requires the `INTERNET` permission (declared in `AndroidManifest.xml`).
5. **Build/Run:** Build and run the app on an emulator or physical device.

## Contribution

*(Placeholder: Add contribution guidelines, code style, issue reporting process, etc., if applicable
later.)*
