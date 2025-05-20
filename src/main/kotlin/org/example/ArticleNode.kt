import org.example.dataDirectory
import org.jsoup.Jsoup
import java.io.File

data class ArticleNode(
    val id: String,
    val parentId: String?,
    val title: String,
    val url: String,
    val children: MutableList<ArticleNode> = mutableListOf()
)

val fileLog = File("$dataDirectory/application.log")

fun parseArticles(html: String): List<ArticleNode> {
    val doc = Jsoup.parse(html)
    val elements = doc.select("li.tree-item")

    val nodesById = mutableMapOf<String, ArticleNode>()

    for (el in elements) {
        val id = el.attr("keyname")
        val ancestorIds = el.attr("ancestorids")
        val parentId = ancestorIds.split(",").firstOrNull()
        val linkEl = el.selectFirst("a.tree-item__main-content-title-link")
        val url = linkEl?.attr("href") ?: continue
        val title = linkEl.text().trim()

        val node = ArticleNode(
            id = id,
            parentId = parentId,
            title = title,
            url = url
        )

        nodesById[id] = node
    }

    // Построим иерархию
    val roots = mutableListOf<ArticleNode>()
    for (node in nodesById.values) {
        if (node.parentId != null && nodesById.containsKey(node.parentId)) {
            nodesById[node.parentId]?.children?.add(node)
        } else {
            roots.add(node)
        }
    }
    return roots
}

fun printTree(node: ArticleNode, indent: String = "") {
    println("$indent- ${node.title} (${node.url})")
    if (!fileLog.exists()) {fileLog.createNewFile() }
    fileLog.appendText("$indent- ${node.title} (${node.url})\n")
    node.children.forEach { printTree(it, "$indent  ") }
}

//Функция для очистки имени файла от недопустимых символов
private fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

//Определение пути сохранения файла
fun ArticleNode.getFullPath(nodesById: Map<String, ArticleNode>): String {
    val pathParts = mutableListOf<String>()
    var current: ArticleNode? = this
    while (current != null) {
        pathParts.add(sanitizeFileName(current.title))
        current = current.parentId?.let { nodesById[it] }
    }
    return pathParts.reversed().joinToString("/")
}

//Вспомогательная функция
fun List<ArticleNode>.flattenToMap(): Map<String, ArticleNode> {
    val map = mutableMapOf<String, ArticleNode>()
    fun recurse(node: ArticleNode) {
        map[node.id] = node
        node.children.forEach { recurse(it) }
    }
    forEach { recurse(it) }
    return map
}
