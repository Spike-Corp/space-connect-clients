#pragma once

#include "launcherapi.h"
#include <QPointer>
#include <QVariantList>
#include <QVariantMap>

class StreamGuestController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool ownerSession READ ownerSession NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(bool panelOpen READ panelOpen WRITE setPanelOpen NOTIFY changed)
    Q_PROPERTY(bool loaded READ loaded NOTIFY changed)
    Q_PROPERTY(QVariantList guests READ guests NOTIFY changed)
    Q_PROPERTY(int unidentifiedCount READ unidentifiedCount NOTIFY changed)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY changed)
    Q_PROPERTY(QString notice READ notice NOTIFY changed)
    Q_PROPERTY(QVariantMap partialResult READ partialResult NOTIFY changed)
    Q_PROPERTY(QString confirmationName READ confirmationName NOTIFY changed)

public:
    StreamGuestController(LauncherApi* api, const QString& machineId, quint64 account,
                          bool owner, QObject* parent = nullptr);
    ~StreamGuestController() override;
    bool ownerSession() const { return m_Owner && m_CurrentAccount && !m_Disposed; }
    bool busy() const { return m_Work != Idle; }
    bool panelOpen() const { return m_PanelOpen; }
    bool loaded() const { return m_Loaded; }
    QVariantList guests() const { return m_Guests; }
    int unidentifiedCount() const { return m_Unidentified; }
    QString errorMessage() const { return m_Error; }
    QString notice() const { return m_Notice; }
    QVariantMap partialResult() const { return m_PartialResult; }
    QString confirmationName() const { return m_ConfirmationName; }

    void start();
    void dispose();
    void setPanelOpen(bool open);
    Q_INVOKABLE void refresh();
    Q_INVOKABLE void requestDisconnect(const QString& userId);
    Q_INVOKABLE void cancelDisconnect();
    Q_INVOKABLE void confirmDisconnect();

signals:
    void changed();
    void noticeRaised(QString message);

private:
    enum Work { Idle, Identity, List, Disconnect };
    bool checkAccount();
    void identify();
    void submit(Work work, const QByteArray& method, const QString& path, const QJsonObject& body,
                std::function<void(const QJsonObject&)> complete);
    void cancelRequest();
    void fail(const QJsonObject& error = {});
    void invalidResponse();

    QPointer<LauncherApi> m_Api;
    const QString m_MachineId;
    const quint64 m_Account;
    const bool m_Owner;
    bool m_CurrentAccount = true, m_Started = false, m_Disposed = false;
    bool m_PanelOpen = false, m_Loaded = false, m_CanManage = false;
    Work m_Work = Idle;
    quint64 m_Generation = 0;
    QObject* m_Scope = nullptr;
    QString m_BindingId, m_Error, m_Notice, m_ConfirmationId, m_ConfirmationName;
    QVariantList m_Guests;
    QVariantMap m_PartialResult;
    int m_Unidentified = 0;
};
