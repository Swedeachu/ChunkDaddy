#pragma once

#include <QSet>
#include <QString>
#include <QStringList>

namespace chunkdaddy {

/// Slug derivation and export-name rules.
///
/// Export IDs are `slug-ordinal`. A slug can itself contain a hyphen
/// (`tropical-ruins`), so an export ID must never be split on `-` to recover the
/// template family; use the recorded template UUID instead.
class InstanceNaming {
public:
    /// Suggest a slug from a schematic file name, stripping a leading numeric prefix.
    static QString suggestSlug(const QString& fileName);

    static QString exportId(const QString& slug, int ordinal);

    static bool isValidSlug(const QString& slug);

    /// Natural numeric ordering, so `2-oriental` sorts before `10-greek`.
    static bool naturalLess(const QString& a, const QString& b);

    /// Sort file names naturally. Placement order is then saved, never re-derived from
    /// a directory listing.
    static QStringList sortNaturally(QStringList names);

    /// Make `slug` unique against `taken`, by appending `-2`, `-3` and so on.
    static QString deduplicate(const QString& slug, const QSet<QString>& taken);
};

} // namespace chunkdaddy
