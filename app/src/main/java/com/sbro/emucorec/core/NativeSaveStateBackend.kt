package com.sbro.emucorec.core

import net.rpcsx.RPCSX
import org.json.JSONArray

internal class NativeSaveStateBackend : SaveStateBackend {
    override fun slots(): List<SaveStateSlot> {
        val entries = JSONArray(RPCSX.instance.saveStateSlots())
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val slot = entry.getInt("index")
                if (slot in 0 until SaveStateController.SLOT_COUNT) {
                    add(SaveStateSlot(slot, entry.getLong("modified"), entry.getLong("size")))
                }
            }
        }
    }

    override fun save(slot: Int, progressId: Long) = RPCSX.instance.requestSaveState(slot, progressId)
    override fun load(slot: Int, progressId: Long) = RPCSX.instance.requestLoadState(slot, progressId)
}
