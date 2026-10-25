# FlipCover

Galaxy Z Flip7 のカバー画面に、アニメーションを表示する自作アプリです。

## 仕組み
端末を閉じると、カバー画面（displayId=1）だけが点灯します。
アプリはこれを `DisplayManager` で検知します。
そしてユーザー補助サービスが、カバー画面の上にアニメーションを重ねて描きます（`TYPE_ACCESSIBILITY_OVERLAY`）。

- 内蔵アニメーション：オーロラ、パーティクル
- 自分の GIF / アニメーション WebP / 動画（無音でループ）
- 時計を重ねて表示
- ダブルタップで一時的に非表示（閉じ直すと再び表示）

## ビルド
- **Android Studio**：このフォルダを開いて Run
- **GitHub Actions**：GitHub に push すると APK が作られます（Actions → Artifacts）
- **コマンド**：`./gradlew assembleDebug`（Android SDK が必要）

## 使い方
1. APK をインストールしてアプリを開く
2. 「ユーザー補助の設定を開く」→ インストール済みアプリ → FlipCover をオン
   - Play ストア以外から入れたアプリは、オンにできないことがあります。その場合は、アプリ情報の右上「︙」→「制限付き設定を許可」を押してください
3. アニメーションを選んで、端末を閉じる

## 調整したいとき
- カバー画面が見つからないとき：アプリ下部の「ディスプレイ情報」を見て、
  `CoverOverlayService.findCoverDisplay()` の id を直してください
- アニメーションの追加：`CoverContent.kt` に View を追加してください
