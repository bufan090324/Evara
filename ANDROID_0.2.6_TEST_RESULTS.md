# Android 0.2.6 验证

增加 5 个针对实际节点特征的测试：缺少背景日期栏时选择关闭控件、拒绝禁用/无关关闭按钮、拒绝多个匹配、已展开日历取选中月日、关闭后允许星期文字差异但拒绝日期变化。保留日历年份、字段、连接控制等测试。

128 个 JVM 测试通过，0 失败。执行 testDebugUnitTest、assembleDebug、lintDebug，最终构建成功；Lint 0 错误、9 警告。APK 包名 cn.personal.phonebridge，versionName 0.2.6 / versionCode 13，apksigner 与 zipalign 检查通过，签名与既有版本一致。

直接回放用户导出的日历节点，生产 CalendarDate 解析器返回 selected_heading=10月7日、report_date=2026-10-07、close_path=0/12。该回放只验证解析与控件定位，不执行点击。

本次依据用户导出的活动日历根结构修复，不包含用户原始报告到源码包。没有连接真机或模拟器，未执行实际关闭点击及整条任务。电脑继续使用 Windows 0.2.4，不需修改或重新打包。
