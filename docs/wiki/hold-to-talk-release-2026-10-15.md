# 按住说话松手提交修复

准备录音会修改 micPreparing/capturePreparing，进而修改按钮 enabled。旧版 pointerInput(enabled) 因 key 变化重启手势协程，可能让同一次按压失去松手回调。

改为稳定 pointerInput(Unit)，只在按下瞬间读取最新 enabled。回调使用 rememberUpdatedState；try/finally 在系统取消或界面退出时执行取消，正常松手只走结束提交。离开组合时即使仍在准备录音也清理按住状态，防止迟到录音。未识别到语音时提供明确重说提示。

assembleDebug 通过，新版安装 e7105a12。真机 19:19:30.756 按下，19:19:30.798 开始录音，19:19:32.563 收到松手（recording=true），19:19:32.623 进入提交路径。该检查覆盖录音准备到松手的完整手势，并非仅检查编译；没有据此声称所有识别准确率或取消边界均已人工验收。
