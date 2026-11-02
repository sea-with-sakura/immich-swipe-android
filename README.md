# Immich Swipe — 相册整理版

保留原项目的照片卡片、视频预览和 Material 风格，主流程围绕未加入相册的照片和视频：

- 首页直接显示未加入任何相册的媒体，按拍摄日期分组，可选最新/最早顺序、跳转日期、逐张或整日多选并批量加入相册。
- 右侧 Swipe 直接处理同一份未归类队列。点击常驻相册按钮后，成功才自动显示下一张，已归类照片从首页移除，不会重复处理。
- 相册采用三行紧凑网格，普通手机一屏约可显示 12 个，更多可横向滚动；底部导航压缩为 48dp 的纯图标栏。
- 相册显示封面；长按拖动可调整位置，横向拖到边缘可滚动。顺序按服务器和账号保存，重启后保留，两个页面共用。
- 新建相册可直接加入选中的照片。新建成功但添加失败时保留相册，方便直接重试。
- 「Undo」真正取消最近一次相册归类（包括整批），照片回到待整理中。只撤回本次新增的关系，已有关系不会误删；撤回失败保留记录供重试。撤回记录保留在当前应用会话中。
- 分享、跳转 Immich、旋转和显示模式等照片按钮已收起；保留预览全屏、视频静音。Next photo 跳过当前照片，左滑仍可标记删除，顶部垃圾桶打开汇总后确认删除。
- 相册操作立即写入 Immich。批量操作逐张检查响应，失败照片保留供重试。
- API Key 需 `user.read`、`asset.read`、`asset.view`、`asset.statistics`、`album.read`、`albumAsset.create`；撤回需 `albumAsset.delete`，新建需 `album.create`。左滑删除需 `asset.delete`。
- 安装名为 **Immich Albums**，独立包名 `com.minos2020.immichswipe.albums`（调试版加 `.debug`），可与原版共存。正式版与之前的 debug 版包名、签名不同，会并存，需要重新登录并设置相册顺序；以后正式版使用同一发布签名即可覆盖升级。

正式版构建与检查：`bash ./gradlew :app:assembleRelease :app:test :app:lintRelease`。需要 JDK 21 和 Android SDK 36.1。`:app:test` 运行项目现有的单元测试，安装包由 `assembleRelease` 生成。

首次构建时复制 `keystore.properties.example` 为 `keystore.properties`，填写仓库外的发布密钥路径、别名和密码；也可通过环境变量 `IMMICH_SIGNING_PROPERTIES` 指定配置文件。密钥和实际签名配置不会提交到 Git，缺少配置时正式版打包会明确失败。后续升级必须使用同一密钥，请备份密钥及签名配置。

正式包位于 `app/build/outputs/apk/release/app-release.apk`，版本为 **3.0.3**，包名 `com.minos2020.immichswipe.albums`，关闭调试和 HTTP 请求／响应详细日志。调试构建仍可使用 `bash ./gradlew :app:assembleDebug`。

以下保留原项目说明。


