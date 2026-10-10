Evara 图标

原始图案由用户提供，原文件保存在 evara-original.png。当前 evara-master.png 使用确定性透明度蒙版处理，不使用生成式编辑。只修改 alpha：从中心连通的彩色图块生成蒙版，内收四个原图像素去除边沿亮线，使用一个原图像素的透明度过渡。RGB 字节不修改；原分辨率 1254×1254 不修改。

先执行 tools/extract_icon.py，再执行 tools/build_icons.py。使用现有 PySide6 构建环境。第一步生成主图并校验 PNG 保存后的 RGB 与原图逐像素一致，写入 icon-verification.json。第二步生成 Windows ICO、预览 PNG 和 Android 各密度 PNG；各部署尺寸必然进行缩放，不添加白色底板或阴影。

2026-10-08：1254×1254 主图与原图 RGB 不同像素数为 0；检查浅色、蓝色背景预览。1.0.3-test3 使用本资源。此前生成式抠图改变了颜色，本版已从原图重新制作并替换。真实 MIUI 显示由用户核对。
