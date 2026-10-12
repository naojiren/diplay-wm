# DiPlay WM APP 图标

用户授权自由设计前景图形、颜色和比例。选用银色五竖条作为主体，青蓝色弧线和播放箭头融合为一个前景图形，配深蓝色底。

使用内置 imagegen 工具生成单个设计；参考本项目的银色五竖条图标及用户提供的 C／播放图形。
设计原图为 `wm-app-logo-master.png`，Android 各密度资源及 256×256 预览仅进行等比例缩放。

提示词：

> Use case: logo-brand / compositing. Produce ONE finished Android launcher app icon for DiPlay WM, square 1024x1024, edge-to-edge flat front-facing artwork (no mockup, no device, no text). Reference image 1 is the WM brand foundation: EXACTLY FIVE large silver vertical rounded capsule bars in the distinctive staggered heights shown. Preserve the count and staggered silhouette, make them the strong primary element. Reference image 2 is only a loose motif of a C curve and a play triangle: freely redesign this motif, proportions, shape, placement and color to create a cohesive professional icon. Art direction: premium, restrained automotive interface; charcoal / midnight-blue rounded-square tile, luminous satin silver bars with subtle tonal depth, beautifully balanced negative space. Integrate a single sleek cyan-to-teal C/play swoosh as a foreground layer sweeping around the lower portion of the silver emblem; merge the small play triangle into the open end of the curved stroke so it feels like one engineered mark, not a circle plus a triangle pasted on the bars. The teal motif should be subordinate, elegantly thin but legible at 48px, and must leave all five silver bars clearly recognizable. Use creative asymmetric motion and a small amount of subtle glow, clean precise curves, smooth corners, no heavy bevels or shiny chrome, no separate badge, no large central triangle obscuring the WM logo. The teal curve may overlap the lower part of the bars slightly to convey front-to-back depth, but do not put the C and the WM bars side by side. Aim for a simple beautiful real app icon, polished and readable at small sizes. No lettering, no extra bars, no symbols other than the five-bar logo and the single integrated C/play swoosh. No outer margins or surrounding scene.

## perf22 透明圆角修复

根据实车桌面截图，将图标圆角外侧的黑色区域改为真正透明。使用内置 imagegen 编辑工具，开启 `transparent_background`，保留中央图案和深蓝底。原图及各密度 PNG 均保留 alpha 通道。

编辑提示词：

> Use case: background-extraction / precise-object-edit. Edit this exact Android app icon. Make ONLY the solid black regions OUTSIDE the rounded dark-blue tile in its four corner areas fully transparent alpha. Keep the complete rounded midnight-blue tile opaque, including its dark interior. Preserve the existing artwork inside the tile unchanged: exactly five silver vertical capsules in this staggered pattern, the cyan/teal swoosh and integrated play arrow, all colors, lighting, spacing, proportions and composition. Do not regenerate or redesign the emblem; do not remove the midnight blue tile. Keep the rounded-square silhouette at the same position and scale, with smooth antialiased alpha edges, no black matte/fringe outside the corners. One square PNG app icon with genuinely transparent outside corners, no checkerboard baked in, no shadow outside the tile, no text or mockup. Output dimensions preferably match the supplied 1254 x 1254.
