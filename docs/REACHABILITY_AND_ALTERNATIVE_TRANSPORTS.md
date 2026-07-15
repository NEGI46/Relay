# 到達性、端末ロール、代替搬送

## 到達性の前提

Relayをインストールしていないスマートフォンは、自動中継端末として機能しない。ネットワークの到達性は、固定Relay、Gateway、Courierの配置および導入済み端末の移動に依存する。

したがって、災害時の到達性を一般利用者の導入率だけで見積もらない。運用計画では、常時給電の固定 `RELAY`、外部同期を担う `GATEWAY`、地域を巡回する `COURIER` の配置・稼働時間・移動経路を別途管理する。未導入端末は情報の閲覧または別経路での受け渡しの対象になり得るが、Nearby Connectionsの自動中継ノードにはならない。

## DeviceRole

`DeviceRole` は導入済みRelay端末の運用責務であり、通信を開始するかを決める `OperatingMode` とは独立している。既存の `MEMBER` と `GATEWAY` は維持し、次の値を拡張可能なenumとして扱う。

| ロール | 責務 |
|---|---|
| `MEMBER` | 一般利用者が情報を登録・受信する端末 |
| `COURIER` | 地域を巡回して情報を意図的に運ぶ端末 |
| `RELAY` | 常時給電された固定中継端末 |
| `GATEWAY` | 情報を集約し、将来の外部同期を担う端末 |
| `ADMIN` | 地域管理者が運用する端末 |

`OperatingMode.RELAY` は通信を明示的に開始するモード、`DeviceRole.RELAY` は固定中継端末という責務を表す。名称が同じでも意味は異なる。

## 非Nearby入出力の境界

媒体依存の実装は、次のdomain契約の実装として追加する。これらは接続型の `OfflineTransport` とは別であり、DBを直接操作しない。

```kotlin
interface MessageImportTransport {
    suspend fun importData(data: ByteArray): ImportResult
}

interface MessageExportTransport {
    suspend fun exportMessages(messageIds: List<String>): ExportResult
}
```

将来の各インポート実装は、共通Ingress UseCaseを通して以下を順に適用する。

1. 媒体ごとの上限と `ResourcePolicy.maxPayloadBytes`
2. 媒体Envelopeの構文、protocolVersion、形式の検証
3. integrity情報の検証
4. `MessagePolicy` によるpayload、TTL、hopの検証
5. `messageId` の重複排除とRepositoryの保存上限

未検証のQRまたはファイルは、公式情報として保存・表示・再転送しない。将来のintegrity verifierが検証成功を返せるようになるまでは、拒否または明示的な隔離モデルを採用する必要がある。今回のMVPは実装を追加しないため、未検証データを取り込む経路自体を提供しない。

## QRバックアップ経路（将来設計）

Nearby Connectionsが使えない場合、単一 `REPORT` のQR入出力をバックアップ経路として検討する。QRの媒体Envelopeには少なくとも次を含める。

```text
protocolVersion
messageId
messageType
payload
lifetimeMs
accumulatedAgeMs
hopCount
hopLimit
originId
integrity
```

`hopLimit` はdomainモデルの `maxHopCount` に明示的に変換する。QRから得たデータもNearbyからの受信と同じサイズ制限、検証、重複排除、TTL判定を通す。QRの符号化・読み取り・署名またはintegrity検証は今回実装しない。

## 将来のアダプタ候補と今回の範囲

| 経路 | 今回 | 将来 |
|---|---|---|
| QR単一REPORT | 契約と検証方針のみ | export/import adapter、integrity検証 |
| 署名付きRelayファイル | 未実装 | export/import adapter |
| 固定GatewayのローカルWeb | 未実装 | Gateway adapter |
| USB/外部ストレージ | 未実装 | file adapter |
| SMS/インターネット補助同期 | 未実装 | 補助同期adapter |

ローカルWebサーバーとSMS送信は今回実装しない。
