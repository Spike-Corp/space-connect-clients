#include "richpresencemanager.h"

#include <QDebug>
#include <QSettings>

RichPresenceManager::RichPresenceManager(StreamingPreferences& prefs, QString gameName, QString machineName)
    : m_DiscordActive(false)
{
#ifdef HAVE_DISCORD
    // App id do Discord: o nosso (vindo do backend via QSettings, cacheado no
    // login/startup) tem prioridade — é o que faz aparecer "Space Connect" como
    // nome do app no perfil. Sem ele, cai no id padrão do Moonlight (funciona,
    // mas com a marca deles).
    const QByteArray appId = QSettings()
        .value(QStringLiteral("launcher/discordAppId"), QStringLiteral("594668102021677159"))
        .toString().toUtf8();

    if (prefs.richPresence) {
        DiscordEventHandlers handlers = {};
        handlers.ready = discordReady;
        handlers.disconnected = discordDisconnected;
        handlers.errored = discordErrored;
        Discord_Initialize(appId.constData(), &handlers, 0, nullptr);
        m_DiscordActive = true;
    }

    if (m_DiscordActive) {
        // PT-BR: "Jogando <game>" + "via Space Connect · <máquina>".
        QByteArray detailsStr = (QStringLiteral("Jogando ") + gameName).toUtf8();
        QByteArray stateStr = machineName.isEmpty()
            ? QByteArray("via Space Connect")
            : (QStringLiteral("via Space Connect · ") + machineName).toUtf8();

        DiscordRichPresence discordPresence = {};
        discordPresence.details = detailsStr.constData();
        discordPresence.state = stateStr.constData();
        discordPresence.startTimestamp = time(nullptr);
        discordPresence.largeImageKey = "icon";
        discordPresence.largeImageText = "Space Connect";
        Discord_UpdatePresence(&discordPresence);
    }
#else
    Q_UNUSED(prefs)
    Q_UNUSED(gameName)
    Q_UNUSED(machineName)
#endif
}

RichPresenceManager::~RichPresenceManager()
{
#ifdef HAVE_DISCORD
    if (m_DiscordActive) {
        Discord_ClearPresence();
        Discord_Shutdown();
    }
#endif
}

void RichPresenceManager::runCallbacks()
{
#ifdef HAVE_DISCORD
    if (m_DiscordActive) {
        Discord_RunCallbacks();
    }
#endif
}

#ifdef HAVE_DISCORD
void RichPresenceManager::discordReady(const DiscordUser* request)
{
    qInfo() << "Discord integration ready for user:" << request->username;
}

void RichPresenceManager::discordDisconnected(int errorCode, const char *message)
{
    qInfo() << "Discord integration disconnected:" << errorCode << message;
}

void RichPresenceManager::discordErrored(int errorCode, const char *message)
{
    qWarning() << "Discord integration error:" << errorCode << message;
}
#endif
