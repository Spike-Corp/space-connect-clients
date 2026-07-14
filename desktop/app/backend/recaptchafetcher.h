#pragma once

#include <QObject>
#include <QTcpServer>
#include <QTimer>

#include <functional>

// Obtains a reCAPTCHA v3 token for the desktop Space Connect client without an
// embedded browser (Qt WebEngine is intentionally not a dependency).
//
// It starts a short-lived loopback HTTP server on 127.0.0.1 and opens the
// server-hosted token page in the user's default browser. That page runs
// grecaptcha on the spacecloud.gg origin (matching the site key allowlist) and
// redirects back to the loopback callback with the token in the query string.
//
// The callback always fires exactly once. The token is an empty string when it
// could not be obtained (browser failed to open, timeout, or reCAPTCHA disabled
// server-side); the backend decides whether that is acceptable.
class RecaptchaFetcher : public QObject
{
    Q_OBJECT

public:
    explicit RecaptchaFetcher(QObject* parent = nullptr);

    void fetch(const QString& action, std::function<void(const QString&)> callback);

private:
    void finish(const QString& token);

    QTcpServer m_Server;
    QTimer m_Timeout;
    std::function<void(const QString&)> m_Callback;
    bool m_Done = false;
};
