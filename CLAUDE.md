About foro, see ~/RustroverProjects/foro

## IntelliJ Platform Plugin Development Docs

公式ドキュメントのトップ: https://plugins.jetbrains.com/docs/intellij/welcome.html

### よく参照するページ

| トピック | URL |
|--------|-----|
| プラグイン開発全般（入口） | https://plugins.jetbrains.com/docs/intellij/welcome.html |
| サービス（AppService / ProjectService） | https://plugins.jetbrains.com/docs/intellij/plugin-services.html |
| 設定UI（Configurable / DSL） | https://plugins.jetbrains.com/docs/intellij/settings-guide.html |
| 設定永続化（PersistentStateComponent） | https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html |
| アクション（AnAction / ActionListener） | https://plugins.jetbrains.com/docs/intellij/action-system.html |
| ドキュメント・エディタ操作 | https://plugins.jetbrains.com/docs/intellij/documents.html |
| 通知（Notifications） | https://plugins.jetbrains.com/docs/intellij/notifications.html |
| スレッドモデル（EDT / BGT） | https://plugins.jetbrains.com/docs/intellij/threading-model.html |
| ファイル保存リスナー | https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html |
| plugin.xml リファレンス | https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html |
| Gradle IntelliJ Plugin (ビルド設定) | https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html |
| 互換性・バージョン管理 | https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html |

### このプロジェクトで使っている主要API

- `FileDocumentManagerListener.beforeDocumentSaving` — 保存前フック（EDTで呼ばれる）
- `PersistentStateComponent<T>` — 設定永続化（`getState()` が `this` を返すパターン）
- `CommandProcessor.executeCommand` + `runWriteAction` — undo対応のドキュメント書き換え
- `AnActionListener` — `SaveAllAction` 検出による手動保存判定
- `Notification` + `NotificationGroup` — バルーン通知
- `FileChooserDescriptorFactory` — ファイル/フォルダ選択UI
