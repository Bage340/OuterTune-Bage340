package com.dd3boh.outertune.utils

/** Local organization paths, independent of playlist titles and remote metadata. */
object PlaylistFolders {
    const val ROOT = "/"

    data class Contents(
        val playlistIndexes: List<Int>,
        val folderPaths: List<String>,
    )

    fun canonical(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." || it.any(Char::isISOControl) })
        return if (segments.isEmpty()) ROOT else segments.joinToString("/", "/", "/")
    }

    fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
        name != "." && name != ".." && '/' !in name && name.none(Char::isISOControl)

    fun child(parent: String, name: String): String {
        require(validName(name))
        return canonical(parent) + name + "/"
    }

    fun parent(path: String): String = canonical(path).trimEnd('/').substringBeforeLast('/', "")
        .let(::canonical)

    fun name(path: String): String = canonical(path).trimEnd('/').substringAfterLast('/')

    fun same(first: String, second: String): Boolean =
        canonical(first).equals(canonical(second), ignoreCase = true)

    fun contains(parent: String, path: String): Boolean =
        canonical(path).startsWith(canonical(parent), ignoreCase = true)

    fun resolve(path: String, paths: Collection<String>): String? =
        paths.firstOrNull { same(path, it) }?.let(::canonical)

    fun children(parent: String, paths: Collection<String>): List<String> {
        val base = canonical(parent)
        return paths.map(::canonical).filter { !same(it, base) && contains(base, it) }
            .map { base + it.substring(base.length).substringBefore('/') + "/" }
            .distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun relocate(path: String, from: String, to: String): String {
        require(contains(from, path))
        val current = canonical(path)
        val source = canonical(from)
        return canonical(to) + current.substring(source.length)
    }

    /**
     * Builds an atomic subtree move plan, or returns null when the destination is unsafe.
     */
    fun relocationPlan(
        source: String,
        destinationParent: String,
        paths: Collection<String>,
        newName: String = name(source),
    ): Map<String, String>? {
        if (!validName(newName)) return null
        val canonicalPaths = paths.map(::canonical)
        if (canonicalPaths.distinctBy { it.lowercase() }.size != canonicalPaths.size) return null
        val from = resolve(source, canonicalPaths) ?: return null
        val parent = if (same(destinationParent, ROOT)) ROOT
        else resolve(destinationParent, canonicalPaths) ?: return null
        if (from == ROOT || contains(from, parent)) return null

        val target = child(parent, newName)
        if (target == from) return emptyMap()
        val subtree = canonicalPaths.filter { contains(from, it) }
        val outside = canonicalPaths - subtree.toSet()
        val plan = subtree.associateWith { relocate(it, from, target) }
        return plan.takeIf { relocation ->
            relocation.values.none { targetPath -> outside.any { same(targetPath, it) } }
        }
    }

    fun contents(
        parent: String,
        playlistPaths: List<String>,
        folderPaths: Collection<String>,
    ): Contents {
        val base = canonical(parent)
        return Contents(
            playlistIndexes = playlistPaths.map(::canonical).mapIndexedNotNull { index, path ->
                index.takeIf { path == base }
            },
            folderPaths = children(base, folderPaths),
        )
    }

    fun pathAfterDelete(path: String, deletedFolder: String): String {
        val source = canonical(deletedFolder)
        val current = canonical(path)
        return if (contains(source, current)) parent(source) else current
    }
}
