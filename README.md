# FlipCover

Galaxy Z Flip7 のカバー画面に、アニメーションを表示する自作アプリです。

## 仕組み
端末を閉じると、カバー画面（displayId=1）だけが点灯します。
アプリはこれを `DisplayManager` で検知します。
そしてユーザー補助サービスが、カバー画面の上にアニメーションを重ねて描きます（`TYPE_ACCESSIBILITY_OVERLAY`）。

- 内蔵アニメーション：地球・月（宇宙）、機材風（ドット時計・ドット秒・レベルメーター・ドットウェーブ・シーケンサー）
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

## 画像のクレジット
- 地球の地表：NASA Visible Earth「Blue Marble」（パブリックドメイン）。[three-globe](https://github.com/vasturiano/three-globe) 同梱の画像を使用
- 地球の雲：`tools/make_clouds.py` で生成したもの
- 月：NASA の Apollo 撮影写真（[NASA 3D Resources](https://github.com/nasa/NASA-3D-Resources)「Moon」）を、`tools/make_moon.py` で表側の正距円筒図法に変換して使用