![GitHub release (latest by date)](https://img.shields.io/github/v/release/minos2020/immich-swipe)
![GitHub License](https://img.shields.io/github/license/minos2020/immich-swipe)
![Kotlin](https://img.shields.io/badge/language-Kotlin-purple)
![Android](https://img.shields.io/badge/platform-Android-green)

[Version française ici](README.fr.md)

Immich Swipe is an open-source Android application designed to make sorting your photos and videos hosted on your [Immich](https://immich.app/) server easy and fun.

Inspired by the [Sponge](https://play.google.com/store/apps/details?id=com.prismtree.sponge&pcampaignid=web_share) app (similar concept for local files), it allows you to quickly sort your media using simple swipe gestures, featuring a smooth and modern interface.

> **Note**: This project started as a personal need and was developed with the help of AI. Although I am not a developer by trade, I place great importance on stability and user experience.
>
> **Disclaimer**: This is an independent project and is not affiliated in any way with the official Immich project.

## 📸 Overview

|                                         Home Screen                                          |                                    Sorting Stack                                     |                                       Review Mode                                        |                                       Usage Stats                                       |
|:--------------------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------:|:----------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------:|
| <img src="metadata/en-US/images/phoneScreenshots/01_Light_HomeScreen_GridView.png" width="200"> | <img src="metadata/en-US/images/phoneScreenshots/02_Light_SwipeScreen.png" width="200"> | <img src="metadata/en-US/images/phoneScreenshots/03_Light_ReviewScreen.png" width="200"> | <img src="metadata/en-US/images/phoneScreenshots/09_Light_StatsScreen.png" width="200"> |
|                                     *Browse your albums*                                     |                                  *Swipe to decide*                                   |                                 *Check before deleting*                                  |                              *See what you have achieved*                               |

<details>
<summary>🌙 <b>View Dark Mode Gallery</b></summary>

|                                       Home Screen (Dark)                                       |                                  Sorting Stack (Dark)                                  |                                   Review Mode (Dark)                                    |                                     Usage Stats (Dark)                                     |
|:----------------------------------------------------------------------------------------------:|:--------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------:|
| <img src="metadata/en-US/images/phoneScreenshots/05_Dark_HomeScreen_GridView.png" width="200"> | <img src="metadata/en-US/images/phoneScreenshots/07_Dark_SwipeScreen.png" width="200"> | <img src="metadata/en-US/images/phoneScreenshots/08_Dark_ReviewScreen.png" width="200"> | <img src="metadata/en-US/images/phoneScreenshots/10_Dark_StatsScreen.png" width="200"> |

</details>

## ✨ Features

#### Swipe screen
- **Fast Sorting Stack**: Swipe right to keep, left to delete. You can also archive, lock, or SKIP.
- **Snooze (SKIP)**: Unsure? Skip it. Assets will automatically reappear after a configurable delay (1 day, 1 month, or never).
- **Collections (Virtual Albums)**: Access special groups like **All Assets**, **Orphans** or **SKIPs Collection**
- **Review Mode**: Review all your swipe decisions before syncing and see estimated reclaimed space.
- **Asset Descriptions**: View, create, or edit asset descriptions directly from the metadata panel, synced in real-time with the server.
- **Media Rotation**: Rotate photos on-the-fly directly from the swipe card, with optional server-side sync.

#### Home screen
- **Quick Album Actions**: Swipe left on any album in the home screen list to quickly reset its decisions or mark everything as "KEEP".

- **📊 Global Usage Stats**: Visualize your progress with detailed statistics and a breakdown of your sorting actions.
- **Multi-Account Support**: Switch between different users seamlessly; your local decisions and progress are preserved for each account.
- **Database Management**: Export, import, or clear your local database (globally or per user) to safeguard your sorting data.
- **🚦 Connection Diagnostic**: Real-time connection status and background loading indicator, HTTP/direct IP support, and in-app logs for easy troubleshooting.
- **🎨 Modern Interface**: Jetpack Compose and Material Design 3, with support for English, French, and Spanish.

## ⚙️ Configuration

1. Enter your Immich server URL (e.g., `https://immich.your-domain.com`).
2. Enter your Immich API Key (if you don't have it already, [create one here](https://my.immich.app/user-settings?isOpen=api-keys))
   - **Required Permissions**: For the app to function correctly, your API key must have the following permissions:
     - `user.read`
     - `album.read`
     - `asset.read`
     - `asset.view`
     - `asset.delete`
     - `asset.statistics`
     - `asset.update` (Optional --> if you want to archive assets, add them to favourites or to locked folder)
     - `albumAsset.create` (Optional --> if you want to add assets to other albums)
     - `asset.download` (Optional --> if you want to share assets)
     - `asset.edit.create` (Optional --> if you want to sync photo rotations)
     - `userProfileImage.read` (Optional --> to show user profile image)
3. Select an album and start sorting!

## 📦 Installation

|                                                                                                                       **Obtainium**                                                                                                                        |   **Direct Download**   |  **IzzyOnDroid / F-Droid**    |
|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:-------------------------:|:----------------------------:|
| [<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png"  alt="Get it on Obtainium" height="50">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.minos2020.immichswipe%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMinos2020%2Fimmich-swipe%22%2C%22author%22%3A%22Minos2020%22%2C%22name%22%3A%22Immich%20Swipe%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Atrue%2C%5C%22sortMethodChoice%5C%22%3A%5C%22date%5C%22%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Afalse%2C%5C%22releaseTitleAsVersion%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Atrue%2C%5C%22releaseDateAsVersion%5C%22%3Afalse%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22appName%5C%22%3A%5C%22%5C%22%2C%5C%22appAuthor%5C%22%3A%5C%22%5C%22%2C%5C%22shizukuPretendToBeGooglePlay%5C%22%3Afalse%2C%5C%22allowInsecure%5C%22%3Afalse%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22%5C%22%2C%5C%22refreshBeforeDownload%5C%22%3Afalse%2C%5C%22includeZips%5C%22%3Afalse%2C%5C%22zippedApkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22github-creds%5C%22%3A%5C%22%5C%22%2C%5C%22GHReqPrefix%5C%22%3A%5C%22%5C%22%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D) | Get the latest APK from the [Releases](https://github.com/minos2020/immich-swipe/releases) section   |  Coming soon, hopefully


## 🛠️ Build

If you want to compile the application yourself:

- **JDK 17** or higher required.
- **Android Studio** (Ladybug version or newer recommended).
- Clone the repository and import the project into Android Studio.
- Sync Gradle
- Use `./gradlew assembleDebug` to generate a test APK.

## 📄 License

This project is licensed under the GNU GPL v3. See the [LICENSE](LICENSE) file for more details.

## ⚖️ Disclaimer

While this project is developed with care and tested regularly, I cannot guarantee absolute data safety. By using Immich Swipe, you acknowledge that the author shall not be held liable for any data loss or accidental deletion of media.

Please keep in mind:
- **Trash Safety**: Immich Swipe never empties the trash on your Immich server. If you make a mistake while sorting, your photos remain recoverable through the official Immich interface during the configured trash retention period.
- **Principle of Least Privilege**: To minimize risks, it is highly recommended to configure your API key with only the strictly required permissions listed in the [Configuration](#configuration) section.
