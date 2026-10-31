#include "streamguestcontroller.h"
#include "identitymanager.h"

#include <QJsonArray>
#include <QSet>
#include <QUrlQuery>
#include <cmath>
#include <stdexcept>

namespace {
bool text(const QJsonValue& value, int limit)
{
    return value.isString() && !value.toString().trimmed().isEmpty() && value.toString().size() <= limit;
}

bool count(const QJsonValue& value)
{
    const double number = value.toDouble(-1);
    return value.isDouble() && number >= 0 && number <= 64 && std::floor(number) == number;
}
}

StreamGuestController::StreamGuestController(LauncherApi* api, const QString& machineId, quint64 account,
                                             bool owner, QObject* parent)
    : QObject(parent), m_Api(api), m_MachineId(machineId), m_Account(account), m_Owner(owner)
{
    connect(api, &LauncherApi::accountChanging, this, [this]() {
        m_CurrentAccount = false;
        checkAccount();
    });
    connect(api, &QObject::destroyed, this, [this]() { checkAccount(); });
    checkAccount();
}

StreamGuestController::~StreamGuestController() { dispose(); }

bool StreamGuestController::checkAccount()
{
    if (m_CurrentAccount && m_Api && m_Api->loggedIn() && m_Api->accountGeneration() == m_Account)
        return true;
    m_CurrentAccount = false;
    cancelRequest();
    m_BindingId.clear();
    m_Guests.clear();
    m_Loaded = m_CanManage = m_PanelOpen = false;
    m_Notice.clear();
    m_PartialResult.clear();
    m_ConfirmationId.clear();
    m_ConfirmationName.clear();
    m_Error = tr("Your account changed. Reconnect to use guest controls.");
    emit changed();
    return false;
}

void StreamGuestController::start()
{
    if (m_Started || m_Disposed) return;
    m_Started = true;
    identify();
}

void StreamGuestController::identify()
{
    if (busy() || m_Disposed || !checkAccount()) return;
    const QString certificate = QString::fromUtf8(IdentityManager::get()->getCertificate());
    submit(Identity, "POST", QStringLiteral("stream/identity/challenge"),
           {{QStringLiteral("machineId"), m_MachineId}, {QStringLiteral("certificatePem"), certificate}},
           [this, certificate](const QJsonObject& root) {
        const auto challenge = root.value(QStringLiteral("challenge"));
        if (!text(challenge, 8192) || challenge.toString().toUtf8().size() > 8192) {
            invalidResponse();
            return;
        }
        QByteArray signature;
        try {
            signature = IdentityManager::get()->signStreamChallenge(challenge.toString());
        }
        catch (const std::runtime_error&) {
            qWarning("Could not sign the paired stream identity");
            fail();
            return;
        }
        submit(Identity, "POST", QStringLiteral("stream/identity"),
               {{QStringLiteral("machineId"), m_MachineId}, {QStringLiteral("certificatePem"), certificate},
                {QStringLiteral("challenge"), challenge}, {QStringLiteral("signature"), QString::fromLatin1(signature)}},
               [this](const QJsonObject& registration) {
            if (!text(registration.value(QStringLiteral("bindingId")), 128)
                    || !registration.value(QStringLiteral("canManageGuests")).isBool()) {
                invalidResponse();
                return;
            }
            m_BindingId = registration.value(QStringLiteral("bindingId")).toString();
            m_CanManage = registration.value(QStringLiteral("canManageGuests")).toBool();
            if (m_Owner && !m_CanManage)
                fail({{QStringLiteral("code"), QStringLiteral("STREAM_OWNER_REQUIRED")}});
            else if (m_PanelOpen)
                refresh();
        });
    });
}

