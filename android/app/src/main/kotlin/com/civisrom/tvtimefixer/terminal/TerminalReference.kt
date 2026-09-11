package com.civisrom.tvtimefixer.terminal

import com.civisrom.tvtimefixer.R

// Examples supported by the embedded Android client, adapted from desktop help and AOSP adb.1.
internal val additionalTerminalExamples: Map<String, List<CommandExample>> = mapOf(
    "connection" to listOf(
        CommandExample("reference_1", R.string.terminal_reference_1, "adb devices -l"),
        CommandExample("reference_5", R.string.terminal_reference_5, "adb disconnect IP:PORT"),
        CommandExample("reference_15", R.string.terminal_reference_15, "adb --help"),
        CommandExample("reference_16", R.string.terminal_reference_16, "adb help"),
    ),
    "files" to listOf(
        CommandExample("reference_24", R.string.terminal_reference_24, "adb push \"local file.txt\" /sdcard/Download/file.txt"),
        CommandExample("reference_25", R.string.terminal_reference_25, "adb pull /sdcard/Download/file.txt \"local file.txt\""),
        CommandExample("reference_28", R.string.terminal_reference_28, "adb shell ls -l /sdcard/Download"),
        CommandExample("reference_30", R.string.terminal_reference_30, "adb shell mv /sdcard/Download/old.txt /sdcard/Download/new.txt"),
        CommandExample("reference_31", R.string.terminal_reference_31, "adb shell rm /sdcard/Download/file.txt"),
        CommandExample("reference_32", R.string.terminal_reference_32, "adb shell rmdir /sdcard/Download/adb-demo"),
        CommandExample("reference_33", R.string.terminal_reference_33, "adb shell screencap -p /sdcard/screen.png"),
        CommandExample("reference_34", R.string.terminal_reference_34, "adb pull /sdcard/screen.png screen.png"),
        CommandExample("reference_35", R.string.terminal_reference_35, "adb shell screenrecord --time-limit 30 /sdcard/demo.mp4"),
        CommandExample("reference_36", R.string.terminal_reference_36, "adb pull /sdcard/demo.mp4 demo.mp4"),
    ),
    "apps" to listOf(
        CommandExample("reference_37", R.string.terminal_reference_37, "adb install \"app.apk\""),
        CommandExample("reference_38", R.string.terminal_reference_38, "adb install -r \"app.apk\""),
        CommandExample("reference_39", R.string.terminal_reference_39, "adb install-multiple \"base.apk\" \"split_config.apk\""),
        CommandExample("reference_42", R.string.terminal_reference_42, "adb shell pm list packages"),
        CommandExample("reference_45", R.string.terminal_reference_45, "adb shell pm uninstall --user USER_ID PACKAGE"),
        CommandExample("reference_46", R.string.terminal_reference_46, "adb shell pm disable-user --user USER_ID PACKAGE"),
        CommandExample("reference_47", R.string.terminal_reference_47, "adb shell pm enable --user USER_ID PACKAGE"),
        CommandExample("reference_48", R.string.terminal_reference_48, "adb shell pm clear --user USER_ID PACKAGE"),
        CommandExample("reference_49", R.string.terminal_reference_49, "adb shell am start -n PACKAGE/ACTIVITY"),
        CommandExample("reference_52", R.string.terminal_reference_52, "adb shell am broadcast -a ACTION"),
        CommandExample("reference_extra_2", R.string.terminal_reference_extra_2, "adb uninstall -k PACKAGE"),
    ),
    "input" to listOf(
        CommandExample("reference_57", R.string.terminal_reference_57, "adb shell input tap X Y"),
        CommandExample("reference_58", R.string.terminal_reference_58, "adb shell input swipe X1 Y1 X2 Y2 DURATION_MS"),
    ),
    "time" to listOf(
        CommandExample("reference_66", R.string.terminal_reference_66, "adb shell settings delete global ntp_server"),
    ),
    "logs" to listOf(
        CommandExample("reference_73", R.string.terminal_reference_73, "adb shell dumpsys SERVICE"),
        CommandExample("reference_75", R.string.terminal_reference_75, "adb logcat -b crash -d"),
        CommandExample("reference_76", R.string.terminal_reference_76, "adb logcat"),
        CommandExample("reference_extra_1", R.string.terminal_reference_extra_1, "adb bugreport"),
    ),
    "services" to listOf(
        CommandExample("reference_100", R.string.terminal_reference_100, "adb reboot bootloader"),
        CommandExample("reference_102", R.string.terminal_reference_102, "adb reboot sideload"),
        CommandExample("reference_103", R.string.terminal_reference_103, "adb reboot sideload-auto-reboot"),
        CommandExample("reference_108", R.string.terminal_reference_108, "adb disable-verity"),
        CommandExample("reference_109", R.string.terminal_reference_109, "adb enable-verity"),
        CommandExample("reference_extra_0", R.string.terminal_reference_extra_0, "adb remount"),
    ),
)

internal val additionalTerminalCategories: List<CommandCategory> = listOf(
    CommandCategory("help_device", R.string.terminal_category_help_device, listOf(
        CommandExample("reference_115", R.string.terminal_reference_115, "adb exec-out COMMAND"),
        CommandExample("reference_118", R.string.terminal_reference_118, "adb shell toybox"),
        CommandExample("reference_122", R.string.terminal_reference_122, "adb shell cmd SERVICE help"),
        CommandExample("reference_124", R.string.terminal_reference_124, "adb shell input --help"),
        CommandExample("reference_125", R.string.terminal_reference_125, "adb shell dumpsys --help"),
        CommandExample("reference_126", R.string.terminal_reference_126, "adb shell top --help"),
        CommandExample("reference_127", R.string.terminal_reference_127, "adb shell screenrecord --help"),
        CommandExample("reference_128", R.string.terminal_reference_128, "adb logcat --help"),
        CommandExample("reference_extra_5", R.string.terminal_reference_extra_5, "adb shell cmd package help"),
    )),
)
