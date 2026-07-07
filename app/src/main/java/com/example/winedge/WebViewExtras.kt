package com.example.winedge

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import android.widget.Toast

class BlobDownloadInterface(
    private val context: Context
) {

    @android.webkit.JavascriptInterface
    fun processBlob(
        base64Data: String,
        mimeType: String,
        contentDisposition: String
    ) {
        try {
            val pureBase64 = if (base64Data.contains(",")) {
                base64Data.substringAfter(",")
            } else {
                base64Data
            }

            val fileBytes = android.util.Base64.decode(
                pureBase64,
                android.util.Base64.DEFAULT
            )

            val fileName = URLUtil.guessFileName(
                "",
                contentDisposition,
                mimeType
            )

            val resolver = context.contentResolver

            val contentValues = android.content.ContentValues().apply {
                put(
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    fileName
                )
                put(
                    android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    mimeType
                )
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
            }

            val outputUri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (outputUri != null) {
                resolver.openOutputStream(outputUri)?.use { outputStream ->
                    outputStream.write(fileBytes)
                }

                showToast("Sikeresen mentve a Letöltésekbe: $fileName")
            } else {
                showToast("Nem sikerült létrehozni a letöltési fájlt.")
            }
        } catch (e: Exception) {
            showToast("Blob letöltési hiba: ${e.localizedMessage}")
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

object UniversalNotificationScript {

    fun create(): String {
        return """
            (function() {
                if (window.__WinEdgeUniversalWatcherInstalled === true) {
                    return;
                }

                window.__WinEdgeUniversalWatcherInstalled = true;

                var lastSignal = "";
                var lastSignalAt = 0;

                function cleanText(value) {
                    if (!value) {
                        return "";
                    }

                    return String(value)
                        .replace(/\s+/g, " ")
                        .trim()
                        .substring(0, 240);
                }

                function sendSignal(title, message) {
                    try {
                        var currentUrl = location.href || "";
                        var cleanTitle = cleanText(title || document.title || "Web activity detected");
                        var cleanMessage = cleanText(message || "Possible activity detected");

                        var signal = cleanTitle + "|" + cleanMessage + "|" + currentUrl;
                        var currentTime = Date.now();

                        if (signal === lastSignal && currentTime - lastSignalAt < 60000) {
                            return;
                        }

                        if (currentTime - lastSignalAt < 8000) {
                            return;
                        }

                        lastSignal = signal;
                        lastSignalAt = currentTime;

                        if (window.WinEdgeNotifier && window.WinEdgeNotifier.notifyFromWeb) {
                            window.WinEdgeNotifier.notifyFromWeb(
                                cleanTitle,
                                cleanMessage,
                                currentUrl
                            );
                        }
                    } catch (e) {
                    }
                }

                function titleLooksLikeNotification(title) {
                    if (!title) {
                        return false;
                    }

                    var text = title.toLowerCase();

                    if (text.indexOf("unread") >= 0) return true;
                    if (text.indexOf("notification") >= 0) return true;
                    if (text.indexOf("message") >= 0) return true;
                    if (text.indexOf("new message") >= 0) return true;
                    if (text.indexOf("értesítés") >= 0) return true;
                    if (text.indexOf("üzenet") >= 0) return true;
                    if (text.indexOf("új üzenet") >= 0) return true;

                    if (title.length > 2 && title.charAt(0) === "(") {
                        var endIndex = title.indexOf(")");
                        if (endIndex > 1) {
                            var numberPart = title.substring(1, endIndex);
                            if (!isNaN(parseInt(numberPart))) {
                                return true;
                            }
                        }
                    }

                    return false;
                }

                var lastTitle = document.title || "";

                setInterval(function() {
                    try {
                        var currentTitle = document.title || "";

                        if (currentTitle !== lastTitle) {
                            lastTitle = currentTitle;

                            if (titleLooksLikeNotification(currentTitle)) {
                                sendSignal("Web notification", currentTitle);
                            }
                        }
                    } catch (e) {
                    }
                }, 3000);

                function detectBadges() {
                    try {
                        var selectors = [
                            "[aria-label*='unread' i]",
                            "[aria-label*='notification' i]",
                            "[aria-label*='message' i]",
                            "[aria-label*='new' i]",
                            "[aria-label*='értesítés' i]",
                            "[aria-label*='üzenet' i]",
                            "[aria-label*='új' i]",
                            "[title*='unread' i]",
                            "[title*='notification' i]",
                            "[title*='message' i]",
                            "[title*='new' i]",
                            "[title*='értesítés' i]",
                            "[title*='üzenet' i]",
                            "[data-testid*='badge' i]",
                            "[data-testid*='notification' i]",
                            "[data-testid*='unread' i]",
                            "[class*='badge' i]",
                            "[class*='unread' i]",
                            "[class*='notification' i]",
                            "[class*='counter' i]",
                            "[class*='count' i]"
                        ];

                        var foundTexts = [];

                        for (var s = 0; s < selectors.length; s++) {
                            var nodes = document.querySelectorAll(selectors[s]);
                            var maxCount = Math.min(nodes.length, 20);

                            for (var i = 0; i < maxCount; i++) {
                                var node = nodes[i];

                                var text =
                                    node.getAttribute("aria-label") ||
                                    node.getAttribute("title") ||
                                    node.getAttribute("data-count") ||
                                    node.innerText ||
                                    node.textContent ||
                                    "";

                                text = cleanText(text);

                                if (!text) {
                                    continue;
                                }

                                var lower = text.toLowerCase();

                                if (
                                    lower.indexOf("unread") >= 0 ||
                                    lower.indexOf("notification") >= 0 ||
                                    lower.indexOf("message") >= 0 ||
                                    lower.indexOf("new") >= 0 ||
                                    lower.indexOf("értesítés") >= 0 ||
                                    lower.indexOf("üzenet") >= 0 ||
                                    lower.indexOf("új") >= 0 ||
                                    !isNaN(parseInt(lower))
                                ) {
                                    foundTexts.push(text);
                                }
                            }
                        }

                        if (foundTexts.length > 0) {
                            sendSignal(
                                "Web activity detected",
                                foundTexts.slice(0, 3).join(" | ")
                            );
                        }
                    } catch (e) {
                    }
                }

                var badgeTimer = null;

                function scheduleBadgeDetection() {
                    if (badgeTimer !== null) {
                        return;
                    }

                    badgeTimer = setTimeout(function() {
                        badgeTimer = null;
                        detectBadges();
                    }, 1500);
                }

                try {
                    var observer = new MutationObserver(function() {
                        scheduleBadgeDetection();
                    });

                    observer.observe(document.documentElement || document.body, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: [
                            "aria-label",
                            "title",
                            "class",
                            "data-testid",
                            "data-count"
                        ]
                    });
                } catch (e) {
                }

                try {
                    if (window.Notification && !window.__WinEdgeNotificationPatched) {
                        window.__WinEdgeNotificationPatched = true;

                        var OriginalNotification = window.Notification;

                        var PatchedNotification = function(title, options) {
                            try {
                                var body = "";

                                if (options && options.body) {
                                    body = options.body;
                                }

                                sendSignal(
                                    title || "Web notification",
                                    body || "Notification requested by page"
                                );
                            } catch (e) {
                            }

                            return new OriginalNotification(title, options);
                        };

                        try {
                            Object.defineProperty(PatchedNotification, "permission", {
                                get: function() {
                                    return OriginalNotification.permission;
                                }
                            });
                        } catch (e) {
                        }

                        if (OriginalNotification.requestPermission) {
                            PatchedNotification.requestPermission =
                                OriginalNotification.requestPermission.bind(OriginalNotification);
                        }

                        window.Notification = PatchedNotification;
                    }
                } catch (e) {
                }

                setTimeout(function() {
                    try {
                        if (titleLooksLikeNotification(document.title || "")) {
                            sendSignal("Web notification", document.title);
                        }

                        detectBadges();
                    } catch (e) {
                    }
                }, 4000);
            })();
        """.trimIndent()
    }
}