void StreamGuestController::submit(Work work, const QByteArray& method, const QString& path,
                                   const QJsonObject& body, std::function<void(const QJsonObject&)> complete)
{
    if (m_Disposed || busy() || !checkAccount()) return;
    m_Work = work;
    m_Error.clear();
    m_PartialResult.clear();
    const quint64 generation = ++m_Generation;
    m_Scope = new QObject(this);
    m_Api->streamRequest(m_Account, method, path, body, *m_Scope,
                        [this, generation, complete](int status, const QJsonObject& root) {
        if (m_Disposed || generation != m_Generation) return;
        m_Scope->deleteLater();
        m_Scope = nullptr;
        m_Work = Idle;
        if (!checkAccount()) return;
        if (status < 200 || status >= 300)
            fail(root.value(QStringLiteral("error")).toObject());
        else
            complete(root);
        emit changed();
    });
    emit changed();
}

void StreamGuestController::setPanelOpen(bool open)
{
    if (open && (m_Disposed || !ownerSession() || !checkAccount())) return;
    if (m_PanelOpen == open) return;
    m_PanelOpen = open;
    m_ConfirmationId.clear();
    m_ConfirmationName.clear();
    if (!open) {
        m_Guests.clear();
        m_Loaded = false;
        if (m_Work == List) cancelRequest();
    }
    emit changed();
    if (open) {
        if (!m_Started) start();
        else if (!busy()) refresh();
    }
}

void StreamGuestController::refresh()
{
    if (!m_PanelOpen || !ownerSession() || busy() || !checkAccount()) return;
    m_ConfirmationId.clear();
    m_ConfirmationName.clear();
    if (m_BindingId.isEmpty()) {
        identify();
        return;
    }
    if (!m_CanManage) {
        fail({{QStringLiteral("code"), QStringLiteral("STREAM_OWNER_REQUIRED")}});
        emit changed();
        return;
    }
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("machineId"), m_MachineId);
    query.addQueryItem(QStringLiteral("bindingId"), m_BindingId);
    submit(List, "GET", QStringLiteral("stream/guests?") + query.toString(QUrl::FullyEncoded), {},
           [this](const QJsonObject& root) {
        const auto guests = root.value(QStringLiteral("guests"));
        const auto unknown = root.value(QStringLiteral("unidentifiedCount"));
        if (!guests.isArray() || guests.toArray().size() > 64 || !count(unknown)) {
            invalidResponse();
            return;
        }
        QSet<QString> accounts, bindings{m_BindingId};
        int devices = unknown.toInt();
        for (const auto& item : guests.toArray()) {
            const QJsonObject guest = item.toObject();
            const QString userId = guest.value(QStringLiteral("userId")).toString();
            const auto clients = guest.value(QStringLiteral("devices"));
            if (!text(guest.value(QStringLiteral("userId")), 128)
                    || !text(guest.value(QStringLiteral("displayName")), 512)
                    || accounts.contains(userId) || !clients.isArray() || clients.toArray().isEmpty()) {
                invalidResponse();
                return;
            }
            accounts.insert(userId);
            for (const auto& client : clients.toArray()) {
                const auto device = client.toObject();
                const auto binding = device.value(QStringLiteral("bindingId"));
                if (!text(binding, 128) || bindings.contains(binding.toString())
                        || !device.value(QStringLiteral("platform")).isString() || ++devices > 64) {
                    invalidResponse();
                    return;
                }
                bindings.insert(binding.toString());
            }
        }
        m_Guests = guests.toArray().toVariantList();
        m_Unidentified = unknown.toInt();
        m_Loaded = true;
    });
}

void StreamGuestController::requestDisconnect(const QString& userId)
{
    if (!m_PanelOpen || !ownerSession() || busy() || !m_Loaded || !checkAccount()) return;
    for (const auto& guest : m_Guests) {
        if (guest.toMap().value(QStringLiteral("userId")).toString() == userId) {
            m_ConfirmationId = userId;
            m_ConfirmationName = guest.toMap().value(QStringLiteral("displayName")).toString();
            emit changed();
            return;
        }
    }
    m_Error = tr("Refresh the guests before selecting an account.");
    emit changed();
}

void StreamGuestController::cancelDisconnect()
{
    m_ConfirmationId.clear();
    m_ConfirmationName.clear();
    emit changed();
}

