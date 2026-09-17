# Manga Panel Search (漫画コマ検索)

漫画の1コマやスマートフォンの撮影写真、切り抜き画像から、ライブラリ内の全巻・全ページを照合し、該当する「作品名・巻数・ページ番号・コマの正確な位置」を特定するデスクトップアプリケーションです。

Google Material Design 3 (M3) に準拠したUIと、コンピュータビジョン技術（SIFT特徴量照合・幾何学的ホモグラフィ検証・DirectML GPU加速）を融合し、高速かつ信頼性の高い特定を実現しています。

---

## 🚀 主な機能・特徴

### 1. 高精度な幾何学的コマ特定 (SIFT + Bijective RANSAC)
- **回転・歪み・トリミングに強い照合**: スマートフォンで斜めから撮影した単行本の写真や、周囲がトリミングされたコマ画像でも頑健に一致箇所を検出。
- **Many-to-One 崩壊の防止 (Bijective 1-to-1 Constraint)**: 繰り返しパターンやトーンの誤認識を防ぎ、厳密な1対1幾何マッチングを適用。
- **紙焼け・黄ばみ・コントラスト自動補正**: 古い単行本の黄ばみや陰影を適応的ヒストグラム・バイパス処理によって正規化し、デジタル原画との差分を吸収。

### 2. 直感的なライブラリ管理 & フォルダ分類
- **ドラッグ＆ドロップ対応**: ZIP / CBZ 圧縮ファイルや画像フォルダをそのままドロップして登録・インデックス化。
- **作品別フォルダ自動分類**: フォルダごとに作品をまとめ、チェックボックスで検索対象を柔軟に選択・除外可能。
- **対象作品の絞り込み**: 検索対象を限定することで、探索を効率化し、より高速かつ的確な特定が可能。
- **開閉状態の自動保存**: 作品ごとの展開・折りたたみ状態を記憶。

### 3. 高速検索パイプライン & GPU 加速
- **2段階ハイブリッドパイプライン**:
  - **Stage 1 (粗探索)**: 空間ストライドサンプリングによる候補ページの高速絞り込み。
  - **Stage 2 (精密幾何検証)**: RANSAC ホモグラフィ変換による確信度スコアリングと正確なコマ枠バウンディングボックスの描画。
- **DirectX 12 / DirectML GPU 加速**: ONNX Runtime (DirectML) を活用し、Intel Arc / NVIDIA / AMD GPU での行列積計算を高速化。

---

## 📥 インストール方法 (Windows)

最も簡単な方法は、ビルド済みインストーラーを使用することです。Python や Node.js のインストールは不要です。

1. [GitHub Releases](https://github.com/nanbeimerco/Manga-Panel-Search/releases) ページにアクセスします。
2. 最新リリースの `Manga Panel Search Setup 1.0.8.exe` をダウンロードします。
3. ダウンロードしたインストーラーを実行し、画面の指示に従ってインストールを完了してください。
4. デスクトップまたはスタートメニューのショートカットからアプリを起動できます。

---

## 📖 使い方

1. **漫画データの登録**:
   - アプリを起動し、「登録ライブラリ」タブを開きます。
   - お手持ちの漫画アーカイブ（`.zip`, `.cbz`）または画像が入ったフォルダをドラッグ＆ドロップします。
   - バックグラウンドで特徴量インデックス（`.cache_descriptors`）が作成されます。
2. **検索対象の設定**:
   - フォルダ単位または巻単位のチェックボックスで、検索対象に含めたい作品を選択します。
3. **コマ画像の検索**:
   - 「コマ検索」タブを開きます。
   - 探したいコマの画像（スマホ撮影写真、スクショ、切り抜きなど）を画面中央のエリアにドロップ（またはクリックしてファイル選択）します。
   - 「照合開始」をクリックすると照合が実行されます。
4. **結果の確認**:
   - 検出された候補一覧（巻数、ページ番号、一致スコア、インライア数）が表示されます。
   - 一致したコマの位置が緑色のバウンディングボックスでハイライト表示されます。

---

## 🛠️ 開発者向けセットアップ (Build from Source)

ソースコードからビルド・開発を行う場合の手順です。

### 必要環境
- OS: Windows 10 / 11 (64-bit)
- Python 3.10 以上 (Python 3.12 推奨)
- Node.js 18.x 以上

### 1. リポジトリのクローン
```bash
git clone https://github.com/nanbeimerco/Manga-Panel-Search.git
cd Manga-Panel-Search
```

### 2. バックエンドのセットアップ
```bash
# 仮想環境の作成と有効化
python -m venv venv
.\venv\Scripts\Activate.ps1

# 依存パッケージのインストール
pip install -r requirements.txt
```

### 3. フロントエンドのセットアップ
```bash
npm install
```

### 4. 開発モードでの起動
```bash
npm start
```
Electron ウィンドウが立ち上がり、ローカルの Python バックエンド (`run.py`) が自動起動します。

### 5. 配布パッケージのビルド
```bash
# Python バックエンドのバイナリ化
pyinstaller manga_backend.spec

# 静的ファイルの同期後、インストーラーを生成
npm run dist
```
`release_installer/` 配下に `Manga Panel Search Setup 1.0.8.exe` が生成されます。

---

## 🏗️ 技術スタック

| レイヤー | 技術 / ライブラリ | 用途 |
| :--- | :--- | :--- |
| **GUI Framework** | Electron (v33) | クロスプラットフォーム デスクトップシェル |
| **Frontend** | HTML5, CSS3, Vanilla JS, Material Design 3 | レスポンシブなモダン UI |
| **Backend** | Python 3.12, FastAPI, Uvicorn | RESTful API & 画像処理エンジン |
| **Computer Vision** | OpenCV (cv2), Pillow | SIFT特徴抽出、FLANN、RANSAC幾何検証 |
| **Acceleration** | Numba JIT, ONNX Runtime (DirectML) | CPU/GPU 高速行列演算 & 幾何的一貫性照合 |
| **Packaging** | PyInstaller, electron-builder, NSIS | スタンドアロン実行可能ファイルの生成 |

---

## 📄 ライセンス

本プロジェクトは [MIT License](LICENSE) のもとで公開されています。
