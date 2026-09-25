#include "xdapi.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>

namespace {
const QUrl kApiBase(QStringLiteral("https://spacecloud.gg/api/"));

QString errorMessageOf(const QJsonObject& root, const QString& fallback)
{
    const QJsonValue err = root.value(QStringLiteral("error"));
    if (err.isObject()) {
        const QString msg = err.toObject().value(QStringLiteral("message")).toString();
        if (!msg.isEmpty()) return msg;
    }
    if (err.isString() && !err.toString().isEmpty()) return err.toString();
    const QString msg = root.value(QStringLiteral("message")).toString();
    return msg.isEmpty() ? fallback : msg;
}
}

XdApi::XdApi(QObject* parent) : QObject(parent)
{
    QSettings settings;
    m_Token = settings.value(QStringLiteral("xd/token")).toString();
    m_AccountLabel = settings.value(QStringLiteral("xd/account")).toString();
    m_LoggedIn = !m_Token.isEmpty();
}

void XdApi::setBusy(bool busy)
{
    if (m_Busy == busy) return;
    m_Busy = busy;
    emit busyChanged();
}

void XdApi::setError(const QString& message)
{
    if (m_ErrorMessage == message) return;
    m_ErrorMessage = message;
    emit errorMessageChanged();
}

void XdApi::saveSession(const QString& token, const QString& email, const QString& name)
{
    m_Token = token;
    m_AccountLabel = name.isEmpty() ? email : QStringLiteral("%1 (%2)").arg(name, email);
    QSettings settings;
    settings.setValue(QStringLiteral("xd/token"), token);
    settings.setValue(QStringLiteral("xd/account"), m_AccountLabel);
    m_LoggedIn = true;
    m_AccessDenied = false;
    emit loggedInChanged();
    emit accessDeniedChanged();
    emit accountLabelChanged();
}

void XdApi::clearSession()
{
    m_Token.clear();
    QSettings settings;
    settings.remove(QStringLiteral("xd/token"));
    settings.remove(QStringLiteral("xd/account"));
    m_LoggedIn = false;
    m_AccessDenied = false;
    m_Overview = {};
    m_Machines = {};
    emit loggedInChanged();
    emit accessDeniedChanged();
    emit overviewChanged();
    emit machinesChanged();
}

void XdApi::request(const QByteArray& method, const QString& path,
                    const QJsonObject& body, ResponseHandler handler)
{
    QNetworkRequest request(kApiBase.resolved(QUrl(path)));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    request.setRawHeader("User-Agent", "XDConsole-Qt/0.1.0");
    if (!m_Token.isEmpty())
        request.setRawHeader("Authorization", "Bearer " + m_Token.toUtf8());

    QNetworkReply* reply;
    if (method == "GET")
        reply = m_Network.get(request);
    else if (method == "PATCH")
        reply = m_Network.sendCustomRequest(request, "PATCH", QJsonDocument(body).toJson(QJsonDocument::Compact));
    else
        reply = m_Network.post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));

    connect(reply, &QNetworkReply::finished, this, [reply, handler = std::move(handler)]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QJsonParseError parseError;
        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll(), &parseError);
        QJsonObject root;
        if (parseError.error == QJsonParseError::NoError && document.isObject())
            root = document.object();
        handler(status > 0 ? status : 503, root);
        reply->deleteLater();
    });
}

void XdApi::handleXdError(int status, const QJsonObject& root, const QString& fallback)
{
    if (status == 401) {
        // Token expirado — volta pro login sem drama.
        clearSession();
        return;
    }
    if (status == 403) {
        m_AccessDenied = true;
        emit accessDeniedChanged();
        return;
    }
    setError(errorMessageOf(root, fallback));
}

