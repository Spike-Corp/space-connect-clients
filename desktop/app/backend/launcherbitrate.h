#pragma once

#include <QString>
#include <QVariantList>
#include <QVariantMap>

// Resolução do teto de bitrate de vídeo por tipo de host (plano da VM). Só QtCore, sem
// rede/GUI, pra poder ser testada sozinha (tests/launcherbitrate_test.cpp).
namespace LauncherBitrate {

// Perfis do seletor de plano nas configurações. Os tetos espelham a política do backend
// (bitrateTierForProvider): host dedicado/proxmox 100 Mbps, cloud 25 Mbps.
enum Profile {
    ProfileAuto = 0,
    ProfilePhysical = 1,
    ProfileCloud = 2,
};

constexpr int kPhysicalMaxKbps = 100000;
constexpr int kPhysicalRecommendedKbps = 50000;
constexpr int kCloudMaxKbps = 25000;
constexpr int kCloudRecommendedKbps = 25000;

struct Detection {
    QString tier;    // "physical" | "cloud" | "" (desconhecido)
    QString source;  // "machine" | "machines" | "cache" | "" (nada detectado)
    QString machineName;
    QString planName;
    int maxKbps = 0;
    int recommendedKbps = 0;

    // Veio do backend por VM (e não do cache global da última conexão)?
    bool fromBackend() const
    {
        return source == QLatin1String("machine") || source == QLatin1String("machines");
    }
};

inline int normalizeProfile(int profile)
{
    return (profile == ProfilePhysical || profile == ProfileCloud) ? profile : ProfileAuto;
}

// machines: entradas do LauncherApi (id, name, planName, bitrateTier, maxBitrateKbps,
// recommendedBitrateKbps). machineId: VM em uso (sessão ativa ou escolhida no seletor);
// vazio = nenhuma escolhida. cached*: teto salvo da última conexão (0 = nunca recebido).
// Ordem: VM em uso -> todas as VMs (se forem do mesmo tipo) -> cache.
inline Detection detect(const QVariantList& machines,
                        const QString& machineId,
                        int cachedMaxKbps,
                        int cachedRecommendedKbps)
{
    Detection detection;

    const auto fromMachine = [&detection](const QVariantMap& map, const QString& source) {
        detection.tier = map.value(QStringLiteral("bitrateTier")).toString();
        detection.source = source;
        detection.machineName = map.value(QStringLiteral("name")).toString();
        detection.planName = map.value(QStringLiteral("planName")).toString();
        detection.maxKbps = map.value(QStringLiteral("maxBitrateKbps")).toInt();
        detection.recommendedKbps = map.value(QStringLiteral("recommendedBitrateKbps")).toInt();
    };

    if (!machineId.isEmpty()) {
        for (const QVariant& item : machines) {
            const QVariantMap map = item.toMap();
            if (map.value(QStringLiteral("id")).toString() == machineId
                && map.value(QStringLiteral("maxBitrateKbps")).toInt() > 0) {
                fromMachine(map, QStringLiteral("machine"));
                return detection;
            }
        }
    }
    else {
        // Conta com 2+ VMs e nenhuma escolhida: só dá pra afirmar o tipo se todas as VMs
        // classificadas pelo backend forem do mesmo tipo.
        QVariantMap first;
        int classified = 0;
        bool uniform = true;
        for (const QVariant& item : machines) {
            const QVariantMap map = item.toMap();
            if (map.value(QStringLiteral("maxBitrateKbps")).toInt() <= 0)
                continue;
            classified++;
            if (first.isEmpty())
                first = map;
            else if (map.value(QStringLiteral("bitrateTier")).toString()
                     != first.value(QStringLiteral("bitrateTier")).toString())
                uniform = false;
        }
        if (classified > 0 && uniform) {
            fromMachine(first, QStringLiteral("machines"));
            if (classified > 1) {
                detection.machineName.clear();
                detection.planName.clear();
            }
            return detection;
        }
    }

    if (cachedMaxKbps > 0) {
        detection.source = QStringLiteral("cache");
        detection.maxKbps = cachedMaxKbps;
        detection.recommendedKbps = cachedRecommendedKbps;
    }
    return detection;
}

// Teto efetivo em Kbps (0 = desconhecido; quem chama cai na heurística local).
inline int effectiveMaxKbps(const Detection& detection, int profile)
{
    profile = normalizeProfile(profile);
    if (profile == ProfileAuto)
        return detection.maxKbps;

    int ceiling = profile == ProfilePhysical ? kPhysicalMaxKbps : kCloudMaxKbps;
    // Uma VM que o backend já confirmou com teto menor (cloud) nunca passa dele, mesmo com
    // "Host dedicado" marcado à mão: o seletor é pra quando o app não consegue detectar.
    if (detection.fromBackend() && detection.maxKbps > 0 && detection.maxKbps < ceiling)
        ceiling = detection.maxKbps;
    return ceiling;
}

inline int effectiveRecommendedKbps(const Detection& detection, int profile)
{
    profile = normalizeProfile(profile);
    if (profile == ProfileAuto)
        return detection.recommendedKbps;

    const int recommended =
        profile == ProfilePhysical ? kPhysicalRecommendedKbps : kCloudRecommendedKbps;
    // qMin, não std::min: o windows.h (sem NOMINMAX) define min() como macro e quebra o MSVC.
    return qMin(recommended, effectiveMaxKbps(detection, profile));
}

}  // namespace LauncherBitrate
