#include "recaptchafetcher.h"

#include <QByteArray>
#include <QDesktopServices>
#include <QHostAddress>
#include <QTcpSocket>
#include <QUrl>
#include <QUrlQuery>

namespace {
const QString kRecaptchaUrl = QStringLiteral("https://spacecloud.gg/api/launcher/v1/recaptcha");
// The user may have to interact with the browser, so allow a generous window.
const int kTimeoutMs = 120000;
}

RecaptchaFetcher::RecaptchaFetcher(QObject* parent)
    : QObject(parent)
{
    m_Timeout.setSingleShot(true);
    connect(&m_Timeout, &QTimer::timeout, this, [this]() { finish(QString()); });
}

void RecaptchaFetcher::fetch(const QString& action, std::function<void(const QString&)> callback)
{
    m_Callback = std::move(callback);
    m_Done = false;

    if (!m_Server.isListening() && !m_Server.listen(QHostAddress::LocalHost, 0)) {
        finish(QString());
        return;
    }

    disconnect(&m_Server, &QTcpServer::newConnection, nullptr, nullptr);
    connect(&m_Server, &QTcpServer::newConnection, this, [this]() {
        QTcpSocket* socket = m_Server.nextPendingConnection();
        if (!socket) {
            return;
        }
        connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
        connect(socket, &QTcpSocket::readyRead, this, [this, socket]() {
            const QByteArray data = socket->readAll();

            QString token;
            const int firstSpace = data.indexOf(' ');
            const int secondSpace = firstSpace >= 0 ? data.indexOf(' ', firstSpace + 1) : -1;
            if (firstSpace >= 0 && secondSpace > firstSpace) {
                const QString path =
                    QString::fromUtf8(data.mid(firstSpace + 1, secondSpace - firstSpace - 1));
                const int queryStart = path.indexOf('?');
                if (queryStart >= 0) {
                    const QUrlQuery query(path.mid(queryStart + 1));
                    token = query.queryItemValue(QStringLiteral("token"), QUrl::FullyDecoded);
                }
            }

            const QByteArray body =
                "<!doctype html><html lang=pt-br><head><meta charset=utf-8>"
                "<title>Space Connect</title></head>"
                "<body style=\"font-family:system-ui,sans-serif;background:#0d0a1a;color:#A79BC9;"
                "text-align:center;padding-top:48px\">"
                "Verifica&ccedil;&atilde;o conclu&iacute;da. Pode fechar esta aba e voltar ao "
                "Space Connect.</body></html>";

            QByteArray response = "HTTP/1.1 200 OK\r\n";
            response += "Content-Type: text/html; charset=utf-8\r\n";
            response += "Connection: close\r\n";
            response += "Content-Length: " + QByteArray::number(body.size()) + "\r\n\r\n";
            response += body;
            socket->write(response);
            socket->flush();
            socket->disconnectFromHost();

            finish(token);
        });
    });

    const quint16 port = m_Server.serverPort();
    QUrl url(kRecaptchaUrl);
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("action"), action);
    query.addQueryItem(QStringLiteral("redirect"),
                       QStringLiteral("http://127.0.0.1:%1/cb").arg(port));
    url.setQuery(query);

    if (!QDesktopServices::openUrl(url)) {
        finish(QString());
        return;
    }

    m_Timeout.start(kTimeoutMs);
}

void RecaptchaFetcher::finish(const QString& token)
{
    if (m_Done) {
        return;
    }
    m_Done = true;
    m_Timeout.stop();
    m_Server.close();

    if (m_Callback) {
        auto callback = m_Callback;
        m_Callback = nullptr;
        callback(token);
    }
}
