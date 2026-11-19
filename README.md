# 流帖（nagarecho）

タスクシュート方式の時間記録Androidアプリ。WebView + 単一HTML（`assets/index.html`）構成、通信権限なし（権限はカレンダーの読み取りのみ）、データは端末内保存。

## ビルド

Ubuntu で `apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23`、JDK と Pillow、Noto Serif CJK フォントを用意して:

```
bash build.sh
```

`out/nagarecho-1.11.apk` ができます。署名鍵 `release.jks` はリポジトリに含めていません（無ければビルド時に新規生成されます）。既存インストールへ上書き更新するには、初回ビルド時と同じ鍵を置いてください。

## ダウンロード

最新版APK: https://github.com/marionette1994/nagarecho/raw/main/dist/nagarecho.apk