void XdApi::login(const QString& email, const QString& password)
{
    if (m_Busy) return;
    setBusy(true);
    setError(QString());
    request("POST", QStringLiteral("auth/login"),
            QJsonObject{{QStringLiteral("email"), email.trimmed().toLower()},
                        {QStringLiteral("password"), password}},
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status == 403 && root.value(QStringLiteral("error")).toString() == QStringLiteral("2fa_required")) {
                    m_TempToken = root.value(QStringLiteral("tempToken")).toString();
                    emit twoFactorRequired();
                    return;
                }
                if (status < 200 || status >= 300) {
                    setError(errorMessageOf(root, tr("Falha no login")));
                    return;
                }
                const QString token = root.value(QStringLiteral("token")).toString();
                if (token.isEmpty()) {
                    setError(tr("Resposta de login sem token"));
                    return;
                }
                const QJsonObject user = root.value(QStringLiteral("user")).toObject();
                saveSession(token,
                            user.value(QStringLiteral("email")).toString(),
                            user.value(QStringLiteral("name")).toString());
            });
}

void XdApi::verifyTwoFactor(const QString& code)
{
    if (m_Busy || m_TempToken.isEmpty()) return;
    setBusy(true);
    setError(QString());
    request("POST", QStringLiteral("auth/2fa/verify"),
            QJsonObject{{QStringLiteral("code"), code.trimmed()},
                        {QStringLiteral("tempToken"), m_TempToken}},
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                const QString token = root.value(QStringLiteral("token")).toString();
                if (status < 200 || status >= 300 || token.isEmpty()) {
                    setError(errorMessageOf(root, tr("Código inválido")));
                    return;
                }
                const QJsonObject user = root.value(QStringLiteral("user")).toObject();
                saveSession(token,
                            user.value(QStringLiteral("email")).toString(),
                            user.value(QStringLiteral("name")).toString());
            });
}

void XdApi::logout()
{
    clearSession();
}

void XdApi::tryAutoLogin()
{
    // Sessão salva? Valida contra a API (aproveita pra carregar o overview).
    if (m_LoggedIn)
        refresh(QString(), false);
}

void XdApi::refresh(const QString& query, bool onlyRunning)
{
    if (!m_LoggedIn) return;
    setBusy(true);
    setError(QString());

    request("GET", QStringLiteral("admin/xd/overview"), {}, [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    m_Overview = root.toVariantMap();
                    emit overviewChanged();
                } else {
                    handleXdError(status, root, tr("Falha ao carregar overview"));
                }
            });

    QString path = QStringLiteral("admin/xd/machines");
    QStringList params;
    if (!query.trimmed().isEmpty())
        params << QStringLiteral("q=") + QString(QUrl::toPercentEncoding(query.trimmed()));
    if (onlyRunning)
        params << QStringLiteral("onlyRunning=1");
    if (!params.isEmpty())
        path += QLatin1Char('?') + params.join(QLatin1Char('&'));

    request("GET", path, {}, [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300) {
                    m_Machines = root.value(QStringLiteral("machines")).toArray().toVariantList();
                    m_ServerNow = root.value(QStringLiteral("serverNow")).toString();
                    emit machinesChanged();
                    emit serverNowChanged();
                } else {
                    handleXdError(status, root, tr("Falha ao listar VMs"));
                }
            });
}

void XdApi::loadMachineDetail(const QString& machineId)
{
    if (!m_LoggedIn || machineId.isEmpty()) return;
    request("GET", QStringLiteral("admin/xd/machines/") + machineId, {},
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    m_MachineDetail = root.value(QStringLiteral("machine")).toObject().toVariantMap();
                    m_MachineHistory = root.value(QStringLiteral("sessions")).toArray().toVariantList();
                    emit machineDetailChanged();
                } else {
                    handleXdError(status, root, tr("Falha ao carregar a VM"));
                }
            });
}

void XdApi::powerAction(const QString& machineId, const QString& action)
{
    if (!m_LoggedIn || machineId.isEmpty()) return;
    setBusy(true);
    request("POST", QStringLiteral("admin/xd/machines/") + machineId + QStringLiteral("/power"),
            QJsonObject{{QStringLiteral("action"), action}},
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300) {
                    emit actionFinished(true, root.value(QStringLiteral("message")).toString());
                } else {
                    emit actionFinished(false, errorMessageOf(root, tr("Falha na ação de energia")));
                    if (status == 401) clearSession();
                    if (status == 403) { m_AccessDenied = true; emit accessDeniedChanged(); }
                }
            });
}
