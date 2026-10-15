package com.sbro.emucorec.core

import android.content.Context
import android.content.Intent
import com.sbro.emucorec.core.ps3.Emulator
import com.sbro.emucorec.data.InstalledGameRepository

object Ps3LaunchBridge {
    enum class LaunchResult { Success, MissingFirmware, Failure }

    fun launchSaveState(context: Context, titleId: String, slot: Int): LaunchResult {
        if (!EmulatorStorage.hasInstalledFirmware(context)) return LaunchResult.MissingFirmware
        return runCatching {
            val file = SaveStateRepository(context).stateFile(titleId, slot) ?: return LaunchResult.Failure
            context.startActivity(Intent(context, Emulator::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Emulator.EXTRA_TITLE_ID, titleId)
                putExtra(Emulator.EXTRA_GAME_PATH, file.absolutePath)
                action = ACTION_LAUNCH
            })
            LaunchResult.Success
        }.getOrDefault(LaunchResult.Failure)
    }

    fun launchInstalledTitle(context: Context, titleId: String): LaunchResult {
        if (!EmulatorStorage.hasInstalledFirmware(context)) return LaunchResult.MissingFirmware
        val game = InstalledGameRepository().findByTitleId(context, titleId) ?: return LaunchResult.Failure
        return runCatching {
            context.startActivity(
                Intent(context, Emulator::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Emulator.EXTRA_TITLE_ID, game.titleId)
                    putExtra(Emulator.EXTRA_GAME_PATH, game.installPath)
                    action = ACTION_LAUNCH
                }
            )
        }.fold({ LaunchResult.Success }, { LaunchResult.Failure })
    }

    fun launchSystemMenu(context: Context): LaunchResult {
        val vsh = EmulatorStorage.systemMenuExecutable(context)
        if (!vsh.isFile) return LaunchResult.MissingFirmware
        return runCatching {
            context.startActivity(
                Intent(context, Emulator::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Emulator.EXTRA_GAME_PATH, vsh.absolutePath)
                    action = ACTION_LAUNCH_SYSTEM_MENU
                }
            )
        }.fold({ LaunchResult.Success }, { LaunchResult.Failure })
    }

    fun installFirmware(context: Context, firmwarePath: String): Boolean =
        Ps3Runtime.installFirmware(context, firmwarePath)

    fun installPkg(context: Context, pkgPath: String): Boolean =
        Ps3Runtime.installPackage(context, pkgPath)

    private const val ACTION_LAUNCH = "com.sbro.emucorec.action.LAUNCH"
    private const val ACTION_LAUNCH_SYSTEM_MENU = "com.sbro.emucorec.action.LAUNCH_SYSTEM_MENU"
}
