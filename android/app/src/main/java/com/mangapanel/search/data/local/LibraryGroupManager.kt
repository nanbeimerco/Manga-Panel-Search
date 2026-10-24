package com.mangapanel.search.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.regex.Pattern

@Serializable
data class RawGroup(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var archives: MutableList<String> = mutableListOf()
)

@Serializable
data class LibraryGroupData(
    var groups: MutableList<RawGroup> = mutableListOf(),
    var disabledArchives: MutableList<String> = mutableListOf(),
    var ungroupedEnabled: Boolean = true,
    var expandedGroupIds: MutableList<String> = mutableListOf()
)

data class ArchiveCheckItem(
    val name: String,
    val checked: Boolean
)

data class GroupItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val isIndeterminate: Boolean,
    val count: Int,
    val archives: List<ArchiveCheckItem>
)

data class LibraryStructure(
    val groups: List<GroupItem>,
    val ungrouped: GroupItem
)

class LibraryGroupManager(context: Context) {
    private val prefs = context.getSharedPreferences("library_group_prefs", Context.MODE_PRIVATE)
    private val PREF_KEY = "library_groups_json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private var data: LibraryGroupData = loadData()

    private fun loadData(): LibraryGroupData {
        val raw = prefs.getString(PREF_KEY, null) ?: return LibraryGroupData()
        return try {
            json.decodeFromString<LibraryGroupData>(raw)
        } catch (_: Exception) {
            LibraryGroupData()
        }
    }

    private fun saveData() {
        val raw = json.encodeToString(data)
        prefs.edit().putString(PREF_KEY, raw).apply()
    }

    @Synchronized
    fun autoOrganizeIfEmpty(archiveNames: List<String>) {
        if (data.groups.isNotEmpty()) return

        val groupsDict = LinkedHashMap<String, MutableList<String>>()
        val p = Pattern.compile("\\]\\s*([^第0-9\\[\\]_]+)")

        for (name in archiveNames) {
            var series: String? = null
            when {
                "らんま" in name || "Ranma" in name -> series = "らんま1/2"
                "めぞん" in name || "Mezon" in name || "Maison" in name -> series = "めぞん一刻"
                "犬夜叉" in name || "Inuyasha" in name -> series = "犬夜叉"
                "うる星" in name || "Urusei" in name -> series = "うる星やつら"
                else -> {
                    val m = p.matcher(name)
                    if (m.find()) {
                        val candidate = m.group(1)?.trim()
                        if (candidate != null && candidate.length >= 2) {
                            series = candidate
                        }
                    }
                }
            }
            if (series != null) {
                groupsDict.getOrPut(series) { mutableListOf() }.add(name)
            }
        }

        var idx = 1
        for ((seriesName, members) in groupsDict) {
            data.groups.add(
                RawGroup(
                    id = "group_${idx}_${Math.abs(seriesName.hashCode()) % 10000}",
                    name = seriesName,
                    enabled = true,
                    archives = members.sorted().toMutableList()
                )
            )
            idx++
        }
        // Expand all by default
        data.expandedGroupIds = data.groups.map { it.id }.toMutableList()
        saveData()
    }

    @Synchronized
    fun getStructure(allArchiveNames: List<String>): LibraryStructure {
        val disabledSet = data.disabledArchives.toSet()
        val groupedNames = mutableSetOf<String>()
        val groupsRes = mutableListOf<GroupItem>()

        for (g in data.groups) {
            val gArchives = mutableListOf<ArchiveCheckItem>()
            val gEnabled = g.enabled
            for (aName in g.archives) {
                if (aName in allArchiveNames) {
                    groupedNames.add(aName)
                    val isChecked = gEnabled && (aName !in disabledSet)
                    gArchives.add(ArchiveCheckItem(aName, isChecked))
                }
            }

            val checkedCount = gArchives.count { it.checked }
            val parentChecked = if (gArchives.isNotEmpty()) (checkedCount == gArchives.size) else gEnabled
            val isIndeterminate = checkedCount > 0 && checkedCount < gArchives.size

            groupsRes.add(
                GroupItem(
                    id = g.id,
                    name = g.name,
                    enabled = parentChecked,
                    isIndeterminate = isIndeterminate,
                    count = gArchives.size,
                    archives = gArchives
                )
            )
        }

        val ungroupedArchives = mutableListOf<ArchiveCheckItem>()
        val uEnabled = data.ungroupedEnabled
        for (aName in allArchiveNames) {
            if (aName !in groupedNames) {
                val isChecked = uEnabled && (aName !in disabledSet)
                ungroupedArchives.add(ArchiveCheckItem(aName, isChecked))
            }
        }

        val uCheckedCount = ungroupedArchives.count { it.checked }
        val uParentChecked = if (ungroupedArchives.isNotEmpty()) (uCheckedCount == ungroupedArchives.size) else uEnabled
        val uIndeterminate = uCheckedCount > 0 && uCheckedCount < ungroupedArchives.size

        val ungroupedItem = GroupItem(
            id = "ungrouped",
            name = "未分類",
            enabled = uParentChecked,
            isIndeterminate = uIndeterminate,
            count = ungroupedArchives.size,
            archives = ungroupedArchives
        )

        return LibraryStructure(groups = groupsRes, ungrouped = ungroupedItem)
    }