void StreamGuestController::confirmDisconnect()
{
    if (!m_PanelOpen || !ownerSession() || busy() || m_ConfirmationId.isEmpty() || !checkAccount()) return;
    const QString userId = m_ConfirmationId;
    m_ConfirmationId.clear();
    m_ConfirmationName.clear();
    m_Guests.clear();
    m_Loaded = false;
    m_Notice.clear();
    submit(Disconnect, "POST", QStringLiteral("stream/guests/disconnect"),
           {{QStringLiteral("machineId"), m_MachineId}, {QStringLiteral("bindingId"), m_BindingId},
            {QStringLiteral("guestUserId"), userId}}, [this](const QJsonObject& root) {
        if (!count(root.value(QStringLiteral("requested"))) || !count(root.value(QStringLiteral("alreadyDisconnected")))
                || root.value(QStringLiteral("requested")).toInt() + root.value(QStringLiteral("alreadyDisconnected")).toInt() > 64) {
            invalidResponse();
            return;
        }
        m_Notice = root.value(QStringLiteral("requested")).toInt() > 0
                ? tr("Disconnection requested. Your friend can reconnect.")
                : tr("This friend is no longer connected.");
        if (m_PanelOpen) refresh();
        else emit noticeRaised(m_Notice);
    });
}

void StreamGuestController::invalidResponse()
{
    qWarning("Invalid stream guest response");
    fail();
}

void StreamGuestController::fail(const QJsonObject& error)
{
    m_Guests.clear();
    m_Loaded = false;
    m_Notice.clear();
    m_PartialResult.clear();
    m_Error = tr("Could not manage guests. Refresh and try again.");
    const QString code = error.value(QStringLiteral("code")).toString();
    if (code == QStringLiteral("STREAM_GUESTS_UNSUPPORTED") || code == QStringLiteral("STREAM_HOST_AUTH_UNAVAILABLE"))
        m_Error = tr("Guest controls are not available on this host.");
    else if (code == QStringLiteral("STREAM_OWNER_REQUIRED") || code == QStringLiteral("STREAM_OWNER_PROTECTED")
             || code == QStringLiteral("STREAM_ACCESS_DENIED"))
        m_Error = tr("Only the connected owner can manage guests. The owner cannot be disconnected.");
    else if (code == QStringLiteral("STREAM_HOST_CHANGED") || code == QStringLiteral("STREAM_BINDING_INVALID"))
        m_Error = tr("The host changed. Reconnect before managing guests.");
    else if (code == QStringLiteral("STREAM_GUEST_NOT_CONNECTED"))
        m_Error = tr("This friend is no longer connected.");
    else if (code == QStringLiteral("ACCESS_REVOKED") || code == QStringLiteral("ACCOUNT_CHANGED"))
        m_Error = tr("Your account changed. Reconnect to use guest controls.");
    const QStringList fields{QStringLiteral("requested"), QStringLiteral("alreadyDisconnected"),
                             QStringLiteral("failed"), QStringLiteral("notAttempted")};
    QVariantMap partial;
    int total = 0;
    for (const auto& field : fields) {
        if (!count(error.value(field))) break;
        partial.insert(field, error.value(field).toInt());
        total += error.value(field).toInt();
    }
    if (partial.size() == fields.size() && total <= 64) m_PartialResult = partial;
    qWarning("Stream guest operation failed");
    if (!m_PanelOpen) emit noticeRaised(m_Error);
}

void StreamGuestController::cancelRequest()
{
    ++m_Generation;
    delete m_Scope;
    m_Scope = nullptr;
    m_Work = Idle;
}

void StreamGuestController::dispose()
{
    if (m_Disposed) return;
    m_Disposed = true;
    setPanelOpen(false);
    cancelRequest();
    m_BindingId.clear();
    m_Guests.clear();
    m_Notice.clear();
    m_Error.clear();
    m_PartialResult.clear();
    m_Loaded = false;
    emit changed();
}
