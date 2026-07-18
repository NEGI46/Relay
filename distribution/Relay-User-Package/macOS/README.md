# macOS PC Gateway

このフォルダには、macOS向けの完成済み・署名済みGatewayアプリは入っていません。`setup-pc-gateway-macos.sh` と `run-pc-gateway.sh` は、**Relayのソース一式を持つ開発・運用担当者**がGatewayをビルドして起動するための補助スクリプトです。

必要なものはmacOS、Java 17以上、Gradleを実行できるRelayソース一式です。配布フォルダ単体からはビルドできません。

```bash
# Relayソースのルートで実行する例
./scripts/setup-pc-gateway-macos.sh --all
./scripts/run-pc-gateway.sh
```

macOSのBLE Peripheralによる一般利用者端末の自動提出は、現時点では配布・実機検証済みのbridgeを提供していません。そのためmacOS Gatewayは、管理画面・保存・復号・運用確認用として扱い、ゼロ操作のBLE受付拠点としてはWindows + MSIX bridge構成を使用してください。

本番運用前には、組織のDeveloper ID署名と公証、署名済み避難所Manifest、実機での通信・復旧試験が必要です。
