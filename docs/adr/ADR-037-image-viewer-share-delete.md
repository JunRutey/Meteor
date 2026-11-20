# ADR-037 · 图片查看器居中、分享与删除，相册统一卡片语言

- 状态：已接受
- 日期：2026-11-20
- 关联：ADR-022（相册按天分组、长按删除）、ADR-024（edge-to-edge 与 insets）

## 背景

相册在 ADR-022 里已经按天分组、长按多选删除，但查看器还停留在「把原图按宽度铺满、
从上到下滚动」的旧实现：

- 竖图会贴着顶部、甚至需要滚动，短图下方留下大片空白，视觉上不在屏幕中央；
- 查看器没有分享和删除，用户在相册里看到一张图，想发给别人或删掉，只能先返回；
- 相册页头用文字「返回」，加载态、空态是裸文本，缩略图圆角与间距也和设置页 / 关于页
  的圆角卡片语言不一致。

## 决定

### 1. 查看器：剩余空间内居中，ContentScale.Fit

`ImageViewerScreen` 改成固定的三段式布局：

1. 页头：手绘返回箭头（`GalleryIconKind.Back`，内容描述仍走 `gallery_back`）+ 20sp 标题；
2. 中间 `Box(Modifier.weight(1f))`，`contentAlignment = Alignment.Center`，图片
   `fillMaxSize()` + `ContentScale.Fit`，无论横竖图都落在剩余空间正中；
3. 底部圆角卡片：图片日期（可选）+ 错误提示 + 「分享」「删除」两个按钮。

加载态（进度圈或文字）与「图片缺失」卡片也居中放在同一个 Box 里，不再靠上。

### 2. 分享：解密到应用私有 cache，再交给 FileProvider

分享按钮不直接暴露 `meteor://image/...`，而是由 `ImageViewerViewModel` 在
`Dispatchers.IO` 上：

1. `ImageStore.load(token)` 取回解密后的字节；
2. 写入 `cacheDir/share/`，文件名由纯函数 `ImageShareFile.fileName` 生成；
3. 通过新增的 `FileProvider` 生成 content URI。

Manifest 新增：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.share"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/share_file_paths" />
</provider>
```

`share_file_paths.xml` 只开放 `cache-path name="shared_images" path="share/"`，不开放
filesDir、外部存储或整个 cache。发送时使用 `Intent.ACTION_SEND` +
`Intent.createChooser`，设置 `FLAG_GRANT_READ_URI_PERMISSION` 并同时挂 `ClipData`，
保证接收方能读到 URI。

MIME 由 `ImageShareFile.mimeType` 根据文件头推断（JPEG / PNG / GIF / WebP / BMP /
TIFF / HEIC / AVIF），未知内容回退 `image/*`；文件名只保留字母、数字、`-`、`_`，
不会出现路径分隔符。

**临时文件生命周期**：分享成功后在分享回调里**不删除**文件——接收方可能异步读取。
下一次分享会清理超过 24 小时的旧文件；系统清缓存也会回收。文件位于应用私有
cacheDir，不参与备份，也不出现在相册 `ImageStore` 的目录里。

分享失败（图片不可读、无接收 Activity、文件写入失败）会记日志并在底部卡片显示
「分享失败」；不会静默无反应。

### 3. 删除：先确认，再删密文，再退回相册

查看器删除只删当前这一张：

- 点「删除」弹二次确认，文案明确说明引用过这张图的日记 / 档案不会跟着改写；
- 确认后 `ImageViewerViewModel.delete()` 在 IO 线程调用 `ImageStore.delete(token)`；
- 成功置 `deleted`，导航回上一页；相册页原有的 `RefreshOnResume` 会重新扫描，
  被删图片立即消失；
- 失败（文件已不存在、路径被拒绝）保持停留并显示「删除失败」，同时写 warning 日志。

删除仍然只删密文文件，不级联改写 Markdown 引用，与 ADR-022 的相册多选删除保持一致。

### 4. 相册：向设置页 / 关于页的卡片风格靠拢

在不改动分组、多选、长按删除逻辑的前提下：

- 页头改为「手绘返回箭头 + 20sp 半粗标题 + 小字提示」，与日记、人物、关于页一致；
- 加载态 / 空态包进 20dp 圆角的 `surface` 描边卡片；
- 缩略图圆角 12dp、网格间距 8dp、左右留白 16dp；
- 选中对勾从字体字符「✓」改成本地 Canvas 手绘 `GalleryIconKind.Check`，不依赖字体。

### 5. 测试

`ImageShareFileTest`（`:app` 的 JVM 单测）覆盖：

- JPEG / PNG / GIF / WebP / HEIC 文件头识别；
- 未知字节回退 `image/*`；
- 文件名清洗、扩展名与 MIME 对应；
- token 没有合法主干时回退 `image`。

`:feature-gallery` 仍不引入测试依赖；纯逻辑放在 `:app`，与既有 ViewModel 同层。

## R1 模块准入

1. **模块归属**：查看器 / 相册 UI 在既有 `:feature-gallery`；FileProvider、cache 写入、
   `ACTION_SEND` 与导航接线在既有的 `:app`。不新增模块。
2. **依赖方向**：没有新增模块依赖边。`:app → :feature-gallery → 无业务模块` 保持；
   `:app` 继续直接依赖 `:core-storage` 的 `ImageStore`。Manifest 中新增的 provider
   属于 `:app` 自身的 Android 组件，不跨模块。
3. **对外接口**：`ImageViewerScreen` 新增 `onShare` / `onDelete` / `dateLabel` /
   `deleting` / `sharing` / `error` 参数；`ImageViewerViewModel` 暴露
   `state`、`share()`、`delete()`。没有改 `ImageStore` 等冻结接口。
4. **数据格式**：没有改档案、图片目录或加密格式。新增的只是 `cacheDir/share/` 下的
   明文临时副本，应用私有、可被系统回收，24 小时后下次分享时主动清理。

## 已知限制

- 分享会在应用私有 cache 里留下最多 24 小时的**明文图片**。这是 Android 分享机制要求
  的（接收方需要在应用外读取），目录不备份、不出现在相册、可被系统清理；如果在意
  这段窗口，可在设置里手动清除缓存。后续如需更短窗口，可改成分享返回后延迟清理。
- 查看器一次只处理当前 token，没有左右滑动切换。
- 删除仍不改写引用该图片的正文；日记会显示「图片无法显示」。
- 日期来自 `ImageStore.list()` 里的文件修改时间，导入备份后可能变化；这与相册分组
  使用的是同一个时间来源。