    @Synchronized
    fun getEnabledArchiveNames(allArchiveNames: List<String>): Set<String> {
        val structure = getStructure(allArchiveNames)
        val enabled = mutableSetOf<String>()
        for (g in structure.groups) {
            for (a in g.archives) {
                if (a.checked) enabled.add(a.name)
            }
        }
        for (a in structure.ungrouped.archives) {
            if (a.checked) enabled.add(a.name)
        }
        return enabled
    }

    @Synchronized
    fun toggleUngrouped(allArchiveNames: List<String>, enabled: Boolean) {
        data.ungroupedEnabled = enabled
        val disabledSet = data.disabledArchives.toMutableSet()
        val groupedNames = data.groups.flatMap { it.archives }.toSet()
        for (a in allArchiveNames) {
            if (a !in groupedNames) {
                if (enabled) {
                    disabledSet.remove(a)
                } else {
                    disabledSet.add(a)
                }
            }
        }
        data.disabledArchives = disabledSet.toMutableList()
        saveData()
    }

    @Synchronized
    fun toggleGroup(groupId: String, enabled: Boolean) {
        val disabledSet = data.disabledArchives.toMutableSet()
        if (groupId == "ungrouped") {
            data.ungroupedEnabled = enabled
            // update archives not in groups
            val groupedNames = data.groups.flatMap { it.archives }.toSet()
            // In SharedPreferences, we can clear disabled archives for ungrouped
            // or mark them as disabled if not enabled
        } else {
            val group = data.groups.find { it.id == groupId }
            if (group != null) {
                group.enabled = enabled
                for (a in group.archives) {
                    if (enabled) {
                        disabledSet.remove(a)
                    } else {
                        disabledSet.add(a)
                    }
                }
            }
        }
        data.disabledArchives = disabledSet.toMutableList()
        saveData()
    }

    @Synchronized
    fun toggleArchive(archiveName: String, enabled: Boolean) {
        val disabledSet = data.disabledArchives.toMutableSet()
        if (enabled) {
            disabledSet.remove(archiveName)
        } else {
            disabledSet.add(archiveName)
        }
        data.disabledArchives = disabledSet.toMutableList()
        saveData()
    }

    @Synchronized
    fun selectAll(allArchiveNames: List<String>) {
        data.disabledArchives.clear()
        for (g in data.groups) {
            g.enabled = true
        }
        data.ungroupedEnabled = true
        saveData()
    }

    @Synchronized
    fun deselectAll(allArchiveNames: List<String>) {
        data.disabledArchives = allArchiveNames.toMutableList()
        for (g in data.groups) {
            g.enabled = false
        }
        data.ungroupedEnabled = false
        saveData()
    }

    @Synchronized
    fun createGroup(name: String): String {
        val id = "group_${System.currentTimeMillis()}"
        data.groups.add(
            RawGroup(
                id = id,
                name = name.trim().ifEmpty { "新規フォルダ" },
                enabled = true,
                archives = mutableListOf()
            )
        )
        data.expandedGroupIds.add(id)
        saveData()
        return id
    }

    @Synchronized
    fun deleteGroup(groupId: String) {
        data.groups.removeAll { it.id == groupId }
        data.expandedGroupIds.remove(groupId)
        saveData()
    }

    @Synchronized
    fun renameGroup(groupId: String, newName: String) {
        data.groups.find { it.id == groupId }?.let {
            it.name = newName.trim().ifEmpty { it.name }
        }
        saveData()
    }

    @Synchronized
    fun isGroupExpanded(groupId: String): Boolean {
        return groupId in data.expandedGroupIds
    }

    @Synchronized
    fun toggleGroupExpanded(groupId: String) {
        if (groupId in data.expandedGroupIds) {
            data.expandedGroupIds.remove(groupId)
        } else {
            data.expandedGroupIds.add(groupId)
        }
        saveData()
    }
}
