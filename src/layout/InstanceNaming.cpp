#include "layout/InstanceNaming.h"

#include <QRegularExpression>
#include <algorithm>

namespace chunkdaddy {
namespace {

/// Last path component, treating both separators as such.
///
/// A Windows path can reach a Linux build through a saved project or a protocol message,
/// and QFileInfo only recognizes the host platform's separator, so backslashes are
/// normalized here rather than relying on it.
QString lastPathComponent(const QString& path) {
    const int slash = std::max(path.lastIndexOf(QLatin1Char('/')), path.lastIndexOf(QLatin1Char('\\')));
    return slash >= 0 ? path.mid(slash + 1) : path;
}

QString stripExtension(const QString& fileName) {
    const int dot = fileName.lastIndexOf(QLatin1Char('.'));
    return dot > 0 ? fileName.left(dot) : fileName;
}

} // namespace

QString InstanceNaming::suggestSlug(const QString& fileName) {
    QString base = stripExtension(lastPathComponent(fileName)).trimmed().toLower();

    static const QRegularExpression nonAlnum(QStringLiteral("[^a-z0-9]+"));
    static const QRegularExpression edges(QStringLiteral("^-+|-+$"));
    base.replace(nonAlnum, QStringLiteral("-"));
    base.remove(edges);

    // Strip a leading numeric ordering prefix, but only when something remains; a file
    // genuinely named "12" keeps its name.
    static const QRegularExpression numericPrefix(QStringLiteral("^[0-9]+-(?=.)"));
    const QString stripped = QString(base).remove(numericPrefix);
    if (!stripped.isEmpty()) {
        base = stripped;
    }
    return base.isEmpty() ? QStringLiteral("template") : base;
}

QString InstanceNaming::exportId(const QString& slug, int ordinal) {
    return QStringLiteral("%1-%2").arg(slug).arg(ordinal);
}

bool InstanceNaming::isValidSlug(const QString& slug) {
    static const QRegularExpression pattern(QStringLiteral("\\A[a-z0-9]+(-[a-z0-9]+)*\\z"));
    return pattern.match(slug).hasMatch();
}

bool InstanceNaming::naturalLess(const QString& a, const QString& b) {
    // Deliberately not QCollator: its numeric mode depends on an ICU build being present,
    // and placement order must be identical on every machine. "2-oriental" has to sort
    // before "10-greek" whether or not the host has ICU.
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
        const QChar ca = a.at(i);
        const QChar cb = b.at(j);

        if (ca.isDigit() && cb.isDigit()) {
            // Skip leading zeros so "007" and "7" compare equal in value.
            int startA = i;
            int startB = j;
            while (startA < a.size() && a.at(startA) == QLatin1Char('0')) ++startA;
            while (startB < b.size() && b.at(startB) == QLatin1Char('0')) ++startB;

            int endA = startA;
            int endB = startB;
            while (endA < a.size() && a.at(endA).isDigit()) ++endA;
            while (endB < b.size() && b.at(endB).isDigit()) ++endB;

            const int lengthA = endA - startA;
            const int lengthB = endB - startB;
            if (lengthA != lengthB) {
                return lengthA < lengthB;
            }
            for (int k = 0; k < lengthA; ++k) {
                if (a.at(startA + k) != b.at(startB + k)) {
                    return a.at(startA + k) < b.at(startB + k);
                }
            }
            i = endA;
            j = endB;
            continue;
        }

        const QChar lowerA = ca.toLower();
        const QChar lowerB = cb.toLower();
        if (lowerA != lowerB) {
            return lowerA < lowerB;
        }
        ++i;
        ++j;
    }
    return (a.size() - i) < (b.size() - j);
}

QStringList InstanceNaming::sortNaturally(QStringList names) {
    std::sort(names.begin(), names.end(), [](const QString& a, const QString& b) {
        return naturalLess(lastPathComponent(a), lastPathComponent(b));
    });
    return names;
}

QString InstanceNaming::deduplicate(const QString& slug, const QSet<QString>& taken) {
    if (!taken.contains(slug)) {
        return slug;
    }
    for (int suffix = 2; suffix < 1000; ++suffix) {
        const QString candidate = QStringLiteral("%1-%2").arg(slug).arg(suffix);
        if (!taken.contains(candidate)) {
            return candidate;
        }
    }
    return slug;
}

} // namespace chunkdaddy
